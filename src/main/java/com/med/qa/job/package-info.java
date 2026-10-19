/**
 * Runtime accounting for the background maintenance jobs.
 *
 * <p>This package exists because of a structural blind spot rather than a missing feature. The four
 * maintenance jobs ({@code MedSessionRetentionScheduler}, {@code MedSessionArchiveExportScheduler},
 * {@code MedSessionPurgeScheduler}, {@code MedSessionReclaimScheduler}) each document the same
 * sentence -- "a job that stopped running looks exactly like a hospital with nothing to clean up" --
 * and each of them answers it with an alert that <em>only fires when the job runs</em>. A job that
 * never runs produces no alert at all, so the sentence was true of every one of them.</p>
 *
 * <p>{@link com.med.qa.job.MedJobRunLedger} closes that hole by publishing the absence of data
 * instead of an event: a monotonically refreshed "last attempt" timestamp per job, a "last
 * completed run" timestamp, the interval the deployment armed the job with, and a run counter split
 * by outcome. The Prometheus rules in {@code deploy/prometheus/med-qa-alerts.yml} then page on the
 * <em>staleness</em> of those timestamps, which is the only kind of signal that survives the job
 * itself being gone.</p>
 *
 * <p>{@link com.med.qa.job.MedColdStoreMetrics} (D54) answers the question the ledger cannot: not
 * "is the job alive" but "is the job keeping up". The ledger and every job-specific alert describe one
 * run at a time, so a reclaim that falls further behind every day looks exactly like one that retires
 * every orphan. The cold store's row count, the age of its oldest row and the orphan backlog the last
 * deleting run left behind are published as the {@code med_qa_cold_store_*} gauges, and
 * {@code MedQaColdStoreBacklogGrowing} compares the backlog against itself. Both components read
 * metadata only — aggregates over the cold table, never a payload.</p>
 *
 * <p>Two boundaries are deliberate and documented rather than accidental:</p>
 *
 * <ul>
 *   <li>the two alert monitors ({@code MedStorageAlertMonitor}, {@code MedVectorIndexAlertMonitor})
 *       are <em>not</em> registered. Their liveness is a chicken-and-egg problem -- the alert chain
 *       cannot report that its own producer died -- and the process-level
 *       {@code MedQaTargetDown} rule is what covers it;</li>
 *   <li>neither component has an on/off switch. The ledger is the only evidence that a job did not
 *       run, and the cold-store gauges are registered lazily on the first measurement, so a deployment
 *       whose jobs are all switched off publishes nothing rather than a zero -- "deliberately disabled"
 *       is an operator decision, not a page.</li>
 * </ul>
 *
 * <p>Everything is built on maintained components: Micrometer for the meters, {@code java.time} for
 * the clock. No metric registry, histogram or timer is hand-written.</p>
 */
package com.med.qa.job;
