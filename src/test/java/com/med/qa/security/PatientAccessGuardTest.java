package com.med.qa.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.domain.entity.ChatSessionDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link PatientAccessGuard}: the "patients only access their own sessions" rule.
 */
class PatientAccessGuardTest {

    private PatientAccessGuard guard;

    private MedPrincipal patient;

    private MedPrincipal staff;

    @BeforeEach
    void setUp() {
        guard = new PatientAccessGuard();
        patient = new MedPrincipal("hosp-1", "cardiology", MedRole.PATIENT, "pat-77");
        staff = new MedPrincipal("hosp-1", "cardiology", MedRole.STAFF, null);
    }

    private static ChatSessionDO session(String tenant, String dept, String patientId) {
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId("s-1");
        session.setTenantId(tenant);
        session.setDeptId(dept);
        session.setPatientId(patientId);
        return session;
    }

    @Nested
    @DisplayName("assertOwned")
    class AssertOwned {

        @Test
        @DisplayName("a patient may access their own session")
        void patientOwns() {
            guard.assertOwned(patient, session("hosp-1", "cardiology", "pat-77"));
        }

        @Test
        @DisplayName("a patient is forbidden from another patient's session in the same department")
        void patientOtherForbidden() {
            assertThatThrownBy(() -> guard.assertOwned(patient, session("hosp-1", "cardiology", "pat-99")))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
        }

        @Test
        @DisplayName("staff may access any session of their department")
        void staffAnySession() {
            guard.assertOwned(staff, session("hosp-1", "cardiology", "pat-99"));
        }

        @Test
        @DisplayName("an unauthenticated caller is forbidden")
        void noPrincipalForbidden() {
            assertThatThrownBy(() -> guard.assertOwned(null, session("hosp-1", "cardiology", "pat-77")))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
        }

        @Test
        @DisplayName("a tenant mismatch is forbidden")
        void tenantMismatch() {
            assertThatThrownBy(() -> guard.assertOwned(patient, session("hosp-2", "cardiology", "pat-77")))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
        }

        @Test
        @DisplayName("a department mismatch is forbidden")
        void deptMismatch() {
            assertThatThrownBy(() -> guard.assertOwned(patient, session("hosp-1", "neurology", "pat-77")))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
        }

        @Test
        @DisplayName("a null session is a programming error")
        void nullSession() {
            assertThatThrownBy(() -> guard.assertOwned(patient, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("session");
        }
    }

    @Nested
    @DisplayName("assertScope")
    class AssertScope {

        @Test
        @DisplayName("a patient may only operate on their own patient id")
        void patientOwnScope() {
            guard.assertScope(patient, "hosp-1", "cardiology", "pat-77");
        }

        @Test
        @DisplayName("a patient cannot scope to another patient's id")
        void patientOtherScopeForbidden() {
            assertThatThrownBy(() -> guard.assertScope(patient, "hosp-1", "cardiology", "pat-99"))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
        }

        @Test
        @DisplayName("a patient cannot scope without a patient id")
        void patientBlankScopeForbidden() {
            assertThatThrownBy(() -> guard.assertScope(patient, "hosp-1", "cardiology", null))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
        }

        @Test
        @DisplayName("staff may scope to another patient in their department")
        void staffOtherScopeAllowed() {
            guard.assertScope(staff, "hosp-1", "cardiology", "pat-99");
        }

        @Test
        @DisplayName("staff may scope to the whole department")
        void staffDepartmentScopeAllowed() {
            guard.assertScope(staff, "hosp-1", "cardiology", null);
        }

        @Test
        @DisplayName("a tenant mismatch in the scope is forbidden")
        void tenantMismatchForbidden() {
            assertThatThrownBy(() -> guard.assertScope(staff, "hosp-2", "cardiology", null))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
        }

        @Test
        @DisplayName("a department mismatch in the scope is forbidden")
        void deptMismatchForbidden() {
            assertThatThrownBy(() -> guard.assertScope(staff, "hosp-1", "neurology", null))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
        }

        @Test
        @DisplayName("blank identity segments are a programming error")
        void blankSegmentError() {
            assertThatThrownBy(() -> guard.assertScope(staff, "  ", "cardiology", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("an unauthenticated caller is forbidden from any scope")
        void noPrincipalForbidden() {
            assertThatThrownBy(() -> guard.assertScope(null, "hosp-1", "cardiology", "pat-77"))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
        }
    }
}
