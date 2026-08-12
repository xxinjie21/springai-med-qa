package com.med.qa.controller.dto;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * Result of a RAG admin delete call.
 *
 * @param byId        {@code true} when the deletion was performed by document identifiers
 * @param ids         the identifiers that were deleted, or {@code null} for a scope-based deletion
 * @param scope       the isolation scope that was deleted, or {@code null} for an id-based deletion
 */
public record RagDeleteResponse(boolean byId, @Nullable List<String> ids, @Nullable String scope) {
}
