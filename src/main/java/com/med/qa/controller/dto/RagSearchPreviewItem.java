package com.med.qa.controller.dto;

import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * One document returned by the RAG retrieval-preview endpoint.
 *
 * @param id        store identifier of the document
 * @param score     relevance score reported by the vector store, or {@code null} when absent
 * @param content   document text; returned verbatim to the administrator for inspection
 * @param metadata  document metadata including the isolation tags, never {@code null}
 */
public record RagSearchPreviewItem(String id,
                                   @Nullable Double score,
                                   String content,
                                   Map<String, Object> metadata) {
}
