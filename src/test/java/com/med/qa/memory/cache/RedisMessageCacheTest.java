package com.med.qa.memory.cache;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.enums.RoleType;
import com.med.qa.memory.serde.ProtoMessageCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the Redis message cache. Redis itself is mocked, so the whole suite runs without
 * any middleware — only the key schema, the protobuf payload contract, the TTL handling and the
 * failure semantics are under test.
 */
@ExtendWith(MockitoExtension.class)
class RedisMessageCacheTest {

    private static final String TENANT = "hosp-01";
    private static final String DEPT = "cardiology";
    private static final String SESSION = "sess-9f2c";
    private static final String KEY = "med:chat:hosp-01:cardiology:sess-9f2c";

    @Mock
    private RedisTemplate<String, byte[]> redisTemplate;

    @Mock
    private ListOperations<String, byte[]> listOps;

    private final ProtoMessageCodec codec = new ProtoMessageCodec();

    private MedCacheProperties properties;

    private RedisMessageCache cache;

    @BeforeEach
    void setUp() {
        properties = new MedCacheProperties();
        cache = new RedisMessageCache(redisTemplate, codec, properties);
        lenient().when(redisTemplate.opsForList()).thenReturn(listOps);
    }

    private static ChatMessageDO message(String id, RoleType role, String content) {
        return ChatMessageDO.builder()
                .messageId(id)
                .sessionId(SESSION)
                .tenantId(TENANT)
                .deptId(DEPT)
                .patientId("pat-77")
                .role(role)
                .content(content)
                .tokenCount(12)
                .masked(false)
                .createdAt(1_754_000_000_000L)
                .metadata(Map.of("channel", "app"))
                .build();
    }

    // ---------------------------------------------------------------- cacheKey

    @Test
    @DisplayName("positive: the key follows med:chat:{tenant}:{dept}:{session}")
    void buildsSpecCompliantKey() {
        assertEquals(KEY, cache.cacheKey(TENANT, DEPT, SESSION));
    }

    @Test
    @DisplayName("positive: a session entity yields the same key as its own redisKey()")
    void sessionEntityYieldsSameKey() {
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId(SESSION);
        session.setTenantId(TENANT);
        session.setDeptId(DEPT);

        assertEquals(session.redisKey(), cache.cacheKey(session));
    }

    @Test
    @DisplayName("exception: blank identity segments would collide across tenants")
    void rejectsBlankSegments() {
        assertThrows(IllegalArgumentException.class, () -> cache.cacheKey("  ", DEPT, SESSION));
        assertThrows(IllegalArgumentException.class, () -> cache.cacheKey(TENANT, null, SESSION));
        assertThrows(IllegalArgumentException.class, () -> cache.cacheKey(TENANT, DEPT, ""));
    }

    @Test
    @DisplayName("exception: a null session entity is a programming error")
    void rejectsNullSessionEntity() {
        assertThrows(IllegalArgumentException.class, () -> cache.cacheKey((ChatSessionDO) null));
    }

    // ---------------------------------------------------------------- append

