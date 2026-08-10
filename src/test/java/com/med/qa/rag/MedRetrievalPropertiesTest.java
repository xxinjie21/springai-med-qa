package com.med.qa.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests of the retrieval guard rails.
 *
 * <p>Each setter is expected to reject its invalid range so a typo in {@code application.yml}
 * fails the context startup rather than producing a retrieval that quietly returns nothing or
 * pulls an unbounded amount of patient text into a prompt.</p>
 */
class MedRetrievalPropertiesTest {

    @Test
    @DisplayName("defaults match the documented retrieval policy")
    void defaultsAreConservative() {
        MedRetrievalProperties properties = new MedRetrievalProperties();

        assertThat(properties.getTopK()).isEqualTo(4);
        assertThat(properties.getMaxTopK()).isEqualTo(50);
        assertThat(properties.getSimilarityThreshold()).isEqualTo(0.0d);
        assertThat(properties.getMaxQueryLength()).isEqualTo(1000);
        assertThat(properties.getTopK()).isLessThanOrEqualTo(properties.getMaxTopK());
    }

    @Test
    @DisplayName("prefix is the documented configuration namespace")
    void prefixIsStable() {
        assertThat(MedRetrievalProperties.PREFIX).isEqualTo("med.rag.retrieval");
    }

    @Test
    @DisplayName("top-k accepts a value inside the allowed range")
    void topKAcceptsValidValue() {
        MedRetrievalProperties properties = new MedRetrievalProperties();

        properties.setTopK(12);

        assertThat(properties.getTopK()).isEqualTo(12);
    }

    @ParameterizedTest(name = "top-k {0} is rejected")
    @ValueSource(ints = {0, -1, MedRetrievalProperties.TOP_K_LIMIT + 1})
    @DisplayName("top-k rejects values outside the allowed range")
    void topKRejectsInvalidValue(int candidate) {
        MedRetrievalProperties properties = new MedRetrievalProperties();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setTopK(candidate))
                .withMessageContaining("med.rag.retrieval.top-k");
    }

    @Test
    @DisplayName("top-k accepts the absolute ceiling")
    void topKAcceptsLimit() {
        MedRetrievalProperties properties = new MedRetrievalProperties();

        properties.setTopK(MedRetrievalProperties.TOP_K_LIMIT);

        assertThat(properties.getTopK()).isEqualTo(MedRetrievalProperties.TOP_K_LIMIT);
    }

    @Test
    @DisplayName("max-top-k accepts a value inside the allowed range")
    void maxTopKAcceptsValidValue() {
        MedRetrievalProperties properties = new MedRetrievalProperties();

        properties.setMaxTopK(80);

        assertThat(properties.getMaxTopK()).isEqualTo(80);
    }

    @ParameterizedTest(name = "max-top-k {0} is rejected")
    @ValueSource(ints = {0, -5, MedRetrievalProperties.TOP_K_LIMIT + 1})
    @DisplayName("max-top-k rejects values outside the allowed range")
    void maxTopKRejectsInvalidValue(int candidate) {
        MedRetrievalProperties properties = new MedRetrievalProperties();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setMaxTopK(candidate))
                .withMessageContaining("med.rag.retrieval.max-top-k");
    }

    @ParameterizedTest(name = "similarity threshold {0} is accepted")
    @ValueSource(doubles = {0.0d, 0.42d, 1.0d})
    @DisplayName("similarity threshold accepts the whole unit interval")
    void similarityThresholdAcceptsUnitInterval(double candidate) {
        MedRetrievalProperties properties = new MedRetrievalProperties();

        properties.setSimilarityThreshold(candidate);

        assertThat(properties.getSimilarityThreshold()).isEqualTo(candidate);
    }

    @ParameterizedTest(name = "similarity threshold {0} is rejected")
    @ValueSource(doubles = {-0.01d, 1.01d, Double.NaN})
    @DisplayName("similarity threshold rejects values outside the unit interval")
    void similarityThresholdRejectsInvalidValue(double candidate) {
        MedRetrievalProperties properties = new MedRetrievalProperties();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setSimilarityThreshold(candidate))
                .withMessageContaining("med.rag.retrieval.similarity-threshold");
    }

    @Test
    @DisplayName("max query length accepts a positive value")
    void maxQueryLengthAcceptsPositiveValue() {
        MedRetrievalProperties properties = new MedRetrievalProperties();

        properties.setMaxQueryLength(2048);

        assertThat(properties.getMaxQueryLength()).isEqualTo(2048);
    }

    @ParameterizedTest(name = "max query length {0} is rejected")
    @ValueSource(ints = {0, -1})
    @DisplayName("max query length rejects non-positive values")
    void maxQueryLengthRejectsNonPositive(int candidate) {
        MedRetrievalProperties properties = new MedRetrievalProperties();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setMaxQueryLength(candidate))
                .withMessageContaining("med.rag.retrieval.max-query-length");
    }

    @Test
    @DisplayName("toString reports every knob")
    void toStringReportsEveryKnob() {
        MedRetrievalProperties properties = new MedRetrievalProperties();
        properties.setTopK(7);
        properties.setMaxTopK(9);
        properties.setSimilarityThreshold(0.3d);
        properties.setMaxQueryLength(64);

        assertThat(properties.toString())
                .contains("topK=7")
                .contains("maxTopK=9")
                .contains("similarityThreshold=0.3")
                .contains("maxQueryLength=64");
    }
}
