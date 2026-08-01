package com.med.qa.memory.serde;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.enums.RoleType;
import com.med.qa.domain.enums.SessionStatus;
import com.med.qa.proto.ChatMessage;
import com.med.qa.proto.ChatSession;
import com.med.qa.proto.Role;
import com.med.qa.proto.SessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ProtoMessageCodec}.
 *
 * <p>The codec is the contract point between this service and the heterogeneous Python storage
 * middleware, so the tests assert three things: every spec field survives a round trip, the
 * produced bytes are identical to a hand-built Protobuf message (wire compatibility), and corrupt
 * storage payloads surface as {@link ErrorCode#STORAGE_ERROR} rather than leaking raw parser
 * exceptions.</p>
 */
class ProtoMessageCodecTest {

    private ProtoMessageCodec codec;

    @BeforeEach
    void setUp() {
        codec = new ProtoMessageCodec();
    }

    private static ChatMessageDO sampleMessage() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source", "llm");
        metadata.put("model", "qwen-med");
        return ChatMessageDO.builder()
                .messageId("018f4c2a-0000-7000-8000-000000000001")
                .sessionId("ses-7b21")
                .tenantId("t-001")
                .deptId("dept-cardio")
                .patientId("p-99231")
                .role(RoleType.ASSISTANT)
                .content("建议复查心电图并监测血压。")
                .tokenCount(36)
                .masked(true)
                .createdAt(1_718_000_000_123L)
                .metadata(metadata)
                .build();
    }

    private static ChatSessionDO sampleSession() {
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId("ses-7b21");
        session.setTenantId("t-001");
        session.setDeptId("dept-cardio");
        session.setPatientId("p-99231");
        session.setStatus(SessionStatus.CLOSED);
        session.setTitle("高血压复诊咨询");
        session.setCreatedAt(1_718_000_000_000L);
        session.setUpdatedAt(1_718_000_123_000L);
        return session;
    }

    // ---------------------------------------------------------------- toProto(ChatMessageDO)

    @Test
    void toProtoMessage_mapsEverySpecField() {
        ChatMessage proto = codec.toProto(sampleMessage());

        assertEquals("018f4c2a-0000-7000-8000-000000000001", proto.getMessageId());
        assertEquals("ses-7b21", proto.getSessionId());
        assertEquals("t-001", proto.getTenantId());
        assertEquals("dept-cardio", proto.getDeptId());
        assertEquals("p-99231", proto.getPatientId());
        assertEquals(Role.ASSISTANT, proto.getRole());
        assertEquals(2, proto.getRoleValue());
        assertEquals("建议复查心电图并监测血压。", proto.getContent());
        assertEquals(36, proto.getTokenCount());
        assertTrue(proto.getMasked());
        assertEquals(1_718_000_000_123L, proto.getCreatedAt());
        assertEquals(Map.of("source", "llm", "model", "qwen-med"), proto.getMetadataMap());
    }

    @Test
    void toProtoMessage_normalizesNullStringsToEmpty() {
        ChatMessageDO message = ChatMessageDO.builder()
                .messageId("m-1")
                .sessionId("s-1")
                .role(RoleType.SYSTEM)
                .build();

        ChatMessage proto = codec.toProto(message);

        assertEquals("", proto.getTenantId());
        assertEquals("", proto.getDeptId());
        assertEquals("", proto.getPatientId());
        assertEquals("", proto.getContent());
        assertEquals(0, proto.getTokenCount());
        assertFalse(proto.getMasked());
        assertTrue(proto.getMetadataMap().isEmpty());
    }

    @Test
    void toProtoMessage_dropsMetadataEntriesWithNullKeyOrValue() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("kept", "yes");
        metadata.put("nullValue", null);
        metadata.put(null, "nullKey");

        ChatMessageDO message = ChatMessageDO.builder()
                .messageId("m-1").sessionId("s-1").role(RoleType.PATIENT).metadata(metadata).build();

        assertEquals(Map.of("kept", "yes"), codec.toProto(message).getMetadataMap());
    }

    @Test
    void toProtoMessage_rejectsNullEntity() {
        assertThrows(IllegalArgumentException.class, () -> codec.toProto((ChatMessageDO) null));
    }

    @Test
    void toProtoMessage_rejectsNullRoleInsteadOfDefaultingToPatient() {
        ChatMessageDO message = ChatMessageDO.builder().messageId("m-1").sessionId("s-1").build();

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> codec.toProto(message));
        assertTrue(ex.getMessage().contains("role"));
    }

    // -------------------------------------------------------------------- fromProto(ChatMessage)

    @Test
    void fromProtoMessage_mapsEverySpecField() {
        ChatMessage proto = ChatMessage.newBuilder()
                .setMessageId("m-1").setSessionId("s-1").setTenantId("t-1").setDeptId("d-1")
                .setPatientId("p-1").setRole(Role.DOCTOR).setContent("请描述症状")
                .setTokenCount(12).setMasked(false).setCreatedAt(1_700_000_000_000L)
                .putMetadata("channel", "web")
                .build();

        ChatMessageDO message = codec.fromProto(proto);

        assertEquals("m-1", message.getMessageId());
        assertEquals("s-1", message.getSessionId());
        assertEquals("t-1", message.getTenantId());
        assertEquals("d-1", message.getDeptId());
        assertEquals("p-1", message.getPatientId());
        assertEquals(RoleType.DOCTOR, message.getRole());
        assertEquals("请描述症状", message.getContent());
        assertEquals(12, message.getTokenCount());
        assertFalse(message.isMasked());
        assertEquals(1_700_000_000_000L, message.getCreatedAt());
        assertEquals(Map.of("channel", "web"), message.getMetadata());
    }

    @Test
    void fromProtoMessage_rejectsUnknownRoleCodeAsStorageError() {
        ChatMessage proto = ChatMessage.newBuilder()
                .setMessageId("m-1").setSessionId("s-1").setRoleValue(99).build();

        BizException ex = assertThrows(BizException.class, () -> codec.fromProto(proto));
        assertEquals(ErrorCode.STORAGE_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("99"));
    }

    @Test
    void fromProtoMessage_rejectsBlankIdentityFieldsAsStorageError() {
        ChatMessage proto = ChatMessage.newBuilder().setSessionId("s-1").build();

        BizException ex = assertThrows(BizException.class, () -> codec.fromProto(proto));
        assertEquals(ErrorCode.STORAGE_ERROR, ex.getErrorCode());
    }

    @Test
    void fromProtoMessage_rejectsNullProto() {
        assertThrows(IllegalArgumentException.class, () -> codec.fromProto((ChatMessage) null));
    }

    // ------------------------------------------------------- encodeMessage / decodeMessage

    @Test
    void messageRoundTrip_preservesAllSpecFields() {
        ChatMessageDO source = sampleMessage();

        ChatMessageDO restored = codec.decodeMessage(codec.encodeMessage(source));

        assertEquals(source.getMessageId(), restored.getMessageId());
        assertEquals(source.getSessionId(), restored.getSessionId());
        assertEquals(source.getTenantId(), restored.getTenantId());
        assertEquals(source.getDeptId(), restored.getDeptId());
        assertEquals(source.getPatientId(), restored.getPatientId());
        assertEquals(source.getRole(), restored.getRole());
        assertEquals(source.getContent(), restored.getContent());
        assertEquals(source.getTokenCount(), restored.getTokenCount());
        assertEquals(source.isMasked(), restored.isMasked());
        assertEquals(source.getCreatedAt(), restored.getCreatedAt());
        assertEquals(source.getMetadata(), restored.getMetadata());
    }

    @Test
    void encodeMessage_producesBytesIdenticalToHandBuiltProto() {
        ChatMessageDO source = ChatMessageDO.builder()
                .messageId("m-1").sessionId("s-1").tenantId("t-1").deptId("d-1").patientId("p-1")
                .role(RoleType.PATIENT).content("头痛三天").tokenCount(8).masked(true)
                .createdAt(1_718_000_000_123L)
                .build();

        byte[] expected = ChatMessage.newBuilder()
                .setMessageId("m-1").setSessionId("s-1").setTenantId("t-1").setDeptId("d-1")
                .setPatientId("p-1").setRole(Role.PATIENT).setContent("头痛三天").setTokenCount(8)
                .setMasked(true).setCreatedAt(1_718_000_000_123L)
                .build()
                .toByteArray();

        assertArrayEquals(expected, codec.encodeMessage(source));
    }

    @Test
    void encodeMessage_rejectsNullEntity() {
        assertThrows(IllegalArgumentException.class, () -> codec.encodeMessage(null));
    }

    @Test
    void decodeMessage_rejectsMalformedPayloadAsStorageError() {
        byte[] garbage = {0, 1, 2, 3, 4, (byte) 0xFF, (byte) 0xFE};

        BizException ex = assertThrows(BizException.class, () -> codec.decodeMessage(garbage));
        assertEquals(ErrorCode.STORAGE_ERROR, ex.getErrorCode());
    }

    @Test
    void decodeMessage_rejectsEmptyPayloadAsStorageError() {
        BizException ex = assertThrows(BizException.class, () -> codec.decodeMessage(new byte[0]));
        assertEquals(ErrorCode.STORAGE_ERROR, ex.getErrorCode());
    }

    @Test
    void decodeMessage_rejectsNullPayload() {
        assertThrows(IllegalArgumentException.class, () -> codec.decodeMessage(null));
    }

    // ---------------------------------------------------------------- toProto(ChatSessionDO)

    @Test
    void toProtoSession_mapsEveryIdentityField() {
        ChatSession proto = codec.toProto(sampleSession());

        assertEquals("ses-7b21", proto.getSessionId());
        assertEquals("t-001", proto.getTenantId());
        assertEquals("dept-cardio", proto.getDeptId());
        assertEquals("p-99231", proto.getPatientId());
        assertEquals(SessionState.CLOSED, proto.getStatus());
        assertEquals("高血压复诊咨询", proto.getTitle());
        assertEquals(1_718_000_000_000L, proto.getCreatedAt());
        assertEquals(1_718_000_123_000L, proto.getUpdatedAt());
    }

    @Test
    void toProtoSession_treatsNullStatusAsActiveAndNullTitleAsEmpty() {
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId("s-1");
        session.setStatus(null);

        ChatSession proto = codec.toProto(session);

        assertEquals(SessionState.ACTIVE, proto.getStatus());
        assertEquals("", proto.getTitle());
        assertEquals("", proto.getTenantId());
    }

    @Test
    void toProtoSession_rejectsNullEntity() {
        assertThrows(IllegalArgumentException.class, () -> codec.toProto((ChatSessionDO) null));
    }

    // -------------------------------------------------------------------- fromProto(ChatSession)

    @Test
    void fromProtoSession_mapsEveryIdentityField() {
        ChatSession proto = ChatSession.newBuilder()
                .setSessionId("s-1").setTenantId("t-1").setDeptId("d-1").setPatientId("p-1")
                .setStatus(SessionState.ARCHIVED).setTitle("既往病史").setCreatedAt(1L).setUpdatedAt(2L)
                .build();

        ChatSessionDO session = codec.fromProto(proto);

        assertEquals("s-1", session.getSessionId());
        assertEquals("t-1", session.getTenantId());
        assertEquals("d-1", session.getDeptId());
        assertEquals("p-1", session.getPatientId());
        assertEquals(SessionStatus.ARCHIVED, session.getStatus());
        assertEquals("既往病史", session.getTitle());
        assertEquals(1L, session.getCreatedAt());
        assertEquals(2L, session.getUpdatedAt());
    }

    @Test
    void fromProtoSession_rejectsUnknownStatusCodeAsStorageError() {
        ChatSession proto = ChatSession.newBuilder().setSessionId("s-1").setStatusValue(42).build();

        BizException ex = assertThrows(BizException.class, () -> codec.fromProto(proto));
        assertEquals(ErrorCode.STORAGE_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("42"));
    }

    @Test
    void fromProtoSession_rejectsBlankSessionIdAsStorageError() {
        BizException ex =
                assertThrows(BizException.class, () -> codec.fromProto(ChatSession.getDefaultInstance()));
        assertEquals(ErrorCode.STORAGE_ERROR, ex.getErrorCode());
    }

    @Test
    void fromProtoSession_rejectsNullProto() {
        assertThrows(IllegalArgumentException.class, () -> codec.fromProto((ChatSession) null));
    }

    // ------------------------------------------------------- encodeSession / decodeSession

    @Test
    void sessionRoundTrip_preservesAllFieldsAndKeepsRedisKeyDerivable() {
        ChatSessionDO source = sampleSession();

        ChatSessionDO restored = codec.decodeSession(codec.encodeSession(source));

        assertEquals(source.getSessionId(), restored.getSessionId());
        assertEquals(source.getTenantId(), restored.getTenantId());
        assertEquals(source.getDeptId(), restored.getDeptId());
        assertEquals(source.getPatientId(), restored.getPatientId());
        assertEquals(source.getStatus(), restored.getStatus());
        assertEquals(source.getTitle(), restored.getTitle());
        assertEquals(source.getCreatedAt(), restored.getCreatedAt());
        assertEquals(source.getUpdatedAt(), restored.getUpdatedAt());
        assertEquals("med:chat:t-001:dept-cardio:ses-7b21", restored.redisKey());
    }

    @Test
    void encodeSession_producesBytesIdenticalToHandBuiltProto() {
        byte[] expected = ChatSession.newBuilder()
                .setSessionId("ses-7b21").setTenantId("t-001").setDeptId("dept-cardio")
                .setPatientId("p-99231").setStatus(SessionState.CLOSED).setTitle("高血压复诊咨询")
                .setCreatedAt(1_718_000_000_000L).setUpdatedAt(1_718_000_123_000L)
                .build()
                .toByteArray();

        assertArrayEquals(expected, codec.encodeSession(sampleSession()));
    }

    @Test
    void encodeSession_rejectsNullEntity() {
        assertThrows(IllegalArgumentException.class, () -> codec.encodeSession(null));
    }

    @Test
    void decodeSession_rejectsMalformedPayloadAsStorageError() {
        byte[] garbage = {0, 1, 2, 3, 4, (byte) 0xFF, (byte) 0xFE};

        BizException ex = assertThrows(BizException.class, () -> codec.decodeSession(garbage));
        assertEquals(ErrorCode.STORAGE_ERROR, ex.getErrorCode());
    }

    @Test
    void decodeSession_rejectsNullPayload() {
        assertThrows(IllegalArgumentException.class, () -> codec.decodeSession(null));
    }

    // ------------------------------------------------------------------ cross-system contract

    @Test
    void decodedMessage_isReadableFromPayloadWrittenByAnExternalProducer() {
        // Simulates a record written by the Python middleware: raw protobuf, no Java involved.
        byte[] foreignPayload = ChatMessage.newBuilder()
                .setMessageId("018f4c2a-0000-7000-8000-0000000000ff")
                .setSessionId("ses-external")
                .setTenantId("t-777")
                .setDeptId("dept-neuro")
                .setPatientId("p-12345")
                .setRoleValue(RoleType.SYSTEM.getCode())
                .setContent("system prompt")
                .setCreatedAt(1_719_000_000_000L)
                .putMetadata("producer", "python")
                .build()
                .toByteArray();

        ChatMessageDO message = codec.decodeMessage(foreignPayload);

        assertNotNull(message);
        assertEquals(RoleType.SYSTEM, message.getRole());
        assertEquals("ses-external", message.getSessionId());
        assertEquals("python", message.getMetadata().get("producer"));
    }
}
