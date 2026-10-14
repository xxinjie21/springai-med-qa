package com.med.qa.job;

import java.time.Instant;

/**
 * One background job run, as handed to {@link MedJobRunLedger}.
 *
 * <p>This is the only shape the ledger accepts, and it carries no business counters on purpose: the
 * ledger answers "when did this job last run and how did it end", not "how much did it process". A
 * run's own report (for example {@code SessionPurgeReport}) remains the place where candidates,
 * deletions and backlogs live, so the two notions of "how the run went" cannot drift into two
 * incompatible sets of counters.</p>
 *
 * <p>{@link #startedAt} is the instant the attempt began, and the ledger publishes that instant
 * rather than the instant the run finished. The series therefore measures <em>attempt cadence</em>,
 * which is what a stall check needs, and it does not move around with how long a run happened to
 * take.</p>
 *
 * @param job            the job's stable identifier, the same string used as the {@code component}
 *                       label of its alerts (for example {@code session-retention})
 * @param outcome        how the run ended
 * @param startedAt      the instant the attempt began
 * @param durationMillis wall-clock duration of the attempt
 */
public record MedJobRun(String job,
                        MedJobOutcome outcome,
                        Instant startedAt,
                        long durationMillis) {

    /**
     * Normalises and validates the record.
     *
     * @throws IllegalArgumentException if {@code job} is {@code null} or blank, if {@code outcome} or
     *                                  {@code startedAt} is {@code null}, or if
     *                                  {@code durationMillis} is negative
     */
    public MedJobRun {
        if (job == null || job.isBlank()) {
            throw new IllegalArgumentException("job must not be blank");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
        if (startedAt == null) {
            throw new IllegalArgumentException("startedAt must not be null");
        }
        if (durationMillis < 0) {
            throw new IllegalArgumentException(
                    "durationMillis must not be negative but was " + durationMillis);
        }
    }

    /**
     * Creates a record for a run that held the mutex and performed its passes.
     *
     * @param job            the job's stable identifier
     * @param startedAt      the instant the attempt began
     * @param durationMillis wall-clock duration of the attempt
     * @return the record, with {@link MedJobOutcome#COMPLETED}
     */
    public static MedJobRun completed(String job, Instant startedAt, long durationMillis) {
        return new MedJobRun(job, MedJobOutcome.COMPLETED, startedAt, durationMillis);
    }

    /**
     * Creates a record for a run that stepped aside for another replica.
     *
     * @param job            the job's stable identifier
     * @param startedAt      the instant the attempt began
     * @param durationMillis wall-clock duration of the attempt
     * @return the record, with {@link MedJobOutcome#SKIPPED_LOCK_HELD}
     */
    public static MedJobRun skippedLockHeld(String job, Instant startedAt, long durationMillis) {
        return new MedJobRun(job, MedJobOutcome.SKIPPED_LOCK_HELD, startedAt, durationMillis);
    }

    /**
     * Creates a record for a run that threw.
     *
     * @param job            the job's stable identifier
     * @param startedAt      the instant the attempt began
     * @param durationMillis wall-clock duration of the attempt
     * @return the record, with {@link MedJobOutcome#FAILED}
     */
    public static MedJobRun failed(String job, Instant startedAt, long durationMillis) {
        return new MedJobRun(job, MedJobOutcome.FAILED, startedAt, durationMillis);
    }

    /**
     * Tells whether this run actually performed the job's work.
     *
     * @return {@code true} when the outcome is {@link MedJobOutcome#COMPLETED}
     */
    public boolean completed() {
        return outcome == MedJobOutcome.COMPLETED;
    }
}
