package com.med.qa.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.common.result.PageResult;
import com.med.qa.config.MedSessionProperties;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.enums.SessionStatus;
import com.med.qa.mapper.ChatSessionMapper;
import com.med.qa.memory.cache.RedisMessageCache;
import com.med.qa.memory.lock.SessionLockService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;

/**
 * Unit tests of the consultation-session lifecycle service.
 *
 * <p>The three collaborators (mapper, lock, cache) are mocked, so the suite verifies the business rules
 * themselves — identity validation, the ACTIVE/CLOSED/ARCHIVED transition matrix, the compare-and-set
 * semantics of {@code updateStatus}, the tenant/department isolation of lookups, and the listing guard
 * rails — without MySQL, Redis or a live lock.</p>
 */
@ExtendWith(MockitoExtension.class)
class MedChatSessionServiceTest {

    private static final String TENANT = "hosp-01";

    private static final String DEPT = "cardiology";

    private static final String PATIENT = "pat-77";

    private static final long NOW = 1_700_000_000_000L;

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);

    @Mock
    private ChatSessionMapper sessionMapper;

    @Mock
    private SessionLockService lockService;

    @Mock
    private RedisMessageCache cache;

    private MedChatSessionService service;

    @BeforeEach
    void setUp() {
        // The Redisson lock executes the guarded action inline in the unit test; concurrency is covered
        // by the lock service's own tests. Marked lenient because only the close/archive tests exercise
        // the lock path, while lookup/create/list tests do not.
        lenient().when(lockService.executeLocked(anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> invocation.<Supplier<Object>>getArgument(3).get());
        service = new MedChatSessionService(sessionMapper, lockService, cache,
                new MedSessionProperties(), FIXED_CLOCK);
    }

    private ChatSessionDO storedSession(SessionStatus status) {
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId("sess-1");
        session.setTenantId(TENANT);
        session.setDeptId(DEPT);
        session.setPatientId(PATIENT);
        session.setTitle("chest pain");
        session.setStatus(status);
        session.setCreatedAt(NOW);
        session.setUpdatedAt(NOW);
        return session;
    }

    // ---------------------------------------------------------------- create

    @Test
    @DisplayName("createSession persists an ACTIVE session with a generated id and timestamps")
    void createSessionSucceeds() {
        when(sessionMapper.insert(any(ChatSessionDO.class))).thenReturn(1);

        ChatSessionDO created = service.createSession(TENANT, DEPT, PATIENT, "chest pain");

        assertNotNull(created.getSessionId());
        assertEquals(SessionStatus.ACTIVE, created.getStatus());
        assertEquals(NOW, created.getCreatedAt());
        assertEquals(NOW, created.getUpdatedAt());
        verify(sessionMapper).insert(any(ChatSessionDO.class));
    }

    @Test
    @DisplayName("createSession stores a blank title as null")
    void createSessionNormalizesBlankTitle() {
        when(sessionMapper.insert(any(ChatSessionDO.class))).thenReturn(1);

        ChatSessionDO created = service.createSession(TENANT, DEPT, PATIENT, "   ");

        assertFalse(created.getTitle() != null && !created.getTitle().isEmpty());
        assertTrue(created.getTitle() == null || created.getTitle().isEmpty());
    }

    @Test
    @DisplayName("createSession rejects a title longer than the configured maximum")
    void createSessionRejectsOverlongTitle() {
        MedSessionProperties tight = new MedSessionProperties();
        tight.setMaxTitleLength(5);
        MedChatSessionService tightService =
                new MedChatSessionService(sessionMapper, lockService, cache, tight, FIXED_CLOCK);

        BizException ex = assertThrows(BizException.class,
                () -> tightService.createSession(TENANT, DEPT, PATIENT, "this title is far too long"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verify(sessionMapper, never()).insert(any(ChatSessionDO.class));
    }

    @Test
    @DisplayName("createSession rejects a blank identity segment as a programming error")
    void createSessionRejectsBlankIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createSession("", DEPT, PATIENT, null));
    }

    @Test
    @DisplayName("createSession maps an insert failure onto STORAGE_ERROR")
    void createSessionPropagatesStorageError() {
        when(sessionMapper.insert(any(ChatSessionDO.class)))
                .thenThrow(mock(DataAccessException.class));

        BizException ex = assertThrows(BizException.class,
                () -> service.createSession(TENANT, DEPT, PATIENT, null));
        assertEquals(ErrorCode.STORAGE_ERROR, ex.getErrorCode());
    }

    // ---------------------------------------------------------------- lookup

    @Test
    @DisplayName("getSession returns the stored session")
    void getSessionReturnsStored() {
        when(sessionMapper.selectById("sess-1")).thenReturn(storedSession(SessionStatus.ACTIVE));

        ChatSessionDO session = service.getSession(TENANT, DEPT, "sess-1");
        assertEquals("sess-1", session.getSessionId());
    }

    @Test
    @DisplayName("getSession throws NOT_FOUND for an unknown id")
    void getSessionNotFound() {
        when(sessionMapper.selectById("missing")).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> service.getSession(TENANT, DEPT, "missing"));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("findSession reports a session under another department as absent, never as existing")
    void findSessionHidesCrossDepartment() {
        ChatSessionDO other = storedSession(SessionStatus.ACTIVE);
        other.setDeptId("other-dept");
        when(sessionMapper.selectById("sess-1")).thenReturn(other);

        assertTrue(service.findSession(TENANT, DEPT, "sess-1").isEmpty());
    }

    @Test
    @DisplayName("requireWritableSession rejects a closed session as BAD_REQUEST")
    void requireWritableRejectsClosed() {
        when(sessionMapper.selectById("sess-1")).thenReturn(storedSession(SessionStatus.CLOSED));

        BizException ex = assertThrows(BizException.class,
                () -> service.requireWritableSession(TENANT, DEPT, "sess-1"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    // ---------------------------------------------------------------- close

    @Test
    @DisplayName("closeSession transitions ACTIVE to CLOSED")
    void closeSessionTransitions() {
        when(sessionMapper.selectById("sess-1")).thenReturn(storedSession(SessionStatus.ACTIVE));
        when(sessionMapper.updateStatus(anyString(), eq(SessionStatus.CLOSED), eq(SessionStatus.ACTIVE), anyLong()))
                .thenReturn(1);

        ChatSessionDO closed = service.closeSession(TENANT, DEPT, "sess-1");
        assertEquals(SessionStatus.CLOSED, closed.getStatus());
        verify(sessionMapper).updateStatus("sess-1", SessionStatus.CLOSED, SessionStatus.ACTIVE, NOW);
    }

    @Test
    @DisplayName("closeSession is idempotent for an already closed session")
    void closeSessionIdempotent() {
        when(sessionMapper.selectById("sess-1")).thenReturn(storedSession(SessionStatus.CLOSED));

        ChatSessionDO closed = service.closeSession(TENANT, DEPT, "sess-1");
        assertEquals(SessionStatus.CLOSED, closed.getStatus());
        verify(sessionMapper, never()).updateStatus(anyString(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("closeSession rejects an archived session")
    void closeSessionRejectsArchived() {
        when(sessionMapper.selectById("sess-1")).thenReturn(storedSession(SessionStatus.ARCHIVED));

        BizException ex = assertThrows(BizException.class,
                () -> service.closeSession(TENANT, DEPT, "sess-1"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    @DisplayName("closeSession maps a lost compare-and-set onto SESSION_LOCKED")
    void closeSessionLostCas() {
        when(sessionMapper.selectById("sess-1")).thenReturn(storedSession(SessionStatus.ACTIVE));
        when(sessionMapper.updateStatus(anyString(), any(), any(), anyLong())).thenReturn(0);

        BizException ex = assertThrows(BizException.class,
                () -> service.closeSession(TENANT, DEPT, "sess-1"));
        assertEquals(ErrorCode.SESSION_LOCKED, ex.getErrorCode());
    }

    // ---------------------------------------------------------------- archive

    @Test
    @DisplayName("archiveSession transitions ACTIVE to ARCHIVED and evicts the cached window")
    void archiveSessionTransitionsAndEvicts() {
        when(sessionMapper.selectById("sess-1")).thenReturn(storedSession(SessionStatus.ACTIVE));
        when(sessionMapper.updateStatus(anyString(), eq(SessionStatus.ARCHIVED), eq(SessionStatus.ACTIVE), anyLong()))
                .thenReturn(1);

        ChatSessionDO archived = service.archiveSession(TENANT, DEPT, "sess-1");
        assertEquals(SessionStatus.ARCHIVED, archived.getStatus());
        verify(cache).evict(TENANT, DEPT, "sess-1");
    }

    @Test
    @DisplayName("archiveSession is idempotent and still re-evicts a possibly stale window")
    void archiveSessionIdempotentEvicts() {
        when(sessionMapper.selectById("sess-1")).thenReturn(storedSession(SessionStatus.ARCHIVED));

        ChatSessionDO archived = service.archiveSession(TENANT, DEPT, "sess-1");
        assertEquals(SessionStatus.ARCHIVED, archived.getStatus());
        verify(sessionMapper, never()).updateStatus(anyString(), any(), any(), anyLong());
        verify(cache).evict(TENANT, DEPT, "sess-1");
    }

    @Test
    @DisplayName("archiveSession maps a lost compare-and-set onto SESSION_LOCKED")
    void archiveSessionLostCas() {
        when(sessionMapper.selectById("sess-1")).thenReturn(storedSession(SessionStatus.ACTIVE));
        when(sessionMapper.updateStatus(anyString(), any(), any(), anyLong())).thenReturn(0);

        BizException ex = assertThrows(BizException.class,
                () -> service.archiveSession(TENANT, DEPT, "sess-1"));
        assertEquals(ErrorCode.SESSION_LOCKED, ex.getErrorCode());
    }

    // ---------------------------------------------------------------- paging

    @Test
    @DisplayName("pageSessions returns an empty page when nothing matches")
    void pageSessionsEmpty() {
        when(sessionMapper.countByCondition(TENANT, DEPT, null, null)).thenReturn(0L);

        PageResult<ChatSessionDO> page = service.pageSessions(
                SessionPageQuery.builder(TENANT, DEPT).build());
        assertTrue(page.isEmpty());
        assertEquals(0, page.total());
        verify(sessionMapper, never()).selectPage(anyString(), anyString(), any(), any(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("pageSessions returns the requested window and total")
    void pageSessionsReturnsWindow() {
        when(sessionMapper.countByCondition(TENANT, DEPT, null, null)).thenReturn(3L);
        when(sessionMapper.selectPage(anyString(), anyString(), any(), any(), anyLong(), anyInt()))
                .thenReturn(List.of(storedSession(SessionStatus.ACTIVE)));

        PageResult<ChatSessionDO> page = service.pageSessions(
                SessionPageQuery.builder(TENANT, DEPT).page(1).size(10).build());
        assertEquals(3, page.total());
        assertEquals(1, page.records().size());
    }

    @Test
    @DisplayName("pageSessions rejects a page size above the configured maximum")
    void pageSessionsRejectsOversizedPage() {
        BizException ex = assertThrows(BizException.class, () -> service.resolvePageSize(10_000));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    @DisplayName("resolvePageSize applies the configured default and rejects non-positive sizes")
    void resolvePageSizeDefaultsAndRejects() {
        assertEquals(new MedSessionProperties().getDefaultPageSize(), service.resolvePageSize(null));
        BizException ex = assertThrows(BizException.class, () -> service.resolvePageSize(0));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    @DisplayName("pageSessions returns an empty page past the end instead of an error")
    void pageSessionsPastEnd() {
        when(sessionMapper.countByCondition(TENANT, DEPT, null, null)).thenReturn(2L);

        PageResult<ChatSessionDO> page = service.pageSessions(
                SessionPageQuery.builder(TENANT, DEPT).page(5).size(10).build());
        assertTrue(page.isEmpty());
        assertEquals(2, page.total());
    }
}
