package com.med.qa.service;

import java.util.List;

/**
 * Outcome of one orphaned-cold-copy reclaim run (D52).
 *
 * <p>This is the second job in the project that deletes clinical data, so its report has to answer more
 * than "how much did I do": it must say what the run was allowed to do ({@link #dryRun()}), what it
 * looked at ({@link #candidates()}, {@link #remaining()}), what it retired ({@link #reclaimed()},
 * {@link #reclaimedMessages()}) and — the two figures that matter most — what it <em>declined</em> to
 * retire and why ({@link #refused()}, {@link #failures()}). A reclaim that silently skips an
 * unaccountable orphan looks exactly like a reclaim with nothing to do, which is the failure mode this
 * report exists to prevent.</p>
 *
 * <p>{@link Outcome#SKIPPED_LOCK_HELD} is a <em>successful</em> run, not a failure: another replica was
 * already reclaiming, which is exactly what the cluster-wide mutex is for. It is reported separately
 * because "the job ran and retired nothing" and "the job never ran" must not look alike.</p>
 *
 * <p>One field has no counterpart in {@code SessionPurgeReport}: {@link #reclaimedMessages()}. The purge
 * retires a session, and what it removed is best described in sessions; the reclaim frees <em>space</em>,
 * and the only figure that describes it is the number of archived rows that left the cold table.</p>
 *
 * @param outcome           whether the run reclaimed or stepped aside for another replica
 * @param dryRun            whether the run was allowed to delete rows
 * @param candidates        how many orphaned sessions this run looked at, across all passes
 * @param reclaimed         how many sessions had their orphaned cold copy deleted; in a dry run, how
 *                          many <em>would</em> have had it deleted
 * @param reclaimedMessages how many {@code med_message_archive} rows were deleted (or, in a dry run,
 *                          would have been)
 * @param skipped           how many candidates stopped being reclaimable between the candidate query and
 *                          the lock: the cold copy was already gone, the session became cold again, or
 *                          a certification appeared for it. Nothing was deleted for any of them
 * @param refused           how many candidates were <em>declined</em>: the cold copy holds messages the
 *                          live transcript does not, so it may be the only surviving copy of them and the
 *                          run had no basis for deleting anything. Non-zero means a human has to look;
 *                          nothing was deleted
 * @param failed            how many candidates could not be processed at all; one failure never aborts
 *                          the run, but a non-zero value always means a partial run
 * @param batches           how many passes were performed
 * @param remaining         how many orphaned copies were still there when the run ended; unlike the
 *                          purge's backlog this one is observable by the candidate predicate itself, so
 *                          a non-zero value with {@link #outcome()} {@code COMPLETED} simply means the
 *                          run hit {@code max-batches}, and {@code -1} means it was not measured (a run
 *                          that stepped aside before touching the database)
 * @param durationMillis    wall-clock duration of the run
 * @param failures          one human-readable line per refused or failed candidate, empty when there
 *                          were none
 */
public record SessionReclaimReport(Outcome outcome,
                                   boolean dryRun,
                                   int candidates,
                                   int reclaimed,
                                   long reclaimedMessages,
                                   int skipped,
                                   int refused,
                                   int failed,
                                   int batches,
                                   long remaining,
                                   long durationMillis,
                                   List<String> failures) {

    /** How a reclaim run ended. */
    public enum Outcome {

        /** The run held the cluster-wide mutex and performed its passes. */
        COMPLETED,

        /** Another replica was already reclaiming, so this run did not touch the database. */
        SKIPPED_LOCK_HELD
    }

    /**
     * Normalises the report and validates the counters.
     *
     * @throws IllegalArgumentException if a counter is negative (other than {@link #remaining()}, whose
     *                                  {@code -1} means "not measured"), the outcome is {@code null} or
     *                                  the failure list is {@code null}
     */
    public SessionReclaimReport {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
        if (candidates < 0 || reclaimed < 0 || reclaimedMessages < 0 || skipped < 0 || refused < 0
                || failed < 0 || batches < 0 || durationMillis < 0 || remaining < -1) {
            throw new IllegalArgumentException("reclaim counters must not be negative");
        }
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    /**
     * Creates the report of a run that stepped aside for another replica.
     *
     * @param durationMillis wall-clock duration of the attempt
     * @return a report with every counter at zero and outcome {@link Outcome#SKIPPED_LOCK_HELD}
     */
    public static SessionReclaimReport skipped(long durationMillis) {
        return new SessionReclaimReport(Outcome.SKIPPED_LOCK_HELD, false, 0, 0, 0L, 0, 0, 0, 0, -1,
                durationMillis, List.of());
    }

    /**
     * Tells whether this run retired at least one orphaned copy.
     *
     * @return {@code true} when {@link #reclaimed()} is positive
     */
    public boolean reclaimedAnything() {
        return reclaimed > 0;
    }

    /**
     * Tells whether this run declined to delete anything it looked at.
     *
     * @return {@code true} when {@link #refused()} is positive
     */
    public boolean sawRefusal() {
        return refused > 0;
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
                + ", reclaimed=" + reclaimed
                + ", reclaimedMessages=" + reclaimedMessages
                + ", skipped=" + skipped
                + ", refused=" + refused
                + ", failed=" + failed
                + ", batches=" + batches
                + ", remaining=" + (remaining < 0 ? "unknown" : remaining)
                + ", durationMs=" + durationMillis;
    }
}
