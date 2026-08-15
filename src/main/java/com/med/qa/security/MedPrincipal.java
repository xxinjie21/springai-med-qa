package com.med.qa.security;

import org.springframework.lang.Nullable;

import java.util.Objects;

/**
 * Authenticated caller identity resolved from an API key by {@link ApiKeyAuthFilter}.
 *
 * <p>A principal is the trust anchor for every authorization decision: {@link PatientAccessGuard}
 * compares its {@code tenantId} / {@code deptId} / {@code patientId} against the session it is asked to
 * touch. The identity segments are mandatory (a principal without a tenant or department is meaningless
 * in a multi-tenant hospital deployment), whereas {@code patientId} is optional because staff principals
 * are department-scoped, not patient-scoped.</p>
 *
 * @param tenantId hospital/tenant id, never blank
 * @param deptId   department id, never blank
 * @param role     caller role, never {@code null}
 * @param patientId patient id for {@link MedRole#PATIENT} principals, or {@code null} for staff
 */
public record MedPrincipal(String tenantId, String deptId, MedRole role, @Nullable String patientId) {

    /**
     * Creates and validates a principal.
     *
     * @throws IllegalArgumentException if {@code tenantId} / {@code deptId} are blank or {@code role}
     *                                  is {@code null}
     */
    public MedPrincipal {
        requireText(tenantId, "tenantId");
        requireText(deptId, "deptId");
        Objects.requireNonNull(role, "role must not be null");
    }

    /**
     * Whether this principal is a patient.
     *
     * @return {@code true} for {@link MedRole#PATIENT}
     */
    public boolean isPatient() {
        return role.isPatient();
    }

    /**
     * Whether this principal is staff.
     *
     * @return {@code true} for {@link MedRole#STAFF}
     */
    public boolean isStaff() {
        return role.isStaff();
    }

    /**
     * Returns the tenant id (record-component accessor alias).
     *
     * @return tenant id, never blank
     */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * Returns the department id (record-component accessor alias).
     *
     * @return department id, never blank
     */
    public String getDeptId() {
        return deptId;
    }

    /**
     * Returns the role (record-component accessor alias).
     *
     * @return role, never {@code null}
     */
    public MedRole getRole() {
        return role;
    }

    /**
     * Returns the patient id (record-component accessor alias).
     *
     * @return patient id, or {@code null} for staff
     */
    @Nullable
    public String getPatientId() {
        return patientId;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
