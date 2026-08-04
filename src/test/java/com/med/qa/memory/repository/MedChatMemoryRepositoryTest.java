package com.med.qa.memory.repository;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.enums.RoleType;
import com.med.qa.mapper.ChatMessageMapper;
import com.med.qa.memory.cache.RedisMessageCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the two-tier chat memory repository. Both tiers are mocked, so the suite verifies
 * the cache-aside contract itself — hit/miss routing, MySQL back-fill, dual-write ordering and the
 * degradation rules — without MySQL or Redis being available.
 */
@ExtendWith(MockitoExtension.class)
class MedChatMemoryRepositoryTest {

    private static final String TENANT = "hosp-01";
    private static final String DEPT = "cardiology";
    private static final String SESSION = "sess-9f2c";

    @Mock
    private ChatMessageMapper messageMapper;

    @Mock
    private RedisMessageCache cache;

    private MedChatMemoryRepository repository;

    @BeforeEach
    void setUp() {
        repository = new MedChatMemoryRepository(messageMapper, cache);
    }

    private static ChatMessageDO message(String id, long createdAt) {
        return ChatMessageDO.builder()
                .messageId(id)
                .sessionId(SESSION)
                .tenantId(TENANT)
                .deptId(DEPT)
                .patientId("pat-77")
                .role(RoleType.PATIENT)
                .content("持续胸闷三天")
                .tokenCount(12)
                .createdAt(createdAt)
                .build();
    }

    // ---------------------------------------------------------------- append

    @Test
    @DisplayName("append writes MySQL first and only then mirrors the message into the cache")
    void appendWritesMysqlBeforeCache() {
        ChatMessageDO msg = message("msg-1", 1000L);
        when(messageMapper.insert(msg)).thenReturn(1);

        repository.append(msg);

        InOrder ordered = inOrder(messageMapper, cache);
        ordered.verify(messageMapper).insert(msg);
        ordered.verify(cache).append(msg);
        ordered.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("append rejects a message without a role before touching any storage tier")
    void appendRejectsMessageWithoutRole() {
        ChatMessageDO msg = ChatMessageDO.builder()
                .messageId("msg-1").sessionId(SESSION).tenantId(TENANT).deptId(DEPT).build();

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> repository.append(msg));

        assertTrue(ex.getMessage().contains("role"));
        verifyNoInteractions(messageMapper, cache);
    }

    @Test
    @DisplayName("append rejects a null message")
    void appendRejectsNullMessage() {
        assertThrows(IllegalArgumentException.class, () -> repository.append(null));
        verifyNoInteractions(messageMapper, cache);
    }

    @Test
    @DisplayName("append surfaces a MySQL outage as STORAGE_ERROR and never caches the message")
    void appendTranslatesMysqlFailure() {
        ChatMessageDO msg = message("msg-1", 1000L);
        when(messageMapper.insert(msg)).thenThrow(new QueryTimeoutException("shard busy"));

        BizException ex = assertThrows(BizException.class, () -> repository.append(msg));

        assertEquals(ErrorCode.STORAGE_ERROR, ex.getErrorCode());
        verify(cache, never()).append(any());
    }

    @Test
    @DisplayName("append fails when MySQL silently accepts no row")
    void appendFailsWhenNoRowInserted() {
        ChatMessageDO msg = message("msg-1", 1000L);
        when(messageMapper.insert(msg)).thenReturn(0);

        BizException ex = assertThrows(BizException.class, () -> repository.append(msg));

        assertEquals(ErrorCode.STORAGE_ERROR, ex.getErrorCode());
        verify(cache, never()).append(any());
    }

    @Test
    @DisplayName("append invalidates the window when the cache mirror fails, without failing the call")
    void appendInvalidatesWindowWhenMirrorFails() {
        ChatMessageDO msg = message("msg-1", 1000L);
        when(messageMapper.insert(msg)).thenReturn(1);
        doThrow(new BizException(ErrorCode.STORAGE_ERROR, "redis down")).when(cache).append(msg);

        repository.append(msg);

        verify(cache).evict(TENANT, DEPT, SESSION);
    }

