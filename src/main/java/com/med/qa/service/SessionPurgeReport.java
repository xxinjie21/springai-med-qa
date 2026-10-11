package com.med.qa.service;

import java.util.List;

/**
 * Outcome of one hot-shard purge run (D50).
 *
 * <p>This is the only job in the project that deletes clinical data, so its report has to answer more
 * than "how much did I do": it must say what the run was allowed to do ({@link #dryRun()}), what it
 * looked at ({@link #candidates()}, {@link #remaining()}), what it deleted ({@link #purged()}) and —
 * the two figures that matter most — what it <em>declined</em> to delete and why
 * ({@link #refused()}, {@link #failures()}). A purge that silently skips an unaccountable session looks
 * exactly like a purge with nothing to do, which is the failure mode this report exists to prevent.</p>
 *
 * <p>{@link Outcome#SKIPPED_LOCK_HELD} is a <em>successful</em> run, not a failure: another replica was
 * already purging, which is exactly what the cluster-wide mutex is for. It is reported separately
 * because "the job ran and deleted nothing" and "the job never ran" must not look alike.</p>
 *
 * @param outcome                  whether the run purged or stepped aside for another replica
 * @param dryRun                   whether the run was allowed to delete rows
 * @param archivedBeforeEpochMillis the cooling-off cutoff applied: a session is eligible when it was
 *                                 archived at or before this epoch-millisecond instant
 * @param candidates               how many certified sessions this run looked at, across all passes
 * @param purged                   how many sessions had their hot rows deleted; in a dry run, how many
 *                                 <em>would</em> have had them deleted
 * @param skipped                  how many candidates had no hot rows left to delete (a previous run
 *                                 retired them but was interrupted before it could mark them)
 * @param refused                  how many candidates were <em>declined</em>: the cold copy or the live
 *                                 shard did not reproduce the manifest's digest, so the run had no
 *                                 accountable basis for deleting anything. Non-zero means a human has
 *                                 to look; nothing was deleted
 * @param failed                   how many candidates could not be processed at all; one failure never
 *                                 aborts the run, but a non-zero value always means a partial run
 * @param batches                  how many passes were performed
 * @param remaining                how many certified sessions were still holding hot rows when the run
 *                                 ended; a non-zero value with {@link #outcome()} {@code COMPLETED}
 *                                 simply means the run hit {@code max-batches} and the next run will
 *                                 continue, and {@code -1} means it was not measured (a run that stepped
 *                                 aside before touching the database)
 * @param durationMillis           wall-clock duration of the run
 * @param failures                 one human-readable line per refused or failed candidate, empty when
 *                                 there were none
 */
public record SessionPurgeReport(Outcome outcome,
                                 boolean dryRun,
                                 long archivedBeforeEpochMillis,
                                 int candidates,
                                 int purged,
                                 int skipped,
                                 int refused,
                                 int failed,
                                 int batches,
                                 long remaining,
                                 long durationMillis,
                                 List<String> failures) {

    /** How a purge run ended. */
    public enum Outcome {

        /** The run held the cluster-wide mutex and performed its passes. */
        COMPLETED,

        /** Another replica was already purging, so this run did not touch the database. */
        SKIPPED_LOCK_HELD
    }

    /**
     * Normalises the report and validates the counters.
     *
     * @throws IllegalArgumentException if a counter is negative (other than {@link #remaining()}, whose
     *                                  {@code -1} means "not measured"), the outcome is {@code null} or
     *                                  the failure list is {@code null}
     */
    public SessionPurgeReport {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
        if (candidates < 0 || purged < 0 || skipped < 0 || refused < 0 || failed < 0 || batches < 0
                || durationMillis < 0 || remaining < -1) {
            throw new IllegalArgumentException("purge counters must not be negative");
        }
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    /**
     * Creates the report of a run that stepped aside for another replica.
     *
     * @param archivedBeforeEpochMillis the cutoff the run would have applied
     * @param durationMillis            wall-clock duration of the attempt
     * @return a report with every counter at zero and outcome {@link Outcome#SKIPPED_LOCK_HELD}
     */
    public static SessionPurgeReport skipped(long archivedBeforeEpochMillis, long durationMillis) {
        return new SessionPurgeReport(Outcome.SKIPPED_LOCK_HELD, false, archivedBeforeEpochMillis,
                0, 0, 0, 0, 0, 0, -1, durationMillis, List.of());
    }

    /**
     * Tells whether this run retired at least one session.
     *
     * @return {@code true} when {@link #purged()} is positive
     */
    public boolean purgedAnything() {
        return purged > 0;
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
                + ", purged=" + purged
                + ", skipped=" + skipped
                + ", refused=" + refused
                + ", failed=" + failed
                + ", batches=" + batches
                + ", remaining=" + (remaining < 0 ? "unknown" : remaining)
                + ", durationMs=" + durationMillis;
    }
}
