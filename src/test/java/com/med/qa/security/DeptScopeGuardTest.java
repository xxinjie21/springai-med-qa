package com.med.qa.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

/**
 * Unit tests of {@link DeptScopeGuard}: the department-scope rule of D22 with all of its rejections.
 *
 * <p>The guard is deliberately state-free (principal in, decision out), so every branch is reachable
 * without a servlet request, a filter chain or any middleware.</p>
 */
class DeptScopeGuardTest {

    private DeptScopeGuard guard;

    @BeforeEach
    void setUp() {
        guard = new DeptScopeGuard();
    }

    private static MedPrincipal patient() {
        return new MedPrincipal("hosp-1", "cardiology", MedRole.PATIENT, "pat-77");
    }

    private static MedPrincipal staff() {
        return new MedPrincipal("hosp-1", "cardiology", MedRole.STAFF, null);
    }

    @Nested
    @DisplayName("matching department")
    class Allowed {

        @Test
        @DisplayName("a patient may call an endpoint of their own department")
        void patientOwnDepartment() {
            assertThatCode(() -> guard.assertDeptAllowed(patient(), "cardiology", List.of(), true))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("staff may call an endpoint of their own department")
        void staffOwnDepartment() {
            assertThatCode(() -> guard.assertDeptAllowed(staff(), "cardiology", null, true))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a padded department id still matches")
        void paddedDepartmentId() {
            assertThatCode(() -> guard.assertDeptAllowed(staff(), "  cardiology ", List.of(), true))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a missing department id is tolerated when the handler does not require one")
        void missingButNotRequired() {
            assertThatCode(() -> guard.assertDeptAllowed(staff(), null, List.of(), false))
                    .doesNotThrowAnyException();
            assertThatCode(() -> guard.assertDeptAllowed(patient(), "  ", List.of(), false))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an allowed role passes the role gate")
        void allowedRole() {
            assertThatCode(() -> guard.assertDeptAllowed(staff(), "cardiology", Set.of(MedRole.STAFF), true))
                    .doesNotThrowAnyException();
            assertThatCode(() -> guard.assertRoleAllowed(patient(), Set.of(MedRole.PATIENT, MedRole.STAFF)))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("rejections (403)")
    class Rejected {

        @Test
        @DisplayName("another department is refused")
        void otherDepartment() {
            assertThatThrownBy(() -> guard.assertDeptAllowed(staff(), "neurology", List.of(), true))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("department mismatch")
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
        }

        @Test
        @DisplayName("an anonymous caller is refused even when the department matches")
        void anonymous() {
            assertThatThrownBy(() -> guard.assertDeptAllowed(null, "cardiology", List.of(), true))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("authentication required");
            assertThatThrownBy(() -> guard.assertDeptAllowed(null, null, List.of(), false))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("authentication required");
        }

        @Test
        @DisplayName("a required department id that is absent is refused")
        void requiredButMissing() {
            assertThatThrownBy(() -> guard.assertDeptAllowed(staff(), null, List.of(), true))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("required but missing");
            assertThatThrownBy(() -> guard.assertDeptAllowed(staff(), "   ", List.of(), true))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("required but missing");
        }

        @Test
        @DisplayName("a patient is refused on a staff-only endpoint")
        void roleNotAllowed() {
            BizException ex = catchBiz(() ->
                    guard.assertDeptAllowed(patient(), "cardiology", Set.of(MedRole.STAFF), false));
            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
            assertThat(ex.getMessage()).contains("PATIENT").contains("may not invoke");
        }

        @Test
        @DisplayName("the role gate is evaluated before the department id, so a wrong role never leaks scope")
        void roleCheckedFirst() {
            BizException ex = catchBiz(() ->
                    guard.assertDeptAllowed(patient(), "neurology", Set.of(MedRole.STAFF), true));
            assertThat(ex.getMessage()).contains("may not invoke this endpoint");
        }

        @Test
        @DisplayName("assertRoleAllowed refuses an anonymous caller")
        void roleGateAnonymous() {
            assertThatThrownBy(() -> guard.assertRoleAllowed(null, Set.of(MedRole.STAFF)))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("authentication required");
        }

        private static BizException catchBiz(Runnable runnable) {
            try {
                runnable.run();
            } catch (BizException ex) {
                return ex;
            }
            throw new AssertionError("expected a BizException");
        }
    }
}
