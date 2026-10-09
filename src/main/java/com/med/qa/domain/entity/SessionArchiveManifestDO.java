package com.med.qa.domain.entity;

import java.util.Objects;

/**
 * Export manifest of one archived consultation transcript (D48).
 *
 * <p>One row per session, written <em>after</em> the cold copy has been read back and compared with
 * the source transcript. It is therefore the only thing in the system that asserts "this session has
 * been exported", and it carries the two facts that make the assertion checkable later:</p>
 *
 * <ul>
 *   <li>{@link #messageCount} — how many messages the transcript held at export time. A count of zero
 *       is a legal, meaningful value: it is how an archived session that never received a message is
 *       recorded as exported rather than left in the candidate set forever.</li>
 *   <li>{@link #payloadChecksum} — the hex SHA-256 of the canonical transcript form, so a
 *       verification can recompute it from the cold copy (or from the hot transcript) and compare.</li>
 * </ul>
 *
 * <p>The primary key is {@code sessionId}, which is what makes the insert a compare-and-set: a second
 * export of the same session returns zero affected rows and is reported as {@code skipped} rather than
 * silently rewriting a manifest that a verification may be reading.</p>
 *
 * <p>The tenant/department/patient ids are carried over from {@code med_session} so that a future
 * scoped purge of the cold store can be expressed without joining back to the session table. That
 * purge is deliberately not part of this iteration.</p>
 */
public class SessionArchiveManifestDO {

    /** Exported session id; the primary key. */
    private String sessionId;

    /** Tenant (hospital) id the session belongs to. */
    private String tenantId;

    /** Department id the session belongs to. */
    private String deptId;

    /** Patient id the session belongs to. */
    private String patientId;

    /** Number of messages in the exported transcript; zero is legal. */
    private int messageCount;

    /** Hex SHA-256 of the canonical transcript form, exactly 64 characters. */
    private String payloadChecksum;

    /** Export time as epoch milliseconds. */
    private long exportedAt;

    /**
     * Creates an empty manifest for MyBatis.
     */
    public SessionArchiveManifestDO() {
    }

    /**
     * Creates a fully populated manifest.
     *
     * @param sessionId       exported session id, must not be blank
     * @param tenantId        tenant id, must not be blank
     * @param deptId          department id, must not be blank
     * @param patientId       patient id, must not be blank
     * @param messageCount    number of exported messages, must not be negative
     * @param payloadChecksum hex SHA-256 of the canonical transcript form, must be 64 characters
     * @param exportedAt      export time as epoch milliseconds
     * @throws IllegalArgumentException if an identity field is blank, the count is negative or the
     *                                  checksum is not a 64-character hex string
     */
    public SessionArchiveManifestDO(String sessionId, String tenantId, String deptId, String patientId,
                                    int messageCount, String payloadChecksum, long exportedAt) {
        setSessionId(sessionId);
        setTenantId(tenantId);
        setDeptId(deptId);
        setPatientId(patientId);
        setMessageCount(messageCount);
        setPayloadChecksum(payloadChecksum);
        setExportedAt(exportedAt);
    }

    public String getSessionId() {
        return sessionId;
    }

    /**
     * Sets the exported session id.
     *
     * @param sessionId session id, must not be blank
     * @throws IllegalArgumentException if {@code sessionId} is blank
     */
    public void setSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        this.sessionId = sessionId;
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

    public int getMessageCount() {
        return messageCount;
    }

    /**
     * Sets the number of exported messages.
     *
     * @param messageCount number of messages, must not be negative
     * @throws IllegalArgumentException if {@code messageCount} is negative
     */
    public void setMessageCount(int messageCount) {
        if (messageCount < 0) {
            throw new IllegalArgumentException("messageCount must not be negative");
        }
        this.messageCount = messageCount;
    }

    public String getPayloadChecksum() {
        return payloadChecksum;
    }

    /**
     * Sets the transcript checksum.
     *
     * @param payloadChecksum hex SHA-256, exactly 64 characters
     * @throws IllegalArgumentException if {@code payloadChecksum} is not a 64-character hex string
     */
    public void setPayloadChecksum(String payloadChecksum) {
        if (payloadChecksum == null || !payloadChecksum.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "payloadChecksum must be a 64-character lower-case hex SHA-256 but was "
                            + payloadChecksum);
        }
        this.payloadChecksum = payloadChecksum;
    }

    public long getExportedAt() {
        return exportedAt;
    }

    public void setExportedAt(long exportedAt) {
        this.exportedAt = exportedAt;
    }

    /**
     * Equality is defined by the primary key {@link #sessionId}.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SessionArchiveManifestDO that)) {
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
        return "SessionArchiveManifestDO{sessionId='" + sessionId + "', tenantId='" + tenantId
                + "', deptId='" + deptId + "', patientId='" + patientId
                + "', messageCount=" + messageCount
                + ", payloadChecksum='" + payloadChecksum + "', exportedAt=" + exportedAt + '}';
    }
}
