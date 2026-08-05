package com.med.qa.memory.lock;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.domain.entity.ChatSessionDO;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Serializes concurrent mutations of a single consultation session across application instances.
 *
 * <p>The lock itself is Redisson's {@link RLock}: a reentrant, Redis backed lock whose lease is
 * renewed in the background by the Redisson watchdog for as long as the owning thread is alive.
 * Nothing about the locking primitive is implemented here — this service only owns</p>
 * <ul>
 *   <li>the key schema {@code med:lock:chat:{tenant_id}:{dept_id}:{session_id}}, which mirrors the
 *       cache key of the unified storage specification so a session maps to exactly one lock;</li>
 *   <li>the externalized timings from {@link MedLockProperties};</li>
 *   <li>the translation of Redisson failures into the project's {@link ErrorCode} vocabulary.</li>
 * </ul>
 *
 * <h2>Failure semantics</h2>
 * <ul>
 *   <li>Lock busy after the configured wait window → {@link BizException} with
 *       {@link ErrorCode#SESSION_LOCKED}; the guarded action is never executed. Callers are meant
 *       to surface a retryable 409-style answer rather than corrupt the transcript.</li>
 *   <li>Redis unreachable → {@link BizException} with {@link ErrorCode#STORAGE_ERROR}. Unlike cache
 *       reads, a lock failure must never degrade silently: running the guarded write without mutual
 *       exclusion is exactly the situation the lock exists to prevent.</li>
 *   <li>Thread interrupted while queuing → interrupt flag restored, then
 *       {@link ErrorCode#INTERNAL_ERROR}.</li>
 *   <li>A failed release is logged but never rethrown: the guarded action already completed, and
 *       the lease expires on its own once the watchdog stops renewing it.</li>
 *   <li>{@link IllegalArgumentException} is reserved for caller/programming errors (blank identity
 *       segments, {@code null} action).</li>
 * </ul>
 */
@Service
public class SessionLockService {

    private static final Logger log = LoggerFactory.getLogger(SessionLockService.class);

    /** Lock key prefix; kept distinct from the {@code med:chat:} cache namespace. */
    public static final String KEY_PREFIX = "med:lock:chat:";

    private final RedissonClient redissonClient;

    private final MedLockProperties properties;

    /**
     * Creates the lock service.
     *
     * <p>The client is injected lazily on purpose: the Redisson bean connects on creation, and the
     * application context must be able to start without Redis available.</p>
     *
     * @param redissonClient the shared Redisson client, must not be {@code null}
     * @param properties     wait/lease timings, must not be {@code null}
     */
    public SessionLockService(@Lazy RedissonClient redissonClient, MedLockProperties properties) {
        this.redissonClient = redissonClient;
        this.properties = properties;
    }

    /**
     * Builds the lock key {@code med:lock:chat:{tenant_id}:{dept_id}:{session_id}}.
     *
     * @param tenantId  hospital/tenant id, must not be blank
     * @param deptId    department id, must not be blank
     * @param sessionId consultation session id, must not be blank
     * @return the Redis lock key, never {@code null}
     * @throws IllegalArgumentException if any segment is {@code null} or blank; a truncated key
     *                                  would make unrelated sessions share one lock
     */
    public String lockKey(String tenantId, String deptId, String sessionId) {
        requireSegment(tenantId, "tenantId");
        requireSegment(deptId, "deptId");
        requireSegment(sessionId, "sessionId");
        return KEY_PREFIX + tenantId + ":" + deptId + ":" + sessionId;
    }

    /**
     * Builds the lock key of a session entity.
     *
     * @param session the session, must not be {@code null}
     * @return the Redis lock key, never {@code null}
     * @throws IllegalArgumentException if {@code session} is {@code null} or misses an identity
     *                                  segment
     */
    public String lockKey(ChatSessionDO session) {
        if (session == null) {
            throw new IllegalArgumentException("session must not be null");
        }
        return lockKey(session.getTenantId(), session.getDeptId(), session.getSessionId());
    }

    /**
     * Runs a value-returning action while holding the session lock.
     *
     * @param tenantId  hospital/tenant id, must not be blank
     * @param deptId    department id, must not be blank
     * @param sessionId consultation session id, must not be blank
     * @param action    the guarded action, must not be {@code null}
     * @param <T>       action result type
     * @return whatever the action returned, {@code null} included
     * @throws IllegalArgumentException if an identity segment is blank or {@code action} is
     *                                  {@code null}
     * @throws BizException             {@link ErrorCode#SESSION_LOCKED} when the session is busy,
     *                                  {@link ErrorCode#STORAGE_ERROR} when Redis fails,
     *                                  {@link ErrorCode#INTERNAL_ERROR} when interrupted
     */
    public <T> T executeLocked(String tenantId, String deptId, String sessionId, Supplier<T> action) {
        String key = lockKey(tenantId, deptId, sessionId);
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        return doLocked(key, action);
    }

    /**
     * Runs a value-returning action while holding the lock of the given session entity.
     *
     * @param session the session, must not be {@code null}
     * @param action  the guarded action, must not be {@code null}
     * @param <T>     action result type
     * @return whatever the action returned, {@code null} included
     * @throws IllegalArgumentException if {@code session} or {@code action} is {@code null}
     * @throws BizException             see {@link #executeLocked(String, String, String, Supplier)}
     */
    public <T> T executeLocked(ChatSessionDO session, Supplier<T> action) {
        String key = lockKey(session);
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        return doLocked(key, action);
    }

    /**
     * Runs a void action while holding the session lock.
     *
     * @param tenantId  hospital/tenant id, must not be blank
     * @param deptId    department id, must not be blank
     * @param sessionId consultation session id, must not be blank
     * @param action    the guarded action, must not be {@code null}
     * @throws IllegalArgumentException if an identity segment is blank or {@code action} is
     *                                  {@code null}
     * @throws BizException             see {@link #executeLocked(String, String, String, Supplier)}
     */
    public void runLocked(String tenantId, String deptId, String sessionId, Runnable action) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        executeLocked(tenantId, deptId, sessionId, () -> {
            action.run();
            return null;
        });
    }

    /**
     * Runs a void action while holding the lock of the given session entity.
     *
     * @param session the session, must not be {@code null}
     * @param action  the guarded action, must not be {@code null}
     * @throws IllegalArgumentException if {@code session} or {@code action} is {@code null}
     * @throws BizException             see {@link #executeLocked(String, String, String, Supplier)}
     */
    public void runLocked(ChatSessionDO session, Runnable action) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        executeLocked(session, () -> {
            action.run();
            return null;
        });
    }

    /**
     * Tells whether the session is currently locked by anyone in the cluster.
     *
     * @param tenantId  hospital/tenant id, must not be blank
     * @param deptId    department id, must not be blank
     * @param sessionId consultation session id, must not be blank
     * @return {@code true} while some owner holds the lock
     * @throws IllegalArgumentException if an identity segment is blank
     * @throws BizException             {@link ErrorCode#STORAGE_ERROR} when Redis fails
     */
    public boolean isLocked(String tenantId, String deptId, String sessionId) {
        String key = lockKey(tenantId, deptId, sessionId);
        try {
            return redissonClient.getLock(key).isLocked();
        } catch (RedisException e) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to read session lock state " + key, e);
        }
    }

    /**
     * Tells whether the calling thread owns the session lock.
     *
     * @param tenantId  hospital/tenant id, must not be blank
     * @param deptId    department id, must not be blank
     * @param sessionId consultation session id, must not be blank
     * @return {@code true} when the current thread is the lock owner
     * @throws IllegalArgumentException if an identity segment is blank
     * @throws BizException             {@link ErrorCode#STORAGE_ERROR} when Redis fails
     */
    public boolean isHeldByCurrentThread(String tenantId, String deptId, String sessionId) {
        String key = lockKey(tenantId, deptId, sessionId);
        try {
            return redissonClient.getLock(key).isHeldByCurrentThread();
        } catch (RedisException e) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to read session lock ownership " + key, e);
        }
    }

    /**
     * Releases a session lock regardless of its owner.
     *
     * <p>Intended for operational recovery only (a node died mid-write before the watchdog lease
     * expired); normal flows must rely on {@link #executeLocked}.</p>
     *
     * @param tenantId  hospital/tenant id, must not be blank
     * @param deptId    department id, must not be blank
     * @param sessionId consultation session id, must not be blank
     * @return {@code true} if a held lock was actually released
     * @throws IllegalArgumentException if an identity segment is blank
     * @throws BizException             {@link ErrorCode#STORAGE_ERROR} when Redis fails
     */
    public boolean forceUnlock(String tenantId, String deptId, String sessionId) {
        String key = lockKey(tenantId, deptId, sessionId);
        try {
            boolean released = redissonClient.getLock(key).forceUnlock();
            if (released) {
                log.warn("force released session lock {}", key);
            }
            return released;
        } catch (RedisException e) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to force unlock session " + key, e);
        }
    }

    private <T> T doLocked(String key, Supplier<T> action) {
        RLock lock = redissonClient.getLock(key);
        if (!acquire(lock, key)) {
            throw new BizException(ErrorCode.SESSION_LOCKED,
                    "session lock " + key + " is held by another request");
        }
        try {
            return action.get();
        } finally {
            release(lock, key);
        }
    }

    private boolean acquire(RLock lock, String key) {
        long waitMillis = properties.getWaitTime().toMillis();
        try {
            if (properties.isWatchdogEnabled()) {
                // Two-argument form: no explicit lease, so Redisson's watchdog keeps renewing it.
                return lock.tryLock(waitMillis, TimeUnit.MILLISECONDS);
            }
            return lock.tryLock(waitMillis, properties.getLeaseTime().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "interrupted while acquiring session lock " + key, e);
        } catch (RedisException e) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to acquire session lock " + key, e);
        }
    }

    private void release(RLock lock, String key) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException e) {
            // The guarded action already finished; masking its outcome with a release failure would
            // be worse than waiting for the lease to expire.
            log.warn("failed to release session lock {}, leaving it to the lease expiry", key, e);
        }
    }

    private static void requireSegment(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
