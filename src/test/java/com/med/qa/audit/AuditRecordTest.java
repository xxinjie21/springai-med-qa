package com.med.qa.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.med.qa.domain.enums.AuditOutcome;
import com.med.qa.security.MedPrincipal;
import com.med.qa.security.MedRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the immutable {@link AuditRecord} value object: principal mapping, anonymous
 * fallback, normalization/trimming, success/failure builders and the input guards.
 */
class AuditRecordTest {

    private static final MedPrincipal PATIENT = new MedPrincipal("t1", "d1", MedRole.PATIENT, "p1");

    private static final MedPrincipal STAFF = new MedPrincipal("t1", "d1", MedRole.STAFF, null);

    @Test
    @DisplayName("principal maps a patient onto its patient id and a staff key onto the staff marker")
    void principalMapping() {
        AuditRecord patientRecord = AuditRecord.builder("ACT")
                .principal(PATIENT)
                .resource("SESSION", "s1")
                .success("ok")
                .build();
        assertEquals("t1", patientRecord.tenantId());
        assertEquals("d1", patientRecord.deptId());
        assertEquals("p1", patientRecord.operatorId());
        assertEquals(MedRole.PATIENT, patientRecord.operatorRole());

        AuditRecord staffRecord = AuditRecord.builder("ACT").principal(STAFF).build();
        assertEquals(AuditRecord.STAFF_OPERATOR, staffRecord.operatorId());
        assertEquals(MedRole.STAFF, staffRecord.operatorRole());
    }

    @Test
    @DisplayName("a call without a principal is recorded as anonymous")
    void anonymousFallback() {
        AuditRecord anon = AuditRecord.builder("ACT").build();
        assertEquals(AuditRecord.ANONYMOUS, anon.tenantId());
        assertEquals(AuditRecord.ANONYMOUS, anon.deptId());
        assertEquals(AuditRecord.ANONYMOUS, anon.operatorId());
        assertNull(anon.operatorRole());
        assertTrue(anon.isAnonymous());
        // No explicit outcome was set, so the record falls back to the default SUCCESS.
        assertTrue(anon.isSuccessful());
    }

    @Test
    @DisplayName("blank action is rejected by both the builder and the constructor")
    void blankActionRejected() {
        assertThrows(IllegalArgumentException.class, () -> AuditRecord.builder("  ").build());
        assertThrows(IllegalArgumentException.class, () -> AuditRecord.builder(null));
    }

    @Test
    @DisplayName("null outcome and negative latency are rejected")
    void guards() {
        assertThrows(IllegalArgumentException.class,
                () -> new AuditRecord("t", "d", "o", null, "ACT", null, null, null, null, 1L, null));
        assertThrows(IllegalArgumentException.class,
                () -> AuditRecord.builder("ACT").latencyMillis(-1).build());
    }

    @Test
    @DisplayName("action is trimmed and blank optional text collapses to null")
    void trimmingAndNullCollapse() {
        AuditRecord record = AuditRecord.builder("  ACT  ")
                .resource("", "x")
                .success("")
                .build();
        assertEquals("ACT", record.action());
        assertNull(record.resourceType());
        assertNull(record.resourceId());
        assertNull(record.message());
    }

    @Test
    @DisplayName("failure builder sets outcome, error code and message")
    void failureBuilder() {
        AuditRecord record = AuditRecord.builder("ACT").failure(40400, "boom").build();
        assertEquals(AuditOutcome.FAILURE, record.outcome());
        assertEquals(40400, record.errorCode());
        assertEquals("boom", record.message());
        assertFalse(record.isSuccessful());
    }
}
