package com.med.qa.domain.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleTypeTest {

    @ParameterizedTest
    @CsvSource({"PATIENT,0", "DOCTOR,1", "ASSISTANT,2", "SYSTEM,3"})
    @DisplayName("getCode: codes are locked to the unified storage spec")
    void getCode_matchesStorageSpec(RoleType role, int expectedCode) {
        assertEquals(expectedCode, role.getCode());
    }

    @Test
    @DisplayName("fromCode: resolves every declared role (round-trip)")
    void fromCode_roundTrip() {
        for (RoleType role : RoleType.values()) {
            assertEquals(role, RoleType.fromCode(role.getCode()));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 4, 99})
    @DisplayName("fromCode: unknown code throws IllegalArgumentException")
    void fromCode_unknownCode_throws(int badCode) {
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> RoleType.fromCode(badCode));
        assertTrue(ex.getMessage().contains(String.valueOf(badCode)));
    }
}
