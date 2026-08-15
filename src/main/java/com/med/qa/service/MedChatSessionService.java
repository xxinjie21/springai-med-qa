package com.med.qa.service;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.common.result.PageResult;
import com.med.qa.config.MedSessionProperties;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.enums.SessionStatus;
import com.med.qa.mapper.ChatSessionMapper;
import com.med.qa.memory.cache.RedisMessageCache;
import com.med.qa.memory.lock.SessionLockService;
import com.med.qa.security.MedSecurityContext;
import com.med.qa.security.PatientAccessGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Lifecycle service of consultation sessions: creation, lookup, closing, archiving and paged listing.
 *
 * <h2>Where the session row lives</h2>
 * <p>Sessions are persisted in the single {@code med_session} table through {@link ChatSessionMapper}
 * (ShardingSphere-JDBC routes it via its {@code SINGLE} rule), while their messages stay in the 16
 * {@code med_message_{crc32(session_id) % 16}} shards. Identity fields, the numeric status codes and
 * the epoch-millisecond timestamps follow the unified medical storage specification (ROADMAP section
 * 4), so a session written here is readable by the heterogeneous Python middleware unchanged.</p>
 *
 * <h2>Concurrency</h2>
 * <p>Every state transition runs inside the Redisson session lock of {@link SessionLockService}, the
 * very same lock the message-append path takes: closing a session while a streaming answer is still
 * being written would otherwise leave the transcript with messages after the closing timestamp. The
 * {@code UPDATE ... WHERE status = expected} predicate of
 * {@link ChatSessionMapper#updateStatus(String, SessionStatus, SessionStatus, long)} keeps the
 * transition safe even against a writer that bypassed the lock.</p>
 *
 * <h2>Access control</h2>
 * <p>When a {@link PatientAccessGuard} is present (the application context always wires one), every
 * ownership-sensitive operation enforces the caller resolved by {@link ApiKeyAuthFilter} into the
 * {@link MedSecurityContext}: a patient may only reach sessions whose {@code patient_id} equals their
 * own, while staff may reach any session of their department. The guard is injected as an optional
 * dependency so the plain (guard-less) constructor used by unit tests keeps the service behaviour
 * unchanged.</p>
 *
 * <h2>Failure semantics</h2>
 * <ul>
 *   <li>{@link IllegalArgumentException} — caller/programming error (blank identity segment,
 *       {@code null} query).</li>
 *   <li>{@link ErrorCode#BAD_REQUEST} — policy violation of an otherwise well-formed request: a title
 *       longer than the configured limit, a page larger than the configured maximum, or a lifecycle
 *       transition that does not exist (re-closing an archived session).</li>
 *   <li>{@link ErrorCode#NOT_FOUND} — unknown session id. A session that exists under another
 *       tenant/department is reported the same way on purpose: answering "wrong department" would
 *       confirm the existence of another hospital's session.</li>
 *   <li>{@link ErrorCode#SESSION_LOCKED} — the session is busy (lock held elsewhere) or its status
 *       changed underneath the transition; both are retryable.</li>
 *   <li>{@link ErrorCode#STORAGE_ERROR} — MySQL failed, or the cached window of an archived session
 *       could not be dropped. Neither degrades silently: a session marked cold whose hot window keeps
 *       serving messages would feed archived data back into a live consultation.</li>
 * </ul>
 */
@Service
public class MedChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(MedChatSessionService.class);

    private final ChatSessionMapper sessionMapper;

    private final SessionLockService lockService;

    private final RedisMessageCache cache;

    private final MedSessionProperties properties;

    private final Clock clock;

    @Nullable
    private final PatientAccessGuard accessGuard;

    /**
     * Creates the service used by the application context, with patient-ownership enforcement.
     *
     * @param sessionMapper MyBatis mapper over {@code med_session}, must not be {@code null}
     * @param lockService   distributed session lock, must not be {@code null}
     * @param cache         Redis window cache, dropped when a session is archived, must not be
     *                      {@code null}
     * @param properties    listing and title guard rails, must not be {@code null}
     * @param accessGuard   patient-ownership guard, must not be {@code null}
     */
    @Autowired
    public MedChatSessionService(ChatSessionMapper sessionMapper,
                                 SessionLockService lockService,
                                 RedisMessageCache cache,
                                 MedSessionProperties properties,
                                 PatientAccessGuard accessGuard) {
        this(sessionMapper, lockService, cache, properties, accessGuard, Clock.systemUTC());
    }

    /**
     * Creates the service with an explicit clock and no ownership guard, for unit tests.
     *
     * @param sessionMapper MyBatis mapper over {@code med_session}, must not be {@code null}
     * @param lockService   distributed session lock, must not be {@code null}
     * @param cache         Redis window cache, must not be {@code null}
     * @param properties    listing and title guard rails, must not be {@code null}
     * @param clock         clock stamping {@code created_at} / {@code updated_at}, must not be
     *                      {@code null}
     * @throws IllegalArgumentException if any mandatory argument is {@code null}
     */
    public MedChatSessionService(ChatSessionMapper sessionMapper,
                                 SessionLockService lockService,
                                 RedisMessageCache cache,
                                 MedSessionProperties properties,
                                 Clock clock) {
        this(sessionMapper, lockService, cache, properties, null, clock);
    }

    /**
     * Creates the service with an explicit clock, so lifecycle timestamps are deterministic in tests.
     *
     * @param sessionMapper MyBatis mapper over {@code med_session}, must not be {@code null}
     * @param lockService   distributed session lock, must not be {@code null}
     * @param cache         Redis window cache, must not be {@code null}
     * @param properties    listing and title guard rails, must not be {@code null}
     * @param accessGuard   patient-ownership guard, or {@code null} to leave access control off
     * @param clock         clock stamping {@code created_at} / {@code updated_at}, must not be
     *                      {@code null}
     * @throws IllegalArgumentException if any mandatory argument is {@code null}
     */
    public MedChatSessionService(ChatSessionMapper sessionMapper,
                                 SessionLockService lockService,
                                 RedisMessageCache cache,
                                 MedSessionProperties properties,
                                 @Nullable PatientAccessGuard accessGuard,
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
        if (properties == null) {
            throw new IllegalArgumentException("properties must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.sessionMapper = sessionMapper;
        this.lockService = lockService;
        this.cache = cache;
        this.properties = properties;
        this.accessGuard = accessGuard;
        this.clock = clock;
    }

    /**
     * Opens a new {@link SessionStatus#ACTIVE} consultation session.
     *
     * <p>The session id is a fresh random UUID: it is the sharding key of every message written later,
     * so it must be unguessable and never reused. No lock is taken — the row does not exist yet.</p>
     *
     * @param tenantId  hospital/tenant id, must not be blank
     * @param deptId    department id, must not be blank
     * @param patientId owning patient id, must not be blank
     * @param title     optional display title; blank is stored as {@code null}
     * @return the persisted session, never {@code null}
     * @throws IllegalArgumentException if an identity segment is blank
     * @throws BizException             {@link ErrorCode#BAD_REQUEST} when the title exceeds
     *                                  {@link MedSessionProperties#getMaxTitleLength()};
     *                                  {@link ErrorCode#STORAGE_ERROR} when the insert fails
     */
    public ChatSessionDO createSession(String tenantId, String deptId, String patientId,
                                       @Nullable String title) {
        requireText(tenantId, "tenantId");
        requireText(deptId, "deptId");
        requireText(patientId, "patientId");
        if (accessGuard != null) {
            accessGuard.assertScope(MedSecurityContext.getCurrent(), tenantId, deptId, patientId);
        }
        String normalizedTitle = normalizeTitle(title);

        long now = clock.millis();
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId(UUID.randomUUID().toString());
        session.setTenantId(tenantId);
        session.setDeptId(deptId);
        session.setPatientId(patientId);
        session.setTitle(normalizedTitle);
        session.setStatus(SessionStatus.ACTIVE);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);

        int rows;
        try {
            rows = sessionMapper.insert(session);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to insert session " + session.getSessionId() + " into mysql", ex);
        }
        if (rows <= 0) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "mysql accepted no row for session " + session.getSessionId());
        }
        return session;
    }

    /**
     * Loads a session of the given department, failing when it does not exist.
     *
     * @param tenantId  hospital/tenant id, must not be blank
     * @param deptId    department id, must not be blank
     * @param sessionId consultation session id, must not be blank
     * @return the session, never {@code null}
     * @throws IllegalArgumentException if an identity segment is blank
     * @throws BizException             {@link ErrorCode#NOT_FOUND} when no such session exists in
     *                                  this department; {@link ErrorCode#STORAGE_ERROR} on a MySQL
     *                                  failure
     */
    public ChatSessionDO getSession(String tenantId, String deptId, String sessionId) {
        return findSession(tenantId, deptId, sessionId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "session " + sessionId + " does not exist in department " + deptId));
    }

    /**
     * Looks up a session of the given department without failing when it is absent.
     *
     * <p>A row whose {@code tenant_id} / {@code dept_id} do not match the requested department is
     * reported as absent: the caller is not allowed to learn that the id exists elsewhere.</p>
     *
     * @param tenantId  hospital/tenant id, must not be blank
     * @param deptId    department id, must not be blank
     * @param sessionId consultation session id, must not be blank
     * @return the session, or {@link Optional#empty()} when it does not exist in this department
     * @throws IllegalArgumentException if an identity segment is blank
     * @throws BizException             {@link ErrorCode#STORAGE_ERROR} on a MySQL failure
     */
    public Optional<ChatSessionDO> findSession(String tenantId, String deptId, String sessionId) {
        requireText(tenantId, "tenantId");
        requireText(deptId, "deptId");
        requireText(sessionId, "sessionId");
        ChatSessionDO stored;
        try {
            stored = sessionMapper.selectById(sessionId);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to load session " + sessionId + " from mysql", ex);
        }
        if (stored == null) {
            return Optional.empty();
        }
        if (!tenantId.equals(stored.getTenantId()) || !deptId.equals(stored.getDeptId())) {
            log.warn("session {} was requested through department {}/{} but belongs to another one, "
                    + "reporting it as absent", sessionId, tenantId, deptId);
            return Optional.empty();
        }
        if (accessGuard != null) {
            accessGuard.assertOwned(MedSecurityContext.getCurrent(), stored);
        }
        return Optional.of(stored);
    }

    /**
     * Loads a session and asserts that it may still accept messages.
     *
     * <p>This is the guard the streaming consultation path calls before appending a turn: a closed or
     * archived transcript must never grow.</p>
     *
     * @param tenantId  hospital/tenant id, must not be blank
     * @param deptId    department id, must not be blank
     * @param sessionId consultation session id, must not be blank
     * @return the writable session, never {@code null}
     * @throws IllegalArgumentException if an identity segment is blank
     * @throws BizException             {@link ErrorCode#NOT_FOUND} when unknown,
     *                                  {@link ErrorCode#BAD_REQUEST} when already closed or archived
     */
    public ChatSessionDO requireWritableSession(String tenantId, String deptId, String sessionId) {
        ChatSessionDO session = getSession(tenantId, deptId, sessionId);
        if (!session.getStatus().isWritable()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "session " + sessionId + " is " + session.getStatus()
                            + " and accepts no new message");
        }
        return session;
    }

    /**
     * Closes an active session so it stops accepting messages.
     *
     * <p>Idempotent: closing an already closed session returns it unchanged. Archived sessions are
     * rejected — archiving is the terminal state, and pretending to "close" it would hide the fact
     * that the transcript is already cold.</p>
     *
     * @param tenantId  hospital/tenant id, must not be blank
     * @param deptId    department id, must not be blank
     * @param sessionId consultation session id, must not be blank
     * @return the session in its {@link SessionStatus#CLOSED} state, never {@code null}
     * @throws IllegalArgumentException if an identity segment is blank
     * @throws BizException             {@link ErrorCode#NOT_FOUND} when unknown,
     *                                  {@link ErrorCode#BAD_REQUEST} when archived,
     *                                  {@link ErrorCode#SESSION_LOCKED} when busy,
     *                                  {@link ErrorCode#STORAGE_ERROR} on a MySQL failure
     */
    public ChatSessionDO closeSession(String tenantId, String deptId, String sessionId) {
        return lockService.executeLocked(tenantId, deptId, sessionId, () -> {
            ChatSessionDO session = getSession(tenantId, deptId, sessionId);
            if (session.getStatus() == SessionStatus.CLOSED) {
                return session;
            }
            if (session.getStatus() == SessionStatus.ARCHIVED) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "session " + sessionId + " is archived and cannot be closed");
            }
            return transition(session, SessionStatus.CLOSED);
        });
    }

    /**
     * Archives a session as cold data and drops its cached message window.
     *
     * <p>Both an active and an already closed session can be archived, which mirrors the two ways a
     * consultation ends (explicit close, or a retention job sweeping stale sessions). Idempotent for
     * an already archived session, in which case the cached window is evicted again — a leftover key
     * from a previously failed eviction must not survive.</p>
     *
     * @param tenantId  hospital/tenant id, must not be blank
     * @param deptId    department id, must not be blank
     * @param sessionId consultation session id, must not be blank
     * @return the session in its {@link SessionStatus#ARCHIVED} state, never {@code null}
     * @throws IllegalArgumentException if an identity segment is blank
     * @throws BizException             {@link ErrorCode#NOT_FOUND} when unknown,
     *                                  {@link ErrorCode#SESSION_LOCKED} when busy,
     *                                  {@link ErrorCode#STORAGE_ERROR} when MySQL fails or the cached
     *                                  window could not be dropped
     */
    public ChatSessionDO archiveSession(String tenantId, String deptId, String sessionId) {
        return lockService.executeLocked(tenantId, deptId, sessionId, () -> {
            ChatSessionDO session = getSession(tenantId, deptId, sessionId);
            ChatSessionDO archived = session.getStatus() == SessionStatus.ARCHIVED
                    ? session
                    : transition(session, SessionStatus.ARCHIVED);
            cache.evict(tenantId, deptId, sessionId);
            return archived;
        });
    }

    /**
     * Lists one page of sessions, newest first.
     *
     * <p>A page beyond the end of the result set is not an error: it returns an empty page carrying the
     * real total, so a client that paged past the end can still render its pager.</p>
     *
     * @param query the listing request, must not be {@code null}
     * @return the requested page, never {@code null}
     * @throws IllegalArgumentException if {@code query} is {@code null}
     * @throws BizException             {@link ErrorCode#BAD_REQUEST} when the requested size exceeds
     *                                  {@link MedSessionProperties#getMaxPageSize()};
     *                                  {@link ErrorCode#STORAGE_ERROR} on a MySQL failure
     */
    public PageResult<ChatSessionDO> pageSessions(SessionPageQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        if (accessGuard != null) {
            accessGuard.assertScope(
                    MedSecurityContext.getCurrent(), query.tenantId(), query.deptId(), query.patientId());
        }
        int size = resolvePageSize(query.size());
        long total;
        try {
            total = sessionMapper.countByCondition(
                    query.tenantId(), query.deptId(), query.patientId(), query.status());
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to count the sessions of department " + query.deptId(), ex);
        }
        long offset = (long) (query.page() - 1) * size;
        if (total <= 0 || offset >= total) {
            return PageResult.of(query.page(), size, Math.max(total, 0L), Collections.emptyList());
        }
        List<ChatSessionDO> records;
        try {
            records = sessionMapper.selectPage(
                    query.tenantId(), query.deptId(), query.patientId(), query.status(), offset, size);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to list the sessions of department " + query.deptId(), ex);
        }
        return PageResult.of(query.page(), size, total,
                records == null ? Collections.emptyList() : records);
    }

    /**
     * Resolves the effective page size of a listing request.
     *
     * @param requested the size asked for, or {@code null} to apply the configured default
     * @return the effective page size, always positive
     * @throws BizException {@link ErrorCode#BAD_REQUEST} when the requested size is not positive or
     *                      exceeds {@link MedSessionProperties#getMaxPageSize()}
     */
    public int resolvePageSize(@Nullable Integer requested) {
        if (requested == null) {
            return properties.getDefaultPageSize();
        }
        if (requested < 1) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "page size must be positive but was " + requested);
        }
        if (requested > properties.getMaxPageSize()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "page size " + requested + " exceeds the configured maximum "
                            + properties.getMaxPageSize());
        }
        return requested;
    }

    /**
     * Applies a lifecycle transition with a compare-and-set update and mirrors it into the entity.
     */
    private ChatSessionDO transition(ChatSessionDO session, SessionStatus target) {
        long now = clock.millis();
        SessionStatus expected = session.getStatus();
        int rows;
        try {
            rows = sessionMapper.updateStatus(session.getSessionId(), target, expected, now);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to move session " + session.getSessionId() + " to " + target, ex);
        }
        if (rows <= 0) {
            throw new BizException(ErrorCode.SESSION_LOCKED,
                    "session " + session.getSessionId() + " left state " + expected
                            + " concurrently, retry the " + target + " transition");
        }
        session.setStatus(target);
        session.setUpdatedAt(now);
        return session;
    }

    /**
     * Trims a title and enforces the configured length limit.
     */
    @Nullable
    private String normalizeTitle(@Nullable String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String trimmed = title.trim();
        if (trimmed.length() > properties.getMaxTitleLength()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "session title must not exceed " + properties.getMaxTitleLength()
                            + " characters but had " + trimmed.length());
        }
        return trimmed;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
