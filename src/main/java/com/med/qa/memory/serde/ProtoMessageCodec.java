package com.med.qa.memory.serde;

import com.google.protobuf.InvalidProtocolBufferException;
import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.enums.RoleType;
import com.med.qa.domain.enums.SessionStatus;
import com.med.qa.proto.ChatMessage;
import com.med.qa.proto.ChatSession;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bidirectional codec between the domain entities and the Protobuf wire schema of
 * {@code med_session.proto}.
 *
 * <p>Protobuf binary is the storage format required by the unified medical storage specification
 * (ROADMAP section 4). Encoding is therefore a pure, lossless field mapping — no business logic,
 * no content parsing, no entity extraction — so that records written by this service and by the
 * heterogeneous Python middleware stay byte-compatible.</p>
 *
 * <h2>Normalization rules</h2>
 * <ul>
 *   <li>{@code null} strings are written as empty strings, because proto3 scalar fields cannot
 *       hold {@code null}. A decode therefore yields {@code ""} where the source held
 *       {@code null}.</li>
 *   <li>Metadata entries whose key or value is {@code null} are dropped, since a Protobuf map
 *       rejects {@code null} on either side.</li>
 *   <li>A {@code null} {@link SessionStatus} defaults to {@link SessionStatus#ACTIVE}, matching
 *       the entity's own field default. A {@code null} {@link RoleType} is rejected instead —
 *       silently defaulting a medical message to {@code PATIENT} would corrupt the record.</li>
 *   <li>{@link ChatSessionDO} exposes no metadata field, so the {@code ChatSession.metadata} map
 *       stays empty on encode and is ignored on decode. Session-level metadata written by the
 *       Python middleware survives untouched in storage because this service never rewrites a
 *       record it did not produce.</li>
 * </ul>
 *
 * <h2>Failure semantics</h2>
 * <ul>
 *   <li>{@link IllegalArgumentException} — the caller passed {@code null} or an incomplete entity;
 *       a programming error.</li>
 *   <li>{@link BizException} with {@link ErrorCode#STORAGE_ERROR} — the bytes read back from
 *       MySQL/Redis are truncated, corrupt, or carry an enum code this service does not know;
 *       a data error attributable to the storage layer.</li>
 * </ul>
 */
@Component
public class ProtoMessageCodec {

    /**
     * Maps a message entity onto its Protobuf representation.
     *
     * @param message the entity to convert, must not be {@code null}
     * @return the equivalent Protobuf message, never {@code null}
     * @throws IllegalArgumentException if {@code message} is {@code null} or its role is
     *                                  {@code null}
     */
    public ChatMessage toProto(ChatMessageDO message) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        if (message.getRole() == null) {
            throw new IllegalArgumentException("message role must not be null");
        }
        return ChatMessage.newBuilder()
                .setMessageId(nullToEmpty(message.getMessageId()))
                .setSessionId(nullToEmpty(message.getSessionId()))
                .setTenantId(nullToEmpty(message.getTenantId()))
                .setDeptId(nullToEmpty(message.getDeptId()))
                .setPatientId(nullToEmpty(message.getPatientId()))
                .setRoleValue(message.getRole().getCode())
                .setContent(nullToEmpty(message.getContent()))
                .setTokenCount(message.getTokenCount())
                .setMasked(message.isMasked())
                .setCreatedAt(message.getCreatedAt())
                .putAllMetadata(sanitizeMetadata(message.getMetadata()))
                .build();
    }

    /**
     * Maps a Protobuf message back onto a message entity.
     *
     * @param proto the Protobuf message read from storage, must not be {@code null}
     * @return the equivalent entity, never {@code null}
     * @throws IllegalArgumentException if {@code proto} is {@code null}
     * @throws BizException             if the role code is unknown or the required identity
     *                                  fields are blank
     */
    public ChatMessageDO fromProto(ChatMessage proto) {
        if (proto == null) {
            throw new IllegalArgumentException("proto must not be null");
        }
        RoleType role;
        try {
            role = RoleType.fromCode(proto.getRoleValue());
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "stored chat message carries an unknown role code: " + proto.getRoleValue(), ex);
        }
        try {
            return ChatMessageDO.builder()
                    .messageId(proto.getMessageId())
                    .sessionId(proto.getSessionId())
                    .tenantId(proto.getTenantId())
                    .deptId(proto.getDeptId())
                    .patientId(proto.getPatientId())
                    .role(role)
                    .content(proto.getContent())
                    .tokenCount(proto.getTokenCount())
                    .masked(proto.getMasked())
                    .createdAt(proto.getCreatedAt())
                    .metadata(new LinkedHashMap<>(proto.getMetadataMap()))
                    .build();
        } catch (IllegalStateException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "stored chat message misses required identity fields", ex);
        }
    }

    /**
     * Serializes a message entity to the Protobuf binary payload persisted in MySQL and Redis.
     *
     * @param message the entity to serialize, must not be {@code null}
     * @return the Protobuf binary payload, never {@code null}
     * @throws IllegalArgumentException if {@code message} is {@code null} or its role is
     *                                  {@code null}
     */
    public byte[] encodeMessage(ChatMessageDO message) {
        return toProto(message).toByteArray();
    }

    /**
     * Deserializes a Protobuf binary payload back into a message entity.
     *
     * @param payload the bytes read from storage, must not be {@code null}
     * @return the decoded entity, never {@code null}
     * @throws IllegalArgumentException if {@code payload} is {@code null}
     * @throws BizException             if the payload is malformed or semantically incomplete
     */
    public ChatMessageDO decodeMessage(byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        ChatMessage proto;
        try {
            proto = ChatMessage.parseFrom(payload);
        } catch (InvalidProtocolBufferException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "malformed chat message payload of " + payload.length + " bytes", ex);
        }
        return fromProto(proto);
    }

    /**
     * Maps a session entity onto its Protobuf representation.
     *
     * @param session the entity to convert, must not be {@code null}
     * @return the equivalent Protobuf session, never {@code null}
     * @throws IllegalArgumentException if {@code session} is {@code null}
     */
    public ChatSession toProto(ChatSessionDO session) {
        if (session == null) {
            throw new IllegalArgumentException("session must not be null");
        }
        SessionStatus status = session.getStatus() == null ? SessionStatus.ACTIVE : session.getStatus();
        return ChatSession.newBuilder()
                .setSessionId(nullToEmpty(session.getSessionId()))
                .setTenantId(nullToEmpty(session.getTenantId()))
                .setDeptId(nullToEmpty(session.getDeptId()))
                .setPatientId(nullToEmpty(session.getPatientId()))
                .setStatusValue(status.getCode())
                .setTitle(nullToEmpty(session.getTitle()))
                .setCreatedAt(session.getCreatedAt())
                .setUpdatedAt(session.getUpdatedAt())
                .build();
    }

    /**
     * Maps a Protobuf session back onto a session entity.
     *
     * @param proto the Protobuf session read from storage, must not be {@code null}
     * @return the equivalent entity, never {@code null}
     * @throws IllegalArgumentException if {@code proto} is {@code null}
     * @throws BizException             if the status code is unknown or the session id is blank
     */
    public ChatSessionDO fromProto(ChatSession proto) {
        if (proto == null) {
            throw new IllegalArgumentException("proto must not be null");
        }
        if (proto.getSessionId().isBlank()) {
            throw new BizException(ErrorCode.STORAGE_ERROR, "stored chat session misses its session id");
        }
        SessionStatus status;
        try {
            status = SessionStatus.fromCode(proto.getStatusValue());
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "stored chat session carries an unknown status code: " + proto.getStatusValue(), ex);
        }
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId(proto.getSessionId());
        session.setTenantId(proto.getTenantId());
        session.setDeptId(proto.getDeptId());
        session.setPatientId(proto.getPatientId());
        session.setStatus(status);
        session.setTitle(proto.getTitle());
        session.setCreatedAt(proto.getCreatedAt());
        session.setUpdatedAt(proto.getUpdatedAt());
        return session;
    }

    /**
     * Serializes a session entity to its Protobuf binary payload.
     *
     * @param session the entity to serialize, must not be {@code null}
     * @return the Protobuf binary payload, never {@code null}
     * @throws IllegalArgumentException if {@code session} is {@code null}
     */
    public byte[] encodeSession(ChatSessionDO session) {
        return toProto(session).toByteArray();
    }

    /**
     * Deserializes a Protobuf binary payload back into a session entity.
     *
     * @param payload the bytes read from storage, must not be {@code null}
     * @return the decoded entity, never {@code null}
     * @throws IllegalArgumentException if {@code payload} is {@code null}
     * @throws BizException             if the payload is malformed or semantically incomplete
     */
    public ChatSessionDO decodeSession(byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        ChatSession proto;
        try {
            proto = ChatSession.parseFrom(payload);
        } catch (InvalidProtocolBufferException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "malformed chat session payload of " + payload.length + " bytes", ex);
        }
        return fromProto(proto);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Drops entries a Protobuf map cannot hold, so a partially populated metadata map never
     * aborts an otherwise valid encode.
     */
    private static Map<String, String> sanitizeMetadata(Map<String, String> metadata) {
        Map<String, String> sanitized = new LinkedHashMap<>();
        if (metadata == null) {
            return sanitized;
        }
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                sanitized.put(entry.getKey(), entry.getValue());
            }
        }
        return sanitized;
    }
}
