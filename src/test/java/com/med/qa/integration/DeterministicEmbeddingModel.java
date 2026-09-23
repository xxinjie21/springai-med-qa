package com.med.qa.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Test double for the embedding gateway, used by the RAG integration suite.
 *
 * <h2>Why a double is unavoidable here</h2>
 * <p>The production {@code EmbeddingModel} is an OpenAI-compatible HTTP client
 * ({@code config/EmbeddingModelConfig}) that talks to an external model gateway. A Testcontainers
 * suite that started that model would need credentials, network access and a deterministic model
 * build — none of which belong in a repeatable integration test. The gateway is therefore the one
 * collaborator the suite replaces, exactly as the D34/D35 suites replace nothing but the daemon
 * probe. Everything downstream of it — {@code RedisVectorStore}, RediSearch, the JSON index, the
 * {@code TAG} filters, {@code FT.SEARCH} — stays real.</p>
 *
 * <h2>Scope of the substitution: text to vector, and nothing else</h2>
 * <p>This class only performs the one transformation the real gateway performs: it maps a piece of
 * text to a fixed-width {@code float[]}. Similarity computation, Top-K selection, ranking and filter
 * evaluation are <em>not</em> implemented here — they happen inside Redis Stack, driven by the
 * official store. So the suite still proves the real retrieval behaviour; only the numbers that go
 * into the index are produced locally.</p>
 *
 * <p>It is <strong>test-only</strong> (it lives under {@code src/test/java} and is never packaged),
 * and it is <strong>not</strong> a text-preprocessing component: no production class consults it,
 * and the project's hard constraint that documents are indexed verbatim is untouched. The token
 * split below exists solely so the fixture's clinical phrases can be given distinguishable vectors.</p>
 *
 * <h2>The vector it produces</h2>
 * <p>A hashed bag of whitespace/comma-separated tokens, L2-normalised:</p>
 * <ol>
 *   <li>the text is split on whitespace and the common Chinese / ASCII punctuation marks;</li>
 *   <li>each token increments the slot {@code floorMod(token.hashCode(), dimensions)};</li>
 *   <li>the resulting histogram is scaled to unit length.</li>
 * </ol>
 * <p>{@link String#hashCode()} is specified by the Java Language Specification, so the mapping is
 * identical on every JVM and every run: the same text always yields the same vector, and two texts
 * sharing tokens always score higher under cosine similarity than two texts that share none. That
 * is all the suite needs — it asserts on <em>which</em> documents come back, never on the exact
 * similarity value.</p>
 */
public final class DeterministicEmbeddingModel implements EmbeddingModel {

    /**
     * Default vector width. Small on purpose: the fixture corpus holds a handful of documents, and a
     * narrow vector keeps hash collisions (and therefore scores) easy to reason about.
     */
    public static final int DEFAULT_DIMENSIONS = 32;

    /**
     * Token separator: whitespace plus the punctuation that separates clauses in Chinese clinical
     * text. Used only to make the fixture's phrases distinguishable — see the class documentation.
     */
    private static final String TOKEN_SEPARATOR = "[\\s,，。;；、:：/\\\\|]+";

    private final int dimensions;

    /**
     * Creates a model with {@value #DEFAULT_DIMENSIONS} dimensions.
     */
    public DeterministicEmbeddingModel() {
        this(DEFAULT_DIMENSIONS);
    }

    /**
     * Creates a model with an explicit vector width.
     *
     * @param dimensions number of components of every produced vector, must be positive
     * @throws IllegalArgumentException if {@code dimensions} is not positive
     */
    public DeterministicEmbeddingModel(int dimensions) {
        if (dimensions < 1) {
            throw new IllegalArgumentException("dimensions must be positive but is " + dimensions);
        }
        this.dimensions = dimensions;
    }

    /**
     * Returns the width of every vector this model produces.
     *
     * <p>The official {@code RedisVectorStore} reads this value when it creates the RediSearch index,
     * so a mismatch here would be rejected by Redis at write time.</p>
     *
     * @return the configured number of dimensions, always {@code >= 1}
     */
    @Override
    public int dimensions() {
        return dimensions;
    }

    /**
     * Embeds a batch of texts, which is the only entry point the official vector store uses.
     *
     * @param request embedding request carrying the texts to vectorize, must not be {@code null} and
     *                must carry a non-{@code null} instruction list
     * @return one {@link Embedding} per instruction, carrying its index, never {@code null}
     * @throws IllegalArgumentException if {@code request} or its instruction list is {@code null}
     */
    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        List<String> instructions = request.getInstructions();
        if (instructions == null) {
            throw new IllegalArgumentException("request instructions must not be null");
        }
        List<Embedding> embeddings = new ArrayList<>(instructions.size());
        for (int index = 0; index < instructions.size(); index++) {
            embeddings.add(new Embedding(embedText(instructions.get(index)), index));
        }
        return new EmbeddingResponse(embeddings);
    }

    /**
     * Embeds a document by its text.
     *
     * <p>Document metadata is deliberately ignored, mirroring a plain text embedding endpoint.</p>
     *
     * @param document document to vectorize, must not be {@code null}
     * @return the unit-length vector of the document text, never {@code null}
     * @throws IllegalArgumentException if {@code document} is {@code null}
     */
    @Override
    public float[] embed(Document document) {
        if (document == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        return embedText(document.getText());
    }

    /**
     * Maps a piece of text to its deterministic unit-length vector.
     *
     * <p>Exposed so the mapping can be asserted without a vector store. A text without any token
     * yields the zero vector, which cosine similarity treats as "no shared content" rather than as
     * an error — the same thing a real gateway returns for empty input.</p>
     *
     * @param text text to vectorize, must not be {@code null}
     * @return a fresh array of {@link #dimensions()} components, never {@code null}
     * @throws IllegalArgumentException if {@code text} is {@code null}
     */
    public float[] embedText(String text) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        float[] vector = new float[dimensions];
        for (String token : tokenize(text)) {
            vector[Math.floorMod(token.hashCode(), dimensions)] += 1.0f;
        }
        normalise(vector);
        return vector;
    }

    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        for (String candidate : text.split(TOKEN_SEPARATOR)) {
            if (!candidate.isEmpty()) {
                tokens.add(candidate.toLowerCase(Locale.ROOT));
            }
        }
        return tokens;
    }

    private static void normalise(float[] vector) {
        double sumOfSquares = 0.0d;
        for (float value : vector) {
            sumOfSquares += (double) value * value;
        }
        if (sumOfSquares == 0.0d) {
            return;
        }
        float norm = (float) Math.sqrt(sumOfSquares);
        for (int index = 0; index < vector.length; index++) {
            vector[index] /= norm;
        }
    }
}
