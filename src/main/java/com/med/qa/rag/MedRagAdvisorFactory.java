package com.med.qa.rag;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

/**
 * Assembles the official Spring AI {@link QuestionAnswerAdvisor} scoped to a caller's isolation
 * {@link MedDocumentScope}.
 *
 * <h2>Responsibility</h2>
 * <p>One consultation turn is entitled to exactly one tenant / department / patient slice of the RAG
 * corpus. This factory turns that slice into a {@link QuestionAnswerAdvisor} whose {@link SearchRequest}
 * carries the same isolation {@link Filter.Expression} the retrieval service already uses, so the
 * advisor and the standalone retrieval (D16) enforce identical data boundaries. The advisor itself is
 * pure Spring AI: it embeds the question, asks the official store for the most similar documents, and
 * stitches them into the prompt. Nothing here computes similarity, selects Top-K or translates the
 * filter expression — those are the store's job.</p>
 *
 * <h2>Why an advisor per scope rather than one shared advisor</h2>
 * <p>The isolation filter is part of the advisor's {@link SearchRequest}. Building a fresh advisor per
 * consultation (cheap: it only holds a reference to the store) keeps the filter expression dynamic and
 * correct — there is no risk of one patient's scope leaking into another's request. The
 * {@code QuestionAnswerAdvisor} always overrides the request query with the user's question at runtime,
 * so the query text carried by the {@link SearchRequest} is irrelevant; only the Top-K, the similarity
 * threshold and the filter expression matter, and all three come from the project's existing, tested
 * retrieval configuration.</p>
 *
 * <h2>Fail closed on isolation</h2>
 * <p>Building the advisor with a department-wide scope that excludes shared documents throws
 * {@link IllegalArgumentException}, exactly as {@link MedRetrievalFilters#scope(MedDocumentScope, boolean)}
 * does: that combination matches nothing and is a caller mistake, not an empty result.</p>
 */
@Service
public class MedRagAdvisorFactory {

    private final VectorStore vectorStore;

    private final MedRetrievalService retrievalService;

    private final MedRagAdvisorProperties properties;

    /**
     * Creates the factory used by the application context.
     *
     * <p>The vector store is injected {@link Lazy}: the advisor only touches it during a chat request,
     * never during construction, so building advisors stays independent of any Redis connection.</p>
     *
     * @param vectorStore       official vector store, must not be {@code null}
     * @param retrievalService  tag-filtered retrieval service (Top-K / threshold guard rails),
     *                          must not be {@code null}
     * @param properties        advisor tuning, must not be {@code null}
     * @throws IllegalArgumentException if an argument is {@code null}
     */
    @Autowired
    public MedRagAdvisorFactory(@Lazy VectorStore vectorStore,
                                MedRetrievalService retrievalService,
                                MedRagAdvisorProperties properties) {
        Assert.notNull(vectorStore, "vectorStore must not be null");
        Assert.notNull(retrievalService, "retrievalService must not be null");
        Assert.notNull(properties, "properties must not be null");
        this.vectorStore = vectorStore;
        this.retrievalService = retrievalService;
        this.properties = properties;
    }

    /**
     * Builds a RAG advisor for a patient's own documents plus the department's shared guidelines.
     *
     * @param scope tags the caller is entitled to, must not be {@code null}
     * @return a scoped {@link QuestionAnswerAdvisor}, never {@code null}
     * @throws IllegalArgumentException if {@code scope} is {@code null}
     */
    public Advisor createAdvisor(MedDocumentScope scope) {
        return createAdvisor(scope, true);
    }

    /**
     * Builds a RAG advisor for a scope, optionally restricting the retrieval to the patient's own
     * documents.
     *
     * @param scope                   tags the caller is entitled to, must not be {@code null}
     * @param includeSharedDocuments  whether department-wide documents tagged
     *                               {@value MedDocumentScope#SHARED_PATIENT_TAG} take part in the
     *                               retrieval
     * @return a scoped {@link QuestionAnswerAdvisor}, never {@code null}
     * @throws IllegalArgumentException if {@code scope} is {@code null}, or if a department-wide scope
     *                                  excludes shared documents
     */
    public Advisor createAdvisor(MedDocumentScope scope, boolean includeSharedDocuments) {
        return buildAdvisor(toSearchRequest(scope, includeSharedDocuments));
    }

    /**
     * Builds a RAG advisor from a fully described retrieval query.
     *
     * <p>Reuses {@link MedRetrievalService#toSearchRequest(MedRetrievalQuery)} so the advisor and the
     * standalone retrieval share one request-construction path.</p>
     *
     * @param query the retrieval to run, must not be {@code null}
     * @return a scoped {@link QuestionAnswerAdvisor}, never {@code null}
     * @throws IllegalArgumentException if {@code query} is {@code null}
     */
    public Advisor createAdvisor(MedRetrievalQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        return buildAdvisor(retrievalService.toSearchRequest(query));
    }

    /**
     * Assembles the {@link SearchRequest} an advisor will use, without building the advisor.
     *
     * <p>Exposed so the wiring can be asserted offline: Top-K and similarity threshold come from the
     * retrieval guard rails (resolved via {@link MedRetrievalService}), and the filter expression is
     * the isolation predicate produced by {@link MedRetrievalFilters}.</p>
     *
     * @param scope                   tags the caller is entitled to, must not be {@code null}
     * @param includeSharedDocuments  whether department-wide documents take part in the retrieval
     * @return the search request handed to the advisor, never {@code null}
     * @throws IllegalArgumentException if {@code scope} is {@code null}, or if a department-wide scope
     *                                  excludes shared documents
     */
    public SearchRequest toSearchRequest(MedDocumentScope scope, boolean includeSharedDocuments) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        Filter.Expression isolation = MedRetrievalFilters.scope(scope, includeSharedDocuments);
        return SearchRequest.builder()
                .topK(retrievalService.resolveTopK(null))
                .similarityThreshold(retrievalService.resolveSimilarityThreshold(null))
                .filterExpression(isolation)
                .build();
    }

    /**
     * Builds the advisor from a ready {@link SearchRequest}.
     *
     * @param searchRequest request carrying Top-K, threshold and the isolation filter, must not be
     *                      {@code null}
     * @return the configured {@link QuestionAnswerAdvisor}
     */
    private Advisor buildAdvisor(SearchRequest searchRequest) {
        return QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(searchRequest)
                .promptTemplate(new PromptTemplate(properties.getPromptTemplate()))
                .order(properties.getOrder())
                .build();
    }

    /**
     * Returns a fresh {@link PromptTemplate} for the configured augmentation prompt.
     *
     * @return the prompt template, never {@code null}
     */
    public PromptTemplate promptTemplate() {
        return new PromptTemplate(properties.getPromptTemplate());
    }
}
