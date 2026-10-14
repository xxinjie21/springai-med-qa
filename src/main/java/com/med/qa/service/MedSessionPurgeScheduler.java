package com.med.qa.service;

import com.med.qa.alert.MedAlertNotifier;
import com.med.qa.alert.MedAlertSeverity;
import com.med.qa.common.exception.BizException;
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
 * Drives the hot-shard purge on a fixed delay and pushes what it did into the existing alert chain.
 *
 * <p>Split from {@link MedSessionPurgeService} so the purge itself stays a plain, synchronously testable
 * operation: nothing in this class decides <em>what</em> to delete, only <em>when</em> to look and whom
 * to tell. The same split is used by {@code MedSessionRetentionScheduler} and
 * {@code MedSessionArchiveExportScheduler}.</p>
 *
 * <h2>Why the delay is a fixed delay, not a rate</h2>
 * <p>A run holds the cluster mutex while it verifies and deletes its batches. A fixed rate would queue a
 * second run on top of a slow one; a fixed delay measures from the end of the previous run, so a slow
 * purge simply happens less often.</p>
 *
 * <h2>What is alerted and what is only logged</h2>
 * <ul>
 *   <li>{@value #SESSION_PURGE_COMPLETED} (INFO) — the run retired at least one session. Not raised for a
 *       run that deleted nothing (the normal case once the backlog is drained) nor for a dry run,
 *       otherwise a deployment that only measures would page forever.</li>
 *   <li>{@value #SESSION_PURGE_REFUSED} (WARNING) — at least one certified session could not be
 *       accounted for: its cold copy or its live shard no longer reproduces the manifest digest.
 *       <em>Nothing was deleted</em> — this is the job declining to act — but it is the one signal that
 *       says "the two copies of this consultation disagree", and it will not clear on its own. A log
 *       line would hide it: a refused session keeps its hot rows, so the backlog simply stops draining
 *       and looks exactly like a job that is keeping up.</li>
 *   <li>{@value #SESSION_PURGE_FAILED} (WARNING) — the run threw. Consultations keep working and nothing
 *       has been lost (every deletion is preceded by a verification), but the hot tier stops shrinking,
 *       and a purge that stopped running is indistinguishable from a hospital with nothing to purge
 *       unless it is alerted.</li>
 *   <li>A run that stepped aside for another replica is logged only. That is the mutex working, not an
 *       incident.</li>
 * </ul>
 *
 * <p>The notifier arrives as an {@link ObjectProvider} because {@code med.alert.enabled=false} removes it
 * from the context; the scheduler then still purges and simply has nowhere to push, instead of failing to
 * start.</p>
 *
 * <h2>What the alert list above structurally cannot say</h2>
 * <p>Every entry in it is produced <em>by this job</em>, so none of them can report that the job stopped
 * running. That is the whole reason {@link MedJobRunLedger} exists (D53): the scheduler registers itself
 * with the ledger while it is being built and records one {@link MedJobRun} for every attempt -- whether
 * it deleted something, stepped aside for another replica or threw -- so the staleness of the published
 * timestamps becomes the signal a dead job cannot suppress. It matters most for this job: the purge is the
 * only one that deletes clinical records, and "the purge stopped running" is the one failure that leaves no
 * trace in any of its own alerts.</p>
 */
public class MedSessionPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(MedSessionPurgeScheduler.class);

    /** Alert component label used by the purge alerts. */
    public static final String COMPONENT = "session-purge";

    /** Alert code raised when a run retired at least one session. */
    public static final String SESSION_PURGE_COMPLETED = "session-purge-completed";

    /** Alert code raised when a run could not be completed. */
    public static final String SESSION_PURGE_FAILED = "session-purge-failed";

    /** Alert code raised when a run declined to delete a session it could not account for. */
    public static final String SESSION_PURGE_REFUSED = "session-purge-refused";

    private final MedSessionPurgeService purgeService;

    private final ObjectProvider<MedAlertNotifier> notifier;

    private final MedJobRunLedger ledger;

    private final AtomicReference<SessionPurgeReport> lastReport = new AtomicReference<>();

    /**
     * Creates the scheduler and registers it with the run ledger.
     *
     * <p>The registration happens here, in the constructor, and not on the first run: a job that never
     * gets a first run is exactly the case the ledger has to report, so the ledger has to know about it
     * from the moment it exists.</p>
     *
     * @param purgeService     the purge this scheduler drives, must not be {@code null}
     * @param notifier         provider of the alert dispatcher; absent when alerting is switched off,
     *                         must not be {@code null}
     * @param ledger           the run ledger this scheduler records into, must not be {@code null}
     * @param expectedInterval the {@code med.session.purge.check-interval} the deployment armed this job
     *                         with; published by the ledger so the stall rule follows the configuration
     *                         instead of hard-coding a threshold, must be positive
     * @throws IllegalArgumentException if any argument is {@code null} or the interval is not positive
     */
    public MedSessionPurgeScheduler(MedSessionPurgeService purgeService,
                                    ObjectProvider<MedAlertNotifier> notifier,
                                    MedJobRunLedger ledger,
                                    Duration expectedInterval) {
        if (purgeService == null) {
            throw new IllegalArgumentException("purgeService must not be null");
        }
        if (notifier == null) {
            throw new IllegalArgumentException("notifier must not be null");
        }
        if (ledger == null) {
            throw new IllegalArgumentException("ledger must not be null");
        }
        this.purgeService = purgeService;
        this.notifier = notifier;
        this.ledger = ledger;
        ledger.register(COMPONENT, expectedInterval);
    }

    /**
     * Scheduled entry point; see {@link #purgeNow()}.
     *
     * <p>Both placeholders are read from {@code med.session.purge.check-interval} and
     * {@code med.session.purge.initial-delay}, so an operator can slow the purge down without a rebuild.
     * The literal after the colon is only a fallback for a context that never loaded
     * {@code application.yml}; {@code MedSessionPurgeConfigTest} pins the keys so the fallback cannot
     * silently take over.</p>
     */
    @Scheduled(fixedDelayString = "${med.session.purge.check-interval:12h}",
            initialDelayString = "${med.session.purge.initial-delay:15m}")
    public void scheduledPurge() {
        purgeNow();
    }

    /**
     * Runs one purge, reports its outcome and never throws.
     *
     * <p>The run is recorded in the ledger for every outcome. On the failure path the ledger is written
     * <em>before</em> the alert is raised: if the alert chain is itself broken (the D43 scenario), the
     * attempt timestamp must not be lost along with it, because that timestamp is the only remaining
     * evidence that the purge is still being attempted.</p>
     *
     * @return the run report, or {@link Optional#empty()} when the purge threw; the failure has then
     *         already been logged and alerted
     */
    public Optional<SessionPurgeReport> purgeNow() {
        Instant startedAt = Instant.now();
        SessionPurgeReport report;
        try {
            report = purgeService.purgeOnce();
        } catch (RuntimeException ex) {
            log.error("the hot-shard purge failed", ex);
            ledger.record(MedJobRun.failed(COMPONENT, startedAt, elapsedMillis(startedAt)));
            raise(MedAlertSeverity.WARNING, SESSION_PURGE_FAILED,
                    "the hot-shard purge failed: " + describe(ex));
            return Optional.empty();
        }
        lastReport.set(report);
        boolean steppedAside = report.outcome() == SessionPurgeReport.Outcome.SKIPPED_LOCK_HELD;
        ledger.record(steppedAside
                ? MedJobRun.skippedLockHeld(COMPONENT, startedAt, report.durationMillis())
                : MedJobRun.completed(COMPONENT, startedAt, report.durationMillis()));
        if (steppedAside) {
            log.debug("hot-shard purge skipped, another replica holds the lock");
            return Optional.of(report);
        }
        if (report.sawRefusal()) {
            // Raised before the completion notice, and independently of it: a run can retire a hundred
            // sessions and still have refused one, and the refusal is the part that needs attention.
            raise(MedAlertSeverity.WARNING, SESSION_PURGE_REFUSED,
                    report.refused() + " certified transcript(s) could not be accounted for and were "
                            + "left untouched: " + report.summary());
        }
        if (report.purgedAnything() && !report.dryRun()) {
            raise(MedAlertSeverity.INFO, SESSION_PURGE_COMPLETED, report.summary());
        }
        return Optional.of(report);
    }

    /**
     * Returns the most recent successful run report.
     *
     * @return the last report, or {@link Optional#empty()} when no run has completed yet
     */
    public Optional<SessionPurgeReport> lastReport() {
        return Optional.ofNullable(lastReport.get());
    }

    /**
     * Renders a failure for the alert text.
     *
     * <p>A {@link BizException} is rendered with its {@link ErrorCode} rather than its class name, for
     * the same reason the purge service's per-candidate failure lines are: "STORAGE_ERROR" tells an
     * operator whether MySQL was unreachable or a delete was rejected, while "BizException" tells them
     * nothing. The alert is the only artefact an on-call engineer has.</p>
     *
     * @param failure the failure that ended the run
     * @return a single-line description, never {@code null}
     */
    private static String describe(RuntimeException failure) {
        if (failure instanceof BizException bizException) {
            return bizException.getErrorCode().name() + ": " + bizException.getMessage();
        }
        return failure.getClass().getSimpleName() + ": " + failure.getMessage();
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
            log.debug("no alert notifier in this context; purge alert {} was only logged", code);
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
