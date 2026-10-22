package com.med.qa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Externalized policy of the audit-log retention trim, bound from {@code med.audit-log.retention.*}.
 *
 * <pre>
 * med:
 *   audit-log:
 *     retention:
 *       enabled: false           # master switch; false removes the trim and its scheduler entirely
 *       dry-run: true            # count and report what would be trimmed, delete nothing
 *       retention-days: 365      # rows older than this fall out of the retention window
 *       batch-size: 500          # rows deleted per statement
 *       max-batches: 5           # upper bound on statements per run, i.e. rows trimmed per run
 *       check-interval: 24h      # delay between two runs
 *       initial-delay: 15m       # grace period after boot before the first run
 *       lock-wait-time: 5s       # how long a run queues behind another replica's run
 *       lock-lease-time: 5m      # 0 hands lease management to the Redisson watchdog
 * </pre>
 *
 * <h2>Why this exists</h2>
 * <p>{@code AuditLogMapper} has said since D23 that the trail is append-only <em>from the request
 * path</em> and that "retention trimming belongs to a database-level job with its own authorization".
 * This is that job. Without it the audit trail only grows: every audited medical operation appends a
 * row and nothing ever removes one, which is a compliance asset today and an unbounded table
 * tomorrow — and, unlike the session lifecycle, nothing about that growth fails a test.</p>
 *
 * <h2>Two switches, and a floor under the retention window</h2>
 * <p>{@link #isEnabled()} defaults to {@code false} and {@link #isDryRun()} defaults to {@code true},
 * exactly as in the D38 index rebuild, the D47 sweep, the D48 export, the D50 purge and the D52
 * reclaim: the trim deletes rows, so its presence and its teeth are deployment decisions.</p>
 *
 * <p>What the four session jobs do <em>not</em> have is {@link #MIN_RETENTION_DAYS}. They delete data
 * that provably still exists somewhere else, so their guards are about proofs; this job deletes rows
 * that exist nowhere else, so the retention window itself is the guard — and a guard that can be
 * configured to zero is not a guard. A retention window shorter than {@value #MIN_RETENTION_DAYS}
 * days is rejected at binding time (the application context fails to start) rather than silently
 * trimming the compliance record of the last few months. The floor is deliberately coarse: it stops
 * typos and copy-paste accidents, not policy. Operators must still raise {@code retention-days} to
 * whatever their jurisdiction requires of an operation audit trail; the default of one year is a
 * starting point, not a legal opinion.</p>
 *
 * <p>Every setter validates eagerly so a typo fails the application context at startup instead of
 * silently turning the trim into a no-op or into an unbounded walk of the whole table.</p>
 */
@ConfigurationProperties(prefix = MedAuditLogRetentionProperties.PREFIX)
public class MedAuditLogRetentionProperties {

    /** Configuration namespace owned by this class. */
    public static final String PREFIX = "med.audit-log.retention";

    /**
     * Redis key of the cluster-wide trim mutex.
     *
     * <p>Deliberately not configurable: the key schema is what makes two replicas exclude each other,
     * and a per-deployment key would silently remove that guarantee. It stays in the
     * {@code med:lock:} namespace alongside {@code med:lock:chat:*},
     * {@code med:lock:session:retention}, {@code med:lock:session:archive:export},
     * {@code med:lock:session:purge}, {@code med:lock:session:reclaim} and
     * {@code med:lock:rag:index:rebuild:*}.</p>
     */
    public static final String LOCK_KEY = "med:lock:audit-log:retention";

    /**
     * Shortest retention window the trim accepts.
     *
     * <p>The trim deletes rows that exist nowhere else, so the window is the guard; a window that can
     * be set to zero would make the guard optional. Ninety days is coarse on purpose — it stops
     * accidents, not policy.</p>
     */
    public static final int MIN_RETENTION_DAYS = 90;

    /** Default retention window in days. */
    public static final int DEFAULT_RETENTION_DAYS = 365;

    /** Default number of rows deleted per statement. */
    public static final int DEFAULT_BATCH_SIZE = 500;

    /** Default upper bound on the number of statements of a single run. */
    public static final int DEFAULT_MAX_BATCHES = 5;

    /** Default delay between two runs. */
    public static final Duration DEFAULT_CHECK_INTERVAL = Duration.ofHours(24);

    /** Default grace period after boot before the first run. */
    public static final Duration DEFAULT_INITIAL_DELAY = Duration.ofMinutes(15);

    /** Default time a run queues for the trim mutex before giving up. */
    public static final Duration DEFAULT_LOCK_WAIT_TIME = Duration.ofSeconds(5);

    /** Default lease of the trim mutex; {@link Duration#ZERO} hands renewal to the watchdog. */
    public static final Duration DEFAULT_LOCK_LEASE_TIME = Duration.ofMinutes(5);

    private boolean enabled = false;

    private boolean dryRun = true;

    private int retentionDays = DEFAULT_RETENTION_DAYS;

    private int batchSize = DEFAULT_BATCH_SIZE;

    private int maxBatches = DEFAULT_MAX_BATCHES;

    private Duration checkInterval = DEFAULT_CHECK_INTERVAL;

    private Duration initialDelay = DEFAULT_INITIAL_DELAY;

    private Duration lockWaitTime = DEFAULT_LOCK_WAIT_TIME;

    private Duration lockLeaseTime = DEFAULT_LOCK_LEASE_TIME;

    /**
     * Creates the properties with their conservative defaults.
     */
    public MedAuditLogRetentionProperties() {
    }

    /**
     * Tells whether the audit-log retention trim is part of this deployment.
     *
     * @return {@code true} when the service and its scheduler should be contributed
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Switches the trim on or off.
     *
     * @param enabled {@code true} to contribute the trim and its scheduler
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Tells whether the trim only counts and reports.
     *
     * @return {@code true} when no row is deleted
     */
    public boolean isDryRun() {
        return dryRun;
    }

    /**
     * Switches between a reporting run and a deleting one.
     *
     * @param dryRun {@code true} to count what would be trimmed, and delete nothing
     */
    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    /**
     * Returns the retention window in days.
     *
     * @return a value of at least {@value #MIN_RETENTION_DAYS}
     */
    public int getRetentionDays() {
        return retentionDays;
    }

    /**
     * Sets the retention window in days.
     *
     * @param retentionDays how long an audit row is kept; must be at least
     *                      {@value #MIN_RETENTION_DAYS}, because this job deletes rows that exist
     *                      nowhere else and a window below the floor is a typo, not a policy
     * @throws IllegalArgumentException if {@code retentionDays} is below the floor
     */
    public void setRetentionDays(int retentionDays) {
        if (retentionDays < MIN_RETENTION_DAYS) {
            throw new IllegalArgumentException(
                    PREFIX + ".retention-days must be at least " + MIN_RETENTION_DAYS
                            + " but was " + retentionDays + "; the trim deletes rows that exist "
                            + "nowhere else, so the retention window is the guard");
        }
        this.retentionDays = retentionDays;
    }

    /**
     * Returns the number of rows deleted per statement.
     *
     * @return a strictly positive row count
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * Sets the number of rows deleted per statement.
     *
     * @param batchSize a strictly positive row count; it bounds one delete transaction
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
     * Returns the maximum number of statements one run may perform.
     *
     * @return a strictly positive statement count
     */
    public int getMaxBatches() {
        return maxBatches;
    }

    /**
     * Sets the maximum number of statements one run may perform.
     *
     * @param maxBatches a strictly positive statement count; {@code batch-size * max-batches} is the
     *                   largest number of rows one run can trim
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
     * @param checkInterval a strictly positive duration; the scheduler uses it as a fixed delay, so
     *                      a slow run never overlaps the next one
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
     * @param initialDelay a non-negative duration; zero is legal and means "start immediately",
     *                     which is what a short-lived integration test wants
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
     * Returns how long a run queues for the trim mutex.
     *
     * @return a non-negative duration, never {@code null}
     */
    public Duration getLockWaitTime() {
        return lockWaitTime;
    }

    /**
     * Sets how long a run queues for the trim mutex.
     *
     * @param lockWaitTime a non-negative duration; zero turns acquisition into a single attempt,
     *                     which is what a scheduled job wants — a replica that lost the race
     *                     should step aside rather than pile up
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
     * Returns the fixed lease of the trim mutex.
     *
     * @return a non-negative duration, never {@code null}; {@link Duration#ZERO} delegates lease
     *         management to the Redisson watchdog
     */
    public Duration getLockLeaseTime() {
        return lockLeaseTime;
    }

    /**
     * Sets the fixed lease of the trim mutex.
     *
     * @param lockLeaseTime a non-negative duration; zero enables the Redisson watchdog, which is
     *                      the right choice when a run may outlive any hand-picked lease
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
     * Tells whether Redisson's automatic lease renewal is in charge of the trim mutex.
     *
     * @return {@code true} when no fixed lease time is configured
     */
    public boolean isLockWatchdogEnabled() {
        return lockLeaseTime.isZero();
    }

    /**
     * Returns the largest number of rows a single run may trim.
     *
     * @return {@code batch-size * max-batches}, always positive
     */
    public int maxRowsPerRun() {
        return batchSize * maxBatches;
    }

    @Override
    public String toString() {
        return "MedAuditLogRetentionProperties{enabled=" + enabled
                + ", dryRun=" + dryRun
                + ", retentionDays=" + retentionDays
                + ", batchSize=" + batchSize
                + ", maxBatches=" + maxBatches
                + ", checkInterval=" + checkInterval
                + ", initialDelay=" + initialDelay
                + ", lockWaitTime=" + lockWaitTime
                + ", lockLeaseTime=" + lockLeaseTime
                + '}';
    }
}
