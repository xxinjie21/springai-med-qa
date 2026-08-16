package com.med.qa.security;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Set;

/**
 * Enforces the department scope declared by {@link com.med.qa.security.annotation.RequireDept} (D22).
 *
 * <h2>The rule</h2>
 * <p>A caller may only act inside their own department. Where {@link PatientAccessGuard} (D21) answers
 * "may this principal touch <em>this session row</em>?", this guard answers the coarser question "may this
 * principal call this endpoint <em>for this department at all</em>?" — a cheap, row-independent check that
 * runs before the handler and therefore before any storage or LLM call is made.</p>
 *
 * <h2>Fail closed</h2>
 * <p>Every ambiguity is a rejection: no principal, a role outside the allowed set, a missing department id
 * on a handler that requires one, and of course a department id that differs from the caller's own. All of
 * them raise {@link ErrorCode#FORBIDDEN}. The guard performs no IO and reads no thread-local state — the
 * principal is passed in — which keeps it exhaustively unit-testable.</p>
 */
@Service
public class DeptScopeGuard {

    /**
     * Asserts that the principal may invoke a department-scoped handler.
     *
     * @param principal       authenticated caller, or {@code null} for an unauthenticated request
     * @param requestedDeptId department id carried by the request, or {@code null} when absent
     * @param allowedRoles    roles allowed to invoke the handler; {@code null} or empty admits every
     *                        authenticated role
     * @param deptIdRequired  whether a missing department id is a rejection ({@code true}) or is tolerated
     *                        because the scope travels in the request body ({@code false})
     * @throws BizException {@link ErrorCode#FORBIDDEN} when the caller is anonymous, holds a role outside
     *                      {@code allowedRoles}, omits a required department id, or targets another
     *                      department
     */
    public void assertDeptAllowed(@Nullable MedPrincipal principal,
                                  @Nullable String requestedDeptId,
                                  @Nullable Collection<MedRole> allowedRoles,
                                  boolean deptIdRequired) {
        assertRoleAllowed(principal, allowedRoles);
        if (!StringUtils.hasText(requestedDeptId)) {
            if (deptIdRequired) {
                throw new BizException(ErrorCode.FORBIDDEN, "department scope is required but missing");
            }
            return;
        }
        if (!principal.getDeptId().equals(requestedDeptId.trim())) {
            throw new BizException(ErrorCode.FORBIDDEN, "department mismatch");
        }
    }

    /**
     * Asserts that the principal is authenticated and holds one of the allowed roles.
     *
     * @param principal    authenticated caller, or {@code null} for an unauthenticated request
     * @param allowedRoles roles allowed to invoke the handler; {@code null} or empty admits every
     *                     authenticated role
     * @throws BizException {@link ErrorCode#FORBIDDEN} when the caller is anonymous or holds a role
     *                      outside {@code allowedRoles}
     */
    public void assertRoleAllowed(@Nullable MedPrincipal principal,
                                  @Nullable Collection<MedRole> allowedRoles) {
        if (principal == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "authentication required");
        }
        Set<MedRole> allowed = (allowedRoles == null) ? Set.of() : Set.copyOf(allowedRoles);
        if (!allowed.isEmpty() && !allowed.contains(principal.getRole())) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "role " + principal.getRole() + " may not invoke this endpoint");
        }
    }
}
