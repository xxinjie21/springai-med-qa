package com.med.qa.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.med.qa.domain.enums.AuditOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link AuditOutcome} enum: numeric code mapping and the success predicate.
 */
class AuditOutcomeTest {

    @Test
    @DisplayName("numeric codes match the med_audit_log TINYINT contract")
    void codesMatchStorageContract() {
        assertEquals(0, AuditOutcome.SUCCESS.getCode());
        assertEquals(1, AuditOutcome.FAILURE.getCode());
    }

    @Test
    @DisplayName("fromCode resolves both known codes")
    void fromCodeResolvesKnown() {
        assertEquals(AuditOutcome.SUCCESS, AuditOutcome.fromCode(0));
        assertEquals(AuditOutcome.FAILURE, AuditOutcome.fromCode(1));
    }

    @Test
    @DisplayName("fromCode rejects an unknown code")
    void fromCodeRejectsUnknown() {
        assertThrows(IllegalArgumentException.class, () -> AuditOutcome.fromCode(2));
        assertThrows(IllegalArgumentException.class, () -> AuditOutcome.fromCode(-1));
    }

    @Test
    @DisplayName("isSuccess is true only for SUCCESS")
    void isSuccessPredicate() {
        assertTrue(AuditOutcome.SUCCESS.isSuccess());
        assertFalse(AuditOutcome.FAILURE.isSuccess());
    }
}
