package com.med.qa.rag;

import com.knuddels.jtokkit.api.EncodingType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests for {@link MedEmbeddingProperties}.
 *
 * <p>The class is pure configuration, so the contract under test is the validation: every knob that
 * could silently corrupt the RAG index (wrong vector width, zero token budget, a reserve margin
 * that consumes the whole budget) must be rejected at binding time.</p>
 */
class MedEmbeddingPropertiesTest {

    private static MedEmbeddingProperties bind(Map<String, Object> values) {
        ConfigurationPropertySource source = new MapConfigurationPropertySource(values);
        return new Binder(source)
                .bind(MedEmbeddingProperties.PREFIX, Bindable.of(MedEmbeddingProperties.class))
                .orElseGet(MedEmbeddingProperties::new);
    }

    @Test
    @DisplayName("defaults match the 1536-wide OpenAI embedding family")
    void defaultsMatchOpenAiEmbeddingFamily() {
        MedEmbeddingProperties properties = new MedEmbeddingProperties();

        assertThat(properties.getExpectedDimensions()).isEqualTo(1536);
        assertThat(properties.getEncodingType()).isEqualTo(EncodingType.CL100K_BASE);
        assertThat(properties.getMaxInputTokenCount()).isEqualTo(8191);
        assertThat(properties.getReservePercentage()).isEqualTo(0.1d);
    }

    @Test
    @DisplayName("the configuration prefix stays under the med.rag namespace")
    void prefixIsStable() {
        assertThat(MedEmbeddingProperties.PREFIX).isEqualTo("med.rag.embedding");
        assertThat(MedEmbeddingProperties.OPENAI_DIMENSIONS_PROPERTY)
                .isEqualTo("spring.ai.openai.embedding.options.dimensions");
    }

    @Nested
    @DisplayName("expected dimensions")
    class ExpectedDimensions {

        @Test
        @DisplayName("accepts a positive width")
        void acceptsPositiveWidth() {
            MedEmbeddingProperties properties = new MedEmbeddingProperties();

            properties.setExpectedDimensions(3072);

            assertThat(properties.getExpectedDimensions()).isEqualTo(3072);
        }

        @Test
        @DisplayName("rejects zero and negative widths")
        void rejectsNonPositiveWidth() {
            MedEmbeddingProperties properties = new MedEmbeddingProperties();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> properties.setExpectedDimensions(0))
                    .withMessageContaining("expected-dimensions");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> properties.setExpectedDimensions(-1536));
        }
    }

    @Nested
    @DisplayName("encoding type")
    class Encoding {

        @Test
        @DisplayName("accepts another JTokkit encoding")
        void acceptsAnotherEncoding() {
            MedEmbeddingProperties properties = new MedEmbeddingProperties();

            properties.setEncodingType(EncodingType.O200K_BASE);

            assertThat(properties.getEncodingType()).isEqualTo(EncodingType.O200K_BASE);
        }

        @Test
        @DisplayName("rejects a null encoding")
        void rejectsNullEncoding() {
            MedEmbeddingProperties properties = new MedEmbeddingProperties();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> properties.setEncodingType(null))
                    .withMessageContaining("encoding-type");
        }
    }

    @Nested
    @DisplayName("token budget")
    class TokenBudget {

        @Test
        @DisplayName("accepts a positive budget")
        void acceptsPositiveBudget() {
            MedEmbeddingProperties properties = new MedEmbeddingProperties();

            properties.setMaxInputTokenCount(4096);

            assertThat(properties.getMaxInputTokenCount()).isEqualTo(4096);
        }

        @Test
        @DisplayName("rejects a non-positive budget")
        void rejectsNonPositiveBudget() {
            MedEmbeddingProperties properties = new MedEmbeddingProperties();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> properties.setMaxInputTokenCount(0))
                    .withMessageContaining("max-input-token-count");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> properties.setMaxInputTokenCount(-1));
        }
    }

    @Nested
    @DisplayName("reserve percentage")
    class ReservePercentage {

        @Test
        @DisplayName("accepts the whole [0, 1) range")
        void acceptsValidRange() {
            MedEmbeddingProperties properties = new MedEmbeddingProperties();

            assertThatCode(() -> properties.setReservePercentage(0.0d)).doesNotThrowAnyException();
            assertThat(properties.getReservePercentage()).isZero();

            properties.setReservePercentage(0.99d);
            assertThat(properties.getReservePercentage()).isEqualTo(0.99d);
        }

        @Test
        @DisplayName("rejects a margin that leaves no usable token budget")
        void rejectsFullMargin() {
            MedEmbeddingProperties properties = new MedEmbeddingProperties();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> properties.setReservePercentage(1.0d))
                    .withMessageContaining("reserve-percentage");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> properties.setReservePercentage(-0.01d));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> properties.setReservePercentage(Double.NaN));
        }
    }

    @Test
    @DisplayName("binds every knob from kebab-case configuration")
    void bindsFromConfiguration() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("med.rag.embedding.expected-dimensions", "1024");
        values.put("med.rag.embedding.encoding-type", "O200K_BASE");
        values.put("med.rag.embedding.max-input-token-count", "2048");
        values.put("med.rag.embedding.reserve-percentage", "0.25");

        MedEmbeddingProperties properties = bind(values);

        assertThat(properties.getExpectedDimensions()).isEqualTo(1024);
        assertThat(properties.getEncodingType()).isEqualTo(EncodingType.O200K_BASE);
        assertThat(properties.getMaxInputTokenCount()).isEqualTo(2048);
        assertThat(properties.getReservePercentage()).isEqualTo(0.25d);
    }

    @Test
    @DisplayName("an invalid bound value fails instead of being silently clamped")
    void invalidBoundValueFails() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("med.rag.embedding.expected-dimensions", "-1");

        assertThatCode(() -> bind(values))
                .as("binding must propagate the setter validation")
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("toString exposes the tuning without any credential")
    void toStringExposesTuningOnly() {
        String rendered = new MedEmbeddingProperties().toString();

        assertThat(rendered)
                .contains("expectedDimensions=1536")
                .contains("CL100K_BASE")
                .contains("maxInputTokenCount=8191")
                .doesNotContain("apiKey");
    }
}
