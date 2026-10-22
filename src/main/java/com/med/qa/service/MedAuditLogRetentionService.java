package com.med.qa.service;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.config.MedAuditLogRetentionProperties;
import com.med.qa.mapper.AuditLogMapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Trims the medical operation audit trail after its retention window — the job
 * {@link AuditLogMapper}'s javadoc has promised since D23 ("retention trimming belongs to a
 * database-level job with its own authorization, not to the request path that produced the entry")
 * and that no code ever implemented.
 *
 * <h2>Why the delete lives here and nowhere else</h2>
 * <p>The mapper's own argument is the reason: an audit row that can be removed from the request path
 * that produced it is not evidence. So this class is the <em>only</em> writer of a delete against
 * {@code med_audit_log}, it has no request-path caller, it is contributed behind
 * {@code med.audit-log.retention.enabled} (default {@code false}) and it reports through the same
 * channels as the D47/D48/D50/D52 session jobs. There is deliberately no controller endpoint: an
 * HTTP-triggered trim would be exactly the request-path delete the mapper rules out.</p>
 *
 * <h2>Why there is no digest or containment guard</h2>
 * <p>D50 and D52 prove a lot before they delete, because the rows they remove have a second copy:
 * the purge requires the certified cold copy to reproduce the manifest digest, and the reclaim
 * requires every cold message id to still be in the live transcript. An audit row has no second
 * copy — deleting it destroys the only record of that operation — so a "prove it still exists
 * elsewhere" guard is not available and a digest of rows nobody else holds would prove nothing.
 * What guards this job instead is the retention window itself: {@code retention-days} is floored at
 * {@link MedAuditLogRetentionProperties#MIN_RETENTION_DAYS} at binding time, so a deployment cannot
 * be misconfigured into trimming its recent compliance record, and the two switches default to
 * {@code false} / {@code true} (off / dry-run) like every other deleting job in the project.</p>
 *
 * <h2>Why the trim does not export to a cold store first</h2>
 * <p>Migration V3 argues that the audit table deliberately carries no clinical payload because
 * "duplicating protected health information into a second store with its own retention rules is a
 * liability". Building a cold archive for audit rows would be precisely that: a second store, with
 * its own lifecycle, holding copies of the same operations. The rows are small, metadata-only and
 * regenerable in meaning (an operation either happened or it did not — the trail is the record that
 * it happened, not the payload), so the trim deletes after the window and moves nothing.</p>
 *
 * <h2>What one run does</h2>
 * <p>The cutoff is {@code now - retention-days}, computed once per run. The run then performs at
 * most {@code max-batches} bounded deletes ({@code DELETE … WHERE created_at < ? LIMIT ?}), stopping
 * early when a statement removes fewer rows than the batch size — the backlog is drained. A dry run
 * performs no statement at all: it reports the exact number of rows the run <em>would</em> have
 * deleted, simulated per batch as {@code min(remaining expired, batch-size)}, which is what the
 * real loop would remove given the counts it just measured.</p>
 *
 * <h2>Concurrency and failure semantics</h2>
 * <ul>
 *   <li><b>One run per cluster.</b> The cluster-wide mutex {@link MedAuditLogRetentionProperties#LOCK_KEY}
 *       is taken before touching the database; a replica that loses the race reports
 *       {@link AuditLogRetentionReport.Outcome#SKIPPED_LOCK_HELD} without reading a single row. Redis
 *       unreachable propagates as {@link ErrorCode#STORAGE_ERROR} instead of degrading to an
 *       unlocked run.</li>
 *   <li><b>A failed batch ends the run.</b> Unlike the four session jobs, a per-candidate failure
 *       does not get collected and skipped: a batch delete that throws means MySQL is unhappy, the
 *       next batch is likely to fail too, and a partial report would understate what was lost. The
 *       exception propagates to the scheduler, which records the attempt in the run ledger and
 *       alerts.</li>
 *   <li><b>Only expired rows are ever deleted.</b> The cutoff is an exclusive upper bound computed
 *       from the run's own clock, so a row written during the run is never a candidate.</li>
 * </ul>
 *
 * <p>Not annotated with {@code @Service} on purpose: {@code config/MedAuditLogRetentionConfig}
 * declares it behind {@code med.audit-log.retention.enabled}, the same way the D47 sweep, the D48
 * export, the D50 purge and the D52 reclaim are declared behind their switches.</p>
 */
public class MedAuditLogRetentionService {

    private static final Logger log = LoggerFactory.getLogger(MedAuditLogRetentionService.class);

    private static final long MILLIS_PER_DAY = Duration.ofDays(1).toMillis();

    private final AuditLogMapper auditLogMapper;

    private final RedissonClient redissonClient;

    private final MedAuditLogRetentionProperties properties;

    private final Clock clock;

    /**
     * Creates the service used by the application context.
     *
     * <p>The Redisson client is injected lazily on purpose: creating it opens a connection, and the
     * application context must be able to start without Redis available.</p>
     *
     * @param auditLogMapper  MyBatis mapper over {@code med_audit_log}, the only data access this
     *                        job has, must not be {@code null}
     * @param redissonClient  the shared client, used only for the cluster-wide trim mutex, must not
     *                        be {@code null}
     * @param properties      the {@code med.audit-log.retention.*} policy, must not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public MedAuditLogRetentionService(AuditLogMapper auditLogMapper,
                                       @Lazy RedissonClient redissonClient,
                                       MedAuditLogRetentionProperties properties) {
        this(auditLogMapper, redissonClient, properties, Clock.systemUTC());
    }

    /**
     * Creates the service with an explicit clock, so the cutoff and the run duration are
     * deterministic in tests.
     *
     * @param auditLogMapper  MyBatis mapper over {@code med_audit_log}, must not be {@code null}
     * @param redissonClient  the shared client, must not be {@code null}
     * @param properties      the {@code med.audit-log.retention.*} policy, must not be {@code null}
     * @param clock           clock the cutoff is derived from, must not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public MedAuditLogRetentionService(AuditLogMapper auditLogMapper,
                                       @Lazy RedissonClient redissonClient,
                                       MedAuditLogRetentionProperties properties,
                                       Clock clock) {
        if (auditLogMapper == null) {
            throw new IllegalArgumentException("auditLogMapper must not be null");
        }
        if (redissonClient == null) {
            throw new IllegalArgumentException("redissonClient must not be null");
        }
        if (properties == null) {
            throw new IllegalArgumentException("properties must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.auditLogMapper = auditLogMapper;
        this.redissonClient = redissonClient;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Runs one bounded retention trim.
     *
     * <p>The run is single-flight across the cluster: it acquires
     * {@link MedAuditLogRetentionProperties#LOCK_KEY} before touching the database and reports
     * {@link AuditLogRetentionReport.Outcome#SKIPPED_LOCK_HELD} when another replica is already
     * trimming. It then performs at most {@code max-batches} bounded deletes — except in dry-run
     * mode, which stops after the first pass because nothing was deleted and the next pass would
     * simply count the same rows again.</p>
     *
     * @return the run report, never {@code null}
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when the mutex cannot be acquired because
     *                      Redis is unreachable, or when a count or a delete fails
     */
    public AuditLogRetentionReport trimOnce() {
        long started = clock.millis();
        RLock lock = redissonClient.getLock(MedAuditLogRetentionProperties.LOCK_KEY);
        if (!acquire(lock)) {
            log.info("another replica is already trimming the audit trail, stepping aside");
            return AuditLogRetentionReport.skipped(properties.getRetentionDays(),
                    clock.millis() - started);
        }
        try {
            return runTrim(started);
        } finally {
            release(lock);
        }
    }

    /**
     * Counts the audit rows already past the retention cutoff, without changing anything.
     *
     * <p>Exists for the same reason the session jobs' preview queries do: it lets an operator (and
     * the contract tests) ask what the job would touch without arming it.</p>
     *
     * @return the number of expired rows, never negative
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when the count fails
     */
    public long countExpiredAuditRows() {
        return countExpired(cutoffMillis());
    }

    /**
     * Performs the bounded passes of one run that already holds the cluster mutex.
     *
     * @param started epoch milliseconds the run began, used for the duration
     * @return the run report
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when a count or a delete fails; a failed
     *                      batch ends the run rather than being collected
     */
    private AuditLogRetentionReport runTrim(long started) {
        int retentionDays = properties.getRetentionDays();
        long cutoff = cutoffMillis();
        long expired = countExpired(cutoff);
        long deleted = 0L;
        int batches = 0;

        if (properties.isDryRun()) {
            // Exact simulation of the loop below: each pass removes min(what is left, batch-size),
            // so the report says precisely what a deleting run would have removed.
            long left = expired;
            for (int pass = 1; pass <= properties.getMaxBatches() && left > 0L; pass++) {
                long thisBatch = Math.min(left, properties.getBatchSize());
                deleted += thisBatch;
                left -= thisBatch;
                batches++;
            }
            log.info("dry run: {} audit row(s) past the {}-day retention window would be deleted, "
                    + "{} would remain", deleted, retentionDays, left);
            return new AuditLogRetentionReport(AuditLogRetentionReport.Outcome.COMPLETED, true,
                    retentionDays, expired, deleted, left, batches, clock.millis() - started);
        }

        if (expired == 0L) {
            // Nothing past the cutoff: issue no statement at all, and skip the confirmation count
            // the deleting loop needs — it would only re-read the same zero rows.
            log.debug("no audit row is past the {}-day retention window, nothing to trim",
                    retentionDays);
            return new AuditLogRetentionReport(AuditLogRetentionReport.Outcome.COMPLETED, false,
                    retentionDays, 0L, 0L, 0L, 0, clock.millis() - started);
        }

        for (int pass = 1; pass <= properties.getMaxBatches(); pass++) {
            int removed = deleteExpired(cutoff, properties.getBatchSize());
            if (removed == 0) {
                break;
            }
            deleted += removed;
            batches++;
            if (removed < properties.getBatchSize()) {
                break;
            }
        }

        long remaining = countExpired(cutoff);
        AuditLogRetentionReport report = new AuditLogRetentionReport(
                AuditLogRetentionReport.Outcome.COMPLETED, false, retentionDays, expired, deleted,
                remaining, batches, clock.millis() - started);
        log.info("audit log retention trim finished: {}", report.summary());
        return report;
    }

    /**
     * Computes the exclusive upper bound on {@code created_at} for this run.
     *
     * @return epoch milliseconds; rows stamped at or after it are inside the retention window
     */
    private long cutoffMillis() {
        long window = (long) properties.getRetentionDays() * MILLIS_PER_DAY;
        return clock.millis() - window;
    }

    /**
     * Counts the rows past a cutoff.
     *
     * @param cutoff exclusive upper bound on {@code created_at}
     * @return the count, never negative
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when the query fails
     */
    private long countExpired(long cutoff) {
        try {
            return Math.max(0L, auditLogMapper.countAuditLogsCreatedBefore(cutoff));
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to count the audit rows past the retention cutoff", ex);
        }
    }

    /**
     * Deletes at most {@code limit} rows past a cutoff.
     *
     * @param cutoff exclusive upper bound on {@code created_at}
     * @param limit  maximum number of rows this statement may delete
     * @return the number of deleted rows
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when the delete fails
     */
    private int deleteExpired(long cutoff, int limit) {
        try {
            return auditLogMapper.deleteAuditLogsCreatedBefore(cutoff, limit);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to delete the audit rows past the retention cutoff", ex);
        }
    }

    /**
     * Acquires the cluster-wide trim mutex.
     *
     * @param lock the mutex
     * @return {@code true} when this run may trim
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when Redis is unreachable,
     *                      {@link ErrorCode#INTERNAL_ERROR} when interrupted while queuing
     */
    private boolean acquire(RLock lock) {
        long waitMillis = properties.getLockWaitTime().toMillis();
        try {
            if (properties.isLockWatchdogEnabled()) {
                // Two-argument form: no explicit lease, so Redisson's watchdog keeps renewing it.
                return lock.tryLock(waitMillis, TimeUnit.MILLISECONDS);
            }
            return lock.tryLock(waitMillis, properties.getLockLeaseTime().toMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "interrupted while acquiring the audit log retention lock", e);
        } catch (RedisException e) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to acquire the audit log retention lock", e);
        }
    }

    /**
     * Releases the mutex; a failed release is logged and left to the lease, because the run it
     * guarded already finished.
     *
     * @param lock the mutex
     */
    private void release(RLock lock) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException e) {
            log.warn("failed to release the audit log retention lock, leaving it to the lease expiry",
                    e);
        }
    }
}