    @Test
    @DisplayName("append escalates when neither the cache mirror nor the invalidation succeeds")
    void appendEscalatesWhenInvalidationAlsoFails() {
        ChatMessageDO msg = message("msg-1", 1000L);
        when(messageMapper.insert(msg)).thenReturn(1);
        doThrow(new BizException(ErrorCode.STORAGE_ERROR, "redis down")).when(cache).append(msg);
        when(cache.evict(TENANT, DEPT, SESSION))
                .thenThrow(new BizException(ErrorCode.STORAGE_ERROR, "redis still down"));

        BizException ex = assertThrows(BizException.class, () -> repository.append(msg));

        assertEquals(ErrorCode.STORAGE_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("could neither be updated nor invalidated"));
        assertEquals(1, ex.getSuppressed().length);
    }

    // ------------------------------------------------------------- appendAll

    @Test
    @DisplayName("appendAll persists every message and mirrors them in order")
    void appendAllPersistsBatchInOrder() {
        ChatMessageDO question = message("msg-1", 1000L);
        ChatMessageDO answer = message("msg-2", 1001L);
        when(messageMapper.insert(any())).thenReturn(1);

        repository.appendAll(Arrays.asList(question, answer));

        InOrder ordered = inOrder(messageMapper, cache);
        ordered.verify(messageMapper).insert(question);
        ordered.verify(messageMapper).insert(answer);
        ordered.verify(cache).append(question);
        ordered.verify(cache).append(answer);
    }

    @Test
    @DisplayName("appendAll accepts an empty batch as a no-op")
    void appendAllAcceptsEmptyBatch() {
        repository.appendAll(Collections.emptyList());
        verifyNoInteractions(messageMapper, cache);
    }

    @Test
    @DisplayName("appendAll validates the whole batch before writing anything")
    void appendAllValidatesBeforeWriting() {
        ChatMessageDO valid = message("msg-1", 1000L);
        ChatMessageDO invalid = ChatMessageDO.builder()
                .messageId("msg-2").sessionId(SESSION).tenantId(TENANT).build();

        assertThrows(IllegalArgumentException.class,
                () -> repository.appendAll(Arrays.asList(valid, invalid)));

        verifyNoInteractions(messageMapper, cache);
    }

    @Test
    @DisplayName("appendAll rejects a null batch")
    void appendAllRejectsNullBatch() {
        assertThrows(IllegalArgumentException.class, () -> repository.appendAll(null));
    }

    @Test
    @DisplayName("appendAll stops mirroring and invalidates once the cache starts failing")
    void appendAllInvalidatesOnMirrorFailure() {
        ChatMessageDO first = message("msg-1", 1000L);
        ChatMessageDO second = message("msg-2", 1001L);
        when(messageMapper.insert(any())).thenReturn(1);
        doThrow(new BizException(ErrorCode.STORAGE_ERROR, "redis down")).when(cache).append(first);

        repository.appendAll(Arrays.asList(first, second));

        verify(cache).evict(TENANT, DEPT, SESSION);
        verify(cache, never()).append(second);
    }

    // --------------------------------------------------------------- findAll

    @Test
    @DisplayName("findAll serves a cache hit without querying MySQL")
    void findAllServesCacheHit() {
        List<ChatMessageDO> cached = List.of(message("msg-1", 1000L));
        when(cache.findAll(TENANT, DEPT, SESSION)).thenReturn(cached);

        List<ChatMessageDO> found = repository.findAll(TENANT, DEPT, SESSION);

        assertSame(cached, found);
        verifyNoInteractions(messageMapper);
    }

