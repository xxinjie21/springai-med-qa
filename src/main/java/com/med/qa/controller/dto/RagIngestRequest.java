package com.med.qa.controller.dto;

import java.util.List;

/**
 * Payload of the RAG admin ingest endpoint.
 *
 * <p>Carries a batch of {@link RagIngestItem} so several documents can be indexed in a single call.
 * Empty or {@code null} batches are rejected by the controller before any document reaches the
 * vector store.</p>
 *
 * @param documents documents to index, must not be {@code null} or empty
 */
public record RagIngestRequest(List<RagIngestItem> documents) {
}
