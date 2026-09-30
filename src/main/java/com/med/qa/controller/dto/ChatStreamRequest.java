package com.med.qa.controller.dto;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

/**
 * Inbound payload of the streaming consultation endpoint.
 *
 * <h2>The identity fields are claims, not authority (D41)</h2>
 * <p>{@code tenant} / {@code dept} / {@code patientId} are <strong>optional</strong> and carry no
 * authority: the consultation runs in the scope of the authenticated principal that the API key
 * resolved to, and these fields are only cross-checked against it by
 * {@link com.med.qa.security.RequestIdentityGuard}. A request that names a tenant, department or
 * patient the caller is not authenticated for is refused with {@code 403}, and a request that names
 * none is perfectly valid — the principal already carries the identity.</p>
 *
 * <p>Before D41 the endpoint derived its scope from these fields alone, so a caller could read and write
 * another patient's transcript by filling in that patient's identifiers. The fields are kept in the
 * payload so existing clients keep working and so an honest client can still state its intent
 * explicitly, but they can no longer grant anything.</p>
 *
 * <h2>What actually drives the turn</h2>
 * <p>{@code session} is the conversation key and is required: it addresses the Redis key
 * {@code med:chat:{tenant}:{dept}:{session}} and the shard {@code med_message_{crc32(session) % 16}}, and
 * it is validated to exist, to belong to the caller and to still accept messages before the model is
 * called. {@code message} is the patient's question and is required. When {@code patientId} is absent
 * from the effective scope the RAG retrieval falls back to department-wide shared documents only;
 * {@code includeSharedDocuments} and {@code topK} are optional overrides of the configured retrieval
 * defaults.</p>
 *
 * <p>The record carries no validation logic of its own; {@link #validate()} centralises the boundary
 * checks so the controller can reject a malformed request with a single, consistent bad-request error
 * before any streaming or model call begins.</p>
 */
public record ChatStreamRequest(
        @Schema(description = "tenant the caller claims to act for; optional, must match the API key",
            example = "t-1001") @Nullable String tenant,
        @Schema(description = "department the caller claims to act for; optional, must match the API key",
            example = "dept-cardio") @Nullable String dept,
        @Schema(description = "consultation session identifier", example = "sess-9f2c1a") String session,
        @Schema(description = "patient the caller claims to act for; optional, must match the API key. "
            + "Staff may name any patient of their own department; a patient may only name itself",
            example = "pat-7731") @Nullable String patientId,
        @Schema(description = "patient's question", example = "Can I take ibuprofen with my ACE inhibitor?")
        String message,
        @Schema(description = "whether department-wide documents take part, or null for default (true)")
        @Nullable Boolean includeSharedDocuments,
        @Schema(description = "retrieval document count, or null for the configured default", example = "4")
        @Nullable Integer topK) {

    /**
     * Rejects a request that is missing the session key or the question text.
     *
     * <p>The identity fields are deliberately not required: they are consistency claims checked against
     * the authenticated principal, so demanding them would suggest the client has a say in its own
     * scope. Only the two fields that carry information the principal cannot supply — which conversation
     * to append to, and what to ask — are mandatory.</p>
     *
     * @throws BizException {@link ErrorCode#BAD_REQUEST} when a required field is blank
     */
    public void validate() {
        if (!StringUtils.hasText(session)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "session must not be blank");
        }
        if (!StringUtils.hasText(message)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "message must not be blank");
        }
    }
}
