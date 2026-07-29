package com.med.qa.proto;

import org.junit.jupiter.api.Test;

import java.util.Map;

import com.google.protobuf.InvalidProtocolBufferException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip tests for the generated Protobuf classes in {@code com.med.qa.proto}.
 *
 * <p>These tests pin the unified session storage schema to the spec in ROADMAP section 4:
 * every field written by {@code ChatMessage} / {@code ChatSession} survives a
 * serialize -&gt; deserialize cycle unchanged, and malformed input is rejected.</p>
 */
class MedSessionProtoRoundTripTest {

    @Test
    void chatMessage_roundTrip_preservesAllSpecFields() throws InvalidProtocolBufferException {
        ChatMessage source = ChatMessage.newBuilder()
                .setMessageId("msg-018f-4c2a")
                .setSessionId("ses-7b21")
                .setTenantId("t-001")
                .setDeptId("dept-cardio")
                .setPatientId("p-99231")
                .setRole(Role.ASSISTANT)
                .setContent("建议复查心电图并监测血压。")
                .setTokenCount(36)
                .setMasked(true)
                .setCreatedAt(1_718_000_000_123L)
                .putMetadata("source", "llm")
                .putMetadata("model", "qwen-med")
                .build();

        byte[] bytes = source.toByteArray();
        ChatMessage restored = ChatMessage.parseFrom(bytes);

        assertEquals(source.getMessageId(), restored.getMessageId());
        assertEquals(source.getSessionId(), restored.getSessionId());
        assertEquals(source.getTenantId(), restored.getTenantId());
        assertEquals(source.getDeptId(), restored.getDeptId());
        assertEquals(source.getPatientId(), restored.getPatientId());
        assertEquals(Role.ASSISTANT, restored.getRole());
        assertEquals(source.getContent(), restored.getContent());
        assertEquals(36, restored.getTokenCount());
        assertTrue(restored.getMasked());
        assertEquals(1_718_000_000_123L, restored.getCreatedAt());
        assertEquals(Map.of("source", "llm", "model", "qwen-med"), restored.getMetadataMap());
    }

    @Test
    void chatMessage_roundTrip_handlesEmptyAndBoundaryFields() throws InvalidProtocolBufferException {
        // Boundary / edge case: empty strings, zero tokens, default enum, empty metadata map.
        ChatMessage source = ChatMessage.newBuilder()
                .setMessageId("")
                .setSessionId("")
                .setTenantId("")
                .setDeptId("")
                .setPatientId("")
                .setRole(Role.PATIENT)
                .setContent("")
                .setTokenCount(0)
                .setMasked(false)
                .setCreatedAt(0L)
                .build();

        ChatMessage restored = ChatMessage.parseFrom(source.toByteArray());

        assertEquals("", restored.getContent());
        assertEquals(0, restored.getTokenCount());
        assertEquals(Role.PATIENT, restored.getRole());
        assertFalse(restored.getMasked());
        assertEquals(0L, restored.getCreatedAt());
        assertTrue(restored.getMetadataMap().isEmpty());
    }

    @Test
    void chatMessage_parseFrom_rejectsMalformedBytes() {
        byte[] garbage = {0, 1, 2, 3, 4, (byte) 0xFF, (byte) 0xFE};
        assertThrows(com.google.protobuf.InvalidProtocolBufferException.class,
                () -> ChatMessage.parseFrom(garbage));
    }

    @Test
    void chatSession_roundTrip_preservesAllFields() throws InvalidProtocolBufferException {
        ChatSession source = ChatSession.newBuilder()
                .setSessionId("ses-7b21")
                .setTenantId("t-001")
                .setDeptId("dept-cardio")
                .setPatientId("p-99231")
                .setStatus(SessionState.ACTIVE)
                .setTitle("高血压复诊咨询")
                .setCreatedAt(1_718_000_000_000L)
                .setUpdatedAt(1_718_000_123_000L)
                .putMetadata("channel", "web")
                .build();

        ChatSession restored = ChatSession.parseFrom(source.toByteArray());

        assertEquals(source.getSessionId(), restored.getSessionId());
        assertEquals(source.getTenantId(), restored.getTenantId());
        assertEquals(source.getDeptId(), restored.getDeptId());
        assertEquals(source.getPatientId(), restored.getPatientId());
        assertEquals(SessionState.ACTIVE, restored.getStatus());
        assertEquals("高血压复诊咨询", restored.getTitle());
        assertEquals(1_718_000_000_000L, restored.getCreatedAt());
        assertEquals(1_718_000_123_000L, restored.getUpdatedAt());
        assertEquals(Map.of("channel", "web"), restored.getMetadataMap());
    }

    @Test
    void chatSession_roundTrip_handlesArchivedStatusAndEmptyTitle() throws InvalidProtocolBufferException {
        ChatSession source = ChatSession.newBuilder()
                .setSessionId("ses-x")
                .setTenantId("t")
                .setDeptId("d")
                .setPatientId("p")
                .setStatus(SessionState.ARCHIVED)
                .setTitle("")
                .setCreatedAt(0L)
                .setUpdatedAt(0L)
                .build();

        ChatSession restored = ChatSession.parseFrom(source.toByteArray());

        assertEquals(SessionState.ARCHIVED, restored.getStatus());
        assertEquals("", restored.getTitle());
        assertTrue(restored.getMetadataMap().isEmpty());
    }

    @Test
    void generatedSchema_byteEqualityIsStable() {
        ChatMessage a = ChatMessage.newBuilder()
                .setMessageId("m1").setSessionId("s1").setRole(Role.DOCTOR).setContent("x").build();
        ChatMessage b = ChatMessage.newBuilder()
                .setMessageId("m1").setSessionId("s1").setRole(Role.DOCTOR).setContent("x").build();
        assertArrayEquals(a.toByteArray(), b.toByteArray());
    }
}
