package com.med.qa.service;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.config.MedSessionRetentionProperties;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.enums.SessionStatus;
import com.med.qa.mapper.ChatSessionMapper;
import com.med.qa.memory.cache.RedisMessageCache;
import com.med.qa.memory.lock.SessionLockService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.context.annotation.Lazy;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Moves abandoned consultations to {@link SessionStatus#ARCHIVED} — the retention job
 * {@code MedChatSessionService#archiveSession} has documented since D20 but which never existed.
 *
 * <h2>Why this class does not call {@code archiveSession}</h2>
 * <p>The obvious implementation would reuse the request-facing lifecycle service. It cannot: every
 * method of {@code MedChatSessionService} that touches an existing session first resolves it through
 * {@code PatientAccessGuard#assertOwned}, and that guard <em>fails closed</em> when there is no
 * principal (D21) — which is precisely the situation of a background job. Calling it would reject
 * every candidate with {@code FORBIDDEN}.</p>
 *
 * <p>The inverse shortcut is just as wrong: adding a "skip the guard" archive method to
 * {@code MedChatSessionService} would hand every request-scoped caller an unguarded primitive for
 * archiving other departments' sessions — exactly the class of hole D41/D42 closed. So the sweep owns
 * its own system-side archive path and reuses the collaborators that actually encode the invariants
 * ({@link ChatSessionMapper} for the compare-and-set, {@link SessionLockService} for the lock shared
 * with the message-append path, {@link RedisMessageCache} for the eviction).</p>
 *
 * <h2>Why it cannot archive a live consultation</h2>
 * <p>Two independent mechanisms, neither of which relies on the sweep being fast:</p>
 * <ol>
 *   <li>{@link ChatSessionMapper#updateStatusIfStale} carries the staleness predicate
 *       ({@code updated_at <= idleBefore}) inside the same {@code UPDATE} as the status change. A
 *       session that received a question between the candidate query and the archive simply does not
 *       match, and the row is left alone — no read-then-write window to lose.</li>
 *   <li>The archive runs under the session lock of {@link SessionLockService}, the very lock the
 *       message-append path takes ({@code MedSpringAiChatMemoryRepository#saveAll}), so a turn being
 *       written right now either completes before the sweep looks or blocks it. A lock that is busy
 *       counts as {@code skipped}, never as a failure: a busy session is an alive one.</li>
 * </ol>
 *
 * <h2>Failure semantics</h2>
 * <ul>
 *   <li><b>A single candidate never aborts the run.</b> Its failure is logged, counted and listed in
 *       {@link SessionRetentionReport#failures()}; the remaining candidates are still processed.</li>
 *   <li><b>A failed cache eviction counts as a failure.</b> The status transition already happened, so
 *       the transcript is cold in MySQL while a warm window may still serve messages; that is a real
 *       inconsistency and must be visible, not averaged away.</li>
 *   <li><b>The cluster mutex is not optional.</b> Redis unreachable propagates as
 *       {@link ErrorCode#STORAGE_ERROR} instead of degrading to an unlocked sweep — two replicas
 *       sweeping at once is what the lock exists to prevent, and the scheduler turns the exception
 *       into an alert.</li>
 *   <li><b>Nothing here deletes a message.</b> Archiving is a status transition plus a cache eviction;
 *       the transcript stays intact and a wrongly archived session can be moved back.</li>
 * </ul>
 *
 * <p>Not annotated with {@code @Service} on purpose: {@code config/MedSessionRetentionConfig} declares
 * it behind {@code med.session.retention.enabled}, the same way {@code MedVectorIndexRebuilder} is
 * declared behind the rebuild switch. A bean that is always present could not be switched off.</p>
 */
public class MedSessionRetentionService {

    private static final Logger log = LoggerFactory.getLogger(MedSessionRetentionService.class);

    private final ChatSessionMapper sessionMapper;

    private final SessionLockService lockService;

    private final RedisMessageCache cache;

    private final RedissonClient redissonClient;

    private final MedSessionRetentionProperties properties;

    private final Clock clock;

    /**
     * Creates the service used by the application context.
     *
     * <p>The Redisson client is injected lazily on purpose: creating it opens a connection, and the
     * application context must be able to start without Redis available.</p>
     *
     * @param sessionMapper  MyBatis mapper over {@code med_session}, must not be {@code null}
     * @param lockService    distributed session lock, must not be {@code null}
     * @param cache          Redis window cache, evicted for every archived session, must not be
     *                       {@code null}
     * @param redissonClient the shared client, used only for the cluster-wide sweep mutex, must not be
     *                       {@code null}
     * @param properties     the {@code med.session.retention.*} policy, must not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public MedSessionRetentionService(ChatSessionMapper sessionMapper,
                                     SessionLockService lockService,
                                     RedisMessageCache cache,
                                     @Lazy RedissonClient redissonClient,
                                     MedSessionRetentionProperties properties) {
        this(sessionMapper, lockService, cache, redissonClient, properties, Clock.systemUTC());
    }

    /**
     * Creates the service with an explicit clock, so the staleness cutoff is deterministic in tests.
     *
     * @param sessionMapper  MyBatis mapper over {@code med_session}, must not be {@code null}
     * @param lockService    distributed session lock, must not be {@code null}
     * @param cache          Redis window cache, must not be {@code null}
     * @param redissonClient the shared client, must not be {@code null}
     * @param properties     the {@code med.session.retention.*} policy, must not be {@code null}
     * @param clock          clock that stamps the cutoff and the archived {@code updated_at}, must not
     *                       be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public MedSessionRetentionService(ChatSessionMapper sessionMapper,
                                     SessionLockService lockService,
                                     RedisMessageCache cache,
                                     @Lazy RedissonClient redissonClient,
                                     MedSessionRetentionProperties properties,
                                     Clock clock) {
        if (sessionMapper == null) {
            throw new IllegalArgumentException("sessionMapper must not be null");
        }
        if (lockService == null) {
            throw new IllegalArgumentException("lockService must not be null");
        }
        if (cache == null) {
            throw new IllegalArgumentException("cache must not be null");
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
        this.sessionMapper = sessionMapper;
        this.lockService = lockService;
        this.cache = cache;
        this.redissonClient = redissonClient;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Runs one bounded retention sweep.
     *
     * <p>The run is single-flight across the cluster: it acquires
     * {@link MedSessionRetentionProperties#LOCK_KEY} before touching MySQL and reports
     * {@link SessionRetentionReport.Outcome#SKIPPED_LOCK_HELD} when another replica is already
     * sweeping. It then performs at most {@code max-batches} passes of {@code batch-size} candidates —
     * except in dry-run mode, which stops after the first pass because nothing was changed and the
     * next pass would simply fetch the same rows again.</p>
     *
     * @return the run report, never {@code null}
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when the mutex cannot be acquired because
     *                      Redis is unreachable, or when the candidate queries fail
     */
    public SessionRetentionReport sweepOnce() {
        long started = clock.millis();
        long idleBefore = idleBeforeEpochMillis();

        RLock lock = redissonClient.getLock(MedSessionRetentionProperties.LOCK_KEY);
        if (!acquire(lock)) {
            log.info("another replica is already sweeping stale sessions, stepping aside");
            return SessionRetentionReport.skipped(idleBefore, clock.millis() - started);
        }
        try {
            return runSweep(idleBefore, started);
        } finally {
            release(lock);
        }
    }

    /**
     * Returns the cutoff applied by {@link #sweepOnce()}: a session is stale when its
     * {@code updated_at} is not newer than this instant.
     *
     * @return epoch milliseconds of {@code now - idle-threshold}
     */
    public long idleBeforeEpochMillis() {
        return clock.millis() - properties.getIdleThreshold().toMillis();
    }

    /**
     * Reads the stale {@link SessionStatus#ACTIVE} sessions the next sweep would consider, without
     * changing anything.
     *
     * @param limit maximum number of rows, must be strictly positive
     * @return the candidates ordered by {@code updated_at} ascending, possibly empty, never
     *         {@code null}
     * @throws IllegalArgumentException if {@code limit} is not positive
     * @throws BizException             {@link ErrorCode#STORAGE_ERROR} when the query fails
     */
    public List<ChatSessionDO> findStaleCandidates(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive but was " + limit);
        }
        return selectBatch(idleBeforeEpochMillis(), limit);
    }

    /**
     * Counts the stale {@link SessionStatus#ACTIVE} sessions outstanding right now.
     *
     * @return the backlog size, never negative
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when the query fails
     */
    public long countStaleCandidates() {
        return countStale(idleBeforeEpochMillis());
    }

    /**
     * Performs the bounded passes of one sweep that already holds the cluster mutex.
     *
     * @param idleBefore the staleness cutoff
     * @param started    epoch milliseconds the run began, used for the duration
     * @return the run report
     */
    private SessionRetentionReport runSweep(long idleBefore, long started) {
        int candidates = 0;
        int archived = 0;
        int skipped = 0;
        int failed = 0;
        int batches = 0;
        List<String> failures = new ArrayList<>();

        // A dry run must not loop: it changes nothing, so the next pass would return the same rows.
        int maxPasses = properties.isDryRun() ? 1 : properties.getMaxBatches();
        for (int pass = 1; pass <= maxPasses; pass++) {
            List<ChatSessionDO> batch = selectBatch(idleBefore, properties.getBatchSize());
            if (batch.isEmpty()) {
                break;
            }
            batches++;
            candidates += batch.size();
            for (ChatSessionDO candidate : batch) {
                if (properties.isDryRun()) {
                    log.info("dry run: session {} of department {}/{} is stale and would be archived",
                            candidate.getSessionId(), candidate.getTenantId(), candidate.getDeptId());
                    continue;
                }
                try {
                    if (archiveIfStale(candidate, idleBefore)) {
                        archived++;
                    } else {
                        skipped++;
                    }
                } catch (RuntimeException ex) {
                    failed++;
                    failures.add(describeFailure(candidate.getSessionId(), ex));
                    log.warn("failed to archive stale session {}, continuing with the rest",
                            candidate.getSessionId(), ex);
                }
            }
            if (batch.size() < properties.getBatchSize()) {
                break;
            }
        }

        long remaining = countStale(idleBefore);
        SessionRetentionReport report = new SessionRetentionReport(SessionRetentionReport.Outcome.COMPLETED,
                properties.isDryRun(), idleBefore, candidates, archived, skipped, failed, batches,
                remaining, clock.millis() - started, failures);
        log.info("session retention sweep finished: {}", report.summary());
        return report;
    }

    /**
     * Archives one candidate, unless it stopped being stale in the meantime.
     *
     * @param candidate  the session the candidate query returned
     * @param idleBefore the staleness cutoff the archive predicate repeats
     * @return {@code true} when the session was moved to {@code ARCHIVED}
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when MySQL or the cache fails; a
     *                      {@link ErrorCode#SESSION_LOCKED} failure is translated into {@code false},
     *                      because a session busy with a turn is an alive one
     */
    private boolean archiveIfStale(ChatSessionDO candidate, long idleBefore) {
        String tenantId = candidate.getTenantId();
        String deptId = candidate.getDeptId();
        String sessionId = candidate.getSessionId();
        try {
            return Boolean.TRUE.equals(lockService.executeLocked(tenantId, deptId, sessionId,
                    () -> archiveLocked(tenantId, deptId, sessionId, idleBefore)));
        } catch (BizException ex) {
            if (ex.getErrorCode() == ErrorCode.SESSION_LOCKED) {
                log.info("session {} is busy with a turn, leaving it alone", sessionId);
                return false;
            }
            throw ex;
        }
    }

    /**
     * Applies the compare-and-set and drops the cached window, while holding the session lock.
     *
     * @return {@code true} when the row was actually moved to {@code ARCHIVED}
     */
    private boolean archiveLocked(String tenantId, String deptId, String sessionId, long idleBefore) {
        long now = clock.millis();
        int rows;
        try {
            rows = sessionMapper.updateStatusIfStale(sessionId, SessionStatus.ARCHIVED,
                    SessionStatus.ACTIVE, idleBefore, now);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to archive stale session " + sessionId, ex);
        }
        if (rows <= 0) {
            log.info("session {} is no longer stale or no longer active, leaving it alone", sessionId);
            return false;
        }
        cache.evict(tenantId, deptId, sessionId);
        log.info("archived stale session {} of department {}/{}", sessionId, tenantId, deptId);
        return true;
    }

    /**
     * Renders one failure line for the report.
     *
     * <p>A {@link BizException} is rendered with its {@link ErrorCode} rather than its class name:
     * "STORAGE_ERROR" tells an operator whether the sweep could not reach MySQL, could not drop a
     * cached window, or lost the session lock to a live consultation, while "BizException" tells them
     * nothing.</p>
     *
     * @param sessionId the candidate that failed
     * @param failure   the failure
     * @return a single-line description, never {@code null}
     */
    private static String describeFailure(String sessionId, RuntimeException failure) {
        if (failure instanceof BizException bizException) {
            return sessionId + ": " + bizException.getErrorCode().name() + ": "
                    + bizException.getMessage();
        }
        return sessionId + ": " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }

    /**
     * Fetches one page of stale sessions.
     *
     * @param idleBefore the staleness cutoff
     * @param limit      maximum number of rows
     * @return the page, possibly empty, never {@code null}
     */    private List<ChatSessionDO> selectBatch(long idleBefore, int limit) {
        List<ChatSessionDO> batch;
        try {
            batch = sessionMapper.selectStaleSessions(SessionStatus.ACTIVE, idleBefore, limit);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to list the stale sessions of the retention sweep", ex);
        }
        return batch == null ? Collections.emptyList() : batch;
    }

    /**
     * Counts the stale sessions outstanding for a cutoff.
     *
     * @param idleBefore the staleness cutoff
     * @return the backlog size, never negative
     */
    private long countStale(long idleBefore) {
        try {
            return Math.max(sessionMapper.countStaleSessions(SessionStatus.ACTIVE, idleBefore), 0L);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to count the stale sessions of the retention sweep", ex);
        }
    }

    /**
     * Acquires the cluster-wide sweep mutex.
     *
     * @param lock the mutex
     * @return {@code true} when this run may sweep
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
                    "interrupted while acquiring the retention sweep lock", e);
        } catch (RedisException e) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to acquire the retention sweep lock", e);
        }
    }

    /**
     * Releases the mutex; a failed release is logged and left to the lease, because the sweep it
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
            log.warn("failed to release the retention sweep lock, leaving it to the lease expiry", e);
        }
    }
}