    @Test
    @DisplayName("findAll falls back to MySQL on a cache miss and back-fills the window")
    void findAllFallsBackToMysqlAndBackFills() {
        List<ChatMessageDO> stored = List.of(message("msg-1", 1000L), message("msg-2", 1001L));
        when(cache.findAll(TENANT, DEPT, SESSION)).thenReturn(Collections.emptyList());
        when(messageMapper.selectBySessionIdOrderByCreatedAtAsc(SESSION)).thenReturn(stored);

        List<ChatMessageDO> found = repository.findAll(TENANT, DEPT, SESSION);

        assertEquals(stored, found);
        verify(cache).replaceAll(TENANT, DEPT, SESSION, stored);
    }

    @Test
    @DisplayName("findAll skips the back-fill when MySQL holds no message either")
    void findAllSkipsBackFillOnEmptySession() {
        when(cache.findAll(TENANT, DEPT, SESSION)).thenReturn(Collections.emptyList());
        when(messageMapper.selectBySessionIdOrderByCreatedAtAsc(SESSION))
                .thenReturn(Collections.emptyList());

        assertTrue(repository.findAll(TENANT, DEPT, SESSION).isEmpty());

        verify(cache, never()).replaceAll(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("findAll still returns the MySQL answer when the back-fill fails")
    void findAllToleratesBackFillFailure() {
        List<ChatMessageDO> stored = List.of(message("msg-1", 1000L));
        when(cache.findAll(TENANT, DEPT, SESSION)).thenReturn(Collections.emptyList());
        when(messageMapper.selectBySessionIdOrderByCreatedAtAsc(SESSION)).thenReturn(stored);
        doThrow(new BizException(ErrorCode.STORAGE_ERROR, "redis down"))
                .when(cache).replaceAll(TENANT, DEPT, SESSION, stored);

        assertEquals(stored, repository.findAll(TENANT, DEPT, SESSION));
    }

    @Test
    @DisplayName("findAll normalizes a null mapper result to an empty conversation")
    void findAllNormalizesNullMapperResult() {
        when(cache.findAll(TENANT, DEPT, SESSION)).thenReturn(Collections.emptyList());
        when(messageMapper.selectBySessionIdOrderByCreatedAtAsc(SESSION)).thenReturn(null);

        assertTrue(repository.findAll(TENANT, DEPT, SESSION).isEmpty());
    }

    @Test
    @DisplayName("findAll surfaces a MySQL outage as STORAGE_ERROR")
    void findAllTranslatesMysqlFailure() {
        when(cache.findAll(TENANT, DEPT, SESSION)).thenReturn(Collections.emptyList());
        when(messageMapper.selectBySessionIdOrderByCreatedAtAsc(SESSION))
                .thenThrow(new RecoverableDataAccessException("connection lost"));

        BizException ex = assertThrows(BizException.class,
                () -> repository.findAll(TENANT, DEPT, SESSION));

        assertEquals(ErrorCode.STORAGE_ERROR, ex.getErrorCode());
    }

    @Test
    @DisplayName("findAll(session) derives the identity from the session entity")
    void findAllBySessionEntity() {
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId(SESSION);
        session.setTenantId(TENANT);
        session.setDeptId(DEPT);
        List<ChatMessageDO> cached = List.of(message("msg-1", 1000L));
        when(cache.findAll(TENANT, DEPT, SESSION)).thenReturn(cached);

        assertEquals(cached, repository.findAll(session));
    }

    @Test
    @DisplayName("findAll(session) rejects a null session")
    void findAllRejectsNullSession() {
        assertThrows(IllegalArgumentException.class, () -> repository.findAll((ChatSessionDO) null));
        verifyNoInteractions(messageMapper, cache);
    }

    // ------------------------------------------------------------ findRecent

    @Test
    @DisplayName("findRecent serves the tail of the cached window without querying MySQL")
    void findRecentServesCacheHit() {
        List<ChatMessageDO> cached = List.of(message("msg-2", 1001L));
        when(cache.findLast(TENANT, DEPT, SESSION, 1)).thenReturn(cached);

        assertSame(cached, repository.findRecent(TENANT, DEPT, SESSION, 1));
        verifyNoInteractions(messageMapper);
    }

    @Test
    @DisplayName("findRecent trims the MySQL fallback to the requested window size")
    void findRecentTrimsMysqlFallback() {
        List<ChatMessageDO> stored = new ArrayList<>(List.of(
                message("msg-1", 1000L), message("msg-2", 1001L), message("msg-3", 1002L)));
        when(cache.findLast(TENANT, DEPT, SESSION, 2)).thenReturn(Collections.emptyList());
        when(messageMapper.selectBySessionIdOrderByCreatedAtAsc(SESSION)).thenReturn(stored);

        List<ChatMessageDO> found = repository.findRecent(TENANT, DEPT, SESSION, 2);

        assertEquals(2, found.size());
        assertEquals("msg-2", found.get(0).getMessageId());
        assertEquals("msg-3", found.get(1).getMessageId());
        verify(cache).replaceAll(TENANT, DEPT, SESSION, stored);
    }

    @Test
    @DisplayName("findRecent returns the whole conversation when it is shorter than the window")
    void findRecentReturnsWholeShortConversation() {
        List<ChatMessageDO> stored = List.of(message("msg-1", 1000L));
        when(cache.findLast(TENANT, DEPT, SESSION, 10)).thenReturn(Collections.emptyList());
        when(messageMapper.selectBySessionIdOrderByCreatedAtAsc(SESSION)).thenReturn(stored);

        assertEquals(stored, repository.findRecent(TENANT, DEPT, SESSION, 10));
    }

    @Test
    @DisplayName("findRecent rejects a non-positive window size")
    void findRecentRejectsNonPositiveLimit() {
        when(cache.findLast(TENANT, DEPT, SESSION, 0))
                .thenThrow(new IllegalArgumentException("limit must be positive but was 0"));

        assertThrows(IllegalArgumentException.class,
                () -> repository.findRecent(TENANT, DEPT, SESSION, 0));
        verifyNoInteractions(messageMapper);
    }

    // --------------------------------------------------------------- findById

    @Test
    @DisplayName("findById returns the stored message")
    void findByIdReturnsMessage() {
        ChatMessageDO stored = message("msg-1", 1000L);
        when(messageMapper.selectById("msg-1")).thenReturn(stored);

        Optional<ChatMessageDO> found = repository.findById("msg-1");

        assertTrue(found.isPresent());
        assertSame(stored, found.get());
        verifyNoInteractions(cache);
    }

    @Test
    @DisplayName("findById returns empty for an unknown id and rejects a blank one")
    void findByIdHandlesMissAndBlankId() {
        when(messageMapper.selectById("msg-unknown")).thenReturn(null);

        assertTrue(repository.findById("msg-unknown").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> repository.findById("  "));
    }

    @Test
    @DisplayName("findById surfaces a MySQL outage as STORAGE_ERROR")
    void findByIdTranslatesMysqlFailure() {
        when(messageMapper.selectById("msg-1")).thenThrow(new QueryTimeoutException("broadcast timeout"));

        BizException ex = assertThrows(BizException.class, () -> repository.findById("msg-1"));

        assertEquals(ErrorCode.STORAGE_ERROR, ex.getErrorCode());
    }

    // ----------------------------------------------------------------- reload

    @Test
    @DisplayName("reload bypasses the cache and rewrites the window from MySQL")
    void reloadRewritesWindowFromMysql() {
        List<ChatMessageDO> stored = List.of(message("msg-1", 1000L));
        when(messageMapper.selectBySessionIdOrderByCreatedAtAsc(SESSION)).thenReturn(stored);

        assertEquals(stored, repository.reload(TENANT, DEPT, SESSION));

        verify(cache).replaceAll(TENANT, DEPT, SESSION, stored);
        verify(cache, never()).findAll(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("reload drops the window when the session no longer holds any message")
    void reloadDropsWindowOfEmptySession() {
        when(messageMapper.selectBySessionIdOrderByCreatedAtAsc(SESSION))
                .thenReturn(Collections.emptyList());

        assertTrue(repository.reload(TENANT, DEPT, SESSION).isEmpty());

        verify(cache).replaceAll(TENANT, DEPT, SESSION, Collections.emptyList());
    }

    @Test
    @DisplayName("reload rejects a blank identity segment")
    void reloadRejectsBlankIdentity() {
        assertThrows(IllegalArgumentException.class, () -> repository.reload(TENANT, "", SESSION));
        verifyNoInteractions(messageMapper, cache);
    }

    // ------------------------------------------------------------- markMasked

    @Test
    @DisplayName("markMasked updates MySQL and evicts the window holding the unmasked copy")
    void markMaskedUpdatesAndEvicts() {
        when(messageMapper.selectById("msg-1")).thenReturn(message("msg-1", 1000L));
        when(messageMapper.updateMasked("msg-1", true)).thenReturn(1);

        assertTrue(repository.markMasked("msg-1", true));

        InOrder ordered = inOrder(messageMapper, cache);
        ordered.verify(messageMapper).updateMasked("msg-1", true);
        ordered.verify(cache).evict(TENANT, DEPT, SESSION);
    }

    @Test
    @DisplayName("markMasked reports false for an unknown message and never touches the cache")
    void markMaskedReturnsFalseForUnknownMessage() {
        when(messageMapper.selectById("msg-unknown")).thenReturn(null);

        assertFalse(repository.markMasked("msg-unknown", true));

        verify(messageMapper, never()).updateMasked(anyString(), anyBoolean());
        verifyNoInteractions(cache);
    }

    @Test
    @DisplayName("markMasked reports false when the update affects no row")
    void markMaskedReturnsFalseWhenNoRowUpdated() {
        when(messageMapper.selectById("msg-1")).thenReturn(message("msg-1", 1000L));
        when(messageMapper.updateMasked("msg-1", true)).thenReturn(0);

        assertFalse(repository.markMasked("msg-1", true));

        verifyNoInteractions(cache);
    }

    @Test
    @DisplayName("markMasked propagates a failed eviction: a stale unmasked window is a privacy leak")
    void markMaskedPropagatesEvictionFailure() {
        when(messageMapper.selectById("msg-1")).thenReturn(message("msg-1", 1000L));
        when(messageMapper.updateMasked("msg-1", true)).thenReturn(1);
        when(cache.evict(TENANT, DEPT, SESSION))
                .thenThrow(new BizException(ErrorCode.STORAGE_ERROR, "redis down"));

        BizException ex = assertThrows(BizException.class, () -> repository.markMasked("msg-1", true));

        assertEquals(ErrorCode.STORAGE_ERROR, ex.getErrorCode());
    }

    @Test
    @DisplayName("markMasked surfaces a MySQL update outage as STORAGE_ERROR")
    void markMaskedTranslatesMysqlFailure() {
        when(messageMapper.selectById("msg-1")).thenReturn(message("msg-1", 1000L));
        when(messageMapper.updateMasked("msg-1", true))
                .thenThrow(new QueryTimeoutException("shard busy"));

        BizException ex = assertThrows(BizException.class, () -> repository.markMasked("msg-1", true));

        assertEquals(ErrorCode.STORAGE_ERROR, ex.getErrorCode());
        verifyNoInteractions(cache);
    }

    // ---------------------------------------------------------- deleteMessage

    @Test
    @DisplayName("deleteMessage removes the row and evicts the cached window")
    void deleteMessageRemovesRowAndEvicts() {
        when(messageMapper.selectById("msg-1")).thenReturn(message("msg-1", 1000L));
        when(messageMapper.deleteById("msg-1")).thenReturn(1);

        assertTrue(repository.deleteMessage("msg-1"));

        verify(cache).evict(TENANT, DEPT, SESSION);
    }

    @Test
    @DisplayName("deleteMessage reports false for an unknown message")
    void deleteMessageReturnsFalseForUnknownMessage() {
        when(messageMapper.selectById("msg-unknown")).thenReturn(null);

        assertFalse(repository.deleteMessage("msg-unknown"));

        verify(messageMapper, never()).deleteById(anyString());
        verifyNoInteractions(cache);
    }

    @Test
    @DisplayName("deleteMessage reports false when the delete affects no row")
    void deleteMessageReturnsFalseWhenNoRowDeleted() {
        when(messageMapper.selectById("msg-1")).thenReturn(message("msg-1", 1000L));
        when(messageMapper.deleteById("msg-1")).thenReturn(0);

        assertFalse(repository.deleteMessage("msg-1"));

        verifyNoInteractions(cache);
    }

    @Test
    @DisplayName("deleteMessage surfaces a MySQL outage as STORAGE_ERROR")
    void deleteMessageTranslatesMysqlFailure() {
        when(messageMapper.selectById("msg-1")).thenReturn(message("msg-1", 1000L));
        when(messageMapper.deleteById("msg-1")).thenThrow(new QueryTimeoutException("shard busy"));

        BizException ex = assertThrows(BizException.class, () -> repository.deleteMessage("msg-1"));

        assertEquals(ErrorCode.STORAGE_ERROR, ex.getErrorCode());
        verifyNoInteractions(cache);
    }

    // ---------------------------------------------------------- deleteSession

    @Test
    @DisplayName("deleteSession purges both tiers and reports the deleted row count")
    void deleteSessionPurgesBothTiers() {
        when(messageMapper.deleteBySessionId(SESSION)).thenReturn(7);

        assertEquals(7, repository.deleteSession(TENANT, DEPT, SESSION));

        InOrder ordered = inOrder(messageMapper, cache);
        ordered.verify(messageMapper).deleteBySessionId(SESSION);
        ordered.verify(cache).evict(TENANT, DEPT, SESSION);
    }

    @Test
    @DisplayName("deleteSession still evicts when the session held no row")
    void deleteSessionEvictsEvenWithoutRows() {
        when(messageMapper.deleteBySessionId(SESSION)).thenReturn(0);

        assertEquals(0, repository.deleteSession(TENANT, DEPT, SESSION));

        verify(cache).evict(TENANT, DEPT, SESSION);
    }

    @Test
    @DisplayName("deleteSession rejects a blank identity segment before deleting anything")
    void deleteSessionRejectsBlankIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> repository.deleteSession(TENANT, DEPT, "  "));
        verifyNoInteractions(messageMapper, cache);
    }

    @Test
    @DisplayName("deleteSession surfaces a MySQL outage as STORAGE_ERROR and leaves the cache alone")
    void deleteSessionTranslatesMysqlFailure() {
        when(messageMapper.deleteBySessionId(SESSION))
                .thenThrow(new RecoverableDataAccessException("connection lost"));

        BizException ex = assertThrows(BizException.class,
                () -> repository.deleteSession(TENANT, DEPT, SESSION));

        assertEquals(ErrorCode.STORAGE_ERROR, ex.getErrorCode());
        verifyNoInteractions(cache);
    }

    // ------------------------------------------------------------- smoke test

    @Test
    @DisplayName("a cold read followed by a write keeps both tiers consistent")
    void coldReadThenWriteKeepsTiersConsistent() {
        List<ChatMessageDO> stored = List.of(message("msg-1", 1000L));
        when(cache.findAll(TENANT, DEPT, SESSION)).thenReturn(Collections.emptyList());
        when(messageMapper.selectBySessionIdOrderByCreatedAtAsc(SESSION)).thenReturn(stored);
        ChatMessageDO answer = message("msg-2", 1001L);
        when(messageMapper.insert(answer)).thenReturn(1);

        List<ChatMessageDO> history = repository.findAll(TENANT, DEPT, SESSION);
        repository.append(answer);

        assertNotNull(history);
        assertEquals(1, history.size());
        verify(cache).replaceAll(TENANT, DEPT, SESSION, stored);
        verify(cache).append(answer);
        verify(cache, never()).findLast(anyString(), anyString(), anyString(), anyInt());
        verify(messageMapper, never()).selectById(eq("msg-2"));
    }
}
