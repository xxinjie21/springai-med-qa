package com.med.qa.service;

import com.med.qa.alert.MedAlertNotifier;
import com.med.qa.alert.MedAlertSeverity;
import com.med.qa.job.MedJobRun;
import com.med.qa.job.MedJobRunLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives the consultation retention sweep on a fixed delay and pushes what it did into the existing
 * alert chain.
 *
 * <p>Split from {@link MedSessionRetentionService} so the sweep itself stays a plain, synchronously
 * testable operation: nothing in this class decides <em>what</em> to archive, only <em>when</em> to
 * look and whom to tell. The same split is used by {@code MedStorageAlertMonitor} /
 * {@code MedVectorIndexAlertMonitor}, which poll and report while the work lives elsewhere.</p>
 *
 * <h2>Why the delay is a fixed delay, not a rate</h2>
 * <p>A sweep holds the cluster mutex for as long as it takes to walk its batches. A fixed rate would
 * queue a second run on top of a slow one; a fixed delay measures from the end of the previous run, so
 * a slow sweep simply happens less often. That is the behaviour a maintenance job wants.</p>
 *
 * <h2>What is alerted and what is only logged</h2>
 * <ul>
 *   <li>{@value #SESSION_RETENTION_COMPLETED} (INFO) — the sweep archived at least one session. Not
 *       raised for a run that archived nothing (the normal case on a quiet hospital night) nor for a
 *       dry run, otherwise a deployment that only measures would page forever.</li>
 *   <li>{@value #SESSION_RETENTION_FAILED} (WARNING) — the sweep threw. It does not page: consultations
 *       keep working, the consequence is a growing backlog. But it must not be silent either, because
 *       a retention job that stopped running looks exactly like a hospital with no abandoned
 *       sessions.</li>
 *   <li>A run that stepped aside for another replica is logged only. That is the mutex working, not an
 *       incident.</li>
 * </ul>
 *
 * <p>The notifier arrives as an {@link ObjectProvider} because {@code med.alert.enabled=false} removes
 * it from the context; the scheduler then still runs the sweep and simply has nowhere to push, instead
 * of failing to start.</p>
 *
 * <h2>What the alert list above structurally cannot say</h2>
 * <p>Every entry in it is produced <em>by this job</em>, so none of them can report that the job stopped
 * running. That is the whole reason {@link MedJobRunLedger} exists (D53): the scheduler registers itself
 * with the ledger while it is being built and records one {@link MedJobRun} for every attempt -- whether
 * it archived something, stepped aside for another replica or threw -- so the staleness of the published
 * timestamps becomes the signal a dead job cannot suppress.</p>
 */
public class MedSessionRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(MedSessionRetentionScheduler.class);

    /** Alert component label used by the retention alerts. */
    public static final String COMPONENT = "session-retention";

    /** Alert code raised when a sweep archived at least one session. */
    public static final String SESSION_RETENTION_COMPLETED = "session-retention-completed";

    /** Alert code raised when a sweep could not run to completion. */
    public static final String SESSION_RETENTION_FAILED = "session-retention-failed";

    private final MedSessionRetentionService retentionService;

    private final ObjectProvider<MedAlertNotifier> notifier;

    private final MedJobRunLedger ledger;

    private final AtomicReference<SessionRetentionReport> lastReport = new AtomicReference<>();

    /**
     * Creates the scheduler and registers it with the run ledger.
     *
     * <p>The registration happens here, in the constructor, and not on the first run: a job that never
     * gets a first run is exactly the case the ledger has to report, so the ledger has to know about it
     * from the moment it exists.</p>
     *
     * @param retentionService the sweep this scheduler drives, must not be {@code null}
     * @param notifier         provider of the alert dispatcher; absent when alerting is switched off,
     *                         must not be {@code null}
     * @param ledger           the run ledger this scheduler records into, must not be {@code null}
     * @param expectedInterval the {@code med.session.retention.check-interval} the deployment armed this
     *                         job with; published by the ledger so the stall rule follows the
     *                         configuration instead of hard-coding a threshold, must be positive
     * @throws IllegalArgumentException if any argument is {@code null} or the interval is not positive
     */
    public MedSessionRetentionScheduler(MedSessionRetentionService retentionService,
                                        ObjectProvider<MedAlertNotifier> notifier,
                                        MedJobRunLedger ledger,
                                        Duration expectedInterval) {
        if (retentionService == null) {
            throw new IllegalArgumentException("retentionService must not be null");
        }
        if (notifier == null) {
            throw new IllegalArgumentException("notifier must not be null");
        }
        if (ledger == null) {
            throw new IllegalArgumentException("ledger must not be null");
        }
        this.retentionService = retentionService;
        this.notifier = notifier;
        this.ledger = ledger;
        ledger.register(COMPONENT, expectedInterval);
    }

    /**
     * Scheduled entry point; see {@link #sweepNow()}.
     *
     * <p>Both placeholders are read from {@code med.session.retention.check-interval} and
     * {@code med.session.retention.initial-delay}, so an operator can slow the sweeping down without a
     * rebuild. The literal after the colon is only a fallback for a context that never loaded
     * {@code application.yml}; {@code MedSessionRetentionConfigTest} pins the keys so the fallback
     * cannot silently take over.</p>
     */
    @Scheduled(fixedDelayString = "${med.session.retention.check-interval:1h}",
            initialDelayString = "${med.session.retention.initial-delay:5m}")
    public void scheduledSweep() {
        sweepNow();
    }

    /**
     * Runs one sweep, reports its outcome and never throws.
     *
     * <p>The run is recorded in the ledger for every outcome. On the failure path the ledger is written
     * <em>before</em> the alert is raised: if the alert chain is itself broken (a full log volume, an
     * unreachable metrics collector -- the D43 scenario), the attempt timestamp must not be lost along
     * with it, because that timestamp is the only remaining evidence that the sweep is still being
     * attempted.</p>
     *
     * @return the run report, or {@link Optional#empty()} when the sweep threw; the failure has then
     *         already been logged and alerted
     */
    public Optional<SessionRetentionReport> sweepNow() {
        Instant startedAt = Instant.now();
        SessionRetentionReport report;
        try {
            report = retentionService.sweepOnce();
        } catch (RuntimeException ex) {
            log.error("the session retention sweep failed", ex);
            ledger.record(MedJobRun.failed(COMPONENT, startedAt, elapsedMillis(startedAt)));
            raise(MedAlertSeverity.WARNING, SESSION_RETENTION_FAILED,
                    "the session retention sweep failed with " + ex.getClass().getSimpleName()
                            + ": " + ex.getMessage());
            return Optional.empty();
        }
        lastReport.set(report);
        boolean steppedAside = report.outcome() == SessionRetentionReport.Outcome.SKIPPED_LOCK_HELD;
        ledger.record(steppedAside
                ? MedJobRun.skippedLockHeld(COMPONENT, startedAt, report.durationMillis())
                : MedJobRun.completed(COMPONENT, startedAt, report.durationMillis()));
        if (steppedAside) {
            log.debug("session retention sweep skipped, another replica holds the lock");
            return Optional.of(report);
        }
        if (report.archivedAnything()) {
            raise(MedAlertSeverity.INFO, SESSION_RETENTION_COMPLETED, report.summary());
        }
        return Optional.of(report);
    }

    /**
     * Returns the most recent successful run report.
     *
     * @return the last report, or {@link Optional#empty()} when no run has completed yet
     */
    public Optional<SessionRetentionReport> lastReport() {
        return Optional.ofNullable(lastReport.get());
    }

    /**
     * Pushes one alert when the chain is available.
     *
     * @param severity the severity to raise
     * @param code     the alert code
     * @param message  the human-readable detail
     */
    private void raise(MedAlertSeverity severity, String code, String message) {
        MedAlertNotifier alertNotifier = notifier.getIfAvailable();
        if (alertNotifier == null) {
            log.debug("no alert notifier in this context; retention alert {} was only logged", code);
            return;
        }
        alertNotifier.raise(severity, code, COMPONENT, message);
    }

    /**
     * Measures how long an attempt has been running.
     *
     * <p>Only used on the failure path, where there is no report to take the duration from; a successful
     * attempt is recorded with the duration the service itself measured, so the ledger and the report can
     * never disagree about it.</p>
     *
     * @param startedAt the instant the attempt began
     * @return the elapsed wall-clock milliseconds, never negative
     */
    private static long elapsedMillis(Instant startedAt) {
        return Math.max(0L, Duration.between(startedAt, Instant.now()).toMillis());
    }
}
