package com.med.qa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Externalized policy of the hot-shard purge, bound from {@code med.session.purge.*}.
 *
 * <pre>
 * med:
 *   session:
 *     purge:
 *       enabled: false           # master switch; false removes the purge and its scheduler entirely
 *       dry-run: true            # verify and report what would be deleted, delete nothing
 *       min-archived-age: 7d     # cooling-off: a session must have been archived at least this long
 *       batch-size: 50           # certified sessions fetched per pass
 *       max-batches: 5           # upper bound on passes per run, i.e. sessions touched per run
 *       check-interval: 12h      # delay between two runs
 *       initial-delay: 15m       # grace period after boot before the first run
 *       lock-wait-time: 5s       # how long a run queues behind another replica's run
 *       lock-lease-time: 5m      # 0 hands lease management to the Redisson watchdog
 * </pre>
 *
 * <h2>Why this exists</h2>
 * <p>D47 archives a stale consultation, D48 copies its transcript into the cold store and certifies the
 * copy with a digest, D49 makes that copy readable. Until D50 the sharded {@code med_message} rows were
 * nevertheless kept forever: "archived" never reduced the size of the hot tier, so the cold/hot split
 * was only half done. This job completes it — and it is the only job in the project that deletes
 * clinical data, which is why it carries more guards than any other.</p>
 *
 * <h2>Three switches, not two</h2>
 * <p>{@link #isEnabled()} defaults to {@code false} and {@link #isDryRun()} defaults to {@code true},
 * exactly as in the D38 index rebuild, the D47 sweep and the D48 export. On top of those,
 * {@link #getMinArchivedAge()} is a cooling-off window rather than a switch: it keeps the promise D47
 * made — "a wrongly archived session can be moved back" — from becoming an empty one, because moving it
 * back requires the transcript to still be there. A purge that could run immediately after archiving
 * would delete the very rows that promise depends on.</p>
 *
 * <h2>What the purge can never do</h2>
 * <p>It only ever deletes from {@code med_message}. The cold tables are read-only for this class: the
 * archived rows are the evidence that the transcript was archived, and the manifest is the
 * certification; deleting either would destroy the only thing that makes a deletion accountable.</p>
 *
 * <p>Every setter validates eagerly so a typo fails the application context at startup instead of
 * silently turning the purge into a no-op or into an unbounded walk of every archived session.</p>
 */
@ConfigurationProperties(prefix = MedSessionPurgeProperties.PREFIX)
public class MedSessionPurgeProperties {

    /** Configuration namespace owned by this class. */
    public static final String PREFIX = "med.session.purge";

    /**
     * Redis key of the cluster-wide purge mutex.
     *
     * <p>Deliberately not configurable: the key schema is what makes two replicas exclude each other,
     * and a per-deployment key would silently remove that guarantee. It stays in the
     * {@code med:lock:} namespace alongside {@code med:lock:chat:*},
     * {@code med:lock:session:retention}, {@code med:lock:session:archive:export} and
     * {@code med:lock:rag:index:rebuild:*}.</p>
     */
    public static final String LOCK_KEY = "med:lock:session:purge";

    /** Default cooling-off window between archiving a session and being allowed to delete its rows. */
    public static final Duration DEFAULT_MIN_ARCHIVED_AGE = Duration.ofDays(7);

    /** Default number of certified sessions fetched per pass. */
    public static final int DEFAULT_BATCH_SIZE = 50;

    /** Default upper bound on the number of passes of a single run. */
    public static final int DEFAULT_MAX_BATCHES = 5;

    /** Default delay between two runs. */
    public static final Duration DEFAULT_CHECK_INTERVAL = Duration.ofHours(12);

    /** Default grace period after boot before the first run. */
    public static final Duration DEFAULT_INITIAL_DELAY = Duration.ofMinutes(15);

    /** Default time a run queues for the purge mutex before giving up. */
    public static final Duration DEFAULT_LOCK_WAIT_TIME = Duration.ofSeconds(5);

    /** Default lease of the purge mutex; {@link Duration#ZERO} hands renewal to the watchdog. */
    public static final Duration DEFAULT_LOCK_LEASE_TIME = Duration.ofMinutes(5);

    private boolean enabled = false;

    private boolean dryRun = true;

    private Duration minArchivedAge = DEFAULT_MIN_ARCHIVED_AGE;

    private int batchSize = DEFAULT_BATCH_SIZE;

    private int maxBatches = DEFAULT_MAX_BATCHES;

    private Duration checkInterval = DEFAULT_CHECK_INTERVAL;

    private Duration initialDelay = DEFAULT_INITIAL_DELAY;

    private Duration lockWaitTime = DEFAULT_LOCK_WAIT_TIME;

    private Duration lockLeaseTime = DEFAULT_LOCK_LEASE_TIME;

    /**
     * Creates the properties with their conservative defaults.
     */
    public MedSessionPurgeProperties() {
    }

    /**
     * Tells whether the hot-shard purge is part of this deployment.
     *
     * @return {@code true} when the service and its scheduler should be contributed
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Switches the purge on or off.
     *
     * @param enabled {@code true} to contribute the purge and its scheduler
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Tells whether the purge only verifies and reports.
     *
     * @return {@code true} when no row is deleted
     */
    public boolean isDryRun() {
        return dryRun;
    }

    /**
     * Switches between a reporting run and a deleting one.
     *
     * @param dryRun {@code true} to verify and log what would be deleted, and delete nothing
     */
    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    /**
     * Returns the cooling-off window between archiving a session and being allowed to delete its rows.
     *
     * @return a strictly positive duration, never {@code null}
     */
    public Duration getMinArchivedAge() {
        return minArchivedAge;
    }

    /**
     * Sets the cooling-off window.
     *
     * @param minArchivedAge a strictly positive duration; zero would let a session be purged in the
     *                       same instant it was archived, which is precisely the promise D47 made
     *                       ("a wrongly archived session can be moved back") that this window keeps
     * @throws IllegalArgumentException if {@code minArchivedAge} is {@code null}, zero or negative
     */
    public void setMinArchivedAge(Duration minArchivedAge) {
        if (minArchivedAge == null || minArchivedAge.isZero() || minArchivedAge.isNegative()) {
            throw new IllegalArgumentException(
                    PREFIX + ".min-archived-age must be a positive duration but was " + minArchivedAge);
        }
        this.minArchivedAge = minArchivedAge;
    }

    /**
     * Returns the number of certified sessions fetched per pass.
     *
     * @return a strictly positive row count
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * Sets the number of certified sessions fetched per pass.
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
     * @param maxBatches a strictly positive pass count; {@code batch-size * max-batches} is the largest
     *                   number of sessions one run can retire
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
     * Returns how long a run queues for the purge mutex.
     *
     * @return a non-negative duration, never {@code null}
     */
    public Duration getLockWaitTime() {
        return lockWaitTime;
    }

    /**
     * Sets how long a run queues for the purge mutex.
     *
     * @param lockWaitTime a non-negative duration; zero turns acquisition into a single attempt, which
     *                     is what a scheduled job wants — a replica that lost the race should step
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
     * Returns the fixed lease of the purge mutex.
     *
     * @return a non-negative duration, never {@code null}; {@link Duration#ZERO} delegates lease
     *         management to the Redisson watchdog
     */
    public Duration getLockLeaseTime() {
        return lockLeaseTime;
    }

    /**
     * Sets the fixed lease of the purge mutex.
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
     * Tells whether Redisson's automatic lease renewal is in charge of the purge mutex.
     *
     * @return {@code true} when no fixed lease time is configured
     */
    public boolean isLockWatchdogEnabled() {
        return lockLeaseTime.isZero();
    }

    /**
     * Returns the largest number of sessions a single run may retire.
     *
     * @return {@code batch-size * max-batches}, always positive
     */
    public int maxSessionsPerRun() {
        return batchSize * maxBatches;
    }

    @Override
    public String toString() {
        return "MedSessionPurgeProperties{enabled=" + enabled
                + ", dryRun=" + dryRun
                + ", minArchivedAge=" + minArchivedAge
                + ", batchSize=" + batchSize
                + ", maxBatches=" + maxBatches
                + ", checkInterval=" + checkInterval
                + ", initialDelay=" + initialDelay
                + ", lockWaitTime=" + lockWaitTime
                + ", lockLeaseTime=" + lockLeaseTime
                + '}';
    }
}
