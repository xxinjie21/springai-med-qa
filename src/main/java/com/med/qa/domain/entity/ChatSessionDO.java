package com.med.qa.domain.entity;

import com.med.qa.domain.enums.SessionStatus;

import java.util.Objects;

/**
 * Consultation session persistence object. Identity fields
 * ({@code session_id} / {@code tenant_id} / {@code dept_id} / {@code patient_id})
 * follow the unified medical storage specification (ROADMAP section 4) so the
 * Redis key {@code med:chat:{tenant_id}:{dept_id}:{session_id}} can be derived
 * directly from one instance.
 */
public class ChatSessionDO {

    /** Globally unique session id; sharding key of the message tables. */
    private String sessionId;

    /** Tenant (hospital) id. */
    private String tenantId;

    /** Department id. */
    private String deptId;

    /** Owning patient id, used for ownership access control. */
    private String patientId;

    /** Human-readable session title (e.g. first question summary). */
    private String title;

    /** Lifecycle status. */
    private SessionStatus status = SessionStatus.ACTIVE;

    /** Creation time as epoch milliseconds. */
    private long createdAt;

    /** Last update time as epoch milliseconds. */
    private long updatedAt;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
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

    public String getPatientId() {
        return patientId;
    }

    public void setPatientId(String patientId) {
        this.patientId = patientId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public void setStatus(SessionStatus status) {
        this.status = status;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * Builds the spec-compliant Redis cache key
     * {@code med:chat:{tenant_id}:{dept_id}:{session_id}} for this session.
     *
     * @return the Redis key string
     * @throws IllegalStateException if any of tenantId/deptId/sessionId is blank
     */
    public String redisKey() {
        if (isBlank(tenantId) || isBlank(deptId) || isBlank(sessionId)) {
            throw new IllegalStateException(
                    "tenantId, deptId and sessionId are all required to build the redis key");
        }
        return "med:chat:" + tenantId + ":" + deptId + ":" + sessionId;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Equality is defined by {@link #sessionId} only, matching the storage
     * primary key semantics.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ChatSessionDO that)) {
            return false;
        }
        return Objects.equals(sessionId, that.sessionId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(sessionId);
    }

    @Override
    public String toString() {
        return "ChatSessionDO{sessionId='" + sessionId + "', tenantId='" + tenantId
                + "', deptId='" + deptId + "', patientId='" + patientId
                + "', title='" + title + "', status=" + status
                + ", createdAt=" + createdAt + ", updatedAt=" + updatedAt + '}';
    }
}
