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
 * Drives the orphaned-cold-copy reclaim on a fixed delay and pushes what it did into the existing alert
 * chain.
 *
 * <p>Split from {@link MedSessionReclaimService} so the reclaim itself stays a plain, synchronously
 * testable operation: nothing in this class decides <em>what</em> to delete, only <em>when</em> to look
 * and whom to tell. The same split is used by {@code MedSessionRetentionScheduler},
 * {@code MedSessionArchiveExportScheduler} and {@code MedSessionPurgeScheduler}.</p>
 *
 * <h2>Why the delay is a fixed delay, not a rate</h2>
 * <p>A run holds the cluster mutex while it verifies and deletes its batches. A fixed rate would queue a
 * second run on top of a slow one; a fixed delay measures from the end of the previous run, so a slow
 * reclaim simply happens less often.</p>
 *
 * <h2>What is alerted and what is only logged</h2>
 * <ul>
 *   <li>{@value #SESSION_RECLAIM_COMPLETED} (INFO) — the run retired at least one orphaned copy. Not
 *       raised for a run that deleted nothing (the normal case: a restore is a rare, manual operation)
 *       nor for a dry run, otherwise a deployment that only measures would page forever.</li>
 *   <li>{@value #SESSION_RECLAIM_REFUSED} (WARNING) — at least one cold copy holds messages the live
 *       transcript does not, so the run declined to delete it. <em>Nothing was deleted</em> — this is the
 *       job refusing to act — but it is the only signal that says "there is a copy of a consultation that
 *       the application cannot account for", and it will not clear on its own. A log line would hide it:
 *       the orphan stays a candidate, the backlog stops draining, and that looks exactly like a job that
 *       is keeping up.</li>
 *   <li>{@value #SESSION_RECLAIM_FAILED} (WARNING) — the run threw. Consultations keep working and nothing
 *       has been lost (every deletion is preceded by a containment proof), but the cold store stops
 *       shrinking, and a reclaim that stopped running is indistinguishable from a hospital with no
 *       orphaned copies unless it is alerted.</li>
 *   <li>A run that stepped aside for another replica is logged only. That is the mutex working, not an
 *       incident.</li>
 * </ul>
 *
 * <p>The notifier arrives as an {@link ObjectProvider} because {@code med.alert.enabled=false} removes it
 * from the context; the scheduler then still reclaims and simply has nowhere to push, instead of failing
 * to start.</p>
 */
public class MedSessionReclaimScheduler {

    private static final Logger log = LoggerFactory.getLogger(MedSessionReclaimScheduler.class);

    /** Alert component label used by the reclaim alerts. */
    public static final String COMPONENT = "session-reclaim";

    /** Alert code raised when a run retired at least one orphaned cold copy. */
    public static final String SESSION_RECLAIM_COMPLETED = "session-reclaim-completed";

    /** Alert code raised when a run could not be completed. */
    public static final String SESSION_RECLAIM_FAILED = "session-reclaim-failed";

    /** Alert code raised when a run declined to delete a cold copy it could not account for. */
    public static final String SESSION_RECLAIM_REFUSED = "session-reclaim-refused";

    private final MedSessionReclaimService reclaimService;

    private final ObjectProvider<MedAlertNotifier> notifier;

    private final AtomicReference<SessionReclaimReport> lastReport = new AtomicReference<>();

    /**
     * Creates the scheduler.
     *
     * @param reclaimService the reclaim this scheduler drives, must not be {@code null}
     * @param notifier       provider of the alert dispatcher; absent when alerting is switched off, must
     *                       not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public MedSessionReclaimScheduler(MedSessionReclaimService reclaimService,
                                      ObjectProvider<MedAlertNotifier> notifier) {
        if (reclaimService == null) {
            throw new IllegalArgumentException("reclaimService must not be null");
        }
        if (notifier == null) {
            throw new IllegalArgumentException("notifier must not be null");
        }
        this.reclaimService = reclaimService;
        this.notifier = notifier;
    }

    /**
     * Scheduled entry point; see {@link #reclaimNow()}.
     *
     * <p>Both placeholders are read from {@code med.session.reclaim.check-interval} and
     * {@code med.session.reclaim.initial-delay}, so an operator can slow the reclaim down without a
     * rebuild. The literal after the colon is only a fallback for a context that never loaded
     * {@code application.yml}; {@code MedSessionReclaimConfigTest} pins the keys so the fallback cannot
     * silently take over.</p>
     */
    @Scheduled(fixedDelayString = "${med.session.reclaim.check-interval:12h}",
            initialDelayString = "${med.session.reclaim.initial-delay:15m}")
    public void scheduledReclaim() {
        reclaimNow();
    }

    /**
     * Runs one reclaim, reports its outcome and never throws.
     *
     * @return the run report, or {@link Optional#empty()} when the reclaim threw; the failure has then
     *         already been logged and alerted
     */
    public Optional<SessionReclaimReport> reclaimNow() {
        SessionReclaimReport report;
        try {
            report = reclaimService.reclaimOnce();
        } catch (RuntimeException ex) {
            log.error("the orphaned cold copy reclaim failed", ex);
            raise(MedAlertSeverity.WARNING, SESSION_RECLAIM_FAILED,
                    "the orphaned cold copy reclaim failed: " + describe(ex));
            return Optional.empty();
        }
        lastReport.set(report);
        if (report.outcome() == SessionReclaimReport.Outcome.SKIPPED_LOCK_HELD) {
            log.debug("orphaned cold copy reclaim skipped, another replica holds the lock");
            return Optional.of(report);
        }
        if (report.sawRefusal()) {
            // Raised before the completion notice, and independently of it: a run can retire a hundred
            // copies and still have refused one, and the refusal is the part that needs attention.
            raise(MedAlertSeverity.WARNING, SESSION_RECLAIM_REFUSED,
                    report.refused() + " orphaned cold copy(ies) could not be accounted for and were "
                            + "left untouched: " + report.summary());
        }
        if (report.reclaimedAnything() && !report.dryRun()) {
            raise(MedAlertSeverity.INFO, SESSION_RECLAIM_COMPLETED, report.summary());
        }
        return Optional.of(report);
    }

    /**
     * Returns the most recent successful run report.
     *
     * @return the last report, or {@link Optional#empty()} when no run has completed yet
     */
    public Optional<SessionReclaimReport> lastReport() {
        return Optional.ofNullable(lastReport.get());
    }

    /**
     * Renders a failure for the alert text.
     *
     * <p>A {@link BizException} is rendered with its {@link ErrorCode} rather than its class name, for
     * the same reason the reclaim service's per-candidate failure lines are: "STORAGE_ERROR" tells an
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
            log.debug("no alert notifier in this context; reclaim alert {} was only logged", code);
            return;
        }
        alertNotifier.raise(severity, code, COMPONENT, message);
    }
}
