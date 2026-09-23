package com.med.qa.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Offline unit tests of {@link DeterministicEmbeddingModel}.
 *
 * <p>The double is what makes the Redis Stack integration suite deterministic, so its contract has to
 * hold without any middleware: a fixed width, a repeatable mapping, unit-length vectors and a higher
 * cosine score for texts that share vocabulary. The last property is the one the integration suite
 * depends on for its ranking assertions, which is why it is pinned here as well — a silent change to
 * the mapping would otherwise only show up as a confusing failure inside a container.</p>
 */
class DeterministicEmbeddingModelTest {

    private static final String TEXT = "高血压 首选 药物 钙通道阻滞剂";

    @Test
    @DisplayName("the default width is the documented constant")
    void defaultWidthIsTheDocumentedConstant() {
        assertThat(new DeterministicEmbeddingModel().dimensions())
                .isEqualTo(DeterministicEmbeddingModel.DEFAULT_DIMENSIONS);
    }

    @Test
    @DisplayName("an explicitly configured width is honoured by dimensions() and by the vectors")
    void configuredWidthIsHonoured() {
        DeterministicEmbeddingModel model = new DeterministicEmbeddingModel(8);

        assertThat(model.dimensions()).isEqualTo(8);
        assertThat(model.embedText(TEXT)).hasSize(8);
    }

    @Test
    @DisplayName("a non-positive width is rejected")
    void nonPositiveWidthIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DeterministicEmbeddingModel(0))
                .withMessageContaining("dimensions must be positive");
        assertThatIllegalArgumentException().isThrownBy(() -> new DeterministicEmbeddingModel(-3))
                .withMessageContaining("dimensions must be positive");
    }

    @Test
    @DisplayName("the same text always maps to the same vector")
    void embeddingIsDeterministic() {
        DeterministicEmbeddingModel model = new DeterministicEmbeddingModel();

        assertThat(model.embedText(TEXT)).containsExactly(model.embedText(TEXT));
        assertThat(model.embedText(TEXT)).containsExactly(new DeterministicEmbeddingModel().embedText(TEXT));
    }

    @Test
    @DisplayName("a vector with tokens is normalised to unit length")
    void vectorsAreUnitLength() {
        float[] vector = new DeterministicEmbeddingModel().embedText(TEXT);

        assertThat(norm(vector)).isCloseTo(1.0d, within(1.0e-6d));
    }

    @Test
    @DisplayName("texts sharing vocabulary score higher under cosine similarity than disjoint texts")
    void sharedVocabularyScoresHigherThanDisjointVocabulary() {
        DeterministicEmbeddingModel model = new DeterministicEmbeddingModel();

        float[] query = model.embedText("高血压 首选 药物");
        float[] overlapping = model.embedText("高血压 首选 药物 钙通道阻滞剂");
        float[] disjoint = model.embedText("糖尿病 首选 药物 二甲双胍");

        assertThat(cosine(query, overlapping)).isGreaterThan(cosine(query, disjoint));
        assertThat(cosine(query, overlapping)).isGreaterThan(0.5d);
    }

    @Test
    @DisplayName("text without any token yields the zero vector rather than failing")
    void textWithoutTokensYieldsTheZeroVector() {
        DeterministicEmbeddingModel model = new DeterministicEmbeddingModel();

        assertThat(model.embedText("")).containsOnly(0.0f);
        assertThat(model.embedText("   ")).containsOnly(0.0f);
        assertThat(model.embedText(",，。；")).containsOnly(0.0f);
    }

    @Test
    @DisplayName("null text is rejected")
    void nullTextIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DeterministicEmbeddingModel().embedText(null))
                .withMessageContaining("text must not be null");
    }

    @Test
    @DisplayName("a batch request yields one indexed embedding per instruction")
    void batchRequestYieldsOneEmbeddingPerInstruction() {
        DeterministicEmbeddingModel model = new DeterministicEmbeddingModel();

        EmbeddingResponse response = model.call(new EmbeddingRequest(List.of(TEXT, "糖尿病 二甲双胍"), null));

        List<Embedding> embeddings = response.getResults();
        assertThat(embeddings).hasSize(2);
        assertThat(embeddings.get(0).getIndex()).isZero();
        assertThat(embeddings.get(1).getIndex()).isEqualTo(1);
        assertThat(embeddings.get(0).getOutput()).containsExactly(model.embedText(TEXT));
        assertThat(embeddings.get(1).getOutput()).containsExactly(model.embedText("糖尿病 二甲双胍"));
    }

    @Test
    @DisplayName("an empty batch request yields an empty response")
    void emptyBatchRequestYieldsAnEmptyResponse() {
        EmbeddingResponse response = new DeterministicEmbeddingModel().call(new EmbeddingRequest(List.of(), null));

        assertThat(response.getResults()).isEmpty();
    }

    @Test
    @DisplayName("a null request or a request without instructions is rejected")
    void malformedBatchRequestIsRejected() {
        DeterministicEmbeddingModel model = new DeterministicEmbeddingModel();

        assertThatIllegalArgumentException().isThrownBy(() -> model.call(null))
                .withMessageContaining("request must not be null");
        assertThatIllegalArgumentException().isThrownBy(() -> model.call(new EmbeddingRequest(null, null)))
                .withMessageContaining("instructions must not be null");
    }

    @Test
    @DisplayName("embedding a document uses its text and ignores its metadata")
    void documentIsEmbeddedByItsText() {
        DeterministicEmbeddingModel model = new DeterministicEmbeddingModel();
        Document document = Document.builder()
                .text(TEXT)
                .metadata(Map.of("source", "department-guideline"))
                .build();

        assertThat(model.embed(document)).containsExactly(model.embedText(TEXT));
    }

    @Test
    @DisplayName("a null document is rejected")
    void nullDocumentIsRejected() {
        DeterministicEmbeddingModel model = new DeterministicEmbeddingModel();

        assertThatIllegalArgumentException().isThrownBy(() -> model.embed((Document) null))
                .withMessageContaining("document must not be null");
    }

    /**
     * Cosine similarity of two vectors, used purely as an assertion helper.
     *
     * <p>It exists so the tests can state the property they care about ("sharing vocabulary scores
     * higher") without depending on the exact numbers the double produces. No production code
     * computes similarity: inside the application that is RediSearch's job.</p>
     *
     * @param left  first vector, must have the same length as {@code right}
     * @param right second vector
     * @return the cosine similarity of the two vectors
     */
    private static double cosine(float[] left, float[] right) {
        double dot = 0.0d;
        for (int index = 0; index < left.length; index++) {
            dot += (double) left[index] * right[index];
        }
        return dot / (norm(left) * norm(right));
    }

    private static double norm(float[] vector) {
        double sumOfSquares = 0.0d;
        for (float value : vector) {
            sumOfSquares += (double) value * value;
        }
        return Math.sqrt(sumOfSquares);
    }
}
