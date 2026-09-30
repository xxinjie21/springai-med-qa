package com.med.qa.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests of {@link MedCallerScope}, the effective identity triple of a consultation call (D41).
 *
 * <p>The record normalises but does not decide: these tests pin the normalisation (blank patient id
 * becomes "no patient") and the rejections (a blank tenant or department is a programming error), and
 * leave the policy — whether a {@code null} patient id is acceptable for a given principal — to
 * {@link RequestIdentityGuardTest}.</p>
 */
class MedCallerScopeTest {

    private static final MedPrincipal STAFF =
            new MedPrincipal("hosp-1", "dept-cardio", MedRole.STAFF, null);

    private static final MedPrincipal PATIENT =
            new MedPrincipal("hosp-1", "dept-cardio", MedRole.PATIENT, "pat-77");

    @Nested
    @DisplayName("of(principal)")
    class FromPrincipal {

        @Test
        @DisplayName("a staff principal yields a department-wide scope")
        void staffIsDepartmentScoped() {
            MedCallerScope scope = MedCallerScope.of(STAFF);

            assertThat(scope.tenantId()).isEqualTo("hosp-1");
            assertThat(scope.deptId()).isEqualTo("dept-cardio");
            assertThat(scope.patientId()).isNull();
            assertThat(scope.isPatientScoped()).isFalse();
        }

        @Test
        @DisplayName("a patient principal yields a patient-scoped scope without the caller saying anything")
        void patientIsPatientScoped() {
            MedCallerScope scope = MedCallerScope.of(PATIENT);

            assertThat(scope.patientId()).isEqualTo("pat-77");
            assertThat(scope.isPatientScoped()).isTrue();
        }

        @Test
        @DisplayName("rejects a null principal")
        void rejectsNullPrincipal() {
            assertThatThrownBy(() -> MedCallerScope.of(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("principal");
        }
    }

    @Nested
    @DisplayName("normalisation")
    class Normalisation {

        @Test
        @DisplayName("a blank patient id means 'no patient', not an empty tag")
        void blankPatientBecomesNull() {
            assertThat(new MedCallerScope("hosp-1", "dept-cardio", "  ").patientId()).isNull();
            assertThat(new MedCallerScope("hosp-1", "dept-cardio", "").isPatientScoped()).isFalse();
        }

        @Test
        @DisplayName("identifiers are trimmed so a padded value cannot dodge a comparison")
        void trimsIdentifiers() {
            MedCallerScope scope = new MedCallerScope(" hosp-1 ", " dept-cardio ", " pat-77 ");

            assertThat(scope.tenantId()).isEqualTo("hosp-1");
            assertThat(scope.deptId()).isEqualTo("dept-cardio");
            assertThat(scope.patientId()).isEqualTo("pat-77");
        }

        @Test
        @DisplayName("rejects a blank tenant or department as a programming error")
        void rejectsBlankIdentitySegments() {
            assertThatThrownBy(() -> new MedCallerScope(" ", "dept-cardio", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tenantId");
            assertThatThrownBy(() -> new MedCallerScope("hosp-1", "", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("deptId");
        }
    }

    @Nested
    @DisplayName("withPatientId")
    class Narrowing {

        @Test
        @DisplayName("narrows a staff scope to one patient")
        void narrowsToPatient() {
            MedCallerScope narrowed = MedCallerScope.of(STAFF).withPatientId("pat-99");

            assertThat(narrowed.patientId()).isEqualTo("pat-99");
            assertThat(narrowed.isPatientScoped()).isTrue();
            assertThat(narrowed.tenantId()).isEqualTo("hosp-1");
            assertThat(narrowed.deptId()).isEqualTo("dept-cardio");
        }

        @Test
        @DisplayName("widens back to the whole department when given null")
        void widensBackToDepartment() {
            MedCallerScope widened = MedCallerScope.of(STAFF).withPatientId("pat-99").withPatientId(null);

            assertThat(widened.isPatientScoped()).isFalse();
        }

        @Test
        @DisplayName("returns a new value and leaves the receiver untouched")
        void isImmutable() {
            MedCallerScope original = MedCallerScope.of(STAFF);

            MedCallerScope narrowed = original.withPatientId("pat-99");

            assertThat(narrowed).isNotSameAs(original);
            assertThat(original.isPatientScoped()).isFalse();
        }
    }

    @Test
    @DisplayName("is a value: equal components compare equal and hash alike")
    void isAValue() {
        MedCallerScope a = new MedCallerScope("hosp-1", "dept-cardio", "pat-77");
        MedCallerScope b = new MedCallerScope("hosp-1", "dept-cardio", "pat-77");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(new MedCallerScope("hosp-1", "dept-cardio", null));
    }
}
