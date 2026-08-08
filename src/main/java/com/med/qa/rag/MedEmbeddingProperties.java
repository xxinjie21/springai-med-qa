package com.med.qa.rag;

import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Project-side tuning of the embedding stage, bound from {@code med.rag.embedding.*}.
 *
 * <h2>Relationship with the official OpenAI properties</h2>
 * <p>Everything that describes the model endpoint itself — base URL, API key, embeddings path,
 * model name, requested vector width, retry policy — is configured through the canonical Spring AI
 * namespaces {@code spring.ai.openai.*} and {@code spring.ai.retry.*}; this class deliberately does
 * not duplicate them. What it adds are the two decisions the official properties cannot express:</p>
 * <ul>
 *   <li>the vector width the RediSearch index is built with, cross-checked against
 *       {@value #OPENAI_DIMENSIONS_PROPERTY} so a model swap can never silently produce vectors the
 *       existing index rejects;</li>
 *   <li>how documents are grouped into embedding requests, which is handed to Spring AI's
 *       {@code TokenCountBatchingStrategy} — the batching itself is not implemented here.</li>
 * </ul>
 *
 * <p>Every setter validates its input so a typo fails the context startup instead of surfacing as a
 * rejected embedding call in the middle of a consultation.</p>
 */
@ConfigurationProperties(prefix = MedEmbeddingProperties.PREFIX)
public class MedEmbeddingProperties {

    /** Configuration prefix bound by Spring Boot. */
    public static final String PREFIX = "med.rag.embedding";

    /** Official property carrying the vector width requested from the model. */
    public static final String OPENAI_DIMENSIONS_PROPERTY = "spring.ai.openai.embedding.options.dimensions";

    /** Upper bound accepted for {@link #setReservePercentage(double)} (exclusive). */
    public static final double MAX_RESERVE_PERCENTAGE = 1.0d;

    /**
     * Vector width of the RediSearch index. Defaults to 1536, the width of the
     * {@code text-embedding-3-small} / {@code text-embedding-ada-002} family.
     */
    private int expectedDimensions = 1536;

    /** Tokenizer used to estimate request size; {@code CL100K_BASE} matches the OpenAI embedders. */
    private EncodingType encodingType = EncodingType.CL100K_BASE;

    /** Maximum number of tokens a single embedding request may carry (OpenAI's limit is 8192). */
    private int maxInputTokenCount = 8191;

    /** Fraction of the token budget kept free to absorb tokenizer estimation error. */
    private double reservePercentage = 0.1d;

    public int getExpectedDimensions() {
        return expectedDimensions;
    }

    /**
     * Sets the vector width the index is created with.
     *
     * @param expectedDimensions number of floats per embedding, must be positive
     * @throws IllegalArgumentException if {@code expectedDimensions} is not positive
     */
    public void setExpectedDimensions(int expectedDimensions) {
        if (expectedDimensions <= 0) {
            throw new IllegalArgumentException(
                    PREFIX + ".expected-dimensions must be positive, but was " + expectedDimensions);
        }
        this.expectedDimensions = expectedDimensions;
    }

    public EncodingType getEncodingType() {
        return encodingType;
    }

    /**
     * Sets the tokenizer used to size embedding batches.
     *
     * @param encodingType JTokkit encoding, must not be {@code null}
     * @throws IllegalArgumentException if {@code encodingType} is {@code null}
     */
    public void setEncodingType(EncodingType encodingType) {
        if (encodingType == null) {
            throw new IllegalArgumentException(PREFIX + ".encoding-type must not be null");
        }
        this.encodingType = encodingType;
    }

    public int getMaxInputTokenCount() {
        return maxInputTokenCount;
    }

    /**
     * Sets the token budget of one embedding request.
     *
     * @param maxInputTokenCount token budget, must be positive
     * @throws IllegalArgumentException if {@code maxInputTokenCount} is not positive
     */
    public void setMaxInputTokenCount(int maxInputTokenCount) {
        if (maxInputTokenCount <= 0) {
            throw new IllegalArgumentException(
                    PREFIX + ".max-input-token-count must be positive, but was " + maxInputTokenCount);
        }
        this.maxInputTokenCount = maxInputTokenCount;
    }

    public double getReservePercentage() {
        return reservePercentage;
    }

    /**
     * Sets the share of the token budget held back as a safety margin.
     *
     * @param reservePercentage margin in {@code [0, 1)}
     * @throws IllegalArgumentException if the value is negative or reaches
     *                                  {@value #MAX_RESERVE_PERCENTAGE}, which would leave no
     *                                  usable token budget at all
     */
    public void setReservePercentage(double reservePercentage) {
        if (Double.isNaN(reservePercentage) || reservePercentage < 0.0d
                || reservePercentage >= MAX_RESERVE_PERCENTAGE) {
            throw new IllegalArgumentException(
                    PREFIX + ".reserve-percentage must be within [0, " + MAX_RESERVE_PERCENTAGE
                            + "), but was " + reservePercentage);
        }
        this.reservePercentage = reservePercentage;
    }

    @Override
    public String toString() {
        return "MedEmbeddingProperties{expectedDimensions=" + expectedDimensions
                + ", encodingType=" + encodingType
                + ", maxInputTokenCount=" + maxInputTokenCount
                + ", reservePercentage=" + reservePercentage + '}';
    }
}
