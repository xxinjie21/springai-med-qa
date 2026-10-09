package com.med.qa.domain.entity;

import java.util.Objects;

/**
 * Cold-store persistence object for one archived chat message (D48).
 *
 * <p>Unlike {@link ChatMessageDO}, which is the decoded, column-per-field shape of a <em>live</em>
 * message in the sharded {@code med_message} tables, this row keeps the message as the frozen
 * Protobuf payload required by the unified medical storage specification (ROADMAP section 4). That is
 * the point of the cold tier: the copy is byte-comparable with what the heterogeneous Python
 * middleware writes, so the archive can be read by either system from {@code med_session.proto}
 * alone.</p>
 *
 * <p>The identity columns ({@code tenantId} / {@code deptId} / {@code patientId} / {@code createdAt})
 * are a denormalized copy of fields that are also inside {@link #payload}. They exist so an operator
 * can count and scope archive rows with SQL without decoding Protobuf, and so a future scoped purge
 * can be expressed as one statement. They are written from the same entity as the payload, in the
 * same insert, and no code path ever updates an archive row - so they cannot drift from it. The
 * payload remains the authority.</p>
 *
 * <p>The primary key of the table is {@code (sessionId, messageId)}, which is what makes re-running an
 * export idempotent without a read-then-write check: a second insert of the same message is a
 * duplicate-key no-op.</p>
 */
public class ArchivedMessageDO {

    /** Owning session id; first half of the primary key, and the archive's read key. */
    private String sessionId;

    /** Message id as written by the storage spec (UUIDv7); second half of the primary key. */
    private String messageId;

    /** Tenant (hospital) id, carried for scoped operations on the cold store. */
    private String tenantId;

    /** Department id, carried for scoped operations on the cold store. */
    private String deptId;

    /** Patient id, carried for scoped operations on the cold store. */
    private String patientId;

    /** Original creation time as epoch milliseconds, so the transcript keeps its real order. */
    private long createdAt;

    /** When this copy was written to the cold store, as epoch milliseconds. */
    private long archivedAt;

    /** Frozen Protobuf payload of the message; never {@code null} and never empty. */
    private byte[] payload;

    /**
     * Creates an empty row for MyBatis.
     */
    public ArchivedMessageDO() {
    }

    /**
     * Creates a fully populated archive row.
     *
     * @param sessionId  owning session id, must not be blank
     * @param messageId  message id, must not be blank
     * @param tenantId   tenant id, must not be blank
     * @param deptId     department id, must not be blank
     * @param patientId  patient id, must not be blank
     * @param createdAt  original creation time as epoch milliseconds
     * @param archivedAt copy time as epoch milliseconds
     * @param payload    frozen Protobuf payload, must not be empty
     * @throws IllegalArgumentException if an identity field is blank or the payload is empty
     */
    public ArchivedMessageDO(String sessionId, String messageId, String tenantId, String deptId,
                             String patientId, long createdAt, long archivedAt, byte[] payload) {
        setSessionId(sessionId);
        setMessageId(messageId);
        setTenantId(tenantId);
        setDeptId(deptId);
        setPatientId(patientId);
        setCreatedAt(createdAt);
        setArchivedAt(archivedAt);
        setPayload(payload);
    }

    public String getSessionId() {
        return sessionId;
    }

    /**
     * Sets the owning session id.
     *
     * @param sessionId owning session id, must not be blank
     * @throws IllegalArgumentException if {@code sessionId} is blank
     */
    public void setSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        this.sessionId = sessionId;
    }

    public String getMessageId() {
        return messageId;
    }

    /**
     * Sets the message id.
     *
     * @param messageId message id, must not be blank
     * @throws IllegalArgumentException if {@code messageId} is blank
     */
    public void setMessageId(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        this.messageId = messageId;
    }

    public String getTenantId() {
        return tenantId;
    }

    /**
     * Sets the tenant id.
     *
     * @param tenantId tenant id, must not be blank
     * @throws IllegalArgumentException if {@code tenantId} is blank
     */
    public void setTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        this.tenantId = tenantId;
    }

    public String getDeptId() {
        return deptId;
    }

    /**
     * Sets the department id.
     *
     * @param deptId department id, must not be blank
     * @throws IllegalArgumentException if {@code deptId} is blank
     */
    public void setDeptId(String deptId) {
        if (deptId == null || deptId.isBlank()) {
            throw new IllegalArgumentException("deptId must not be blank");
        }
        this.deptId = deptId;
    }

    public String getPatientId() {
        return patientId;
    }

    /**
     * Sets the patient id.
     *
     * @param patientId patient id, must not be blank
     * @throws IllegalArgumentException if {@code patientId} is blank
     */
    public void setPatientId(String patientId) {
        if (patientId == null || patientId.isBlank()) {
            throw new IllegalArgumentException("patientId must not be blank");
        }
        this.patientId = patientId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(long archivedAt) {
        this.archivedAt = archivedAt;
    }

    /**
     * Returns the frozen Protobuf payload.
     *
     * @return the payload bytes; the array is returned as-is and must be treated as read-only
     */
    public byte[] getPayload() {
        return payload;
    }

    /**
     * Sets the frozen Protobuf payload.
     *
     * @param payload payload bytes, must not be {@code null} or empty
     * @throws IllegalArgumentException if {@code payload} is {@code null} or empty
     */
    public void setPayload(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("payload must not be empty");
        }
        this.payload = payload;
    }

    /**
     * Equality is defined by the storage primary key {@code (sessionId, messageId)}.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ArchivedMessageDO that)) {
            return false;
        }
        return Objects.equals(sessionId, that.sessionId) && Objects.equals(messageId, that.messageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, messageId);
    }

    /**
     * Privacy-safe string form: the payload is never printed, only its length, so archive logging
     * cannot leak medical text.
     */
    @Override
    public String toString() {
        return "ArchivedMessageDO{sessionId='" + sessionId + "', messageId='" + messageId
                + "', tenantId='" + tenantId + "', deptId='" + deptId + "', patientId='" + patientId
                + "', createdAt=" + createdAt + ", archivedAt=" + archivedAt
                + ", payloadLength=" + (payload == null ? 0 : payload.length) + '}';
    }
}
