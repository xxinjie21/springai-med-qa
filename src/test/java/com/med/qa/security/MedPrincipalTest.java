package com.med.qa.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of the {@link MedPrincipal} value object.
 */
class MedPrincipalTest {

    @Test
    @DisplayName("builds a patient principal with all fields")
    void buildsPatient() {
        MedPrincipal principal = new MedPrincipal("hosp-1", "cardiology", MedRole.PATIENT, "pat-77");

        assertThat(principal.getTenantId()).isEqualTo("hosp-1");
        assertThat(principal.getDeptId()).isEqualTo("cardiology");
        assertThat(principal.getRole()).isEqualTo(MedRole.PATIENT);
        assertThat(principal.getPatientId()).isEqualTo("pat-77");
        assertThat(principal.isPatient()).isTrue();
        assertThat(principal.isStaff()).isFalse();
    }

    @Test
    @DisplayName("builds a staff principal without a patient id")
    void buildsStaff() {
        MedPrincipal principal = new MedPrincipal("hosp-1", "cardiology", MedRole.STAFF, null);

        assertThat(principal.isStaff()).isTrue();
        assertThat(principal.getPatientId()).isNull();
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("rejects a blank tenant id")
        void rejectsBlankTenant() {
            assertThatThrownBy(() -> new MedPrincipal("  ", "card", MedRole.STAFF, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects a blank department id")
        void rejectsBlankDept() {
            assertThatThrownBy(() -> new MedPrincipal("hosp-1", "", MedRole.STAFF, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects a null role")
        void rejectsNullRole() {
            assertThatThrownBy(() -> new MedPrincipal("hosp-1", "card", null, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("role");
        }
    }

    @Nested
    @DisplayName("equality")
    class Equality {

        @Test
        @DisplayName("two principals with equal fields are equal")
        void equalOnFields() {
            MedPrincipal a = new MedPrincipal("hosp-1", "card", MedRole.PATIENT, "pat-77");
            MedPrincipal b = new MedPrincipal("hosp-1", "card", MedRole.PATIENT, "pat-77");

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("principals differ when the patient id differs")
        void differsOnPatient() {
            MedPrincipal a = new MedPrincipal("hosp-1", "card", MedRole.PATIENT, "pat-77");
            MedPrincipal b = new MedPrincipal("hosp-1", "card", MedRole.PATIENT, "pat-99");

            assertThat(a).isNotEqualTo(b);
        }
    }
}
