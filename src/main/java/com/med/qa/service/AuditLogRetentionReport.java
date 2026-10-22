package com.med.qa.service;

import java.util.List;

/**
 * Outcome of one audit-log retention trim run (D55).
 *
 * <p>The trim is the project's fifth background job and the first one whose deletions have no second
 * copy to fall back on: an audit row that falls out of the retention window exists nowhere else, so
 * the report has to answer "what did this run look at" ({@link #expired()}, {@link #remaining()}),
 * "what did it remove" ({@link #deleted()}) and "under which policy" ({@link #retentionDays()},
 * {@link #dryRun()}) with figures an operator can reconcile against a manual
 * {@code SELECT COUNT(*)}.</p>
 *
 * <p>{@link Outcome#SKIPPED_LOCK_HELD} is a <em>successful</em> run, not a failure: another replica
 * was already trimming, which is exactly what the cluster-wide mutex is for. It is reported
 * separately because "the job ran and trimmed nothing" and "the job never ran" must not look
 * alike — the distinction D53's run ledger is built on.</p>
 *
 * <p>Unlike the four session reports, this one carries no {@code failures} list: a batch that cannot
 * be deleted ends the whole run (see {@code MedAuditLogRetentionService}), so a report with a
 * non-empty failure list could never be produced, and a field that is always empty is a field that
 * only looks like information.</p>
 *
 * @param outcome       whether the run trimmed or stepped aside for another replica
 * @param dryRun        whether the run was allowed to delete rows
 * @param retentionDays the retention window the run applied, in days
 * @param expired       how many rows were already past the retention cutoff when the run began;
 *                      in a dry run this is also the number the run decided about
 * @param deleted       how many rows this run deleted; in a dry run, how many it <em>would</em>
 *                      have deleted — an exact simulation (per-batch
 *                      {@code min(remaining expired, batch-size)}), not an approximation of the
 *                      candidate count
 * @param remaining     how many rows were still past the cutoff when the run ended; equals
 *                      {@link #expired()} in a dry run (nothing was deleted) and {@code -1} when it
 *                      was not measured (a run that stepped aside before touching the database)
 * @param batches       how many delete passes were performed (or simulated)
 * @param durationMillis wall-clock duration of the run
 */
public record AuditLogRetentionReport(Outcome outcome,
                                      boolean dryRun,
                                      int retentionDays,
                                      long expired,
                                      long deleted,
                                      long remaining,
                                      int batches,
                                      long durationMillis) {

    /** How a retention trim run ended. */
    public enum Outcome {

        /** The run held the cluster-wide mutex and performed its passes. */
        COMPLETED,

        /** Another replica was already trimming, so this run did not touch the database. */
        SKIPPED_LOCK_HELD
    }

    /**
     * Normalises the report and validates the counters.
     *
     * @throws IllegalArgumentException if a counter is negative (other than {@link #remaining()},
     *                                  whose {@code -1} means "not measured"), the retention window
     *                                  or the outcome is {@code null}
     */
    public AuditLogRetentionReport {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
        if (retentionDays < 1) {
            throw new IllegalArgumentException("retentionDays must be positive but was " + retentionDays);
        }
        if (expired < 0 || deleted < 0 || batches < 0 || durationMillis < 0 || remaining < -1) {
            throw new IllegalArgumentException("retention counters must not be negative");
        }
    }

    /**
     * Creates the report of a run that stepped aside for another replica.
     *
     * @param retentionDays the retention window the deployment is armed with
     * @param durationMillis wall-clock duration of the attempt
     * @return a report with every counter at zero and outcome {@link Outcome#SKIPPED_LOCK_HELD}
     */
    public static AuditLogRetentionReport skipped(int retentionDays, long durationMillis) {
        return new AuditLogRetentionReport(Outcome.SKIPPED_LOCK_HELD, false, retentionDays, 0L, 0L, -1L,
                0, durationMillis);
    }

    /**
     * Tells whether this run removed at least one audit row.
     *
     * @return {@code true} when {@link #deleted()} is positive
     */
    public boolean deletedAnything() {
        return deleted > 0;
    }

    /**
     * Renders a one-line summary for logs and alert messages.
     *
     * @return a single-line, human-readable summary, never {@code null}
     */
    public String summary() {
        return "outcome=" + outcome
                + ", dryRun=" + dryRun
                + ", retentionDays=" + retentionDays
                + ", expired=" + expired
                + ", deleted=" + deleted
                + ", remaining=" + (remaining < 0 ? "unknown" : remaining)
                + ", batches=" + batches
                + ", durationMs=" + durationMillis;
    }
}
