package com.med.qa.domain.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStatusTest {

    @ParameterizedTest
    @CsvSource({"ACTIVE,0", "CLOSED,1", "ARCHIVED,2"})
    @DisplayName("getCode: codes are stable storage values")
    void getCode_matchesSpec(SessionStatus status, int expectedCode) {
        assertEquals(expectedCode, status.getCode());
    }

    @Test
    @DisplayName("fromCode: resolves every declared status (round-trip)")
    void fromCode_roundTrip() {
        for (SessionStatus status : SessionStatus.values()) {
            assertEquals(status, SessionStatus.fromCode(status.getCode()));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 3, 42})
    @DisplayName("fromCode: unknown code throws IllegalArgumentException")
    void fromCode_unknownCode_throws(int badCode) {
        assertThrows(IllegalArgumentException.class, () -> SessionStatus.fromCode(badCode));
    }

    @Test
    @DisplayName("isWritable: only ACTIVE accepts new messages")
    void isWritable_onlyActive() {
        assertTrue(SessionStatus.ACTIVE.isWritable());
        assertFalse(SessionStatus.CLOSED.isWritable());
        assertFalse(SessionStatus.ARCHIVED.isWritable());
    }
}
