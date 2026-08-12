package com.med.qa.controller.dto;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * Payload of the RAG admin delete endpoint.
 *
 * <p>Deletion targets documents either by their store identifiers or by their isolation scope. At
 * least one of the two must be supplied; when both are present the identifiers take precedence.</p>
 *
 * @param ids       store identifiers of the documents to delete, or {@code null}
 * @param tenantId  hospital / tenant identifier of the scope to delete, or {@code null}
 * @param deptId    department identifier of the scope to delete, or {@code null}
 * @param patientId patient identifier of the scope to delete, or {@code null} for a department-wide
 *                  scope
 */
public record RagDeleteRequest(@Nullable List<String> ids,
                               @Nullable String tenantId,
                               @Nullable String deptId,
                               @Nullable String patientId) {
}
