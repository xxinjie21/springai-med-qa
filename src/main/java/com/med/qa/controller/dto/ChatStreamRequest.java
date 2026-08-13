package com.med.qa.controller.dto;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

/**
 * Inbound payload of the streaming consultation endpoint.
 *
 * <p>Exactly the identity triple that derives the Redis key {@code med:chat:{tenant}:{dept}:{session}}
 * plus the patient's question. {@code patientId} is optional: when present the RAG retrieval narrows
 * to that patient's own documents together with the department's shared guidelines; when absent the
 * retrieval falls back to department-wide shared documents only. {@code includeSharedDocuments} and
 * {@code topK} are optional overrides of the configured retrieval defaults.</p>
 *
 * <p>The record carries no validation logic of its own; {@link #validate()} centralises the boundary
 * checks so the controller can reject a malformed request with a single, consistent bad-request error
 * before any streaming or model call begins.</p>
 */
public record ChatStreamRequest(
        String tenant,
        String dept,
        String session,
        @Nullable String patientId,
        String message,
        @Nullable Boolean includeSharedDocuments,
        @Nullable Integer topK) {

    /**
     * Rejects a request that is missing the identity triple or the question text.
     *
     * @throws BizException {@link ErrorCode#BAD_REQUEST} when a required field is blank
     */
    public void validate() {
        if (!StringUtils.hasText(tenant)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "tenant must not be blank");
        }
        if (!StringUtils.hasText(dept)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "dept must not be blank");
        }
        if (!StringUtils.hasText(session)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "session must not be blank");
        }
        if (!StringUtils.hasText(message)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "message must not be blank");
        }
    }
}
