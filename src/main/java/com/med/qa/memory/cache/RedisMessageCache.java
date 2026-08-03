package com.med.qa.memory.cache;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.config.RedisConfig;
import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.memory.serde.ProtoMessageCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed cache of the recent message window of a consultation session.
 *
 * <p>Storage layout, mandated by the unified medical storage specification (ROADMAP section 4):</p>
 * <ul>
 *   <li><b>Key</b> — {@code med:chat:{tenant_id}:{dept_id}:{session_id}}</li>
 *   <li><b>Type</b> — Redis {@code LIST}, appended chronologically (head = oldest)</li>
 *   <li><b>Value</b> — the very same Protobuf payload written to MySQL, produced by
 *       {@link ProtoMessageCodec}, so both stores stay byte-compatible with the heterogeneous
 *       Python middleware</li>
 *   <li><b>TTL</b> — {@link MedCacheProperties#getTtl()}, refreshed on every write</li>
 * </ul>
 *
 * <p>Trimming to the configured window is delegated to the native Redis {@code LTRIM} command; no
 * eviction policy is implemented in Java.</p>
 *
 * <h2>Failure semantics</h2>
 * <ul>
 *   <li><b>Reads degrade to a miss.</b> MySQL is the source of truth, so a Redis outage or a
 *       corrupt payload is indistinguishable from a cold cache for the caller: the read methods log
 *       and return empty rather than failing the consultation. A corrupt payload additionally
 *       triggers a best-effort eviction, because one unreadable element makes the whole cached
 *       window untrustworthy.</li>
 *   <li><b>Writes fail loudly</b> with {@link BizException} / {@link ErrorCode#STORAGE_ERROR}.
 *       Swallowing a failed append or eviction would leave the cache permanently stale, which is
 *       worse than surfacing the error to the repository layer.</li>
 *   <li>{@link IllegalArgumentException} signals a caller/programming error (blank identity
 *       segments, {@code null} entity, non-positive window size).</li>
 * </ul>
 */
@Component
public class RedisMessageCache {

    private static final Logger log = LoggerFactory.getLogger(RedisMessageCache.class);

    /** Spec-compliant key prefix; every segment is mandatory. */
    private static final String KEY_PREFIX = "med:chat:";

    private final RedisTemplate<String, byte[]> redisTemplate;

    private final ProtoMessageCodec codec;

    private final MedCacheProperties properties;

    /**
     * Creates the cache.
     *
     * @param redisTemplate string-keyed, byte-array-valued template, must not be {@code null}
     * @param codec         Protobuf codec shared with the MySQL layer, must not be {@code null}
     * @param properties    TTL and window settings, must not be {@code null}
     */
    public RedisMessageCache(@Qualifier(RedisConfig.MESSAGE_REDIS_TEMPLATE)
                             RedisTemplate<String, byte[]> redisTemplate,
                             ProtoMessageCodec codec,
                             MedCacheProperties properties) {
        this.redisTemplate = redisTemplate;
        this.codec = codec;
        this.properties = properties;
    }

    /**
     * Builds the spec-compliant cache key {@code med:chat:{tenant_id}:{dept_id}:{session_id}}.
     *
     * @param tenantId  hospital/tenant id, must not be blank
     * @param deptId    department id, must not be blank
     * @param sessionId consultation session id, must not be blank
     * @return the Redis key, never {@code null}
     * @throws IllegalArgumentException if any segment is {@code null} or blank, which would produce
     *                                  a key colliding across tenants or departments
     */
    public String cacheKey(String tenantId, String deptId, String sessionId) {
        requireSegment(tenantId, "tenantId");
        requireSegment(deptId, "deptId");
        requireSegment(sessionId, "sessionId");
        return KEY_PREFIX + tenantId + ":" + deptId + ":" + sessionId;
    }

    /**
     * Builds the cache key of a session entity.
     *
     * @param session the session, must not be {@code null}
     * @return the Redis key, never {@code null}
     * @throws IllegalArgumentException if {@code session} is {@code null} or misses an identity
     *                                  segment
     */
    public String cacheKey(ChatSessionDO session) {
        if (session == null) {
            throw new IllegalArgumentException("session must not be null");
        }
        return cacheKey(session.getTenantId(), session.getDeptId(), session.getSessionId());
    }

    /**
     * Appends one message to the tail of its session window and refreshes the TTL.
     *
     * @param message the message to cache, must not be {@code null} and must carry a role
     * @throws IllegalArgumentException if the message is {@code null}, misses its role or an
     *                                  identity segment
     * @throws BizException             {@link ErrorCode#STORAGE_ERROR} when Redis rejects the write
     */
    public void append(ChatMessageDO message) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        String key = cacheKey(message.getTenantId(), message.getDeptId(), message.getSessionId());
        byte[] payload = codec.encodeMessage(message);
        try {
            ListOperations<String, byte[]> listOps = redisTemplate.opsForList();
            listOps.rightPush(key, payload);
            trimWindow(listOps, key);
            redisTemplate.expire(key, properties.getTtl());
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to append a message to redis cache key " + key, ex);
        }
    }

    /**
     * Replaces the whole cached window of a session, typically to back-fill it from MySQL.
     *
     * <p>An empty list is a legal argument and simply drops the key, so a session known to hold no
     * message is not re-read from MySQL on every request.</p>
     *
     * @param tenantId  hospital/tenant id, must not be blank
     * @param deptId    department id, must not be blank
     * @param sessionId consultation session id, must not be blank
     * @param messages  chronologically ordered messages, must not be {@code null} nor contain
     *                  {@code null}
     * @throws IllegalArgumentException if an argument is {@code null}, blank or invalid
     * @throws BizException             {@link ErrorCode#STORAGE_ERROR} when Redis rejects the write
     */
    public void replaceAll(String tenantId, String deptId, String sessionId,
                           List<ChatMessageDO> messages) {
        String key = cacheKey(tenantId, deptId, sessionId);
        if (messages == null) {
            throw new IllegalArgumentException("messages must not be null");
        }
        List<byte[]> payloads = new ArrayList<>(messages.size());
        for (ChatMessageDO message : messages) {
            if (message == null) {
                throw new IllegalArgumentException("messages must not contain a null element");
            }
            payloads.add(codec.encodeMessage(message));
        }
        try {
            redisTemplate.delete(key);
            if (payloads.isEmpty()) {
                return;
            }
            ListOperations<String, byte[]> listOps = redisTemplate.opsForList();
            listOps.rightPushAll(key, payloads);
            trimWindow(listOps, key);
            redisTemplate.expire(key, properties.getTtl());
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to replace the redis cache window of key " + key, ex);
        }
    }

    /**
     * Reads the whole cached window of a session in chronological order.
     *
     * @param tenantId  hospital/tenant id, must not be blank
     * @param deptId    department id, must not be blank
     * @param sessionId consultation session id, must not be blank
     * @return the cached messages, an empty list on a miss, a Redis outage or a corrupt payload
     * @throws IllegalArgumentException if an identity segment is blank
     */
    public List<ChatMessageDO> findAll(String tenantId, String deptId, String sessionId) {
        return read(cacheKey(tenantId, deptId, sessionId), 0, -1);
    }

    /**
     * Reads the {@code limit} most recent messages of a session in chronological order.
     *
     * @param tenantId  hospital/tenant id, must not be blank
     * @param deptId    department id, must not be blank
     * @param sessionId consultation session id, must not be blank
     * @param limit     window size, must be strictly positive
     * @return the tail of the cached window, an empty list on a miss or failure
     * @throws IllegalArgumentException if an identity segment is blank or {@code limit < 1}
     */
    public List<ChatMessageDO> findLast(String tenantId, String deptId, String sessionId, int limit) {
        String key = cacheKey(tenantId, deptId, sessionId);
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive but was " + limit);
        }
        return read(key, -limit, -1);
    }

    /**
     * Returns how many messages are currently cached for a session.
     *
     * @param tenantId  hospital/tenant id, must not be blank
     * @param deptId    department id, must not be blank
     * @param sessionId consultation session id, must not be blank
     * @return the cached message count, {@code 0} on a miss or a Redis outage
     * @throws IllegalArgumentException if an identity segment is blank
     */
    public long size(String tenantId, String deptId, String sessionId) {
        String key = cacheKey(tenantId, deptId, sessionId);
        try {
            Long size = redisTemplate.opsForList().size(key);
            return size == null ? 0L : size;
        } catch (DataAccessException ex) {
            log.warn("redis unavailable while sizing cache key {}, reporting 0", key, ex);
            return 0L;
        }
    }

    /**
     * Tells whether a session window is currently cached.
     *
     * @param tenantId  hospital/tenant id, must not be blank
     * @param deptId    department id, must not be blank
     * @param sessionId consultation session id, must not be blank
     * @return {@code true} only if the key exists; {@code false} on a Redis outage
     * @throws IllegalArgumentException if an identity segment is blank
     */
    public boolean exists(String tenantId, String deptId, String sessionId) {
        String key = cacheKey(tenantId, deptId, sessionId);
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (DataAccessException ex) {
            log.warn("redis unavailable while probing cache key {}, reporting absent", key, ex);
            return false;
        }
    }

    /**
     * Drops the cached window of a session, e.g. after a message was updated or deleted in MySQL.
     *
     * @param tenantId  hospital/tenant id, must not be blank
     * @param deptId    department id, must not be blank
     * @param sessionId consultation session id, must not be blank
     * @return {@code true} if a key was actually removed
     * @throws IllegalArgumentException if an identity segment is blank
     * @throws BizException             {@link ErrorCode#STORAGE_ERROR} when Redis rejects the
     *                                  deletion, since a surviving key would serve stale data
     */
    public boolean evict(String tenantId, String deptId, String sessionId) {
        String key = cacheKey(tenantId, deptId, sessionId);
        try {
            return Boolean.TRUE.equals(redisTemplate.delete(key));
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to evict redis cache key " + key, ex);
        }
    }

    /**
     * Returns the remaining time-to-live of a cached session window.
     *
     * @param tenantId  hospital/tenant id, must not be blank
     * @param deptId    department id, must not be blank
     * @param sessionId consultation session id, must not be blank
     * @return the remaining TTL, or {@link Optional#empty()} if the key is absent, has no expiry or
     *         Redis is unavailable
     * @throws IllegalArgumentException if an identity segment is blank
     */
    public Optional<Duration> remainingTtl(String tenantId, String deptId, String sessionId) {
        String key = cacheKey(tenantId, deptId, sessionId);
        try {
            Long seconds = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            if (seconds == null || seconds < 0) {
                return Optional.empty();
            }
            return Optional.of(Duration.ofSeconds(seconds));
        } catch (DataAccessException ex) {
            log.warn("redis unavailable while reading the ttl of cache key {}", key, ex);
            return Optional.empty();
        }
    }

    /**
     * Reads and decodes a slice of the cached list, degrading to an empty result on any failure.
     */
    private List<ChatMessageDO> read(String key, long start, long end) {
        List<byte[]> payloads;
        try {
            payloads = redisTemplate.opsForList().range(key, start, end);
        } catch (DataAccessException ex) {
            log.warn("redis unavailable while reading cache key {}, falling back to a cache miss", key, ex);
            return Collections.emptyList();
        }
        if (payloads == null || payloads.isEmpty()) {
            return Collections.emptyList();
        }
        List<ChatMessageDO> messages = new ArrayList<>(payloads.size());
        for (byte[] payload : payloads) {
            try {
                messages.add(codec.decodeMessage(payload));
            } catch (IllegalArgumentException | BizException ex) {
                log.warn("corrupt payload in cache key {}, evicting the whole window", key, ex);
                evictQuietly(key);
                return Collections.emptyList();
            }
        }
        return messages;
    }

    /**
     * Trims the list to the configured window using the native {@code LTRIM} command.
     */
    private void trimWindow(ListOperations<String, byte[]> listOps, String key) {
        if (properties.isWindowBounded()) {
            listOps.trim(key, -properties.getMaxMessages(), -1);
        }
    }

    /**
     * Best-effort eviction used on a read path, where an additional failure must not escalate.
     */
    private void evictQuietly(String key) {
        try {
            redisTemplate.delete(key);
        } catch (DataAccessException ex) {
            log.warn("failed to evict the corrupt cache key {}", key, ex);
        }
    }

    private static void requireSegment(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank to build a med:chat cache key");
        }
    }
}
