package com.med.qa.controller.dto;

import java.util.List;

/**
 * Result of a RAG admin ingest call.
 *
 * @param ingested number of documents that were handed to the vector store
 * @param ids      store-assigned identifiers of the indexed documents, in submission order
 */
public record RagIngestResponse(int ingested, List<String> ids) {
}
