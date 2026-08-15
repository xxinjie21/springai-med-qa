package com.med.qa.security;

/**
 * Role of an authenticated API caller.
 *
 * <p>Only two roles exist in this service: a {@link #PATIENT} is strictly scoped to the sessions that
 * belong to their own patient id, while {@link #STAFF} (a clinician or administrator) may read every
 * session inside their own department/tenant. The role drives {@link PatientAccessGuard}, which is the
 * single place where the "patients only access their own sessions" rule is enforced.</p>
 */
public enum MedRole {

    /** A patient; may only reach sessions whose {@code patient_id} equals their own. */
    PATIENT,

    /** Clinical or administrative staff; may reach every session of their department. */
    STAFF;

    /**
     * Whether this role is a patient.
     *
     * @return {@code true} for {@link #PATIENT}
     */
    public boolean isPatient() {
        return this == PATIENT;
    }

    /**
     * Whether this role is staff.
     *
     * @return {@code true} for {@link #STAFF}
     */
    public boolean isStaff() {
        return this == STAFF;
    }
}
