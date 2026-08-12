package com.med.qa.controller.dto;

import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * One medical document submitted through the RAG admin ingest endpoint.
 *
 * <p>Mirrors the internal {@code MedDocumentRequest}: the text is taken verbatim, the
 * {@code tenantId} / {@code deptId} / {@code patientId} triple becomes the isolation scope, and the
 * optional {@code metadata} carries free-form descriptive attributes. The controller translates each
 * item into a {@code MedDocumentRequest}; no text preprocessing happens here or downstream.</p>
 *
 * @param id         stable document identifier for idempotent re-ingestion, or {@code null} to let
 *                   the store generate one; must not be blank when present
 * @param text       document content, must not be blank
 * @param tenantId   hospital / tenant identifier, must not be blank
 * @param deptId     department identifier, must not be blank
 * @param patientId  patient identifier, or {@code null} for a department-wide document
 * @param metadata   descriptive attributes (title, source, revision, ...), or {@code null}; values
 *                   must be strings, numbers or booleans and must not override the isolation tags
 */
public record RagIngestItem(@Nullable String id,
                            String text,
                            String tenantId,
                            String deptId,
                            @Nullable String patientId,
                            @Nullable Map<String, Object> metadata) {
}
