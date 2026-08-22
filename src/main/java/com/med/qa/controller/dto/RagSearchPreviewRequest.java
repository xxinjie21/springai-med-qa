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
 * @param text                 question or passage to match, must not be blank
 * @param tenantId             hospital / tenant identifier, must not be blank
 * @param deptId               department identifier, must not be blank
 * @param patientId            patient identifier, or {@code null} for a department-wide query
 * @param topK                 number of documents to return, or {@code null} for the configured default
 * @param similarityThreshold  minimum similarity within {@code [0, 1]}, or {@code null} for the
 *                             configured default
 * @param includeSharedDocuments whether department-wide documents take part in the retrieval,
 *                               or {@code null} to use the default ({@code true})
 */
public record RagSearchPreviewRequest(
        @Schema(description = "question or passage to match, embedded verbatim", example = "ACE inhibitor monitoring")
        String text,
        @Schema(description = "hospital / tenant identifier", example = "t-1001") String tenantId,
        @Schema(description = "department identifier", example = "dept-cardio") String deptId,
        @Schema(description = "patient identifier, or null for department-wide", example = "pat-7731")
        @Nullable String patientId,
        @Schema(description = "documents to return, or null for the configured default", example = "5")
        @Nullable Integer topK,
        @Schema(description = "minimum similarity in [0, 1], or null for the configured default",
            example = "0.0") @Nullable Double similarityThreshold,
        @Schema(description = "whether department-wide documents take part, or null for default (true)")
        @Nullable Boolean includeSharedDocuments) {
}
