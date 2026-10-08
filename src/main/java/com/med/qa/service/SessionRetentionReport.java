package com.med.qa.service;

import java.util.List;

/**
 * Outcome of one consultation retention sweep.
 *
 * <p>The report is the only thing an operator gets from an unattended background job, so it answers
 * four questions on its own: what the sweep was told to consider stale
 * ({@link #idleBeforeEpochMillis()}), how much work it found ({@link #candidates()},
 * {@link #remaining()}), what it actually changed ({@link #archived()}, {@link #skipped()},
 * {@link #failed()}) and whether it was allowed to change anything at all ({@link #dryRun()}).</p>
 *
 * <p>{@link Outcome#SKIPPED_LOCK_HELD} is a <em>successful</em> run, not a failure: another replica was
 * already sweeping, which is exactly what the cluster-wide mutex is for. It is reported separately
 * because "the job ran and archived nothing" and "the job never ran" must not look alike — the second
 * one is the silent failure this iteration exists to prevent.</p>
 *
 * @param outcome             whether the sweep ran or stepped aside for another replica
 * @param dryRun              whether the run was allowed to modify rows
 * @param idleBeforeEpochMillis the cutoff applied: a session is stale when its {@code updated_at} is
 *                              not newer than this epoch-millisecond instant
 * @param candidates          how many stale sessions this run looked at, across all passes
 * @param archived            how many sessions this run moved to {@code ARCHIVED}
 * @param skipped             how many candidates were left alone because they were no longer stale by
 *                            the time the sweep reached them (a message arrived in between)
 * @param failed              how many candidates could not be processed; one failure never aborts the
 *                            run, but a non-zero value always means a partial sweep
 * @param batches             how many passes were performed
 * @param remaining           how many stale sessions were still outstanding when the run ended; a
 *                            non-zero value with {@link #outcome()} {@code COMPLETED} simply means the
 *                            run hit {@code max-batches} and the next run will continue, and
 *                            {@code -1} means it was not measured (a run that stepped aside before
 *                            touching the database)
 * @param durationMillis      wall-clock duration of the run
 * @param failures            one human-readable line per failed candidate, empty when none failed
 */
public record SessionRetentionReport(Outcome outcome,
                                     boolean dryRun,
                                     long idleBeforeEpochMillis,
                                     int candidates,
                                     int archived,
                                     int skipped,
                                     int failed,
                                     int batches,
                                     long remaining,
                                     long durationMillis,
                                     List<String> failures) {

    /** How a sweep ended. */
    public enum Outcome {

        /** The sweep held the cluster-wide mutex and performed its passes. */
        COMPLETED,

        /** Another replica was already sweeping, so this run did not touch MySQL. */
        SKIPPED_LOCK_HELD
    }

    /**
     * Normalises the report and validates the counters.
     *
     * @throws IllegalArgumentException if a counter is negative (other than {@link #remaining()},
     *                                  whose {@code -1} means "not measured"), the outcome is
     *                                  {@code null} or the failure list is {@code null}
     */
    public SessionRetentionReport {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
        if (candidates < 0 || archived < 0 || skipped < 0 || failed < 0 || batches < 0
                || durationMillis < 0 || remaining < -1) {
            throw new IllegalArgumentException("retention counters must not be negative");
        }
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    /**
     * Creates the report of a run that stepped aside for another replica.
     *
     * @param idleBeforeEpochMillis the cutoff the run would have applied
     * @param durationMillis        wall-clock duration of the attempt
     * @return a report with every counter at zero and outcome {@link Outcome#SKIPPED_LOCK_HELD}
     */
    public static SessionRetentionReport skipped(long idleBeforeEpochMillis, long durationMillis) {
        return new SessionRetentionReport(Outcome.SKIPPED_LOCK_HELD, false, idleBeforeEpochMillis,
                0, 0, 0, 0, 0, -1, durationMillis, List.of());
    }

    /**
     * Tells whether this run changed at least one session.
     *
     * @return {@code true} when {@link #archived()} is positive
     */
    public boolean archivedAnything() {
        return archived > 0;
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
                + ", archived=" + archived
                + ", skipped=" + skipped
                + ", failed=" + failed
                + ", batches=" + batches
                + ", remaining=" + (remaining < 0 ? "unknown" : remaining)
                + ", durationMs=" + durationMillis;
    }
}
