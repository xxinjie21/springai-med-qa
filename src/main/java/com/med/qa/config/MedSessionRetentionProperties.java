package com.med.qa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Externalized policy of the consultation retention sweep, bound from
 * {@code med.session.retention.*}.
 *
 * <pre>
 * med:
 *   session:
 *     retention:
 *       enabled: false          # master switch; false removes the sweep and its scheduler entirely
 *       dry-run: true           # report what would be archived without touching a single row
 *       idle-threshold: 24h     # a session is stale when it has not been updated for this long
 *       batch-size: 100         # rows fetched per pass
 *       max-batches: 10         # upper bound on passes per run, i.e. rows touched per run
 *       check-interval: 1h      # delay between two runs
 *       initial-delay: 5m       # grace period after boot before the first run
 *       lock-wait-time: 5s      # how long a run queues behind another replica's run
 *       lock-lease-time: 5m     # 0 hands lease management to the Redisson watchdog
 * </pre>
 *
 * <h2>Why this exists</h2>
 * <p>{@code MedChatSessionService#archiveSession} has always documented two ways a consultation ends:
 * an explicit close, or "a retention job sweeping stale sessions". Until D47 the second one did not
 * exist, so {@code ARCHIVED} was reachable only through one explicit API call: a patient who opened a
 * session, asked a single question and never came back left a row that stayed {@code ACTIVE} forever,
 * keeping its Redis window warm (the key is re-filled from MySQL on every read once its TTL expires),
 * and {@code med_session} only ever grew.</p>
 *
 * <h2>Two switches, not one</h2>
 * <p>{@link #isEnabled()} defaults to {@code false}, unlike every other session switch: the sweep is a
 * background actor that changes the lifecycle state of records nobody asked about, so its presence has
 * to be a deliberate deployment decision. {@link #isDryRun()} defaults to {@code true} on top of that,
 * so enabling the capability still archives nothing until an operator has read at least one report and
 * decided the threshold is right. This mirrors the two-switch design of the D38 index rebuild.</p>
 *
 * <h2>What the sweep can never do</h2>
 * <p>Archiving is a status transition plus a cache eviction; no code path here deletes a message. A
 * wrongly archived session is recoverable (the transcript is intact and
 * {@code ChatSessionMapper#updateStatus} can move it back), which is why the conservative defaults
 * above are sufficient and no "allow deletion" switch is needed.</p>
 *
 * <p>Every setter validates eagerly so a typo fails the application context at startup instead of
 * silently turning the sweep into a no-op or into a table-wide scan.</p>
 */
@ConfigurationProperties(prefix = MedSessionRetentionProperties.PREFIX)
public class MedSessionRetentionProperties {

    /** Configuration namespace owned by this class. */
    public static final String PREFIX = "med.session.retention";

    /**
     * Redis key of the cluster-wide sweep mutex.
     *
     * <p>Deliberately not configurable: the key schema is what makes two replicas exclude each other,
     * and a per-deployment key would silently remove that guarantee. It is kept in the
     * {@code med:lock:} namespace, alongside {@code med:lock:chat:*} and
     * {@code med:lock:rag:index:rebuild:*}.</p>
     */
    public static final String LOCK_KEY = "med:lock:session:retention";

    /** Default idle window after which an untouched session counts as abandoned. */
    public static final Duration DEFAULT_IDLE_THRESHOLD = Duration.ofHours(24);

    /** Default number of stale sessions fetched per pass. */
    public static final int DEFAULT_BATCH_SIZE = 100;

    /** Default upper bound on the number of passes of a single run. */
    public static final int DEFAULT_MAX_BATCHES = 10;

    /** Default delay between two runs. */
    public static final Duration DEFAULT_CHECK_INTERVAL = Duration.ofHours(1);

    /** Default grace period after boot before the first run. */
    public static final Duration DEFAULT_INITIAL_DELAY = Duration.ofMinutes(5);

    /** Default time a run queues for the sweep mutex before giving up. */
    public static final Duration DEFAULT_LOCK_WAIT_TIME = Duration.ofSeconds(5);

    /** Default lease of the sweep mutex; {@link Duration#ZERO} hands renewal to the watchdog. */
    public static final Duration DEFAULT_LOCK_LEASE_TIME = Duration.ofMinutes(5);

    private boolean enabled = false;

    private boolean dryRun = true;

    private Duration idleThreshold = DEFAULT_IDLE_THRESHOLD;

    private int batchSize = DEFAULT_BATCH_SIZE;

    private int maxBatches = DEFAULT_MAX_BATCHES;

    private Duration checkInterval = DEFAULT_CHECK_INTERVAL;

    private Duration initialDelay = DEFAULT_INITIAL_DELAY;

    private Duration lockWaitTime = DEFAULT_LOCK_WAIT_TIME;

    private Duration lockLeaseTime = DEFAULT_LOCK_LEASE_TIME;

    /**
     * Creates the properties with their conservative defaults.
     */
    public MedSessionRetentionProperties() {
    }

    /**
     * Tells whether the retention sweep is part of this deployment.
     *
     * @return {@code true} when the service and its scheduler should be contributed
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Switches the retention sweep on or off.
     *
     * @param enabled {@code true} to contribute the sweep and its scheduler
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Tells whether the sweep only reports.
     *
     * @return {@code true} when no session is actually archived
     */
    public boolean isDryRun() {
        return dryRun;
    }

    /**
     * Switches between a reporting run and a mutating one.
     *
     * @param dryRun {@code true} to log what would be archived and change nothing
     */
    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    /**
     * Returns how long a session may stay untouched before it counts as abandoned.
     *
     * @return a strictly positive duration, never {@code null}
     */
    public Duration getIdleThreshold() {
        return idleThreshold;
    }

    /**
     * Sets the idle window that makes a session stale.
     *
     * @param idleThreshold a strictly positive duration; zero would make every session stale the
     *                      instant it was created
     * @throws IllegalArgumentException if {@code idleThreshold} is {@code null}, zero or negative
     */
    public void setIdleThreshold(Duration idleThreshold) {
        if (idleThreshold == null || idleThreshold.isZero() || idleThreshold.isNegative()) {
            throw new IllegalArgumentException(
                    PREFIX + ".idle-threshold must be a positive duration but was " + idleThreshold);
        }
        this.idleThreshold = idleThreshold;
    }

    /**
     * Returns the number of stale sessions fetched per pass.
     *
     * @return a strictly positive row count
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * Sets the number of stale sessions fetched per pass.
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
     *                   largest number of sessions one run can touch
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
     * Returns how long a run queues for the sweep mutex.
     *
     * @return a non-negative duration, never {@code null}
     */
    public Duration getLockWaitTime() {
        return lockWaitTime;
    }

    /**
     * Sets how long a run queues for the sweep mutex.
     *
     * @param lockWaitTime a non-negative duration; zero turns acquisition into a single attempt,
     *                     which is what a scheduled job wants — a replica that lost the race should
     *                     step aside rather than pile up
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
     * Returns the fixed lease of the sweep mutex.
     *
     * @return a non-negative duration, never {@code null}; {@link Duration#ZERO} delegates lease
     *         management to the Redisson watchdog
     */
    public Duration getLockLeaseTime() {
        return lockLeaseTime;
    }

    /**
     * Sets the fixed lease of the sweep mutex.
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
     * Tells whether Redisson's automatic lease renewal is in charge of the sweep mutex.
     *
     * @return {@code true} when no fixed lease time is configured
     */
    public boolean isLockWatchdogEnabled() {
        return lockLeaseTime.isZero();
    }

    /**
     * Returns the largest number of sessions a single run may touch.
     *
     * @return {@code batch-size * max-batches}, always positive
     */
    public int maxSessionsPerRun() {
        return batchSize * maxBatches;
    }

    @Override
    public String toString() {
        return "MedSessionRetentionProperties{enabled=" + enabled
                + ", dryRun=" + dryRun
                + ", idleThreshold=" + idleThreshold
                + ", batchSize=" + batchSize
                + ", maxBatches=" + maxBatches
                + ", checkInterval=" + checkInterval
                + ", initialDelay=" + initialDelay
                + ", lockWaitTime=" + lockWaitTime
                + ", lockLeaseTime=" + lockLeaseTime
                + '}';
    }
}
