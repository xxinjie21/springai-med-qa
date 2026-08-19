package com.med.qa.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.med.qa.domain.entity.AuditLogDO;
import com.med.qa.domain.enums.AuditOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link AuditLogDO} persistence object: getters/setters, the success predicate
 * (including its illegal-state guard), equality by {@code auditId}, and a safe {@code toString}.
 */
class AuditLogDOTest {

    @Test
    @DisplayName("getters mirror setters for every column")
    void gettersMirrorSetters() {
        AuditLogDO row = new AuditLogDO();
        row.setAuditId("a1");
        row.setTenantId("t1");
        row.setDeptId("d1");
        row.setOperatorId("o1");
        row.setOperatorRole("PATIENT");
        row.setAction("SESSION_VIEW");
        row.setResourceType("SESSION");
        row.setResourceId("s1");
        row.setOutcome(AuditOutcome.SUCCESS);
        row.setErrorCode(null);
        row.setLatencyMillis(7L);
        row.setMessage("ok");
        row.setCreatedAt(1_700_000_000_000L);

        assertEquals("a1", row.getAuditId());
        assertEquals("t1", row.getTenantId());
        assertEquals("d1", row.getDeptId());
        assertEquals("o1", row.getOperatorId());
        assertEquals("PATIENT", row.getOperatorRole());
        assertEquals("SESSION_VIEW", row.getAction());
        assertEquals("SESSION", row.getResourceType());
        assertEquals("s1", row.getResourceId());
        assertEquals(AuditOutcome.SUCCESS, row.getOutcome());
        assertEquals(7L, row.getLatencyMillis());
        assertEquals("ok", row.getMessage());
        assertEquals(1_700_000_000_000L, row.getCreatedAt());
    }

    @Test
    @DisplayName("isSuccessful reflects the outcome and guards an unset outcome")
    void isSuccessfulPredicate() {
        AuditLogDO row = new AuditLogDO();
        row.setOutcome(AuditOutcome.SUCCESS);
        assertTrue(row.isSuccessful());
        row.setOutcome(AuditOutcome.FAILURE);
        assertFalse(row.isSuccessful());

        AuditLogDO blank = new AuditLogDO();
        blank.setOutcome(null);
        assertThrows(IllegalStateException.class, blank::isSuccessful);
    }

    @Test
    @DisplayName("equality and hash code are defined by auditId only")
    void equalityByAuditId() {
        AuditLogDO a = new AuditLogDO();
        a.setAuditId("a1");
        a.setAction("X");
        AuditLogDO same = new AuditLogDO();
        same.setAuditId("a1");
        same.setAction("Y");
        AuditLogDO different = new AuditLogDO();
        different.setAuditId("a2");

        assertEquals(a, same);
        assertEquals(a.hashCode(), same.hashCode());
        assertNotEquals(a, different);
        assertNotEquals(a, "not-an-audit-row");
    }

    @Test
    @DisplayName("toString renders without throwing even with clinical-looking content")
    void toStringIsSafe() {
        AuditLogDO row = new AuditLogDO();
        row.setAuditId("a1");
        row.setAction("SESSION_VIEW");
        row.setMessage("patient complained of chest pain");
        assertTrue(row.toString().contains("a1"));
        assertTrue(row.toString().contains("SESSION_VIEW"));
    }
}
