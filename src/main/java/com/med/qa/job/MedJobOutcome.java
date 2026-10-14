package com.med.qa.job;

/**
 * How one background job run ended.
 *
 * <p>The three values are deliberately the only ones: they are exactly the three answers the ledger
 * needs in order to distinguish "the scheduler is alive" from "the job is actually doing its work".
 * Anything richer (a refusal count, a backlog figure, a batch count) belongs to the run's own report
 * and is not published here -- mixing "is the job alive" with "how well is the job doing" is what
 * makes an alert rule impossible to state precisely.</p>
 *
 * <p>{@link #SKIPPED_LOCK_HELD} is an outcome and not a failure: the four jobs share a cluster-wide
 * Redisson mutex with their peers, so on a multi-replica deployment every replica but one steps aside
 * on every cycle. Treating that as "did not run" would make the liveness signal useless exactly in
 * the deployment shape it matters most for.</p>
 */
public enum MedJobOutcome {

    /**
     * The run held the cluster mutex and performed its passes.
     *
     * <p>This is the only outcome that proves the job's actual work happened, so it is the only one
     * that advances {@link MedJobRunLedger#LAST_SUCCESS_GAUGE}. A completed run that found nothing to
     * do is still completed: the guarantee being watched is "the sweep is running", not "the sweep
     * found work".</p>
     */
    COMPLETED,

    /**
     * Another replica was already running the job, so this run did not touch the database.
     *
     * <p>It still proves this instance's scheduler is alive, so it advances
     * {@link MedJobRunLedger#LAST_RUN_GAUGE} but not the success timestamp.</p>
     */
    SKIPPED_LOCK_HELD,

    /**
     * The run threw before it could report.
     *
     * <p>Counted and stamped as an attempt only. A job that fails every cycle is caught by the
     * cluster-wide staleness of the success timestamp rather than by this value, because the failure
     * alert itself is produced by the job and therefore cannot be trusted to report a job that stopped
     * producing anything at all.</p>
     */
    FAILED
}
