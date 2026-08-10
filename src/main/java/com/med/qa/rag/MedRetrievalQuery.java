package com.med.qa.rag;

import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * One tag-scoped similarity search against the medical RAG corpus.
 *
 * <p>The query text is used verbatim: it is embedded by the official {@code EmbeddingModel} and
 * compared by the vector store. It is never tokenized, normalized or scanned for clinical entities,
 * and the documents it may reach are decided solely by the {@link MedDocumentScope} carried here.</p>
 *
 * <h2>Unset knobs versus explicit knobs</h2>
 * <p>{@code topK} and {@code similarityThreshold} are boxed on purpose. {@code null} means "no
 * opinion, use the configured default", which is different from a caller explicitly asking for a
 * value that happens to equal the default — the former follows {@code med.rag.retrieval.*} when it
 * is retuned, the latter does not.</p>
 *
 * <p>Instances are immutable and safe to share.</p>
 */
public final class MedRetrievalQuery {

    private final String text;

    private final MedDocumentScope scope;

    private final Integer topK;

    private final Double similarityThreshold;

    private final boolean includeSharedDocuments;

    private final Filter.Expression additionalFilter;

    private MedRetrievalQuery(Builder builder) {
        if (!StringUtils.hasText(builder.text)) {
            throw new IllegalArgumentException("retrieval query text must not be blank");
        }
        if (builder.scope == null) {
            throw new IllegalArgumentException("retrieval scope must not be null");
        }
        if (builder.topK != null && builder.topK < 1) {
            throw new IllegalArgumentException("topK must be positive but is " + builder.topK);
        }
        if (builder.similarityThreshold != null
                && (Double.isNaN(builder.similarityThreshold)
                || builder.similarityThreshold < 0.0d
                || builder.similarityThreshold > 1.0d)) {
            throw new IllegalArgumentException(
                    "similarityThreshold must be within [0, 1] but is " + builder.similarityThreshold);
        }
        if (!builder.scope.isPatientScoped() && !builder.includeSharedDocuments) {
            throw new IllegalArgumentException(
                    "a department-wide query cannot exclude shared documents; it would match nothing");
        }
        this.text = builder.text;
        this.scope = builder.scope;
        this.topK = builder.topK;
        this.similarityThreshold = builder.similarityThreshold;
        this.includeSharedDocuments = builder.includeSharedDocuments;
        this.additionalFilter = builder.additionalFilter;
    }

    /**
     * Creates a query using the configured defaults for Top-K and similarity threshold.
     *
     * @param text  question or passage to match, must not be blank
     * @param scope tags the caller is entitled to, must not be {@code null}
     * @return the immutable query, never {@code null}
     * @throws IllegalArgumentException if the text is blank or the scope is {@code null}
     */
    public static MedRetrievalQuery of(String text, MedDocumentScope scope) {
        return builder(text, scope).build();
    }

    /**
     * Starts building a query.
     *
     * @param text  question or passage to match, must not be blank
     * @param scope tags the caller is entitled to, must not be {@code null}
     * @return a fresh builder, never {@code null}
     */
    public static Builder builder(String text, MedDocumentScope scope) {
        return new Builder(text, scope);
    }

    /**
     * Returns the text to embed and match.
     *
     * @return the query text, never blank
     */
    public String getText() {
        return text;
    }

    /**
     * Returns the isolation scope of the retrieval.
     *
     * @return the scope, never {@code null}
     */
    public MedDocumentScope getScope() {
        return scope;
    }

    /**
     * Returns the caller's Top-K preference.
     *
     * @return the requested number of documents, or {@code null} to use
     *         {@code med.rag.retrieval.top-k}
     */
    @Nullable
    public Integer getTopK() {
        return topK;
    }

    /**
     * Returns the caller's similarity threshold preference.
     *
     * @return the requested threshold, or {@code null} to use
     *         {@code med.rag.retrieval.similarity-threshold}
     */
    @Nullable
    public Double getSimilarityThreshold() {
        return similarityThreshold;
    }

