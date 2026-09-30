package com.med.qa.security;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests of {@link RequestIdentityGuard}, the boundary that stops a request body from deciding who
 * the caller is (D41).
 *
 * <p>The defect it closes: before D41 the streaming consultation path read the tenant / department /
 * patient straight out of the JSON body, so patient A could read and write patient B's transcript by
 * filling in B's identifiers. Every case below is therefore either "the claim agrees, so the principal's
 * identity is used" or "the claim disagrees, so the request is refused" — the guard has no third
 * outcome, and it never widens a patient principal beyond itself.</p>
 */
class RequestIdentityGuardTest {

    private static final String TENANT = "hosp-1";

    private static final String DEPT = "dept-cardio";

    private final RequestIdentityGuard guard = new RequestIdentityGuard();

    private static MedPrincipal staff() {
        return new MedPrincipal(TENANT, DEPT, MedRole.STAFF, null);
    }

    private static MedPrincipal patient(String patientId) {
        return new MedPrincipal(TENANT, DEPT, MedRole.PATIENT, patientId);
    }

    private static void assertForbidden(Runnable action, String expectedMessage) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BizException.class)
                .hasMessageContaining(expectedMessage)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Nested
    @DisplayName("staff principals")
    class Staff {

        @Test
        @DisplayName("a matching claim resolves to the principal's identity")
        void matchingClaimIsAccepted() {
            MedCallerScope scope = guard.resolve(staff(), TENANT, DEPT, "pat-77");

            assertThat(scope.tenantId()).isEqualTo(TENANT);
            assertThat(scope.deptId()).isEqualTo(DEPT);
            assertThat(scope.patientId()).isEqualTo("pat-77");
            assertThat(scope.isPatientScoped()).isTrue();
        }

        @Test
        @DisplayName("omitting every claim is valid: the principal already carries the identity")
        void absentClaimsAreAccepted() {
            MedCallerScope scope = guard.resolve(staff(), null, null, null);

            assertThat(scope.tenantId()).isEqualTo(TENANT);
            assertThat(scope.deptId()).isEqualTo(DEPT);
            assertThat(scope.patientId()).isNull();
            assertThat(scope.isPatientScoped()).isFalse();
        }

        @Test
        @DisplayName("blank claims are treated as absent, not as a mismatch")
        void blankClaimsAreTreatedAsAbsent() {
            MedCallerScope scope = guard.resolve(staff(), "  ", "", "   ");

            assertThat(scope.tenantId()).isEqualTo(TENANT);
            assertThat(scope.deptId()).isEqualTo(DEPT);
            assertThat(scope.patientId()).isNull();
        }

        @Test
        @DisplayName("may narrow the turn to any patient of its own department")
        void mayNameAnyPatientOfItsDepartment() {
            MedCallerScope scope = guard.resolve(staff(), null, null, "pat-4242");

            assertThat(scope.patientId()).isEqualTo("pat-4242");
            assertThat(scope.tenantId()).isEqualTo(TENANT);
        }

        @Test
        @DisplayName("a padded claim is trimmed before comparison rather than refused")
        void trimsClaims() {
            MedCallerScope scope = guard.resolve(staff(), " hosp-1 ", " dept-cardio ", " pat-77 ");

            assertThat(scope.tenantId()).isEqualTo(TENANT);
            assertThat(scope.deptId()).isEqualTo(DEPT);
            assertThat(scope.patientId()).isEqualTo("pat-77");
        }

        @Test
        @DisplayName("refuses a claim of another tenant")
        void refusesForeignTenant() {
            assertForbidden(() -> guard.resolve(staff(), "hosp-2", DEPT, null), "tenant mismatch");
        }

        @Test
        @DisplayName("refuses a claim of another department")
        void refusesForeignDepartment() {
            assertForbidden(() -> guard.resolve(staff(), TENANT, "dept-onco", null), "department mismatch");
        }

        @Test
        @DisplayName("the tenant is checked first, so a doubly wrong claim is reported as a tenant mismatch")
        void tenantIsCheckedBeforeDepartment() {
            assertForbidden(() -> guard.resolve(staff(), "hosp-2", "dept-onco", null), "tenant mismatch");
        }
    }

    @Nested
    @DisplayName("patient principals")
    class Patients {

        @Test
        @DisplayName("a patient acting for itself is accepted")
        void ownPatientIsAccepted() {
            MedCallerScope scope = guard.resolve(patient("pat-77"), TENANT, DEPT, "pat-77");

            assertThat(scope.patientId()).isEqualTo("pat-77");
            assertThat(scope.isPatientScoped()).isTrue();
        }

        @Test
        @DisplayName("a patient that claims nothing stays scoped to itself")
        void absentClaimKeepsOwnPatient() {
            MedCallerScope scope = guard.resolve(patient("pat-77"), null, null, null);

            assertThat(scope.patientId()).isEqualTo("pat-77");
        }

        @Test
        @DisplayName("refuses a patient claiming another patient's id")
        void refusesAnotherPatient() {
            assertForbidden(() -> guard.resolve(patient("pat-77"), TENANT, DEPT, "pat-99"),
                    "patient mismatch");
        }

        @Test
        @DisplayName("a patient principal without a patient id fails closed instead of widening to the department")
        void refusesPatientWithoutPatientId() {
            assertForbidden(() -> guard.resolve(patient(null), null, null, null),
                    "carries no patient id");
        }

        @Test
        @DisplayName("a patient principal cannot escape its department either")
        void refusesForeignDepartment() {
            assertForbidden(() -> guard.resolve(patient("pat-77"), TENANT, "dept-onco", "pat-77"),
                    "department mismatch");
        }
    }

    @Nested
    @DisplayName("anonymous callers")
    class Anonymous {

        @Test
        @DisplayName("no principal is a refusal even when the body names a complete identity")
        void refusesAnonymousCaller() {
            assertForbidden(() -> guard.resolve(null, TENANT, DEPT, "pat-77"), "authentication required");
        }

        @Test
        @DisplayName("no principal and no claim is still a refusal")
        void refusesAnonymousCallerWithoutClaims() {
            assertForbidden(() -> guard.resolve(null, null, null, null), "authentication required");
        }
    }
}
