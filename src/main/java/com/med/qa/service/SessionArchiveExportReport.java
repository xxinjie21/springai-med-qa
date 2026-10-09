package com.med.qa.service;

import java.util.List;

/**
 * Outcome of one cold-archive export run (D48).
 *
 * <p>The report is the only thing an operator gets from an unattended background job, so it answers
 * what the run was allowed to do ({@link #dryRun()}), how much work it found ({@link #candidates()},
 * {@link #remaining()}), what it achieved ({@link #exported()}) and — the part that matters most for a
 * data-integrity job — whether anything it copied failed to verify ({@link #mismatched()}).</p>
 *
 * <p>{@link Outcome#SKIPPED_LOCK_HELD} is a <em>successful</em> run, not a failure: another replica was
 * already exporting, which is exactly what the cluster-wide mutex is for. It is reported separately
 * because "the job ran and exported nothing" and "the job never ran" must not look alike — the second
 * one is the silent failure this iteration exists to prevent.</p>
 *
 * @param outcome         whether the run exported or stepped aside for another replica
 * @param dryRun          whether the run was allowed to write to the cold store
 * @param candidates      how many archived sessions without a manifest this run looked at, across all
 *                        passes
 * @param exported        how many sessions were copied and certified with a manifest
 * @param skipped         how many candidates turned out to have been exported already by the time the
 *                        manifest insert ran; the copy itself is idempotent, so this is a benign race,
 *                        not a failure
 * @param mismatched      how many candidates were copied but <em>refused certification</em>: either the
 *                        source transcript changed while the export was running, or the cold copy read
 *                        back with a different digest. Non-zero means the cold store does not yet hold
 *                        a trustworthy copy of those sessions, which is the one condition this job must
 *                        never report as a success
 * @param failed          how many candidates could not be processed at all; one failure never aborts
 *                        the run, but a non-zero value always means a partial run
 * @param batches         how many passes were performed
 * @param remaining       how many archived sessions were still without a manifest when the run ended;
 *                        a non-zero value with {@link #outcome()} {@code COMPLETED} simply means the run
 *                        hit {@code max-batches} and the next run will continue, and {@code -1} means it
 *                        was not measured (a run that stepped aside before touching the database)
 * @param durationMillis  wall-clock duration of the run
 * @param failures        one human-readable line per candidate that failed or was refused, empty when
 *                        none did
 */
public record SessionArchiveExportReport(Outcome outcome,
                                         boolean dryRun,
                                         int candidates,
                                         int exported,
                                         int skipped,
                                         int mismatched,
                                         int failed,
                                         int batches,
                                         long remaining,
                                         long durationMillis,
                                         List<String> failures) {

    /** How an export run ended. */
    public enum Outcome {

        /** The run held the cluster-wide mutex and performed its passes. */
        COMPLETED,

        /** Another replica was already exporting, so this run did not touch the database. */
        SKIPPED_LOCK_HELD
    }

    /**
     * Normalises the report and validates the counters.
     *
     * @throws IllegalArgumentException if a counter is negative (other than {@link #remaining()},
     *                                  whose {@code -1} means "not measured"), the outcome is
     *                                  {@code null} or the failure list is {@code null}
     */
    public SessionArchiveExportReport {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
        if (candidates < 0 || exported < 0 || skipped < 0 || mismatched < 0 || failed < 0
                || batches < 0 || durationMillis < 0 || remaining < -1) {
            throw new IllegalArgumentException("archive export counters must not be negative");
        }
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    /**
     * Creates the report of a run that stepped aside for another replica.
     *
     * @param durationMillis wall-clock duration of the attempt
     * @return a report with every counter at zero and outcome {@link Outcome#SKIPPED_LOCK_HELD}
     */
    public static SessionArchiveExportReport skipped(long durationMillis) {
        return new SessionArchiveExportReport(Outcome.SKIPPED_LOCK_HELD, false, 0, 0, 0, 0, 0, 0, -1,
                durationMillis, List.of());
    }

    /**
     * Tells whether this run certified at least one session.
     *
     * @return {@code true} when {@link #exported()} is positive
     */
    public boolean exportedAnything() {
        return exported > 0;
    }

    /**
     * Tells whether this run saw a cold copy it could not certify.
     *
     * @return {@code true} when {@link #mismatched()} is positive
     */
    public boolean sawMismatch() {
        return mismatched > 0;
    }

    /**
     * Renders a one-line summary for logs and alert messages.
     *
     * @return a single-line, human-readable summary, never {@code null}
     */
    public String summary() {
        return "outcome=" + outcome
                + ", dryRun=" + dryRun
                + ", candidates=" + candidates
                + ", exported=" + exported
                + ", skipped=" + skipped
                + ", mismatched=" + mismatched
                + ", failed=" + failed
                + ", batches=" + batches
                + ", remaining=" + (remaining < 0 ? "unknown" : remaining)
                + ", durationMs=" + durationMillis;
    }
}
