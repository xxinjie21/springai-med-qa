package com.med.qa.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Guard rails of the medical RAG retrieval stage.
 *
 * <p>Bound from {@code med.rag.retrieval.*}. As with ingestion, these are policy limits rather than
 * algorithms: the similarity computation, the Top-K selection and the translation of a filter
 * expression into a RediSearch query are all performed by the official {@code RedisVectorStore}.
 * What is configured here is only how large a single retrieval call may become, so one consultation
 * turn cannot pull an unbounded amount of clinical text into the prompt.</p>
 *
 * <h2>Why a maximum Top-K exists</h2>
 * <p>Every retrieved document is eventually inlined into an LLM prompt. An unbounded {@code topK}
 * therefore translates directly into cost, latency and — because retrieved snippets are patient
 * data — the blast radius of a single query. {@link #getMaxTopK()} is the hard ceiling a caller may
 * never exceed, while {@link #getTopK()} is what a caller that expresses no preference gets.</p>
 *
 * <p>Every setter validates its input so a misconfiguration fails at startup.</p>
 */
@ConfigurationProperties(prefix = MedRetrievalProperties.PREFIX)
public class MedRetrievalProperties {

    /** Configuration prefix bound by Spring Boot. */
    public static final String PREFIX = "med.rag.retrieval";

    /** Absolute ceiling accepted for any Top-K setting, whatever the configuration says. */
    public static final int TOP_K_LIMIT = 1000;

    private int topK = 4;

    private int maxTopK = 50;

    private double similarityThreshold = 0.0d;

    private int maxQueryLength = 1000;

    /**
     * Returns the number of documents retrieved when the caller expresses no preference.
     *
     * @return default Top-K, always {@code >= 1}
     */
    public int getTopK() {
        return topK;
    }

    /**
     * Sets the number of documents retrieved when the caller expresses no preference.
     *
     * @param topK default Top-K, must be between {@code 1} and {@value #TOP_K_LIMIT}
     * @throws IllegalArgumentException if the value is out of range
     */
    public void setTopK(int topK) {
        if (topK < 1 || topK > TOP_K_LIMIT) {
            throw new IllegalArgumentException(
                    PREFIX + ".top-k must be between 1 and " + TOP_K_LIMIT + " but is " + topK);
        }
        this.topK = topK;
    }

    /**
     * Returns the largest Top-K a caller may request.
     *
     * @return maximum Top-K, always {@code >= 1}
     */
    public int getMaxTopK() {
        return maxTopK;
    }

    /**
     * Sets the largest Top-K a caller may request.
     *
     * @param maxTopK maximum Top-K, must be between {@code 1} and {@value #TOP_K_LIMIT}
     * @throws IllegalArgumentException if the value is out of range
     */
    public void setMaxTopK(int maxTopK) {
        if (maxTopK < 1 || maxTopK > TOP_K_LIMIT) {
            throw new IllegalArgumentException(
                    PREFIX + ".max-top-k must be between 1 and " + TOP_K_LIMIT + " but is " + maxTopK);
        }
        this.maxTopK = maxTopK;
    }

    /**
     * Returns the minimum similarity a document must reach to be returned.
     *
     * @return similarity threshold in {@code [0, 1]}; {@code 0} accepts every candidate
     */
    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    /**
     * Sets the minimum similarity a document must reach to be returned.
     *
     * <p>The threshold is applied by the vector store itself. {@code 0} is Spring AI's
     * "accept all" value and is the safe default for a fresh corpus: a too aggressive threshold
     * silently returns nothing, which looks to a clinician like "the guideline does not exist".</p>
     *
     * @param similarityThreshold threshold, must be within {@code [0, 1]}
     * @throws IllegalArgumentException if the value is outside the unit interval or not a number
     */
    public void setSimilarityThreshold(double similarityThreshold) {
        if (Double.isNaN(similarityThreshold) || similarityThreshold < 0.0d || similarityThreshold > 1.0d) {
            throw new IllegalArgumentException(
                    PREFIX + ".similarity-threshold must be within [0, 1] but is " + similarityThreshold);
        }
        this.similarityThreshold = similarityThreshold;
    }

    /**
     * Returns the maximum accepted length of a retrieval query, in characters.
     *
     * @return maximum query length, always {@code >= 1}
     */
    public int getMaxQueryLength() {
        return maxQueryLength;
    }

    /**
     * Sets the maximum accepted length of a retrieval query.
     *
     * <p>The query is embedded before the search, so an oversized one is a wasted call to the
     * embedding endpoint. Rejecting it up front turns a downstream failure into a plain business
     * error.</p>
     *
     * @param maxQueryLength maximum number of characters, must be {@code >= 1}
     * @throws IllegalArgumentException if the value is not positive
     */
    public void setMaxQueryLength(int maxQueryLength) {
        if (maxQueryLength < 1) {
            throw new IllegalArgumentException(
                    PREFIX + ".max-query-length must be positive but is " + maxQueryLength);
        }
        this.maxQueryLength = maxQueryLength;
    }

    @Override
    public String toString() {
        return "MedRetrievalProperties{topK=" + topK
                + ", maxTopK=" + maxTopK
                + ", similarityThreshold=" + similarityThreshold
                + ", maxQueryLength=" + maxQueryLength + '}';
    }
}
