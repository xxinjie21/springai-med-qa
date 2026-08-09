package com.med.qa.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Guard rails of the medical document ingestion pipeline.
 *
 * <p>Bound from {@code med.rag.ingestion.*}. These are policy limits, not algorithms: embedding
 * batching itself is performed by Spring AI's {@code TokenCountBatchingStrategy} inside the vector
 * store, and similarity search is the store's business. What is configured here is only how much a
 * single ingestion call is allowed to push at the store in one go, so a bulk import cannot pin an
 * unbounded amount of document text in memory nor blow past the embedding endpoint's request
 * limits.</p>
 *
 * <p>Every setter validates its input so a misconfiguration fails at startup.</p>
 */
@ConfigurationProperties(prefix = MedDocumentIngestionProperties.PREFIX)
public class MedDocumentIngestionProperties {

    /** Configuration prefix bound by Spring Boot. */
    public static final String PREFIX = "med.rag.ingestion";

    /** Upper bound accepted for {@link #setBatchSize(int)}, keeping one write request bounded. */
    public static final int MAX_BATCH_SIZE = 1000;

    private int batchSize = 25;

    private int maxContentLength = 20_000;

    private int maxDocumentsPerRequest = 500;

    /**
     * Returns how many documents are handed to the vector store per write call.
     *
     * @return batch size, always {@code >= 1}
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * Sets how many documents are handed to the vector store per write call.
     *
     * @param batchSize documents per write, must be between {@code 1} and {@value #MAX_BATCH_SIZE}
     * @throws IllegalArgumentException if the value is out of range
     */
    public void setBatchSize(int batchSize) {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    PREFIX + ".batch-size must be between 1 and " + MAX_BATCH_SIZE + " but is " + batchSize);
        }
        this.batchSize = batchSize;
    }

    /**
     * Returns the maximum accepted length of a single document, in characters.
     *
     * @return maximum content length, always {@code >= 1}
     */
    public int getMaxContentLength() {
        return maxContentLength;
    }

    /**
     * Sets the maximum accepted length of a single document.
     *
     * <p>A coarse pre-check in front of the embedding endpoint's token limit: rejecting an
     * oversized document before the HTTP call gives the caller a 4xx business error instead of a
     * downstream failure halfway through a batch.</p>
     *
     * @param maxContentLength maximum number of characters, must be {@code >= 1}
     * @throws IllegalArgumentException if the value is not positive
     */
    public void setMaxContentLength(int maxContentLength) {
        if (maxContentLength < 1) {
            throw new IllegalArgumentException(
                    PREFIX + ".max-content-length must be positive but is " + maxContentLength);
        }
        this.maxContentLength = maxContentLength;
    }

    /**
     * Returns how many documents a single ingestion call may carry.
     *
     * @return maximum documents per call, always {@code >= 1}
     */
    public int getMaxDocumentsPerRequest() {
        return maxDocumentsPerRequest;
    }

    /**
     * Sets how many documents a single ingestion call may carry.
     *
     * @param maxDocumentsPerRequest maximum number of documents, must be {@code >= 1}
     * @throws IllegalArgumentException if the value is not positive
     */
    public void setMaxDocumentsPerRequest(int maxDocumentsPerRequest) {
        if (maxDocumentsPerRequest < 1) {
            throw new IllegalArgumentException(
                    PREFIX + ".max-documents-per-request must be positive but is " + maxDocumentsPerRequest);
        }
        this.maxDocumentsPerRequest = maxDocumentsPerRequest;
    }

    @Override
    public String toString() {
        return "MedDocumentIngestionProperties{batchSize=" + batchSize
                + ", maxContentLength=" + maxContentLength
                + ", maxDocumentsPerRequest=" + maxDocumentsPerRequest + '}';
    }
}
