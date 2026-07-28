package com.med.qa.domain.entity;

import com.med.qa.domain.enums.RoleType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Chat message persistence object, field-level aligned with the unified medical
 * storage specification (ROADMAP section 4):
 *
 * <pre>
 * message_id (UUIDv7) / session_id / tenant_id / dept_id / patient_id / role /
 * content / token_count / masked / created_at (epoch millis) / metadata
 * </pre>
 *
 * <p>Rows are routed by ShardingSphere to {@code med_message_{crc32(session_id) % 16}}.
 * Instances are mutable JavaBeans (required by MyBatis) but a fluent
 * {@link Builder} is provided for construction in business code.</p>
 */
public class ChatMessageDO {

    /** Globally unique message id, UUIDv7 string per storage spec. */
    private String messageId;

    /** Owning session id; also the sharding key. */
    private String sessionId;

    /** Tenant (hospital) id. */
    private String tenantId;

    /** Department id, used as RAG metadata tag and Redis key segment. */
    private String deptId;

    /** Patient id the message belongs to. */
    private String patientId;

    /** Participant role. */
    private RoleType role;

    /** Message text content. */
    private String content;

    /** LLM token count of the content; 0 when unknown. */
    private int tokenCount;

    /** Whether privacy fields inside content have been masked. */
    private boolean masked;

    /** Creation time as epoch milliseconds per storage spec. */
    private long createdAt;

    /** Extension metadata key-value pairs; never {@code null}. */
    private Map<String, String> metadata = new LinkedHashMap<>();

    /**
     * Creates a new fluent builder.
     *
     * @return an empty {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

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

    public RoleType getRole() {
        return role;
    }

    public void setRole(RoleType role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public int getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(int tokenCount) {
        this.tokenCount = tokenCount;
    }

    public boolean isMasked() {
        return masked;
    }

    public void setMasked(boolean masked) {
        this.masked = masked;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * Returns the extension metadata map, never {@code null}.
     *
     * @return mutable metadata map held by this entity
     */
    public Map<String, String> getMetadata() {
        return metadata;
    }

    /**
     * Replaces the metadata map; a {@code null} argument is normalized to an
     * empty map so downstream codecs never face {@code null}.
     *
     * @param metadata new metadata, may be {@code null}
     */
    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata == null ? new LinkedHashMap<>() : metadata;
    }

    /**
     * Equality is defined by {@link #messageId} only, matching the storage
     * primary key semantics.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ChatMessageDO that)) {
            return false;
        }
        return Objects.equals(messageId, that.messageId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(messageId);
    }

    /**
     * Privacy-safe string form: the raw {@link #content} is never printed,
     * only its length, so entity logging cannot leak medical text.
     */
    @Override
    public String toString() {
        return "ChatMessageDO{messageId='" + messageId + "', sessionId='" + sessionId
                + "', tenantId='" + tenantId + "', deptId='" + deptId
                + "', patientId='" + patientId + "', role=" + role
                + ", contentLength=" + (content == null ? 0 : content.length())
                + ", tokenCount=" + tokenCount + ", masked=" + masked
                + ", createdAt=" + createdAt + ", metadataKeys=" + metadata.keySet() + '}';
    }

    /**
     * Fluent builder for {@link ChatMessageDO}.
     */
    public static final class Builder {

        private final ChatMessageDO target = new ChatMessageDO();

        public Builder messageId(String messageId) {
            target.setMessageId(messageId);
            return this;
        }

        public Builder sessionId(String sessionId) {
            target.setSessionId(sessionId);
            return this;
        }

        public Builder tenantId(String tenantId) {
            target.setTenantId(tenantId);
            return this;
        }

        public Builder deptId(String deptId) {
            target.setDeptId(deptId);
            return this;
        }

        public Builder patientId(String patientId) {
            target.setPatientId(patientId);
            return this;
        }

        public Builder role(RoleType role) {
            target.setRole(role);
            return this;
        }

        public Builder content(String content) {
            target.setContent(content);
            return this;
        }

        public Builder tokenCount(int tokenCount) {
            target.setTokenCount(tokenCount);
            return this;
        }

        public Builder masked(boolean masked) {
            target.setMasked(masked);
            return this;
        }

        public Builder createdAt(long createdAt) {
            target.setCreatedAt(createdAt);
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            target.setMetadata(metadata);
            return this;
        }

        /**
         * Validates required spec fields and returns the built entity.
         *
         * @return the built {@link ChatMessageDO}
         * @throws IllegalStateException if messageId or sessionId is blank
         */
        public ChatMessageDO build() {
            if (target.getMessageId() == null || target.getMessageId().isBlank()) {
                throw new IllegalStateException("messageId must not be blank");
            }
            if (target.getSessionId() == null || target.getSessionId().isBlank()) {
                throw new IllegalStateException("sessionId must not be blank");
            }
            return target;
        }
    }
}
