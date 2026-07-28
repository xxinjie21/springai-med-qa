package com.med.qa.domain.entity;

import com.med.qa.domain.enums.SessionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatSessionDOTest {

    private static ChatSessionDO sample() {
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId("sess-001");
        session.setTenantId("t1");
        session.setDeptId("cardio");
        session.setPatientId("p-100");
        session.setTitle("胸口疼咨询");
        session.setStatus(SessionStatus.ACTIVE);
        session.setCreatedAt(1753700000000L);
        session.setUpdatedAt(1753700100000L);
        return session;
    }

    @Test
    @DisplayName("getters/setters: all fields round-trip")
    void gettersSetters_roundTrip() {
        ChatSessionDO session = sample();
        assertEquals("sess-001", session.getSessionId());
        assertEquals("t1", session.getTenantId());
        assertEquals("cardio", session.getDeptId());
        assertEquals("p-100", session.getPatientId());
        assertEquals("胸口疼咨询", session.getTitle());
        assertEquals(SessionStatus.ACTIVE, session.getStatus());
        assertEquals(1753700000000L, session.getCreatedAt());
        assertEquals(1753700100000L, session.getUpdatedAt());
    }

    @Test
    @DisplayName("default status: a new session is ACTIVE")
    void defaultStatus_isActive() {
        assertEquals(SessionStatus.ACTIVE, new ChatSessionDO().getStatus());
    }

    @Test
    @DisplayName("redisKey: builds spec-compliant med:chat:{tenant}:{dept}:{session}")
    void redisKey_followsSpec() {
        assertEquals("med:chat:t1:cardio:sess-001", sample().redisKey());
    }

    @Test
    @DisplayName("redisKey: missing identity fields throw IllegalStateException")
    void redisKey_missingFields_throws() {
        ChatSessionDO noTenant = sample();
        noTenant.setTenantId(null);
        assertThrows(IllegalStateException.class, noTenant::redisKey);

        ChatSessionDO blankDept = sample();
        blankDept.setDeptId(" ");
        assertThrows(IllegalStateException.class, blankDept::redisKey);

        ChatSessionDO noSession = sample();
        noSession.setSessionId(null);
        assertThrows(IllegalStateException.class, noSession::redisKey);
    }

    @Test
    @DisplayName("equals/hashCode: identity is sessionId only")
    void equalsHashCode_bySessionId() {
        ChatSessionDO a = sample();
        ChatSessionDO b = sample();
        b.setTitle("其他标题");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        b.setSessionId("other");
        assertNotEquals(a, b);
        assertNotEquals(a, new Object());
    }

    @Test
    @DisplayName("toString: contains identity fields for troubleshooting")
    void toString_containsIdentity() {
        String printed = sample().toString();
        assertTrue(printed.contains("sess-001"));
        assertTrue(printed.contains("cardio"));
    }
}
