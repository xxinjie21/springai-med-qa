package com.med.qa.security;

import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * The effective identity triple of one consultation call, after the authenticated principal and the
 * identity the request body <em>claims</em> have been reconciled (D41).
 *
 * <h2>Why this type exists</h2>
 * <p>Before D41 the streaming consultation path derived its tenant / department / patient straight from
 * the JSON body, so a caller could ask for another hospital's or another patient's session simply by
 * filling in different values. The authority now lives in the {@link MedPrincipal} that
 * {@link ApiKeyAuthFilter} resolved from the API key, and the body fields are demoted to a
 * <em>consistency claim</em>. This record is the single value that both halves collapse into, so the
 * session coordinate, the RAG isolation scope and every downstream guard are built from one object
 * rather than from three independently-sourced strings.</p>
 *
 * <h2>Value object, not a policy object</h2>
 * <p>It normalises but never decides: a blank patient id becomes {@code null} (a department-wide scope),
 * and a blank tenant / department is rejected as a programming error. Whether a {@code null} patient id
 * is <em>acceptable</em> for a given principal — a patient principal must never end up with a
 * department-wide scope — is a policy question answered by {@link RequestIdentityGuard}, which is the
 * only intended producer of instances. Keeping the two apart means the guard can be exhaustively unit
 * tested by feeding it principals and claims, with no IO and no context involved.</p>
 *
 * @param tenantId  hospital / tenant identifier, never blank
 * @param deptId    department identifier, never blank
 * @param patientId patient identifier, or {@code null} for a department-wide scope
 */
public record MedCallerScope(String tenantId, String deptId, @Nullable String patientId) {

    /**
     * Creates and normalises a caller scope.
     *
     * @throws IllegalArgumentException if {@code tenantId} or {@code deptId} is blank
     */
    public MedCallerScope {
        tenantId = requireText(tenantId, "tenantId");
        deptId = requireText(deptId, "deptId");
        // A blank patient id carries no information: it is normalised to "no patient" rather than kept
        // as an empty string, which would otherwise build a filter tag no document can ever carry.
        patientId = StringUtils.hasText(patientId) ? patientId.trim() : null;
    }

    /**
     * Derives the scope a principal acts in by default.
     *
     * <p>Tenant and department always come from the principal. The patient id is the principal's own, so
     * a patient principal is patient-scoped without the caller having to say anything, while a staff
     * principal (whose patient id is {@code null}) starts out department-scoped and may narrow the scope
     * with {@link #withPatientId(String)}.</p>
     *
     * @param principal the authenticated caller, must not be {@code null}
     * @return the principal's default scope, never {@code null}
     * @throws NullPointerException if {@code principal} is {@code null}
     */
    public static MedCallerScope of(MedPrincipal principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        return new MedCallerScope(principal.getTenantId(), principal.getDeptId(), principal.getPatientId());
    }

    /**
     * Returns a copy of this scope narrowed to one patient.
     *
     * <p>Only ever applied to a staff principal's scope: a patient principal's patient id is not
     * negotiable, and {@link RequestIdentityGuard} refuses any request that claims a different one.</p>
     *
     * @param patientId the patient to narrow to, or {@code null} to widen back to the whole department
     * @return a new scope, never {@code null}
     */
    public MedCallerScope withPatientId(@Nullable String patientId) {
        return new MedCallerScope(tenantId, deptId, patientId);
    }

    /**
     * Tells whether the scope covers a single patient.
     *
     * @return {@code true} when a patient id is present
     */
    public boolean isPatientScoped() {
        return patientId != null;
    }

    private static String requireText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
