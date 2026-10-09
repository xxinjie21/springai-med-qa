package com.med.qa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Externalized policy of the cold transcript archive export, bound from
 * {@code med.session.archive.*}.
 *
 * <pre>
 * med:
 *   session:
 *     archive:
 *       enabled: false          # master switch; false removes the export and its scheduler entirely
 *       dry-run: true           # report what would be exported without writing a single row
 *       batch-size: 50          # sessions fetched per pass
 *       max-batches: 5          # upper bound on passes per run, i.e. sessions touched per run
 *       check-interval: 6h      # delay between two runs
 *       initial-delay: 10m      # grace period after boot before the first run
 *       lock-wait-time: 5s      # how long a run queues behind another replica's run
 *       lock-lease-time: 5m     # 0 hands lease management to the Redisson watchdog
 * </pre>
 *
 * <h2>Why this exists</h2>
 * <p>D47 turns an abandoned consultation into {@code ARCHIVED} and drops its Redis window, but the
 * transcript itself never moves: it stays in the 16 sharded {@code med_message} tables. So "this
 * consultation is archived" had no verifiable counterpart in storage - no cold copy, and no digest
 * that could prove a cold copy complete. The export closes that gap: it copies the transcript into the
 * non-sharded {@code med_message_archive} table in the storage-spec Protobuf encoding and records a
 * manifest with the message count and a SHA-256 of the canonical transcript form.</p>
 *
 * <h2>Two switches, not one</h2>
 * <p>{@link #isEnabled()} defaults to {@code false} for the same reason as every other background
 * actor in this project: it writes to clinical records nobody asked about, so its presence has to be a
 * deliberate deployment decision. {@link #isDryRun()} defaults to {@code true} on top of that, so
 * enabling the capability still copies nothing until an operator has read at least one report. This
 * mirrors the two-switch design of the D38 index rebuild and the D47 retention sweep.</p>
 *
 * <h2>What the export can never do</h2>
 * <p>It only ever inserts. No code path here deletes or updates a message in the hot shards, and none
 * deletes a row from the cold store: {@code MedChatSessionService#archiveSession}'s contract - "a
 * wrongly archived session can be moved back" - stays true only as long as the transcript is still
 * there. Purging the hot rows is a separate, irreversible operation that needs its own guard and its
 * own iteration.</p>
 *
 * <p>Every setter validates eagerly so a typo fails the application context at startup instead of
 * silently turning the export into a no-op or into an unbounded walk of every archived session.</p>
 */
@ConfigurationProperties(prefix = MedSessionArchiveProperties.PREFIX)
public class MedSessionArchiveProperties {

    /** Configuration namespace owned by this class. */
    public static final String PREFIX = "med.session.archive";

    /**
     * Redis key of the cluster-wide export mutex.
     *
     * <p>Deliberately not configurable: the key schema is what makes two replicas exclude each other,
     * and a per-deployment key would silently remove that guarantee. It stays in the
     * {@code med:lock:} namespace alongside {@code med:lock:chat:*},
     * {@code med:lock:session:retention} and {@code med:lock:rag:index:rebuild:*}.</p>
     */
    public static final String LOCK_KEY = "med:lock:session:archive:export";

    /** Default number of archived sessions fetched per pass. */
    public static final int DEFAULT_BATCH_SIZE = 50;

    /** Default upper bound on the number of passes of a single run. */
    public static final int DEFAULT_MAX_BATCHES = 5;

    /** Default delay between two runs. */
    public static final Duration DEFAULT_CHECK_INTERVAL = Duration.ofHours(6);

    /** Default grace period after boot before the first run. */
    public static final Duration DEFAULT_INITIAL_DELAY = Duration.ofMinutes(10);

    /** Default time a run queues for the export mutex before giving up. */
    public static final Duration DEFAULT_LOCK_WAIT_TIME = Duration.ofSeconds(5);

    /** Default lease of the export mutex; {@link Duration#ZERO} hands renewal to the watchdog. */
    public static final Duration DEFAULT_LOCK_LEASE_TIME = Duration.ofMinutes(5);

    private boolean enabled = false;

    private boolean dryRun = true;

    private int batchSize = DEFAULT_BATCH_SIZE;

    private int maxBatches = DEFAULT_MAX_BATCHES;

    private Duration checkInterval = DEFAULT_CHECK_INTERVAL;

    private Duration initialDelay = DEFAULT_INITIAL_DELAY;

    private Duration lockWaitTime = DEFAULT_LOCK_WAIT_TIME;

    private Duration lockLeaseTime = DEFAULT_LOCK_LEASE_TIME;

    /**
     * Creates the properties with their conservative defaults.
     */
    public MedSessionArchiveProperties() {
    }

    /**
     * Tells whether the cold archive export is part of this deployment.
     *
     * @return {@code true} when the service and its scheduler should be contributed
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Switches the cold archive export on or off.
     *
     * @param enabled {@code true} to contribute the export and its scheduler
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Tells whether the export only reports.
     *
     * @return {@code true} when nothing is written to the cold store
     */
    public boolean isDryRun() {
        return dryRun;
    }

    /**
     * Switches between a reporting run and a writing one.
     *
     * @param dryRun {@code true} to log what would be exported and write nothing
     */
    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    /**
     * Returns the number of archived sessions fetched per pass.
     *
     * @return a strictly positive row count
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * Sets the number of archived sessions fetched per pass.
     *
     * @param batchSize a strictly positive row count
     * @throws IllegalArgumentException if {@code batchSize} is not positive
     */
    public void setBatchSize(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException(
                    PREFIX + ".batch-size must be positive but was " + batchSize);
        }
        this.batchSize = batchSize;
    }

    /**
     * Returns the maximum number of passes one run may perform.
     *
     * @return a strictly positive pass count
     */
    public int getMaxBatches() {
        return maxBatches;
    }

    /**
     * Sets the maximum number of passes one run may perform.
     *
     * @param maxBatches a strictly positive pass count; {@code batch-size * max-batches} is the
     *                   largest number of sessions one run can export
     * @throws IllegalArgumentException if {@code maxBatches} is not positive
     */
    public void setMaxBatches(int maxBatches) {
        if (maxBatches < 1) {
            throw new IllegalArgumentException(
                    PREFIX + ".max-batches must be positive but was " + maxBatches);
        }
        this.maxBatches = maxBatches;
    }

    /**
     * Returns the delay between two runs.
     *
     * @return a strictly positive duration, never {@code null}
     */
    public Duration getCheckInterval() {
        return checkInterval;
    }

    /**
     * Sets the delay between two runs.
     *
     * @param checkInterval a strictly positive duration; the scheduler uses it as a fixed delay, so a
     *                      slow run never overlaps the next one
     * @throws IllegalArgumentException if {@code checkInterval} is {@code null}, zero or negative
     */
    public void setCheckInterval(Duration checkInterval) {
        if (checkInterval == null || checkInterval.isZero() || checkInterval.isNegative()) {
            throw new IllegalArgumentException(
                    PREFIX + ".check-interval must be a positive duration but was " + checkInterval);
        }
        this.checkInterval = checkInterval;
    }

    /**
     * Returns the grace period after boot before the first run.
     *
     * @return a non-negative duration, never {@code null}
     */
    public Duration getInitialDelay() {
        return initialDelay;
    }

    /**
     * Sets the grace period after boot before the first run.
     *
     * @param initialDelay a non-negative duration; zero is legal and means "start immediately", which
     *                     is what a short-lived integration test wants
     * @throws IllegalArgumentException if {@code initialDelay} is {@code null} or negative
     */
    public void setInitialDelay(Duration initialDelay) {
        if (initialDelay == null || initialDelay.isNegative()) {
            throw new IllegalArgumentException(
                    PREFIX + ".initial-delay must not be negative but was " + initialDelay);
        }
        this.initialDelay = initialDelay;
    }

    /**
     * Returns how long a run queues for the export mutex.
     *
     * @return a non-negative duration, never {@code null}
     */
    public Duration getLockWaitTime() {
        return lockWaitTime;
    }

    /**
     * Sets how long a run queues for the export mutex.
     *
     * @param lockWaitTime a non-negative duration; zero turns acquisition into a single attempt, which
     *                     is what a scheduled job wants - a replica that lost the race should step
     *                     aside rather than pile up
     * @throws IllegalArgumentException if {@code lockWaitTime} is {@code null} or negative
     */
    public void setLockWaitTime(Duration lockWaitTime) {
        if (lockWaitTime == null || lockWaitTime.isNegative()) {
            throw new IllegalArgumentException(
                    PREFIX + ".lock-wait-time must not be negative but was " + lockWaitTime);
        }
        this.lockWaitTime = lockWaitTime;
    }

    /**
     * Returns the fixed lease of the export mutex.
     *
     * @return a non-negative duration, never {@code null}; {@link Duration#ZERO} delegates lease
     *         management to the Redisson watchdog
     */
    public Duration getLockLeaseTime() {
        return lockLeaseTime;
    }

    /**
     * Sets the fixed lease of the export mutex.
     *
     * @param lockLeaseTime a non-negative duration; zero enables the Redisson watchdog, which is the
     *                      right choice when a run may outlive any hand-picked lease
     * @throws IllegalArgumentException if {@code lockLeaseTime} is {@code null} or negative; a
     *                                  negative lease would create a lock nobody can ever release
     */
    public void setLockLeaseTime(Duration lockLeaseTime) {
        if (lockLeaseTime == null || lockLeaseTime.isNegative()) {
            throw new IllegalArgumentException(
                    PREFIX + ".lock-lease-time must not be negative but was " + lockLeaseTime);
        }
        this.lockLeaseTime = lockLeaseTime;
    }

    /**
     * Tells whether Redisson's automatic lease renewal is in charge of the export mutex.
     *
     * @return {@code true} when no fixed lease time is configured
     */
    public boolean isLockWatchdogEnabled() {
        return lockLeaseTime.isZero();
    }

    /**
     * Returns the largest number of sessions a single run may export.
     *
     * @return {@code batch-size * max-batches}, always positive
     */
    public int maxSessionsPerRun() {
        return batchSize * maxBatches;
    }

    @Override
    public String toString() {
        return "MedSessionArchiveProperties{enabled=" + enabled
                + ", dryRun=" + dryRun
                + ", batchSize=" + batchSize
                + ", maxBatches=" + maxBatches
                + ", checkInterval=" + checkInterval
                + ", initialDelay=" + initialDelay
                + ", lockWaitTime=" + lockWaitTime
                + ", lockLeaseTime=" + lockLeaseTime
                + '}';
    }
}
