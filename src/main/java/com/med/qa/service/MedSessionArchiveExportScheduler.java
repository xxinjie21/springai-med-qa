package com.med.qa.service;

import com.med.qa.alert.MedAlertNotifier;
import com.med.qa.alert.MedAlertSeverity;
import com.med.qa.common.exception.BizException;
import com.med.qa.job.MedColdStoreMetrics;
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
 * Drives the cold archive export on a fixed delay and pushes what it did into the existing alert
 * chain.
 *
 * <p>Split from {@link MedSessionArchiveExportService} so the export itself stays a plain,
 * synchronously testable operation: nothing in this class decides <em>what</em> to copy, only
 * <em>when</em> to look and whom to tell. The same split is used by
 * {@code MedSessionRetentionScheduler} and the two alert monitors.</p>
 *
 * <h2>Why the delay is a fixed delay, not a rate</h2>
 * <p>A run holds the cluster mutex while it copies its batches. A fixed rate would queue a second run
 * on top of a slow one; a fixed delay measures from the end of the previous run, so a slow export
 * simply happens less often. That is the behaviour a maintenance job wants.</p>
 *
 * <h2>What is alerted and what is only logged</h2>
 * <ul>
 *   <li>{@value #SESSION_ARCHIVE_COMPLETED} (INFO) — the run certified at least one session. Not raised
 *       for a run that exported nothing (the normal case on a quiet hospital night) nor for a dry run,
 *       otherwise a deployment that only measures would page forever.</li>
 *   <li>{@value #SESSION_ARCHIVE_MISMATCH} (WARNING) — at least one transcript was copied but refused
 *       certification. Nothing has been lost (the export never deletes), but the cold store does not
 *       yet hold a trustworthy copy of those sessions, and the next run has to converge. A log line
 *       would hide it: the sessions stay in the backlog, which on a quiet night looks exactly like a
 *       job that is simply keeping up.</li>
 *   <li>{@value #SESSION_ARCHIVE_FAILED} (WARNING) — the run threw. Consultations keep working and no
 *       data is at risk; the consequence is an archive that stops growing, and an archive that stopped
 *       growing is indistinguishable from a hospital with nothing to archive unless it is alerted.</li>
 *   <li>A run that stepped aside for another replica is logged only. That is the mutex working, not an
 *       incident.</li>
 * </ul>
 *
 * <p>The notifier arrives as an {@link ObjectProvider} because {@code med.alert.enabled=false} removes
 * it from the context; the scheduler then still exports and simply has nowhere to push, instead of
 * failing to start.</p>
 *
 * <h2>What the alert list above structurally cannot say</h2>
 * <p>Every entry in it is produced <em>by this job</em>, so none of them can report that the job stopped
 * running. That is the whole reason {@link MedJobRunLedger} exists (D53): the scheduler registers itself
 * with the ledger while it is being built and records one {@link MedJobRun} for every attempt -- whether
 * it certified something, stepped aside for another replica or threw -- so the staleness of the published
 * timestamps becomes the signal a dead job cannot suppress.</p>
 *
 * <h2>The export's share of the cold store's observability</h2>
 * <p>The ledger says this job runs and the alerts say what each run did; neither says how big the table
 * the export <em>fills</em> has become. Since the reclaim is the only job that empties it, a cold store
 * whose growth outpaces the reclaim was invisible from the export's side — and the export runs more often
 * than the reclaim, so it is the better refresher. After every run that held the cluster mutex, dry-run
 * runs included, this scheduler therefore refreshes {@link MedColdStoreMetrics}' size gauges (D54). A run
 * that stepped aside for another replica records nothing: the replica that held the mutex is doing the
 * measuring.</p>
 */
public class MedSessionArchiveExportScheduler {

    private static final Logger log = LoggerFactory.getLogger(MedSessionArchiveExportScheduler.class);

    /** Alert component label used by the archive alerts. */
    public static final String COMPONENT = "session-archive";

    /** Alert code raised when a run certified at least one transcript. */
    public static final String SESSION_ARCHIVE_COMPLETED = "session-archive-completed";

    /** Alert code raised when a run could not be completed. */
    public static final String SESSION_ARCHIVE_FAILED = "session-archive-failed";

    /** Alert code raised when a copy was refused certification. */
    public static final String SESSION_ARCHIVE_MISMATCH = "session-archive-mismatch";

    private final MedSessionArchiveExportService exportService;

    private final ObjectProvider<MedAlertNotifier> notifier;

    private final MedJobRunLedger ledger;

    private final MedColdStoreMetrics coldStoreMetrics;

    private final AtomicReference<SessionArchiveExportReport> lastReport = new AtomicReference<>();

    /**
     * Creates the scheduler and registers it with the run ledger.
     *
     * <p>The registration happens here, in the constructor, and not on the first run: a job that never
     * gets a first run is exactly the case the ledger has to report, so the ledger has to know about it
     * from the moment it exists.</p>
     *
     * @param exportService    the export this scheduler drives, must not be {@code null}
     * @param notifier         provider of the alert dispatcher; absent when alerting is switched off,
     *                         must not be {@code null}
     * @param ledger           the run ledger this scheduler records into, must not be {@code null}
     * @param coldStoreMetrics the D54 publisher of the cold store's size gauges, must not be
     *                         {@code null}
     * @param expectedInterval the {@code med.session.archive.check-interval} the deployment armed this job
     *                         with; published by the ledger so the stall rule follows the configuration
     *                         instead of hard-coding a threshold, must be positive
     * @throws IllegalArgumentException if any argument is {@code null} or the interval is not positive
     */
    public MedSessionArchiveExportScheduler(MedSessionArchiveExportService exportService,
                                            ObjectProvider<MedAlertNotifier> notifier,
                                            MedJobRunLedger ledger,
                                            MedColdStoreMetrics coldStoreMetrics,
                                            Duration expectedInterval) {
        if (exportService == null) {
            throw new IllegalArgumentException("exportService must not be null");
        }
        if (notifier == null) {
            throw new IllegalArgumentException("notifier must not be null");
        }
        if (ledger == null) {
            throw new IllegalArgumentException("ledger must not be null");
        }
        if (coldStoreMetrics == null) {
            throw new IllegalArgumentException("coldStoreMetrics must not be null");
        }
        this.exportService = exportService;
        this.notifier = notifier;
        this.ledger = ledger;
        this.coldStoreMetrics = coldStoreMetrics;
        ledger.register(COMPONENT, expectedInterval);
    }

    /**
     * Scheduled entry point; see {@link #exportNow()}.
     *
     * <p>Both placeholders are read from {@code med.session.archive.check-interval} and
     * {@code med.session.archive.initial-delay}, so an operator can slow the export down without a
     * rebuild. The literal after the colon is only a fallback for a context that never loaded
     * {@code application.yml}; {@code MedSessionArchiveConfigTest} pins the keys so the fallback cannot
     * silently take over.</p>
     */
    @Scheduled(fixedDelayString = "${med.session.archive.check-interval:6h}",
            initialDelayString = "${med.session.archive.initial-delay:10m}")
    public void scheduledExport() {
        exportNow();
    }

    /**
     * Runs one export, reports its outcome and never throws.
     *
     * <p>The run is recorded in the ledger for every outcome. On the failure path the ledger is written
     * <em>before</em> the alert is raised: if the alert chain is itself broken (the D43 scenario), the
     * attempt timestamp must not be lost along with it, because that timestamp is the only remaining
     * evidence that the export is still being attempted.</p>
     *
     * <p>After the ledger record, and only for a run that held the cluster mutex, the D54 cold-store size
     * gauges are refreshed — dry-run runs included, because the size of the cold store is a fact about
     * the data rather than about an action (see the class javadoc).</p>
     *
     * @return the run report, or {@link Optional#empty()} when the export threw; the failure has then
     *         already been logged and alerted
     */
    public Optional<SessionArchiveExportReport> exportNow() {
        Instant startedAt = Instant.now();
        SessionArchiveExportReport report;
        try {
            report = exportService.exportOnce();
        } catch (RuntimeException ex) {
            log.error("the cold archive export failed", ex);
            ledger.record(MedJobRun.failed(COMPONENT, startedAt, elapsedMillis(startedAt)));
            raise(MedAlertSeverity.WARNING, SESSION_ARCHIVE_FAILED,
                    "the cold archive export failed: " + describe(ex));
            return Optional.empty();
        }
        lastReport.set(report);
        boolean steppedAside = report.outcome() == SessionArchiveExportReport.Outcome.SKIPPED_LOCK_HELD;
        ledger.record(steppedAside
                ? MedJobRun.skippedLockHeld(COMPONENT, startedAt, report.durationMillis())
                : MedJobRun.completed(COMPONENT, startedAt, report.durationMillis()));
        if (steppedAside) {
            log.debug("cold archive export skipped, another replica holds the lock");
            return Optional.of(report);
        }
        coldStoreMetrics.recordColdStoreSize();
        if (report.sawMismatch()) {
            // Raised before the completion notice, and independently of it: a run can export a hundred
            // sessions and still have refused one, and the refusal is the part that needs attention.
            raise(MedAlertSeverity.WARNING, SESSION_ARCHIVE_MISMATCH,
                    report.mismatched() + " archived transcript(s) were refused certification: "
                            + report.summary());
        }
        if (report.exportedAnything()) {
            raise(MedAlertSeverity.INFO, SESSION_ARCHIVE_COMPLETED, report.summary());
        }
        return Optional.of(report);
    }

    /**
     * Returns the most recent successful run report.
     *
     * @return the last report, or {@link Optional#empty()} when no run has completed yet
     */
    public Optional<SessionArchiveExportReport> lastReport() {
        return Optional.ofNullable(lastReport.get());
    }

    /**
     * Renders a failure for the alert text.
     *
     * <p>A {@link BizException} is rendered with its {@link ErrorCode} rather than its class name, for
     * the same reason the export service's per-candidate failure lines are: "STORAGE_ERROR" tells an
     * operator whether MySQL was unreachable or a write was rejected, while "BizException" tells them
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
            log.debug("no alert notifier in this context; archive alert {} was only logged", code);
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
