package com.med.qa.security;

import org.springframework.lang.Nullable;

/**
 * Configuration of a single API key, bound from {@code med.security.keys}.
 *
 * <p>Each entry of the {@code med.security.keys} map pairs an opaque key string with the principal it
 * stands for. The class is intentionally a plain mutable bean (not a record) so Spring Boot can bind map
 * values through setters without {@code @ConstructorBinding}. {@link MedApiKeyRegistry} reads these and
 * builds the immutable {@link MedPrincipal} instances used at request time.</p>
 */
public class MedApiKey {

    /** Hospital/tenant the key is valid in. */
    private String tenantId;

    /** Department the key is valid in. */
    private String deptId;

    /** Role granted to the key; defaults to {@link MedRole#STAFF} (the broadest intra-department role). */
    private MedRole role = MedRole.STAFF;

    /** Patient id for {@link MedRole#PATIENT} keys; {@code null} for staff. */
    @Nullable
    private String patientId;

    /**
     * Creates an (empty) key descriptor with the default staff role.
     */
    public MedApiKey() {
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getDeptId() {
        return deptId;
    }

    public void setDeptId(String deptId) {
        this.deptId = deptId;
    }

    public MedRole getRole() {
        return role;
    }

    public void setRole(MedRole role) {
        this.role = role;
    }

    @Nullable
    public String getPatientId() {
        return patientId;
    }

    public void setPatientId(@Nullable String patientId) {
        this.patientId = patientId;
    }
}
