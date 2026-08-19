package com.med.qa.domain.entity;

import com.med.qa.domain.enums.AuditOutcome;

import java.util.Objects;

/**
 * Audit trail persistence object: one row per audited medical operation.
 *
 * <p>The columns answer the five questions a hospital audit review asks — who
 * ({@code operator_id} / {@code operator_role} inside {@code tenant_id} / {@code dept_id}), what
 * ({@code action}), on which object ({@code resource_type} / {@code resource_id}), with what result
 * ({@code outcome} / {@code error_code}) and how expensive it was ({@code latency_millis}) — plus the
 * {@code created_at} epoch-millisecond stamp shared with every other table of the unified storage
 * specification (ROADMAP section 4).</p>
 *
 * <p>The row deliberately carries no clinical payload: no question text, no model answer, no
 * retrieved document content. An audit table that mirrors consultation content would duplicate
 * protected health information into a second store with its own retention rules.</p>
 */
public class AuditLogDO {

    /** Primary key of the audit entry (UUID). */
    private String auditId;

    /** Tenant (hospital) id of the operation scope. */
    private String tenantId;

    /** Department id of the operation scope. */
    private String deptId;

    /** Identifier of the acting subject (patient id, or the staff marker for department keys). */
    private String operatorId;

    /** Role of the acting subject ({@code PATIENT} / {@code STAFF}), or {@code null} when anonymous. */
    private String operatorRole;

    /** Stable action code, e.g. {@code SESSION_CLOSE}. */
    private String action;

    /** Type of the touched resource, e.g. {@code SESSION}, or {@code null}. */
    private String resourceType;

    /** Id of the touched resource, or {@code null} when the action has no single target. */
    private String resourceId;

    /** Whether the operation succeeded. */
    private AuditOutcome outcome = AuditOutcome.SUCCESS;

    /** Business error code of a failed operation, or {@code null} on success. */
    private Integer errorCode;

    /** Wall-clock duration of the audited call in milliseconds, never negative. */
    private long latencyMillis;

    /** Description on success, or the failure summary on failure; may be {@code null}. */
    private String message;

    /** Audit time as epoch milliseconds. */
    private long createdAt;

    public String getAuditId() {
        return auditId;
    }

    public void setAuditId(String auditId) {
        this.auditId = auditId;
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

    public String getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(String operatorId) {
        this.operatorId = operatorId;
    }

    public String getOperatorRole() {
        return operatorRole;
    }

    public void setOperatorRole(String operatorRole) {
        this.operatorRole = operatorRole;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public AuditOutcome getOutcome() {
        return outcome;
    }

    public void setOutcome(AuditOutcome outcome) {
        this.outcome = outcome;
    }

    public Integer getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(Integer errorCode) {
        this.errorCode = errorCode;
    }

    public long getLatencyMillis() {
        return latencyMillis;
    }

    public void setLatencyMillis(long latencyMillis) {
        this.latencyMillis = latencyMillis;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * Whether this entry records a successful operation.
     *
     * @return {@code true} when the outcome is {@link AuditOutcome#SUCCESS}
     * @throws IllegalStateException if the outcome was never set
     */
    public boolean isSuccessful() {
        if (outcome == null) {
            throw new IllegalStateException("outcome must be set before asking for the audit result");
        }
        return outcome.isSuccess();
    }

    /**
     * Equality is defined by {@link #auditId} only, matching the storage primary key semantics.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AuditLogDO that)) {
            return false;
        }
        return Objects.equals(auditId, that.auditId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(auditId);
    }

    @Override
    public String toString() {
        return "AuditLogDO{auditId='" + auditId + "', tenantId='" + tenantId
                + "', deptId='" + deptId + "', operatorId='" + operatorId
                + "', operatorRole='" + operatorRole + "', action='" + action
                + "', resourceType='" + resourceType + "', resourceId='" + resourceId
                + "', outcome=" + outcome + ", errorCode=" + errorCode
                + ", latencyMillis=" + latencyMillis + ", createdAt=" + createdAt + '}';
    }
}
