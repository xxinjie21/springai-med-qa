package com.med.qa.rag;

import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Isolation scope attached to every medical document indexed into the RAG vector store.
 *
 * <p>A scope is the triple {@code tenant_id / dept_id / patient_id} of the unified storage
 * specification. It is materialized as RediSearch {@code TAG} metadata on the {@code Document}, so
 * retrieval can be narrowed with the official {@code FilterExpressionBuilder} <em>without ever
 * parsing the document text</em>: no entity extraction, no tokenization, no content inspection —
 * only the tags the caller declared at ingestion time.</p>
 *
 * <h2>Department-wide versus patient-specific documents</h2>
 * <p>Clinical guidelines, drug leaflets and departmental protocols belong to a department but to no
 * particular patient, while a discharge summary belongs to exactly one patient. Both must be
 * retrievable in the same query, and a filter expression cannot test for an <em>absent</em> tag.
 * A department-wide document is therefore tagged with the sentinel {@value #SHARED_PATIENT_TAG}, so
 * a patient-scoped retrieval reduces to {@code patient_id IN [<patient>, __shared__]}.</p>
 *
 * <p>Instances are immutable and safe to share.</p>
 */
public final class MedDocumentScope {

    /** Metadata key holding the tenant tag, aligned with the unified storage specification. */
    public static final String METADATA_TENANT_ID = "tenant_id";

    /** Metadata key holding the department tag. */
    public static final String METADATA_DEPT_ID = "dept_id";

    /** Metadata key holding the patient tag. */
    public static final String METADATA_PATIENT_ID = "patient_id";

    /**
     * Metadata keys owned by the isolation scope. Caller-supplied metadata may never contain them:
     * letting a caller set its own {@code dept_id} would allow a document to be indexed into a
     * department it does not belong to, which is a data-isolation breach rather than a typo.
     */
    public static final Set<String> RESERVED_METADATA_KEYS =
            Set.of(METADATA_TENANT_ID, METADATA_DEPT_ID, METADATA_PATIENT_ID);

    /**
     * Patient tag of a document that is shared by a whole department. Reserved: it can never be a
     * real patient identifier.
     */
    public static final String SHARED_PATIENT_TAG = "__shared__";

    private final String tenantId;

    private final String deptId;

    private final String patientId;

    private MedDocumentScope(String tenantId, String deptId, @Nullable String patientId) {
        this.tenantId = requireTag(tenantId, METADATA_TENANT_ID);
        this.deptId = requireTag(deptId, METADATA_DEPT_ID);
        if (patientId == null) {
            this.patientId = null;
        } else {
            String tag = requireTag(patientId, METADATA_PATIENT_ID);
            if (SHARED_PATIENT_TAG.equals(tag)) {
                throw new IllegalArgumentException(
                        METADATA_PATIENT_ID + " must not be the reserved shared tag " + SHARED_PATIENT_TAG);
            }
            this.patientId = tag;
        }
    }

    /**
     * Creates the scope of a document shared by an entire department (guideline, protocol, leaflet).
     *
     * @param tenantId hospital / tenant identifier, must not be blank
     * @param deptId   department identifier, must not be blank
     * @return an immutable department-wide scope, never {@code null}
     * @throws IllegalArgumentException if a tag is blank or contains a comma or whitespace
     */
    public static MedDocumentScope ofDepartment(String tenantId, String deptId) {
        return new MedDocumentScope(tenantId, deptId, null);
    }

    /**
     * Creates the scope of a document belonging to one patient of one department.
     *
     * @param tenantId  hospital / tenant identifier, must not be blank
     * @param deptId    department identifier, must not be blank
     * @param patientId patient identifier, must not be blank and must not be
     *                  {@value #SHARED_PATIENT_TAG}
     * @return an immutable patient-scoped scope, never {@code null}
     * @throws IllegalArgumentException if a tag is blank, contains a comma or whitespace, or the
     *                                  patient identifier is the reserved shared tag
     */
    public static MedDocumentScope ofPatient(String tenantId, String deptId, String patientId) {
        if (patientId == null) {
            throw new IllegalArgumentException(METADATA_PATIENT_ID + " must not be null");
        }
        return new MedDocumentScope(tenantId, deptId, patientId);
    }

    /**
     * Returns the tenant tag.
     *
     * @return tenant identifier, never blank
     */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * Returns the department tag.
     *
     * @return department identifier, never blank
     */
    public String getDeptId() {
        return deptId;
    }

    /**
     * Returns the patient tag of a patient-scoped document.
     *
     * @return patient identifier, or {@code null} for a department-wide document
     */
    @Nullable
    public String getPatientId() {
        return patientId;
    }

    /**
     * Returns the value actually written to the {@value #METADATA_PATIENT_ID} tag.
     *
     * @return the patient identifier, or {@value #SHARED_PATIENT_TAG} for a department-wide
     *         document; never {@code null}
     */
    public String getPatientTag() {
        return patientId != null ? patientId : SHARED_PATIENT_TAG;
    }

    /**
     * Tells whether the document belongs to a single patient.
     *
     * @return {@code true} when a patient identifier was supplied
     */
    public boolean isPatientScoped() {
        return patientId != null;
    }

    /**
     * Renders the scope as the metadata tags indexed by RediSearch.
     *
     * @return a fresh mutable map holding {@value #METADATA_TENANT_ID},
     *         {@value #METADATA_DEPT_ID} and {@value #METADATA_PATIENT_ID}, never {@code null}
     */
    public Map<String, Object> toMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>(4);
        metadata.put(METADATA_TENANT_ID, tenantId);
        metadata.put(METADATA_DEPT_ID, deptId);
        metadata.put(METADATA_PATIENT_ID, getPatientTag());
        return metadata;
    }

    /**
     * Validates a single tag value.
     *
     * <p>RediSearch splits {@code TAG} values on commas, so an identifier containing one would be
     * silently indexed as two tags and could be matched by a filter it does not belong to.
     * Whitespace is rejected for the same reason: it makes tag escaping ambiguous.</p>
     *
     * @param value candidate tag value
     * @param field metadata key, used in the error message
     * @return the validated value, never {@code null}
     * @throws IllegalArgumentException if the value is blank, holds a comma or holds whitespace
     */
    public static String requireTag(@Nullable String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.indexOf(',') >= 0) {
            throw new IllegalArgumentException(
                    field + " must not contain a comma, which RediSearch reads as a tag separator");
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                throw new IllegalArgumentException(field + " must not contain whitespace");
            }
        }
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MedDocumentScope that)) {
            return false;
        }
        return tenantId.equals(that.tenantId)
                && deptId.equals(that.deptId)
                && Objects.equals(patientId, that.patientId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, deptId, patientId);
    }

    @Override
    public String toString() {
        return "MedDocumentScope{tenantId='" + tenantId + "', deptId='" + deptId
                + "', patientId='" + getPatientTag() + "'}";
    }
}
