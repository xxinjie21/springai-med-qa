package com.med.qa.rag;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ingestion of medical documents into the RAG vector store.
 *
 * <h2>Responsibility</h2>
 * <p>Turn a {@link MedDocumentRequest} into a Spring AI {@link Document} carrying the
 * tenant / department / patient tags of its {@link MedDocumentScope}, then hand the documents to
 * the official {@link VectorStore} in bounded batches. Embedding, vector encoding, index writes and
 * Top-K search are entirely the store's business; this class contains no vector math, no chunking
 * and no retrieval logic.</p>
 *
 * <h2>Hard constraint: no content analysis</h2>
 * <p>The document text is passed through verbatim. It is never split, normalized, tokenized or
 * scanned for clinical entities, and it is never written to a log. Retrieval is later narrowed
 * purely by the metadata tags declared here, through the official
 * {@code FilterExpressionBuilder}.</p>
 *
 * <h2>Failure semantics</h2>
 * <ul>
 *   <li>{@link IllegalArgumentException} — the caller passed {@code null} where the contract forbids
 *       it; a programming error, never a business outcome.</li>
 *   <li>{@link BizException} with {@link ErrorCode#BAD_REQUEST} — the submitted data violates a
 *       configured policy limit (document too long, too many documents, metadata key colliding with
 *       a reserved JSON field of the index).</li>
 *   <li>{@link BizException} with {@link ErrorCode#LLM_SERVICE_ERROR} — the embedding endpoint
 *       failed; Spring AI reports these as {@link TransientAiException} /
 *       {@link NonTransientAiException}.</li>
 *   <li>{@link BizException} with {@link ErrorCode#STORAGE_ERROR} — the index write failed.</li>
 * </ul>
 * <p>Ingestion never degrades silently: a document that was not indexed would be invisible to
 * retrieval, and a doctor would get an answer built on an incomplete corpus without any signal.</p>
 */
@Service
public class MedDocumentService {

    /** Metadata key holding the ingestion timestamp, in epoch milliseconds. */
    public static final String METADATA_INGESTED_AT = "ingested_at";

    private static final Logger log = LoggerFactory.getLogger(MedDocumentService.class);

    private static final int MAX_CAUSE_DEPTH = 16;

    private final VectorStore vectorStore;

    private final MedVectorStoreProperties storeProperties;

    private final MedDocumentIngestionProperties ingestionProperties;

    private final Clock clock;

    /**
     * Creates the service used by the application context.
     *
     * <p>The vector store is injected {@link Lazy}: it opens a Redis connection and creates the
     * search index on first use, which must not happen during context refresh.</p>
     *
     * @param vectorStore         official vector store, must not be {@code null}
     * @param storeProperties     index topology, used to reject metadata keys that would collide
     *                            with the content or embedding JSON fields, must not be {@code null}
     * @param ingestionProperties ingestion guard rails, must not be {@code null}
     */
    @Autowired
    public MedDocumentService(@Lazy VectorStore vectorStore,
                              MedVectorStoreProperties storeProperties,
                              MedDocumentIngestionProperties ingestionProperties) {
        this(vectorStore, storeProperties, ingestionProperties, Clock.systemUTC());
    }

    /**
     * Creates the service with an explicit clock, so ingestion timestamps are deterministic in tests.
     *
     * @param vectorStore         official vector store, must not be {@code null}
     * @param storeProperties     index topology, must not be {@code null}
     * @param ingestionProperties ingestion guard rails, must not be {@code null}
     * @param clock               clock stamping {@value #METADATA_INGESTED_AT}, must not be
     *                            {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public MedDocumentService(VectorStore vectorStore,
                              MedVectorStoreProperties storeProperties,
                              MedDocumentIngestionProperties ingestionProperties,
                              Clock clock) {
        if (vectorStore == null) {
            throw new IllegalArgumentException("vectorStore must not be null");
        }
        if (storeProperties == null) {
            throw new IllegalArgumentException("storeProperties must not be null");
        }
        if (ingestionProperties == null) {
            throw new IllegalArgumentException("ingestionProperties must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.vectorStore = vectorStore;
        this.storeProperties = storeProperties;
        this.ingestionProperties = ingestionProperties;
        this.clock = clock;
    }

    /**
     * Indexes a single medical document.
     *
     * @param request document to index, must not be {@code null}
     * @return the identifier the document is stored under, never {@code null}
     * @throws IllegalArgumentException if {@code request} is {@code null}
     * @throws BizException             {@link ErrorCode#BAD_REQUEST} when the document violates a
     *                                  policy limit, {@link ErrorCode#LLM_SERVICE_ERROR} when
     *                                  embedding fails, {@link ErrorCode#STORAGE_ERROR} when the
     *                                  index write fails
     */
    public String ingest(MedDocumentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        return ingestAll(List.of(request)).get(0);
    }

    /**
     * Indexes several medical documents, writing them in batches of the configured size.
     *
     * <p>An empty list is a no-op: the vector store is not contacted at all, so a bulk import with
     * nothing to do never triggers index creation.</p>
     *
     * @param requests documents to index, must not be {@code null} and must not contain
     *                 {@code null} entries
     * @return the identifiers of the indexed documents, in submission order, never {@code null}
     * @throws IllegalArgumentException if {@code requests} is {@code null} or holds a {@code null}
     *                                  entry
     * @throws BizException             {@link ErrorCode#BAD_REQUEST} when the call exceeds
     *                                  {@code med.rag.ingestion.max-documents-per-request} or a
     *                                  document violates a policy limit,
     *                                  {@link ErrorCode#LLM_SERVICE_ERROR} when embedding fails,
     *                                  {@link ErrorCode#STORAGE_ERROR} when the index write fails
     */
    public List<String> ingestAll(List<MedDocumentRequest> requests) {
        List<Document> documents = toDocuments(requests);
        if (documents.isEmpty()) {
            return List.of();
        }

        int batchSize = ingestionProperties.getBatchSize();
        List<String> ids = new ArrayList<>(documents.size());
        for (int from = 0; from < documents.size(); from += batchSize) {
            int to = Math.min(from + batchSize, documents.size());
            List<Document> batch = documents.subList(from, to);
            writeBatch(batch, from);
            for (Document document : batch) {
                ids.add(document.getId());
            }
        }
        log.debug("indexed {} medical document(s) into vector index '{}'",
                ids.size(), storeProperties.getIndexName());
        return ids;
    }

    /**
     * Converts ingestion requests into Spring AI documents without touching the vector store.
     *
     * @param requests documents to convert, must not be {@code null} and must not contain
     *                 {@code null} entries
     * @return the converted documents in submission order, never {@code null}
     * @throws IllegalArgumentException if {@code requests} is {@code null} or holds a {@code null}
     *                                  entry
     * @throws BizException             {@link ErrorCode#BAD_REQUEST} when the call carries more
     *                                  documents than allowed or a document violates a policy limit
     */
    public List<Document> toDocuments(List<MedDocumentRequest> requests) {
        if (requests == null) {
            throw new IllegalArgumentException("requests must not be null");
        }
        int maxDocuments = ingestionProperties.getMaxDocumentsPerRequest();
        if (requests.size() > maxDocuments) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "at most " + maxDocuments + " documents may be ingested per call but "
                            + requests.size() + " were submitted");
        }
        List<Document> documents = new ArrayList<>(requests.size());
        for (MedDocumentRequest request : requests) {
            if (request == null) {
                throw new IllegalArgumentException("requests must not contain null entries");
            }
            documents.add(toDocument(request));
        }
        return documents;
    }

    /**
     * Converts one ingestion request into the {@link Document} that will be indexed.
     *
     * <p>Metadata is assembled in a stable order: caller attributes first, then the ingestion
     * timestamp, then the isolation tags — which are written last so they can never be shadowed.</p>
     *
     * @param request document to convert, must not be {@code null}
     * @return the document to hand to the vector store, never {@code null}
     * @throws IllegalArgumentException if {@code request} is {@code null}
     * @throws BizException             {@link ErrorCode#BAD_REQUEST} when the text is longer than
     *                                  {@code med.rag.ingestion.max-content-length} or a metadata
     *                                  key collides with the content / embedding field of the index
     */
    public Document toDocument(MedDocumentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        int maxContentLength = ingestionProperties.getMaxContentLength();
        if (request.getText().length() > maxContentLength) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "document text must not exceed " + maxContentLength + " characters but has "
                            + request.getText().length());
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : request.getMetadata().entrySet()) {
            rejectReservedField(entry.getKey());
            metadata.put(entry.getKey(), entry.getValue());
        }
        metadata.put(METADATA_INGESTED_AT, clock.millis());
        metadata.putAll(request.getScope().toMetadata());

        Document.Builder builder = Document.builder().text(request.getText()).metadata(metadata);
        if (request.getId() != null) {
            builder.id(request.getId());
        }
        return builder.build();
    }

    /**
     * Maps a vector store failure onto the business error code the caller should see.
     *
     * <p>Spring AI wraps every embedding endpoint failure into {@link TransientAiException} or
     * {@link NonTransientAiException}; anything else that escapes {@code VectorStore#add} comes from
     * the index write itself. Distinguishing the two matters operationally: one points at the model
     * gateway, the other at Redis.</p>
     *
     * @param failure exception thrown by the vector store, may be {@code null}
     * @return {@link ErrorCode#LLM_SERVICE_ERROR} when the embedding endpoint failed, otherwise
     *         {@link ErrorCode#STORAGE_ERROR}
     */
    public static ErrorCode classifyFailure(@Nullable Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof TransientAiException || current instanceof NonTransientAiException) {
                return ErrorCode.LLM_SERVICE_ERROR;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return ErrorCode.STORAGE_ERROR;
    }

    /**
     * Rejects a metadata key that would collide with a JSON field owned by the index.
     *
     * <p>{@code RedisVectorStore} stores the document text and its embedding as plain attributes of
     * the same JSON object as the metadata. A metadata entry named like one of them would overwrite
     * the indexed content or the vector, corrupting retrieval silently.</p>
     *
     * @param key caller-supplied metadata key
     * @throws BizException {@link ErrorCode#BAD_REQUEST} if the key is reserved by the index
     */
    private void rejectReservedField(String key) {
        if (storeProperties.getContentFieldName().equals(key)
                || storeProperties.getEmbeddingFieldName().equals(key)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "document metadata must not use '" + key + "', which is reserved by the vector index");
        }
    }

    private void writeBatch(List<Document> batch, int offset) {
        try {
            vectorStore.add(List.copyOf(batch));
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            ErrorCode errorCode = classifyFailure(ex);
            log.error("failed to index {} medical document(s) at offset {} into index '{}'",
                    batch.size(), offset, storeProperties.getIndexName(), ex);
            throw new BizException(errorCode,
                    "failed to index " + batch.size() + " document(s) at offset " + offset
                            + " into vector index '" + storeProperties.getIndexName() + "'", ex);
        }
    }
}
