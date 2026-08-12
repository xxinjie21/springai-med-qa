package com.med.qa.controller.dto;

import java.util.List;

/**
 * Result of a RAG retrieval-preview call.
 *
 * @param total      number of documents returned by the scoped search
 * @param documents  the matched documents, best first, never {@code null}
 */
public record RagSearchPreviewResponse(int total, List<RagSearchPreviewItem> documents) {
}
