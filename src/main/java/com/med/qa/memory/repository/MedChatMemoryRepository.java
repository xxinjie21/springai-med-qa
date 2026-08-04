package com.med.qa.memory.repository;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.mapper.ChatMessageMapper;
import com.med.qa.memory.cache.RedisMessageCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Two-tier (Redis + sharded MySQL) repository of consultation messages, implementing the
 * cache-aside strategy required by the conversation memory layer.
 *
 * <h2>Read path — cache-aside with a MySQL back-fill</h2>
 * <ol>
 *   <li>Read the {@code med:chat:{tenant_id}:{dept_id}:{session_id}} window from
 *       {@link RedisMessageCache}.</li>
 *   <li>On a hit, return it as-is: the cached payload is the very same Protobuf encoding stored in
 *       MySQL, so both tiers are byte-identical.</li>
 *   <li>On a miss (cold key, expired TTL, Redis outage or a corrupt payload — the cache maps all of
 *       them to an empty result), replay the session from
 *       {@code med_message_{crc32(session_id) % 16}} through {@link ChatMessageMapper} and
 *       back-fill the cache before returning.</li>
 * </ol>
 *
 * <h2>Write path — dual write, MySQL first</h2>
 * <p>MySQL is the source of truth and is always written first; only then is the cache updated.
 * The inverse order would publish a message to the memory window that a failing insert never
 * persisted.</p>
 *
 * <h2>Failure semantics</h2>
 * <ul>
 *   <li><b>MySQL failures always propagate</b> as {@link BizException} /
 *       {@link ErrorCode#STORAGE_ERROR}: losing the authoritative copy of a medical conversation
 *       must never be silent.</li>
 *   <li><b>Cache failures degrade, but never leave a stale window.</b> When the mirror write
 *       fails, the session key is invalidated so the next read rebuilds it from MySQL. If the
 *       invalidation fails as well, the error is escalated — a surviving window that is missing a
 *       message is worse than a failed request.</li>
 *   <li><b>Back-fill failures are ignored</b> (logged only): the caller already holds the
 *       authoritative MySQL answer, and failing a read because the cache could not be warmed would
 *       turn a Redis outage into an outage of the consultation itself.</li>
 *   <li>{@link IllegalArgumentException} is reserved for caller/programming errors such as blank
 *       identity segments or a non-positive window size.</li>
 * </ul>
 */
@Repository
public class MedChatMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(MedChatMemoryRepository.class);

    private final ChatMessageMapper messageMapper;

    private final RedisMessageCache cache;

    /**
     * Creates the repository.
     *
     * @param messageMapper MyBatis mapper over the sharded {@code med_message} logical table, must
     *                      not be {@code null}
     * @param cache         Redis window cache of the same messages, must not be {@code null}
     */
    public MedChatMemoryRepository(ChatMessageMapper messageMapper, RedisMessageCache cache) {
        this.messageMapper = messageMapper;
        this.cache = cache;
    }

    /**
     * Persists one message to MySQL and mirrors it into the session cache window.
     *
     * @param message the message to store; must carry non-blank {@code messageId},
     *                {@code sessionId}, {@code tenantId}, {@code deptId} and a non-null role
     * @throws IllegalArgumentException if the message is {@code null} or misses a required field
     * @throws BizException             {@link ErrorCode#STORAGE_ERROR} when the MySQL insert fails
     *                                  or affects no row, or when a failed cache write could not be
     *                                  compensated by an eviction
     */
    public void append(ChatMessageDO message) {
        requireStorable(message);
        insertOne(message);
        mirrorToCache(Collections.singletonList(message));
    }

    /**
     * Persists a batch of messages (typically the patient question plus the assistant answer of one
     * turn) and mirrors them into their session cache windows, preserving list order.
     *
     * <p>The batch is intentionally not wrapped in a single transaction: every message is
     * independently addressable by its own primary key, and the cache is always rebuilt from
     * whatever MySQL actually holds, so a partial batch degrades to "fewer messages" rather than to
     * an inconsistent window.</p>
     *
     * @param messages the messages to store, must not be {@code null} nor contain {@code null};
     *                 an empty list is a legal no-op
     * @throws IllegalArgumentException if the list is {@code null}, holds a {@code null} element or
     *                                  an element missing a required field
     * @throws BizException             {@link ErrorCode#STORAGE_ERROR} on a MySQL failure, or when
     *                                  a failed cache write could not be compensated
     */
    public void appendAll(List<ChatMessageDO> messages) {
        if (messages == null) {
            throw new IllegalArgumentException("messages must not be null");
        }
        if (messages.isEmpty()) {
            return;
        }
        for (ChatMessageDO message : messages) {
            requireStorable(message);
        }
        for (ChatMessageDO message : messages) {
            insertOne(message);
        }
        mirrorToCache(messages);
    }

    /**
     * Returns the whole conversation of a session in chronological order, serving it from the cache
     * and falling back to MySQL with a back-fill.
     *
     * @param tenantId  hospital/tenant id, must not be blank
     * @param deptId    department id, must not be blank
     * @param sessionId consultation session id, must not be blank
     * @return the messages, oldest first; an empty list when the session holds none
     * @throws IllegalArgumentException if an identity segment is blank
     * @throws BizException             {@link ErrorCode#STORAGE_ERROR} when the MySQL fallback fails
     */
    public List<ChatMessageDO> findAll(String tenantId, String deptId, String sessionId) {
        List<ChatMessageDO> cached = cache.findAll(tenantId, deptId, sessionId);
        if (!cached.isEmpty()) {
            return cached;
        }
        return loadAndBackFill(tenantId, deptId, sessionId);
    }

    /**
     * Convenience overload of {@link #findAll(String, String, String)} taking the session entity.
     *
     * @param session the session whose conversation is requested, must not be {@code null}
     * @return the messages, oldest first, possibly empty
     * @throws IllegalArgumentException if {@code session} is {@code null} or misses an identity
     *                                  segment
     * @throws BizException             {@link ErrorCode#STORAGE_ERROR} when the MySQL fallback fails
     */
    public List<ChatMessageDO> findAll(ChatSessionDO session) {
        if (session == null) {
            throw new IllegalArgumentException("session must not be null");
        }
        return findAll(session.getTenantId(), session.getDeptId(), session.getSessionId());
    }

    /**
     * Returns the {@code limit} most recent messages of a session in chronological order — the
     * sliding window handed to the LLM as short-term memory.
     *
     * @param tenantId  hospital/tenant id, must not be blank
     * @param deptId    department id, must not be blank
     * @param sessionId consultation session id, must not be blank
     * @param limit     window size, must be strictly positive
     * @return at most {@code limit} messages, oldest first, possibly empty
     * @throws IllegalArgumentException if an identity segment is blank or {@code limit < 1}
     * @throws BizException             {@link ErrorCode#STORAGE_ERROR} when the MySQL fallback fails
     */
    public List<ChatMessageDO> findRecent(String tenantId, String deptId, String sessionId, int limit) {
        List<ChatMessageDO> cached = cache.findLast(tenantId, deptId, sessionId, limit);
        if (!cached.isEmpty()) {
            return cached;
        }
        return tail(loadAndBackFill(tenantId, deptId, sessionId), limit);
    }

    /**
     * Loads a single message by primary key. The lookup always goes to MySQL: the cache is indexed
     * by session window, not by message id, so it cannot answer this query.
     *
     * @param messageId the message primary key, must not be blank
     * @return the message, or {@link Optional#empty()} when it does not exist
     * @throws IllegalArgumentException if {@code messageId} is blank
     * @throws BizException             {@link ErrorCode#STORAGE_ERROR} when the MySQL query fails
     */
    public Optional<ChatMessageDO> findById(String messageId) {
        requireText(messageId, "messageId");
        try {
            return Optional.ofNullable(messageMapper.selectById(messageId));
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to load message " + messageId + " from mysql", ex);
        }
    }

    /**
     * Rebuilds the cached window of a session from MySQL, bypassing the cache on the way in.
     *
     * <p>Unlike {@link #findAll(String, String, String)} this also drops the key when the session
     * turns out to hold no message, so a window left behind by deleted rows cannot survive.</p>
     *
     * @param tenantId  hospital/tenant id, must not be blank
     * @param deptId    department id, must not be blank
     * @param sessionId consultation session id, must not be blank
     * @return the freshly loaded messages, oldest first, possibly empty
     * @throws IllegalArgumentException if an identity segment is blank
     * @throws BizException             {@link ErrorCode#STORAGE_ERROR} when the MySQL query fails
     */
    public List<ChatMessageDO> reload(String tenantId, String deptId, String sessionId) {
        List<ChatMessageDO> stored = selectSession(tenantId, deptId, sessionId);
        backFillQuietly(tenantId, deptId, sessionId, stored);
        return stored;
    }

    /**
     * Flips the privacy {@code masked} flag of a message in MySQL and invalidates the cached window
     * that still holds its previous rendering.
     *
     * @param messageId the message primary key, must not be blank
     * @param masked    the new masking state
     * @return {@code true} when the message existed and was updated
     * @throws IllegalArgumentException if {@code messageId} is blank
     * @throws BizException             {@link ErrorCode#STORAGE_ERROR} when MySQL fails, or when the
     *                                  cache invalidation fails — serving an unmasked copy from a
     *                                  stale window would be a privacy leak
     */
    public boolean markMasked(String messageId, boolean masked) {
        ChatMessageDO existing = findById(messageId).orElse(null);
        if (existing == null) {
            return false;
        }
        int rows;
        try {
            rows = messageMapper.updateMasked(messageId, masked);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to update the masked flag of message " + messageId, ex);
        }
        if (rows <= 0) {
            return false;
        }
        cache.evict(existing.getTenantId(), existing.getDeptId(), existing.getSessionId());
        return true;
    }

    /**
     * Deletes a single message from MySQL and invalidates the cached window of its session.
     *
     * @param messageId the message primary key, must not be blank
     * @return {@code true} when the message existed and was deleted
     * @throws IllegalArgumentException if {@code messageId} is blank
     * @throws BizException             {@link ErrorCode#STORAGE_ERROR} when MySQL fails, or when the
     *                                  cache invalidation fails
     */
    public boolean deleteMessage(String messageId) {
        ChatMessageDO existing = findById(messageId).orElse(null);
        if (existing == null) {
            return false;
        }
        int rows;
        try {
            rows = messageMapper.deleteById(messageId);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to delete message " + messageId + " from mysql", ex);
        }
        if (rows <= 0) {
            return false;
        }
        cache.evict(existing.getTenantId(), existing.getDeptId(), existing.getSessionId());
        return true;
    }

    /**
     * Deletes every message of a session from MySQL and drops its cached window.
     *
     * @param tenantId  hospital/tenant id, must not be blank
     * @param deptId    department id, must not be blank
     * @param sessionId consultation session id, must not be blank
     * @return the number of deleted rows
     * @throws IllegalArgumentException if an identity segment is blank
     * @throws BizException             {@link ErrorCode#STORAGE_ERROR} when MySQL fails, or when the
     *                                  cache invalidation fails
     */
    public int deleteSession(String tenantId, String deptId, String sessionId) {
        requireText(tenantId, "tenantId");
        requireText(deptId, "deptId");
        requireText(sessionId, "sessionId");
        int rows;
        try {
            rows = messageMapper.deleteBySessionId(sessionId);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to delete the messages of session " + sessionId + " from mysql", ex);
        }
        cache.evict(tenantId, deptId, sessionId);
        return rows;
    }

    /**
     * Inserts one row into the sharded table, translating any storage failure.
     */
    private void insertOne(ChatMessageDO message) {
        int rows;
        try {
            rows = messageMapper.insert(message);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to insert message " + message.getMessageId() + " into mysql", ex);
        }
        if (rows <= 0) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "mysql accepted no row for message " + message.getMessageId());
        }
    }

    /**
     * Mirrors already-persisted messages into their cache windows, invalidating instead of failing.
     */
    private void mirrorToCache(List<ChatMessageDO> messages) {
        for (ChatMessageDO message : messages) {
            try {
                cache.append(message);
            } catch (BizException ex) {
                log.warn("cache mirror of message {} failed, invalidating session window {}",
                        message.getMessageId(), message.getSessionId(), ex);
                invalidateAfterFailedMirror(message, ex);
                return;
            }
        }
    }

    /**
     * Compensates a failed mirror write; escalates when the window cannot be invalidated either.
     */
    private void invalidateAfterFailedMirror(ChatMessageDO message, BizException cause) {
        try {
            cache.evict(message.getTenantId(), message.getDeptId(), message.getSessionId());
        } catch (BizException evictFailure) {
            BizException escalated = new BizException(ErrorCode.STORAGE_ERROR,
                    "message " + message.getMessageId() + " was persisted but its cached window "
                            + "could neither be updated nor invalidated", cause);
            escalated.addSuppressed(evictFailure);
            throw escalated;
        }
    }

    /**
     * Reads a session from MySQL and warms the cache with it.
     */
    private List<ChatMessageDO> loadAndBackFill(String tenantId, String deptId, String sessionId) {
        List<ChatMessageDO> stored = selectSession(tenantId, deptId, sessionId);
        if (!stored.isEmpty()) {
            backFillQuietly(tenantId, deptId, sessionId, stored);
        }
        return stored;
    }

    /**
     * Replays a session from the sharded table, oldest first.
     */
    private List<ChatMessageDO> selectSession(String tenantId, String deptId, String sessionId) {
        requireText(tenantId, "tenantId");
        requireText(deptId, "deptId");
        requireText(sessionId, "sessionId");
        List<ChatMessageDO> stored;
        try {
            stored = messageMapper.selectBySessionIdOrderByCreatedAtAsc(sessionId);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to load the messages of session " + sessionId + " from mysql", ex);
        }
        return stored == null ? Collections.emptyList() : stored;
    }

    /**
     * Warms the cache without ever failing the read that triggered it.
     */
    private void backFillQuietly(String tenantId, String deptId, String sessionId,
                                 List<ChatMessageDO> messages) {
        try {
            cache.replaceAll(tenantId, deptId, sessionId, messages);
        } catch (BizException | IllegalArgumentException ex) {
            log.warn("failed to back-fill the cached window of session {}, serving mysql only",
                    sessionId, ex);
        }
    }

    /**
     * Returns the last {@code limit} elements of an already chronological list.
     */
    private static List<ChatMessageDO> tail(List<ChatMessageDO> messages, int limit) {
        if (messages.size() <= limit) {
            return messages;
        }
        return new ArrayList<>(messages.subList(messages.size() - limit, messages.size()));
    }

    /**
     * Validates every field the two storage tiers need before any of them is touched, so a
     * half-written message can never exist.
     */
    private static void requireStorable(ChatMessageDO message) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        requireText(message.getMessageId(), "messageId");
        requireText(message.getSessionId(), "sessionId");
        requireText(message.getTenantId(), "tenantId");
        requireText(message.getDeptId(), "deptId");
        if (message.getRole() == null) {
            throw new IllegalArgumentException(
                    "role must not be null: a medical message without a speaker cannot be stored");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