    /**
     * Tells whether department-wide documents take part in the retrieval.
     *
     * @return {@code true} when documents tagged {@value MedDocumentScope#SHARED_PATIENT_TAG} are
     *         eligible; always {@code true} for a department-wide scope
     */
    public boolean isIncludeSharedDocuments() {
        return includeSharedDocuments;
    }

    /**
     * Returns the extra filter narrowing the retrieval beyond the isolation tags.
     *
     * @return the additional filter expression, or {@code null} when none was supplied
     */
    @Nullable
    public Filter.Expression getAdditionalFilter() {
        return additionalFilter;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MedRetrievalQuery that)) {
            return false;
        }
        return includeSharedDocuments == that.includeSharedDocuments
                && text.equals(that.text)
                && scope.equals(that.scope)
                && Objects.equals(topK, that.topK)
                && Objects.equals(similarityThreshold, that.similarityThreshold)
                && Objects.equals(additionalFilter, that.additionalFilter);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, scope, topK, similarityThreshold, includeSharedDocuments, additionalFilter);
    }

    /**
     * Renders the query without its text.
     *
     * <p>A consultation question is patient data and is never written to a log; only its length is
     * reported.</p>
     *
     * @return a privacy-safe description, never {@code null}
     */
    @Override
    public String toString() {
        return "MedRetrievalQuery{scope=" + scope
                + ", textLength=" + text.length()
                + ", topK=" + topK
                + ", similarityThreshold=" + similarityThreshold
                + ", includeSharedDocuments=" + includeSharedDocuments
                + ", additionalFilter=" + (additionalFilter != null) + '}';
    }

    /** Fluent builder of {@link MedRetrievalQuery}. */
    public static final class Builder {

        private final String text;

        private final MedDocumentScope scope;

        private Integer topK;

        private Double similarityThreshold;

        private boolean includeSharedDocuments = true;

        private Filter.Expression additionalFilter;

        private Builder(String text, MedDocumentScope scope) {
            this.text = text;
            this.scope = scope;
        }

        /**
         * Requests an explicit number of documents.
         *
         * @param topK number of documents, must be positive and within
         *             {@code med.rag.retrieval.max-top-k}
         * @return this builder, never {@code null}
         */
        public Builder topK(int topK) {
            this.topK = topK;
            return this;
        }

        /**
         * Requests an explicit similarity threshold.
         *
         * @param similarityThreshold minimum similarity within {@code [0, 1]}
         * @return this builder, never {@code null}
         */
        public Builder similarityThreshold(double similarityThreshold) {
            this.similarityThreshold = similarityThreshold;
            return this;
        }

        /**
         * Chooses whether department-wide documents are eligible.
         *
         * @param includeSharedDocuments {@code false} to restrict the retrieval to the patient's own
         *                               documents; only meaningful for a patient-scoped query
         * @return this builder, never {@code null}
         */
        public Builder includeSharedDocuments(boolean includeSharedDocuments) {
            this.includeSharedDocuments = includeSharedDocuments;
            return this;
        }

        /**
         * Narrows the retrieval with an extra metadata filter.
         *
         * <p>The isolation tags are always conjoined with it, so this can only restrict the result
         * set further, never widen it.</p>
         *
         * @param additionalFilter filter expression built with the official
         *                         {@code FilterExpressionBuilder}, may be {@code null}
         * @return this builder, never {@code null}
         */
        public Builder additionalFilter(@Nullable Filter.Expression additionalFilter) {
            this.additionalFilter = additionalFilter;
            return this;
        }

        /**
         * Builds the immutable query.
         *
         * @return the query, never {@code null}
         * @throws IllegalArgumentException if the text is blank, the scope is {@code null},
         *                                  {@code topK} is not positive, the threshold is outside
         *                                  {@code [0, 1]}, or a department-wide scope excludes
         *                                  shared documents
         */
        public MedRetrievalQuery build() {
            return new MedRetrievalQuery(this);
        }
    }
}