    @Test
    @DisplayName("positive: append pushes the protobuf payload, trims the window and refreshes ttl")
    void appendPushesEncodedPayload() {
        ChatMessageDO msg = message("m-1", RoleType.PATIENT, "胸口有点闷");

        cache.append(msg);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(listOps).rightPush(eq(KEY), payload.capture());
        assertArrayEquals(codec.encodeMessage(msg), payload.getValue());
        verify(listOps).trim(KEY, -200L, -1L);
        verify(redisTemplate).expire(KEY, Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("positive: the cached payload decodes back into an equivalent message")
    void appendedPayloadIsRoundTrippable() {
        ChatMessageDO msg = message("m-2", RoleType.ASSISTANT, "建议尽快就诊");

        cache.append(msg);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(listOps).rightPush(eq(KEY), payload.capture());
        ChatMessageDO decoded = codec.decodeMessage(payload.getValue());
        assertEquals("m-2", decoded.getMessageId());
        assertEquals(RoleType.ASSISTANT, decoded.getRole());
        assertEquals("建议尽快就诊", decoded.getContent());
    }

    @Test
    @DisplayName("boundary: an unbounded window skips the LTRIM call")
    void appendSkipsTrimWhenWindowUnbounded() {
        properties.setMaxMessages(0);

        cache.append(message("m-3", RoleType.DOCTOR, "已开单"));

        verify(listOps, never()).trim(anyString(), anyLong(), anyLong());
        verify(redisTemplate).expire(KEY, Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("exception: a null message is rejected before touching Redis")
    void appendRejectsNullMessage() {
        assertThrows(IllegalArgumentException.class, () -> cache.append(null));
        verify(listOps, never()).rightPush(anyString(), any());
    }

    @Test
    @DisplayName("exception: a message without a role must never be silently defaulted")
    void appendRejectsRolelessMessage() {
        ChatMessageDO roleless = ChatMessageDO.builder()
                .messageId("m-4")
                .sessionId(SESSION)
                .tenantId(TENANT)
                .deptId(DEPT)
                .build();

        assertThrows(IllegalArgumentException.class, () -> cache.append(roleless));
        verify(listOps, never()).rightPush(anyString(), any());
    }

    @Test
    @DisplayName("exception: a Redis outage on write surfaces as a storage error")
    void appendWrapsRedisFailure() {
        when(listOps.rightPush(eq(KEY), any()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        BizException error = assertThrows(BizException.class,
                () -> cache.append(message("m-5", RoleType.PATIENT, "头晕")));

        assertEquals(ErrorCode.STORAGE_ERROR, error.getErrorCode());
    }

    // ---------------------------------------------------------------- replaceAll

    @Test
    @DisplayName("positive: replaceAll drops the key then re-pushes the whole window in order")
    void replaceAllRewritesWindow() {
        List<ChatMessageDO> messages = List.of(
                message("m-6", RoleType.PATIENT, "第一条"),
                message("m-7", RoleType.ASSISTANT, "第二条"));

        cache.replaceAll(TENANT, DEPT, SESSION, messages);

        verify(redisTemplate).delete(KEY);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<byte[]>> payloads = ArgumentCaptor.forClass(List.class);
        verify(listOps).rightPushAll(eq(KEY), payloads.capture());
        List<byte[]> captured = payloads.getValue();
        assertEquals(2, captured.size());
        assertArrayEquals(codec.encodeMessage(messages.get(0)), captured.get(0));
        assertArrayEquals(codec.encodeMessage(messages.get(1)), captured.get(1));
        verify(redisTemplate).expire(KEY, Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("boundary: an empty window only drops the key, no empty list is pushed")
    void replaceAllWithEmptyListOnlyDeletes() {
        cache.replaceAll(TENANT, DEPT, SESSION, Collections.emptyList());

        verify(redisTemplate).delete(KEY);
        verify(listOps, never()).rightPushAll(anyString(), any(List.class));
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("exception: a null list or a null element is rejected before any Redis call")
    void replaceAllRejectsNullInput() {
        List<ChatMessageDO> withNull = new ArrayList<>();
        withNull.add(message("m-8", RoleType.PATIENT, "有效"));
        withNull.add(null);

        assertThrows(IllegalArgumentException.class,
                () -> cache.replaceAll(TENANT, DEPT, SESSION, null));
        assertThrows(IllegalArgumentException.class,
                () -> cache.replaceAll(TENANT, DEPT, SESSION, withNull));
        verify(listOps, never()).rightPushAll(anyString(), any(List.class));
    }

    @Test
    @DisplayName("exception: a Redis outage during a rewrite surfaces as a storage error")
    void replaceAllWrapsRedisFailure() {
        when(redisTemplate.delete(KEY)).thenThrow(new QueryTimeoutException("timed out"));

        BizException error = assertThrows(BizException.class, () -> cache.replaceAll(
                TENANT, DEPT, SESSION, List.of(message("m-9", RoleType.PATIENT, "超时"))));

        assertEquals(ErrorCode.STORAGE_ERROR, error.getErrorCode());
    }

    // ---------------------------------------------------------------- findAll / findLast

    @Test
    @DisplayName("positive: findAll decodes the full window in chronological order")
    void findAllDecodesWholeWindow() {
        ChatMessageDO first = message("m-10", RoleType.PATIENT, "咳嗽三天");
        ChatMessageDO second = message("m-11", RoleType.ASSISTANT, "是否发热？");
        when(listOps.range(KEY, 0, -1))
                .thenReturn(Arrays.asList(codec.encodeMessage(first), codec.encodeMessage(second)));

        List<ChatMessageDO> found = cache.findAll(TENANT, DEPT, SESSION);

        assertEquals(2, found.size());
        assertEquals("m-10", found.get(0).getMessageId());
        assertEquals("是否发热？", found.get(1).getContent());
    }

    @Test
    @DisplayName("boundary: a cold cache yields an empty list, not null")
    void findAllReturnsEmptyOnMiss() {
        when(listOps.range(KEY, 0, -1)).thenReturn(null);

        assertTrue(cache.findAll(TENANT, DEPT, SESSION).isEmpty());
    }

    @Test
    @DisplayName("boundary: a Redis outage degrades to a cache miss, never fails the consultation")
    void findAllDegradesWhenRedisIsDown() {
        when(listOps.range(KEY, 0, -1)).thenThrow(new RedisConnectionFailureException("down"));

        assertTrue(cache.findAll(TENANT, DEPT, SESSION).isEmpty());
    }

    @Test
    @DisplayName("boundary: one corrupt payload evicts the whole untrustworthy window")
    void findAllEvictsCorruptWindow() {
        ChatMessageDO valid = message("m-12", RoleType.PATIENT, "正常");
        byte[] corrupt = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        when(listOps.range(KEY, 0, -1))
                .thenReturn(Arrays.asList(codec.encodeMessage(valid), corrupt));

        List<ChatMessageDO> found = cache.findAll(TENANT, DEPT, SESSION);

        assertTrue(found.isEmpty());
        verify(redisTemplate).delete(KEY);
    }

    @Test
    @DisplayName("boundary: a failing eviction of a corrupt window is swallowed on the read path")
    void findAllSwallowsFailingEvictionOfCorruptWindow() {
        when(listOps.range(KEY, 0, -1))
                .thenReturn(List.of(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}));
        when(redisTemplate.delete(KEY)).thenThrow(new RedisConnectionFailureException("down"));

        assertTrue(cache.findAll(TENANT, DEPT, SESSION).isEmpty());
    }

    @Test
    @DisplayName("positive: findLast reads only the tail slice of the list")
    void findLastReadsTailSlice() {
        ChatMessageDO tail = message("m-13", RoleType.DOCTOR, "复诊");
        when(listOps.range(KEY, -3, -1)).thenReturn(List.of(codec.encodeMessage(tail)));

        List<ChatMessageDO> found = cache.findLast(TENANT, DEPT, SESSION, 3);

        assertEquals(1, found.size());
        assertEquals("m-13", found.get(0).getMessageId());
    }

    @Test
    @DisplayName("exception: a non positive limit is rejected")
    void findLastRejectsNonPositiveLimit() {
        assertThrows(IllegalArgumentException.class, () -> cache.findLast(TENANT, DEPT, SESSION, 0));
        assertThrows(IllegalArgumentException.class, () -> cache.findLast(TENANT, DEPT, SESSION, -2));
        verify(listOps, never()).range(anyString(), anyLong(), anyLong());
    }

    // ---------------------------------------------------------------- size / exists / ttl

    @Test
    @DisplayName("positive: size reports the cached message count")
    void sizeReportsListLength() {
        when(listOps.size(KEY)).thenReturn(7L);

        assertEquals(7L, cache.size(TENANT, DEPT, SESSION));
    }

    @Test
    @DisplayName("boundary: size reports zero on a miss and on a Redis outage")
    void sizeReportsZeroOnMissAndFailure() {
        when(listOps.size(KEY)).thenReturn(null).thenThrow(new RedisConnectionFailureException("down"));

        assertEquals(0L, cache.size(TENANT, DEPT, SESSION));
        assertEquals(0L, cache.size(TENANT, DEPT, SESSION));
    }

    @Test
    @DisplayName("positive: exists mirrors the key presence")
    void existsMirrorsKeyPresence() {
        when(redisTemplate.hasKey(KEY)).thenReturn(true).thenReturn(false);

        assertTrue(cache.exists(TENANT, DEPT, SESSION));
        assertFalse(cache.exists(TENANT, DEPT, SESSION));
    }

    @Test
    @DisplayName("boundary: exists reports absent when Redis cannot answer")
    void existsReportsAbsentOnFailure() {
        when(redisTemplate.hasKey(KEY)).thenThrow(new QueryTimeoutException("timed out"));

        assertFalse(cache.exists(TENANT, DEPT, SESSION));
    }

    @Test
    @DisplayName("positive: evict deletes the key and reports the outcome")
    void evictDeletesKey() {
        when(redisTemplate.delete(KEY)).thenReturn(true);

        assertTrue(cache.evict(TENANT, DEPT, SESSION));
        verify(redisTemplate).delete(KEY);
    }

    @Test
    @DisplayName("exception: a failed eviction must not be swallowed, it would serve stale data")
    void evictWrapsRedisFailure() {
        when(redisTemplate.delete(KEY)).thenThrow(new RedisConnectionFailureException("down"));

        BizException error = assertThrows(BizException.class, () -> cache.evict(TENANT, DEPT, SESSION));

        assertEquals(ErrorCode.STORAGE_ERROR, error.getErrorCode());
        assertSame(RedisConnectionFailureException.class, error.getCause().getClass());
    }

    @Test
    @DisplayName("positive: the remaining ttl is exposed as a Duration")
    void remainingTtlIsExposed() {
        when(redisTemplate.getExpire(KEY, TimeUnit.SECONDS)).thenReturn(900L);

        assertEquals(Optional.of(Duration.ofMinutes(15)), cache.remainingTtl(TENANT, DEPT, SESSION));
    }

    @Test
    @DisplayName("boundary: an absent key, a persistent key and an outage all yield an empty ttl")
    void remainingTtlEmptyWhenUnavailable() {
        when(redisTemplate.getExpire(KEY, TimeUnit.SECONDS))
                .thenReturn(-2L)
                .thenReturn(-1L)
                .thenReturn(null)
                .thenThrow(new RedisConnectionFailureException("down"));

        assertTrue(cache.remainingTtl(TENANT, DEPT, SESSION).isEmpty());
        assertTrue(cache.remainingTtl(TENANT, DEPT, SESSION).isEmpty());
        assertTrue(cache.remainingTtl(TENANT, DEPT, SESSION).isEmpty());
        assertTrue(cache.remainingTtl(TENANT, DEPT, SESSION).isEmpty());
    }
}
