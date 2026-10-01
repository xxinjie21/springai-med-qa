package com.med.qa.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Result of a RAG admin delete call (D42).
 *
 * <p>Deletion is always scope-based now, so the receipt names the isolation scope that was removed
 * instead of a list of document identifiers. That is the whole point of the change: a caller can only
 * ever state <em>whose</em> documents to drop, and the scope it sees echoed back is the effective scope
 * derived from its API key — not the one it typed into the body.</p>
 *
 * @param scope         canonical rendering of the deleted isolation scope
 *                      ({@code tenantId} / {@code deptId} / {@code patientId}, where a department-wide
 *                      scope shows the reserved shared patient tag)
 * @param patientScoped {@code true} when only a single patient's documents were removed,
 *                      {@code false} for a department-wide removal
 */
public record RagDeleteResponse(
        @Schema(description = "isolation scope that was deleted",
                example = "MedDocumentScope{tenantId='t-1001', deptId='dept-cardio', "
                        + "patientId='pat-7731'}")
        String scope,
        @Schema(description = "true when only one patient's documents were removed", example = "true")
        boolean patientScoped) {
}
