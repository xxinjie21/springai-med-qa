package com.med.qa.security;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.domain.entity.ChatSessionDO;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Enforces that a caller may only touch the sessions they are allowed to see.
 *
 * <h2>The single rule of D21</h2>
 * <p>A {@link MedRole#PATIENT} principal may only access the sessions whose {@code patient_id} equals
 * their own; a {@link MedRole#STAFF} principal may access every session of their department. Cross-tenant
 * and cross-department access is always rejected, and an unauthenticated caller (no principal) is
 * rejected from any ownership-sensitive operation — the guard fails closed.</p>
 *
 * <p>The guard is invoked by {@code MedChatSessionService} with the principal from
 * {@link MedSecurityContext}; it never reads the context itself, which keeps it trivially testable.</p>
 */
@Service
public class PatientAccessGuard {

    /**
     * Asserts that the principal may access the given session.
     *
     * @param principal authenticated caller, or {@code null} for an unauthenticated request
     * @param session   the session being touched, must not be {@code null}
     * @throws IllegalArgumentException when {@code session} is {@code null}
     * @throws BizException             {@link ErrorCode#FORBIDDEN} on any ownership/tenant/department
     *                                  violation, or when no principal is present
     */
    public void assertOwned(@Nullable MedPrincipal principal, ChatSessionDO session) {
        Objects.requireNonNull(session, "session must not be null");
        requireAuthenticated(principal);
        if (!principal.getTenantId().equals(session.getTenantId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "session belongs to another tenant");
        }
        if (!principal.getDeptId().equals(session.getDeptId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "session belongs to another department");
        }
        if (principal.isPatient()
                && !Objects.equals(principal.getPatientId(), session.getPatientId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "patients may only access their own sessions");
        }
    }

    /**
     * Asserts that the principal is allowed to act within the given scope.
     *
     * <p>Used for creation and listing: a patient may only operate on their own {@code patient_id}, so a
     * patient cannot enumerate or create sessions for another patient. Staff may operate anywhere inside
     * their department.</p>
     *
     * @param principal authenticated caller, or {@code null} for an unauthenticated request
     * @param tenantId  requested tenant id, must not be blank
     * @param deptId    requested department id, must not be blank
     * @param patientId requested patient filter, or {@code null} for a department-wide request
     * @throws IllegalArgumentException when {@code tenantId} / {@code deptId} are blank
     * @throws BizException             {@link ErrorCode#FORBIDDEN} on any scope/ownership violation, or
     *                                  when no principal is present
     */
    public void assertScope(@Nullable MedPrincipal principal,
                            String tenantId,
                            String deptId,
                            @Nullable String patientId) {
        requireText(tenantId, "tenantId");
        requireText(deptId, "deptId");
        requireAuthenticated(principal);
        if (!principal.getTenantId().equals(tenantId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "tenant mismatch");
        }
        if (!principal.getDeptId().equals(deptId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "department mismatch");
        }
        if (principal.isPatient()) {
            if (patientId == null || patientId.isBlank()) {
                throw new BizException(ErrorCode.FORBIDDEN, "patients may only access their own sessions");
            }
            if (!principal.getPatientId().equals(patientId)) {
                throw new BizException(ErrorCode.FORBIDDEN, "patients may only access their own sessions");
            }
        }
    }

    private static void requireAuthenticated(@Nullable MedPrincipal principal) {
        if (principal == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "authentication required");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
