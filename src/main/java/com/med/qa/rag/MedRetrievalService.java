package com.med.qa.rag;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Tag-filtered similarity search over the medical RAG corpus.
 *
 * <h2>Responsibility</h2>
 * <p>Turn a {@link MedRetrievalQuery} into an official {@link SearchRequest} — query text, Top-K,
 * similarity threshold and the isolation {@link Filter.Expression} produced by
 * {@link MedRetrievalFilters} — hand it to the official {@link VectorStore}, and translate the
 * failures. Embedding of the query, cosine scoring, Top-K selection and the translation of the
 * filter expression into a native RediSearch query all happen inside the store; this class contains
 * no vector math, no ranking and no text analysis.</p>
 *
 * <h2>Hard constraint: no content analysis</h2>
 * <p>The query text is passed through verbatim and is never logged. Which documents a caller may
 * reach is decided exclusively by the {@code tenant_id} / {@code dept_id} / {@code patient_id} tags
 * stamped at ingestion time, never by inspecting the text of the query or of the corpus.</p>
 *
 * <h2>Fail closed on an isolation breach</h2>
 * <p>Results are re-checked against the scope that asked for them. The store is trusted to apply
 * the filter, but a stale index definition or a document indexed without tags would otherwise
 * surface as another department's record inside a prompt. Such a result aborts the retrieval with
 * {@link ErrorCode#INTERNAL_ERROR} rather than being quietly dropped: a corpus that can return
 * out-of-scope documents is a compliance incident, not a ranking detail.</p>
 *
 * <h2>Failure semantics</h2>
 * <ul>
 *   <li>{@link IllegalArgumentException} — the caller passed {@code null}; a programming error.</li>
 *   <li>{@link BizException} with {@link ErrorCode#BAD_REQUEST} — the query violates a configured
 *       policy limit (text too long, Top-K above {@code med.rag.retrieval.max-top-k}).</li>
 *   <li>{@link BizException} with {@link ErrorCode#LLM_SERVICE_ERROR} — embedding the query
 *       failed.</li>
 *   <li>{@link BizException} with {@link ErrorCode#STORAGE_ERROR} — the index query failed.</li>
 * </ul>
 * <p>Retrieval never degrades to an empty list on failure: an answer generated from silently
 * missing evidence is indistinguishable from one generated from a corpus that truly has nothing.</p>
 */
@Service
public class MedRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(MedRetrievalService.class);

    private final VectorStore vectorStore;

    private final MedRetrievalProperties properties;

    /**
     * Creates the service used by the application context.
     *
     * <p>The vector store is injected {@link Lazy}: it opens a Redis connection and creates the
     * search index on first use, which must not happen during context refresh.</p>
     *
     * @param vectorStore official vector store, must not be {@code null}
     * @param properties  retrieval guard rails, must not be {@code null}
     * @throws IllegalArgumentException if an argument is {@code null}
     * @throws IllegalStateException    if the configured default Top-K exceeds the configured
     *                                  maximum, which would make every default query illegal
     */
    @Autowired
    public MedRetrievalService(@Lazy VectorStore vectorStore, MedRetrievalProperties properties) {
        if (vectorStore == null) {
            throw new IllegalArgumentException("vectorStore must not be null");
        }
        if (properties == null) {
            throw new IllegalArgumentException("properties must not be null");
        }
        if (properties.getTopK() > properties.getMaxTopK()) {
            throw new IllegalStateException(MedRetrievalProperties.PREFIX + ".top-k ("
                    + properties.getTopK() + ") must not exceed " + MedRetrievalProperties.PREFIX
                    + ".max-top-k (" + properties.getMaxTopK() + ')');
        }
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    /**
     * Retrieves the documents of a scope that best match a question, using the configured defaults.
     *
     * @param text  question or passage to match, must not be blank
     * @param scope tags the caller is entitled to, must not be {@code null}
     * @return the matching documents, best first, never {@code null} and possibly empty
     * @throws IllegalArgumentException if the text is blank or the scope is {@code null}
     * @throws BizException             see the class documentation for the error codes
     */
    public List<Document> search(String text, MedDocumentScope scope) {
        return search(MedRetrievalQuery.of(text, scope));
    }

    /**
     * Retrieves the documents of a scope that best match a query.
     *
     * @param query the retrieval to run, must not be {@code null}
     * @return the matching documents, best first, never {@code null} and possibly empty
     * @throws IllegalArgumentException if {@code query} is {@code null}
     * @throws BizException             {@link ErrorCode#BAD_REQUEST} when the query violates a
     *                                  policy limit, {@link ErrorCode#LLM_SERVICE_ERROR} when
     *                                  embedding fails, {@link ErrorCode#STORAGE_ERROR} when the
     *                                  index query fails, {@link ErrorCode#INTERNAL_ERROR} when a
     *                                  returned document falls outside the requested scope
     */
    public List<Document> search(MedRetrievalQuery query) {
        SearchRequest request = toSearchRequest(query);
        List<Document> documents;
        try {
            documents = vectorStore.similaritySearch(request);
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            ErrorCode errorCode = MedDocumentService.classifyFailure(ex);
            log.error("similarity search failed for scope {} (topK={})",
                    query.getScope(), request.getTopK(), ex);
            throw new BizException(errorCode,
                    "failed to retrieve documents for " + query.getScope(), ex);
        }
        if (documents == null) {
            log.warn("vector store returned no result object for scope {}", query.getScope());
            return List.of();
        }
        enforceScope(documents, query);
        log.debug("retrieved {} document(s) for scope {} (topK={})",
                documents.size(), query.getScope(), request.getTopK());
        return documents;
    }

    /**
     * Assembles the official search request of a query without contacting the vector store.
     *
     * <p>Exposed so the RAG advisor of the next iteration can reuse exactly the same request
     * construction, and so the wiring can be asserted offline.</p>
     *
     * @param query the retrieval to describe, must not be {@code null}
     * @return the search request handed to the store, never {@code null}
     * @throws IllegalArgumentException if {@code query} is {@code null}
     * @throws BizException             {@link ErrorCode#BAD_REQUEST} if the query text is longer
     *                                  than {@code med.rag.retrieval.max-query-length} or the
     *                                  requested Top-K exceeds
     *                                  {@code med.rag.retrieval.max-top-k}
     */
    public SearchRequest toSearchRequest(MedRetrievalQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        int maxQueryLength = properties.getMaxQueryLength();
        if (query.getText().length() > maxQueryLength) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "retrieval query must not exceed " + maxQueryLength + " characters but has "
                            + query.getText().length());
        }
        return SearchRequest.builder()
                .query(query.getText())
                .topK(resolveTopK(query.getTopK()))
                .similarityThreshold(resolveSimilarityThreshold(query.getSimilarityThreshold()))
                .filterExpression(toFilterExpression(query))
                .build();
    }

    /**
     * Builds the complete filter expression of a query: isolation tags conjoined with the optional
     * caller filter.
     *
     * @param query the retrieval to describe, must not be {@code null}
     * @return the filter expression, never {@code null}
     * @throws IllegalArgumentException if {@code query} is {@code null}
     */
    public Filter.Expression toFilterExpression(MedRetrievalQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        Filter.Expression isolation =
                MedRetrievalFilters.scope(query.getScope(), query.isIncludeSharedDocuments());
        return MedRetrievalFilters.and(isolation, query.getAdditionalFilter());
    }

    /**
     * Resolves the effective Top-K of a query against the configured guard rails.
     *
     * @param requested caller preference, or {@code null} to use {@code med.rag.retrieval.top-k}
     * @return the number of documents to retrieve, always between {@code 1} and
     *         {@code med.rag.retrieval.max-top-k}
     * @throws BizException {@link ErrorCode#BAD_REQUEST} if the caller asked for more documents than
     *                      allowed, or for a non-positive count
     */
    public int resolveTopK(@Nullable Integer requested) {
        if (requested == null) {
            return properties.getTopK();
        }
        if (requested < 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "topK must be positive but is " + requested);
        }
        if (requested > properties.getMaxTopK()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "topK must not exceed " + properties.getMaxTopK() + " but is " + requested);
        }
        return requested;
    }

    /**
     * Resolves the effective similarity threshold of a query.
     *
     * @param requested caller preference, or {@code null} to use
     *                  {@code med.rag.retrieval.similarity-threshold}
     * @return the threshold applied by the store, always within {@code [0, 1]}
     * @throws BizException {@link ErrorCode#BAD_REQUEST} if the caller asked for a threshold outside
     *                      the unit interval
     */
    public double resolveSimilarityThreshold(@Nullable Double requested) {
        if (requested == null) {
            return properties.getSimilarityThreshold();
        }
        if (Double.isNaN(requested) || requested < 0.0d || requested > 1.0d) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "similarityThreshold must be within [0, 1] but is " + requested);
        }
        return requested;
    }

    /**
     * Aborts the retrieval if the store returned a document the scope is not entitled to.
     *
     * @param documents documents returned by the store
     * @param query     retrieval that produced them
     * @throws BizException {@link ErrorCode#INTERNAL_ERROR} on the first out-of-scope document
     */
    private void enforceScope(List<Document> documents, MedRetrievalQuery query) {
        for (Document document : documents) {
            if (document == null
                    || !MedRetrievalFilters.matches(document.getMetadata(), query.getScope(),
                    query.isIncludeSharedDocuments())) {
                String documentId = document != null ? document.getId() : null;
                log.error("vector index returned document '{}' outside scope {}; aborting retrieval",
                        documentId, query.getScope());
                throw new BizException(ErrorCode.INTERNAL_ERROR,
                        "vector index returned document '" + documentId + "' outside the requested scope");
            }
        }
    }
}
