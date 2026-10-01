package com.med.qa.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;

/**
 * Payload of the RAG retrieval-preview endpoint.
 *
 * <p>Describes a tag-scoped similarity search. The query text is embedded verbatim by the store; the
 * documents it may reach are decided solely by the {@code tenantId} / {@code deptId} / {@code patientId}
 * tags, never by inspecting the text. {@code topK} and {@code similarityThreshold} default to the
 * configured guard rails when left {@code null}.</p>
 *
 * <h2>The isolation tags are claims, not authority (D42)</h2>
 * <p>{@code tenantId} / {@code deptId} are <strong>optional</strong> and grant nothing: the search runs
 * in the scope of the authenticated principal the API key resolved to, and these fields are only
 * cross-checked against it by {@link com.med.qa.security.RequestIdentityGuard}. Naming another tenant or
 * department is refused with {@code 403}. Before D42 the body alone decided which department's corpus
 * was previewed, so any staff key could read another department's documents.</p>
 *
 * <p>{@code patientId} still narrows a staff caller to one patient of its own department; a patient
 * principal may only ever name itself, and omitting it for such a caller is refused rather than widened
 * into a department-wide query.</p>
 *
 * @param text                 question or passage to match, must not be blank
 * @param tenantId             hospital / tenant identifier the caller claims to act for, or {@code null}
 *                             to use the principal's own
 * @param deptId               department identifier the caller claims to act for, or {@code null} to use
 *                             the principal's own
 * @param patientId            patient to narrow to, or {@code null} for a department-wide query
 * @param topK                 number of documents to return, or {@code null} for the configured default
 * @param similarityThreshold  minimum similarity within {@code [0, 1]}, or {@code null} for the
 *                             configured default
 * @param includeSharedDocuments whether department-wide documents take part in the retrieval,
 *                               or {@code null} to use the default ({@code true})
 */
public record RagSearchPreviewRequest(
        @Schema(description = "question or passage to match, embedded verbatim", example = "ACE inhibitor monitoring")
        String text,
        @Schema(description = "tenant the caller claims to act for; optional, must match the API key",
                example = "t-1001") @Nullable String tenantId,
        @Schema(description = "department the caller claims to act for; optional, must match the API key",
                example = "dept-cardio") @Nullable String deptId,
        @Schema(description = "patient to narrow to, or null for department-wide", example = "pat-7731")
        @Nullable String patientId,
        @Schema(description = "documents to return, or null for the configured default", example = "5")
        @Nullable Integer topK,
        @Schema(description = "minimum similarity in [0, 1], or null for the configured default",
            example = "0.0") @Nullable Double similarityThreshold,
        @Schema(description = "whether department-wide documents take part, or null for default (true)")
        @Nullable Boolean includeSharedDocuments) {
}
