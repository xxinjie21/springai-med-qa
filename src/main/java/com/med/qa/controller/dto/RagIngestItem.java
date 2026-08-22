package com.med.qa.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
public record RagIngestItem(@Schema(description = "stable id for idempotent re-ingestion, or null",
            example = "doc-htn-guideline") @Nullable String id,
                            @Schema(description = "document content, indexed verbatim",
            example = "Patients on ACE inhibitors should be monitored for hyperkalemia.") String text,
                            @Schema(description = "hospital / tenant identifier", example = "t-1001")
            String tenantId,
                            @Schema(description = "department identifier", example = "dept-cardio")
            String deptId,
                            @Schema(description = "patient identifier, or null for department-wide",
            example = "pat-7731") @Nullable String patientId,
                            @Schema(description = "descriptive attributes (title, source, revision, ...)",
            example = "{\"title\":\"Hypertension Guideline\",\"revision\":\"2025-04\"}")
            @Nullable Map<String, Object> metadata) {
}
