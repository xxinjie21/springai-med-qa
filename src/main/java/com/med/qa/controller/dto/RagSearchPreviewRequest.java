package com.med.qa.controller.dto;

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
public record RagSearchPreviewRequest(String text,
                                      String tenantId,
                                      String deptId,
                                      @Nullable String patientId,
                                      @Nullable Integer topK,
                                      @Nullable Double similarityThreshold,
                                      @Nullable Boolean includeSharedDocuments) {
}
