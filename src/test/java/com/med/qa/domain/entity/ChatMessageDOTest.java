package com.med.qa.domain.entity;

import com.med.qa.domain.enums.RoleType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMessageDOTest {

    private static ChatMessageDO sample() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("source", "sse");
        return ChatMessageDO.builder()
                .messageId("018f-uuid7-0001")
                .sessionId("sess-001")
                .tenantId("t1")
                .deptId("cardio")
                .patientId("p-100")
                .role(RoleType.PATIENT)
                .content("我最近胸口疼，手机号13800001234")
                .tokenCount(18)
                .masked(false)
                .createdAt(1753700000000L)
                .metadata(meta)
                .build();
    }

    @Test
    @DisplayName("builder: all spec fields are populated")
    void builder_populatesAllFields() {
        ChatMessageDO msg = sample();
        assertEquals("018f-uuid7-0001", msg.getMessageId());
        assertEquals("sess-001", msg.getSessionId());
        assertEquals("t1", msg.getTenantId());
        assertEquals("cardio", msg.getDeptId());
        assertEquals("p-100", msg.getPatientId());
        assertEquals(RoleType.PATIENT, msg.getRole());
        assertEquals(18, msg.getTokenCount());
        assertFalse(msg.isMasked());
        assertEquals(1753700000000L, msg.getCreatedAt());
        assertEquals("sse", msg.getMetadata().get("source"));
    }

    @Test
    @DisplayName("builder: blank messageId or sessionId is rejected")
    void builder_rejectsBlankRequiredFields() {
        assertThrows(IllegalStateException.class,
                () -> ChatMessageDO.builder().sessionId("s1").build());
        assertThrows(IllegalStateException.class,
                () -> ChatMessageDO.builder().messageId("m1").sessionId(" ").build());
    }

    @Test
    @DisplayName("setMetadata: null is normalized to empty map")
    void setMetadata_nullBecomesEmptyMap() {
        ChatMessageDO msg = sample();
        msg.setMetadata(null);
        assertNotNull(msg.getMetadata());
        assertTrue(msg.getMetadata().isEmpty());
    }

    @Test
    @DisplayName("equals/hashCode: identity is messageId only")
    void equalsHashCode_byMessageId() {
        ChatMessageDO a = sample();
        ChatMessageDO b = sample();
        b.setContent("different content");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        b.setMessageId("other-id");
        assertNotEquals(a, b);
        assertNotEquals(a, new Object());
    }

    @Test
    @DisplayName("toString: never leaks raw medical content")
    void toString_doesNotLeakContent() {
        ChatMessageDO msg = sample();
        String printed = msg.toString();
        assertFalse(printed.contains("13800001234"));
        assertFalse(printed.contains("胸口疼"));
        assertTrue(printed.contains("contentLength=" + msg.getContent().length()));
    }

    @Test
    @DisplayName("toString: null content is printed as length 0")
    void toString_nullContentSafe() {
        ChatMessageDO msg = sample();
        msg.setContent(null);
        assertTrue(msg.toString().contains("contentLength=0"));
    }
}
