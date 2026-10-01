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
 * <h2>The isolation tags are claims, not authority (D42)</h2>
 * <p>{@code tenantId} / {@code deptId} are <strong>optional</strong> and grant nothing: the document is
 * indexed in the scope of the authenticated principal the API key resolved to, and these fields are only
 * cross-checked against it by {@link com.med.qa.security.RequestIdentityGuard}. Naming another tenant or
 * department is refused with {@code 403} instead of indexing the document there — which is exactly the
 * escalation the pre-D42 controller allowed, because it took the tags straight from the body.</p>
 *
 * <p>{@code patientId} keeps a real job: a staff principal may index a document for any patient of its
 * own department (a discharge summary belongs to one patient), while omitting it produces a
 * department-wide document — a guideline or protocol shared by the whole department.</p>
 *
 * @param id         stable document identifier for idempotent re-ingestion, or {@code null} to let
 *                   the store generate one; must not be blank when present
 * @param text       document content, must not be blank
 * @param tenantId   hospital / tenant identifier the caller claims to act for, or {@code null} to use the
 *                   principal's own
 * @param deptId     department identifier the caller claims to act for, or {@code null} to use the
 *                   principal's own
 * @param patientId  patient the document belongs to, or {@code null} for a department-wide document
 * @param metadata   descriptive attributes (title, source, revision, ...), or {@code null}; values
 *                   must be strings, numbers or booleans and must not override the isolation tags
 */
public record RagIngestItem(@Schema(description = "stable id for idempotent re-ingestion, or null",
            example = "doc-htn-guideline") @Nullable String id,
                            @Schema(description = "document content, indexed verbatim",
            example = "Patients on ACE inhibitors should be monitored for hyperkalemia.") String text,
                            @Schema(description = "tenant the caller claims to act for; optional, must "
                                    + "match the API key", example = "t-1001") @Nullable String tenantId,
                            @Schema(description = "department the caller claims to act for; optional, must "
                                    + "match the API key", example = "dept-cardio") @Nullable String deptId,
                            @Schema(description = "patient the document belongs to, or null for "
                                    + "department-wide", example = "pat-7731") @Nullable String patientId,
                            @Schema(description = "descriptive attributes (title, source, revision, ...)",
            example = "{\"title\":\"Hypertension Guideline\",\"revision\":\"2025-04\"}")
            @Nullable Map<String, Object> metadata) {
}
