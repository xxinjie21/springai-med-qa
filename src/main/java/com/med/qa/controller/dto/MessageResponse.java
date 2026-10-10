package com.med.qa.controller.dto;

import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.enums.RoleType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-side view of one stored chat message, shaped for the transcript API (D49).
 *
 * <p>The DTO mirrors the unified storage contract (ROADMAP section 4) field for field: the numeric
 * {@link RoleType} is surfaced as its enum name so a client never has to hard-code the codes, and
 * {@code createdAt} stays in epoch milliseconds exactly as it is persisted. The {@code masked} flag is
 * carried through unchanged — it records whether the stored message was already desensitized at
 * ingestion time, which a reader has to be able to see.</p>
 *
 * <h2>Why the content is not desensitized here</h2>
 * <p>The privacy layer ({@code @Desensitize}, D24) masks contact-shaped fields — phone numbers, id
 * cards, medical-record numbers. The clinical text of a message <em>is</em> the record, and the caller
 * has already been authorized for it (patient ownership via {@code PatientAccessGuard}, department via
 * {@code @RequireDept}); masking it here would destroy the very data the endpoint exists to return
 * while protecting nothing.</p>
 *
 * @param messageId  storage-spec message id (UUIDv7)
 * @param sessionId  owning session id
 * @param role       author role: PATIENT, DOCTOR, ASSISTANT or SYSTEM
 * @param content    message text as stored
 * @param tokenCount token count recorded at write time, or {@code 0} when unknown
 * @param masked     whether the stored message was already desensitized
 * @param createdAt  creation time as epoch milliseconds
 * @param metadata   storage-spec metadata map, possibly empty, never {@code null}
 */
public record MessageResponse(
        @Schema(description = "message identifier (UUIDv7)", example = "018f2c1a-...")
        String messageId,
        @Schema(description = "owning session identifier", example = "sess-9f2c1a")
        String sessionId,
        @Schema(description = "author role: PATIENT, DOCTOR, ASSISTANT or SYSTEM")
        RoleType role,
        @Schema(description = "message text as stored")
        String content,
        @Schema(description = "token count recorded at write time", example = "42")
        int tokenCount,
        @Schema(description = "whether the stored message was already desensitized")
        boolean masked,
        @Schema(description = "creation time, epoch millis", example = "1718000000000")
        long createdAt,
        @Schema(description = "storage-spec metadata map, possibly empty")
        Map<String, String> metadata) {

    /**
     * Builds the API view of a stored message.
     *
     * @param message the message entity, must not be {@code null}
     * @return the API view, never {@code null}
     * @throws IllegalArgumentException if {@code message} is {@code null}
     */
    public static MessageResponse from(ChatMessageDO message) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        Map<String, String> metadata = message.getMetadata();
        return new MessageResponse(
                message.getMessageId(),
                message.getSessionId(),
                message.getRole(),
                message.getContent(),
                message.getTokenCount(),
                message.isMasked(),
                message.getCreatedAt(),
                copyMetadata(metadata));
    }

    /**
     * Copies the metadata map defensively, dropping entries a JSON map cannot carry.
     *
     * <p>{@code Map.copyOf} rejects a {@code null} key or value outright, and a metadata map that
     * arrived from a heterogeneous writer is not guaranteed to be free of them — dropping the two
     * fields is the same normalization {@code ProtoMessageCodec} applies when it encodes a message,
     * so the read view cannot fail where the write path succeeded.</p>
     *
     * @param metadata the stored metadata map, or {@code null}
     * @return an immutable copy without {@code null} keys or values, never {@code null}
     */
    private static Map<String, String> copyMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, String> sanitized = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (key != null && value != null) {
                sanitized.put(key, value);
            }
        });
        return Map.copyOf(sanitized);
    }
}
