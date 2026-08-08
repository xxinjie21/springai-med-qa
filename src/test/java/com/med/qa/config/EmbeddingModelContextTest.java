package com.med.qa.config;

import com.knuddels.jtokkit.api.EncodingType;
import com.med.qa.rag.MedEmbeddingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Context-level guard tests for the embedding model wiring.
 *
 * <p>Adding the OpenAI starter puts six model auto-configurations on the classpath, each of which
 * builds its model eagerly and throws when no API key is present. These tests pin the invariants
 * that keep the whole suite runnable on a machine without credentials or middleware: the model
 * auto-configuration stays off, the project beans stay lazy, and a missing key surfaces as a
 * precise message on first use rather than as a context startup failure.</p>
 *
 * <p>The API key is explicitly blanked so the assertions do not depend on the developer's
 * environment variables.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.ai.openai.api-key="
})
class EmbeddingModelContextTest {

    @Autowired
    private ConfigurableApplicationContext context;

    @Test
    @DisplayName("the embedding model is declared but not instantiated during context refresh")
    void embeddingModelBeanIsLazy() {
        assertThat(context.getBeanFactory()
                .getBeanDefinition(EmbeddingModelConfig.EMBEDDING_MODEL).isLazyInit()).isTrue();
        assertThat(context.getBeanFactory()
                .containsSingleton(EmbeddingModelConfig.EMBEDDING_MODEL)).isFalse();
    }

    @Test
    @DisplayName("the api client and the batching strategy stay lazy as well")
    void collaboratorBeansAreLazy() {
        assertThat(context.getBeanFactory()
                .getBeanDefinition(EmbeddingModelConfig.EMBEDDING_API).isLazyInit()).isTrue();
        assertThat(context.getBeanFactory()
                .containsSingleton(EmbeddingModelConfig.EMBEDDING_API)).isFalse();
        assertThat(context.getBeanFactory()
                .getBeanDefinition(EmbeddingModelConfig.BATCHING_STRATEGY).isLazyInit()).isTrue();
    }

    @Test
    @DisplayName("the eager model auto-configuration is switched off")
    void modelAutoConfigurationIsDisabled() {
        assertThat(context.getEnvironment().getProperty("spring.ai.model.embedding"))
                .as("the official opt-out switch must keep OpenAiEmbeddingAutoConfiguration off")
                .isEqualTo("none");
        assertThat(context.getEnvironment().getProperty("spring.ai.model.chat")).isEqualTo("none");
        assertThat(context.containsBean("openAiEmbeddingModel"))
                .as("no auto-configured embedding model may exist")
                .isFalse();
    }

    @Test
    @DisplayName("the vector store can resolve exactly one embedding model without creating it")
    void vectorStoreSeesASingleEmbeddingModel() {
        String[] names = context.getBeanFactory()
                .getBeanNamesForType(EmbeddingModel.class, true, false);

        assertThat(names).containsExactly(EmbeddingModelConfig.EMBEDDING_MODEL);
        assertThat(context.getBeanFactory()
                .containsSingleton(EmbeddingModelConfig.EMBEDDING_MODEL)).isFalse();
    }

    @Test
    @DisplayName("the official retry template is available for model calls")
    void retryTemplateIsAvailable() {
        assertThat(context.getBeanProvider(RetryTemplate.class).getIfUnique()).isNotNull();
        assertThat(context.getEnvironment().getProperty("spring.ai.retry.max-attempts")).isEqualTo("3");
    }

    @Test
    @DisplayName("the endpoint settings are bound from the official namespace")
    void officialPropertiesAreBound() {
        OpenAiEmbeddingProperties properties = context.getBean(OpenAiEmbeddingProperties.class);

        assertThat(properties.getEmbeddingsPath()).isEqualTo("/v1/embeddings");
        assertThat(properties.getMetadataMode()).isEqualTo(MetadataMode.EMBED);
        assertThat(properties.getOptions().getModel()).isEqualTo("text-embedding-3-small");
        assertThat(properties.getOptions().getDimensions()).isEqualTo(1536);
    }

    @Test
    @DisplayName("the project tuning is bound and agrees with the index width")
    void projectTuningIsBound() {
        MedEmbeddingProperties properties = context.getBean(MedEmbeddingProperties.class);
        OpenAiEmbeddingProperties official = context.getBean(OpenAiEmbeddingProperties.class);

        assertThat(properties.getExpectedDimensions()).isEqualTo(1536);
        assertThat(properties.getEncodingType()).isEqualTo(EncodingType.CL100K_BASE);
        assertThat(properties.getMaxInputTokenCount()).isEqualTo(8191);
        assertThat(official.getOptions().getDimensions()).isEqualTo(properties.getExpectedDimensions());
    }

    @Test
    @DisplayName("a missing credential fails on first use, not during startup")
    void missingCredentialFailsOnFirstUse() {
        assertThatThrownBy(() -> context.getBean(EmbeddingModelConfig.EMBEDDING_MODEL))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.ai.openai.api-key");
    }
}
