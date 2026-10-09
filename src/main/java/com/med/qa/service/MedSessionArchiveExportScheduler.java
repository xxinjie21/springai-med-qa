package com.med.qa.service;

import com.med.qa.alert.MedAlertNotifier;
import com.med.qa.alert.MedAlertSeverity;
import com.med.qa.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;

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

    private final AtomicReference<SessionArchiveExportReport> lastReport = new AtomicReference<>();

    /**
     * Creates the scheduler.
     *
     * @param exportService the export this scheduler drives, must not be {@code null}
     * @param notifier      provider of the alert dispatcher; absent when alerting is switched off, must
     *                      not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public MedSessionArchiveExportScheduler(MedSessionArchiveExportService exportService,
                                            ObjectProvider<MedAlertNotifier> notifier) {
        if (exportService == null) {
            throw new IllegalArgumentException("exportService must not be null");
        }
        if (notifier == null) {
            throw new IllegalArgumentException("notifier must not be null");
        }
        this.exportService = exportService;
        this.notifier = notifier;
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
     * @return the run report, or {@link Optional#empty()} when the export threw; the failure has then
     *         already been logged and alerted
     */
    public Optional<SessionArchiveExportReport> exportNow() {
        SessionArchiveExportReport report;
        try {
            report = exportService.exportOnce();
        } catch (RuntimeException ex) {
            log.error("the cold archive export failed", ex);
            raise(MedAlertSeverity.WARNING, SESSION_ARCHIVE_FAILED,
                    "the cold archive export failed: " + describe(ex));
            return Optional.empty();
        }
        lastReport.set(report);
        if (report.outcome() == SessionArchiveExportReport.Outcome.SKIPPED_LOCK_HELD) {
            log.debug("cold archive export skipped, another replica holds the lock");
            return Optional.of(report);
        }
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
}
