package com.med.qa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Externalized policy of the orphaned-cold-copy reclaim, bound from {@code med.session.reclaim.*}.
 *
 * <pre>
 * med:
 *   session:
 *     reclaim:
 *       enabled: false           # master switch; false removes the reclaim and its scheduler entirely
 *       dry-run: true            # verify and report what would be retired, delete nothing
 *       batch-size: 50           # orphaned sessions fetched per pass
 *       max-batches: 5           # upper bound on passes per run, i.e. sessions touched per run
 *       check-interval: 12h      # delay between two runs
 *       initial-delay: 15m       # grace period after boot before the first run
 *       lock-wait-time: 5s       # how long a run queues behind another replica's run
 *       lock-lease-time: 5m      # 0 hands lease management to the Redisson watchdog
 * </pre>
 *
 * <h2>Why this exists</h2>
 * <p>D51 moves a mis-archived session back to the live tier and retires its export certification, but the
 * rows D48 copied into {@code med_message_archive} stay exactly where they are. What is left behind is
 * neither evidence nor state: the read path never consults it (only a manifest can make a cold copy
 * answer, see {@code MedTranscriptService}), and D50 never touches it (the purge deletes from
 * {@code med_message} only). It is a duplicate of bytes that the live transcript still holds, occupying
 * space in a table nobody will ever query for it.</p>
 *
 * <p>This job retires those copies. It is the second job in the project that deletes clinical data, which
 * is why it carries the same conservative defaults as the D50 purge — and why the guards it does
 * <em>not</em> copy matter just as much (see below).</p>
 *
 * <h2>Two switches, and deliberately no cooling-off window</h2>
 * <p>{@link #isEnabled()} defaults to {@code false} and {@link #isDryRun()} defaults to {@code true},
 * exactly as in the D38 index rebuild, the D47 sweep, the D48 export and the D50 purge. What is
 * <em>absent</em> here is equally deliberate: there is no {@code min-archived-age} equivalent, and adding
 * one would be wrong.</p>
 *
 * <p>The D50 cooling-off window exists because that job destroys a <em>capability</em>: after the purge a
 * session can no longer be moved back, so a mis-archived consultation stops being reversible. This job
 * destroys nothing of the sort — the bytes it deletes are, by construction, still present in the live
 * transcript, and the session they belonged to is already writable again. A cooling-off window would
 * therefore only delay the release of space while pretending to protect something, and a window that
 * protects nothing is worse than no window: it teaches an operator that the knob means something.</p>
 *
 * <p>What replaces it is a stronger guard inside the service: the live transcript must hold every message
 * the cold copy holds, and a copy that holds anything else is refused rather than deleted.</p>
 *
 * <p>Every setter validates eagerly so a typo fails the application context at startup instead of
 * silently turning the reclaim into a no-op or into an unbounded walk of every session.</p>
 */
@ConfigurationProperties(prefix = MedSessionReclaimProperties.PREFIX)
public class MedSessionReclaimProperties {

    /** Configuration namespace owned by this class. */
    public static final String PREFIX = "med.session.reclaim";

    /**
     * Redis key of the cluster-wide reclaim mutex.
     *
     * <p>Deliberately not configurable: the key schema is what makes two replicas exclude each other,
     * and a per-deployment key would silently remove that guarantee. It stays in the
     * {@code med:lock:} namespace alongside {@code med:lock:chat:*},
     * {@code med:lock:session:retention}, {@code med:lock:session:archive:export},
     * {@code med:lock:session:purge} and {@code med:lock:rag:index:rebuild:*}.</p>
     */
    public static final String LOCK_KEY = "med:lock:session:reclaim";

    /** Default number of orphaned sessions fetched per pass. */
    public static final int DEFAULT_BATCH_SIZE = 50;

    /** Default upper bound on the number of passes of a single run. */
    public static final int DEFAULT_MAX_BATCHES = 5;

    /** Default delay between two runs. */
    public static final Duration DEFAULT_CHECK_INTERVAL = Duration.ofHours(12);

    /** Default grace period after boot before the first run. */
    public static final Duration DEFAULT_INITIAL_DELAY = Duration.ofMinutes(15);

    /** Default time a run queues for the reclaim mutex before giving up. */
    public static final Duration DEFAULT_LOCK_WAIT_TIME = Duration.ofSeconds(5);

    /** Default lease of the reclaim mutex; {@link Duration#ZERO} hands renewal to the watchdog. */
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
    public MedSessionReclaimProperties() {
    }

    /**
     * Tells whether the orphaned-cold-copy reclaim is part of this deployment.
     *
     * @return {@code true} when the service and its scheduler should be contributed
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Switches the reclaim on or off.
     *
     * @param enabled {@code true} to contribute the reclaim and its scheduler
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Tells whether the reclaim only verifies and reports.
     *
     * @return {@code true} when no row is deleted
     */
    public boolean isDryRun() {
        return dryRun;
    }

    /**
     * Switches between a reporting run and a deleting one.
     *
     * @param dryRun {@code true} to verify and log what would be retired, and delete nothing
     */
    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    /**
     * Returns the number of orphaned sessions fetched per pass.
     *
     * @return a strictly positive row count
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * Sets the number of orphaned sessions fetched per pass.
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
     * Returns how long a run queues for the reclaim mutex.
     *
     * @return a non-negative duration, never {@code null}
     */
    public Duration getLockWaitTime() {
        return lockWaitTime;
    }

    /**
     * Sets how long a run queues for the reclaim mutex.
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
     * Returns the fixed lease of the reclaim mutex.
     *
     * @return a non-negative duration, never {@code null}; {@link Duration#ZERO} delegates lease
     *         management to the Redisson watchdog
     */
    public Duration getLockLeaseTime() {
        return lockLeaseTime;
    }

    /**
     * Sets the fixed lease of the reclaim mutex.
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
     * Tells whether Redisson's automatic lease renewal is in charge of the reclaim mutex.
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
        return "MedSessionReclaimProperties{enabled=" + enabled
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
