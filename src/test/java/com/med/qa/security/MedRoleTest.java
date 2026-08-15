package com.med.qa.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of the {@link MedRole} enum.
 */
class MedRoleTest {

    @Test
    @DisplayName("PATIENT reports itself as a patient and not staff")
    void patientRole() {
        assertThat(MedRole.PATIENT.isPatient()).isTrue();
        assertThat(MedRole.PATIENT.isStaff()).isFalse();
    }

    @Test
    @DisplayName("STAFF reports itself as staff and not a patient")
    void staffRole() {
        assertThat(MedRole.STAFF.isStaff()).isTrue();
        assertThat(MedRole.STAFF.isPatient()).isFalse();
    }

    @Test
    @DisplayName("exactly the two expected roles exist")
    void onlyTwoRoles() {
        assertThat(MedRole.values()).containsExactlyInAnyOrder(MedRole.PATIENT, MedRole.STAFF);
    }
}
