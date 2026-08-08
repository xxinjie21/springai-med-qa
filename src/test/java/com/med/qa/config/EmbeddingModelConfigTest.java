package com.med.qa.config;

import com.med.qa.rag.MedEmbeddingProperties;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.model.ApiKey;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingProperties;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EmbeddingModelConfig}.
 *
 * <p>No embedding endpoint is contacted: {@code OpenAiApi} and {@code OpenAiEmbeddingModel} only
 * perform I/O when a request is issued, so the wiring can be asserted offline. What is verified is
 * that the official {@code spring.ai.openai.*} properties end up in the right places, that the
 * embedding-scoped overrides beat the connection-wide ones, and that every misconfiguration that
 * would corrupt the RAG index or leak an unusable model fails fast with a message naming the
 * offending property.</p>
 */
class EmbeddingModelConfigTest {

    private static final String BASE_URL = "https://med-gateway.internal";

    private static final String API_KEY = "sk-test-key";

    private EmbeddingModelConfig config;

    private OpenAiConnectionProperties connection;

    private OpenAiEmbeddingProperties embedding;

    private MedEmbeddingProperties medProperties;

    /**
     * Emulates a bean provider that resolves to {@code value}, or falls back to the caller supplied
     * default when {@code value} is {@code null} (the "no such bean" case).
     */
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfUnique(any(Supplier.class))).thenAnswer(invocation -> {
            Supplier<T> fallback = invocation.getArgument(0);
            return value != null ? value : fallback.get();
        });
        return provider;
    }

    @BeforeEach
    void setUp() {
        config = new EmbeddingModelConfig();
        connection = new OpenAiConnectionProperties();
        connection.setBaseUrl(BASE_URL);
        connection.setApiKey(API_KEY);
        embedding = new OpenAiEmbeddingProperties();
        medProperties = new MedEmbeddingProperties();
    }

    @Nested
    @DisplayName("base url resolution")
    class BaseUrlResolution {

        @Test
        @DisplayName("falls back to the connection-wide endpoint")
        void fallsBackToConnectionEndpoint() {
            assertThat(EmbeddingModelConfig.resolveBaseUrl(connection, embedding)).isEqualTo(BASE_URL);
        }

        @Test
        @DisplayName("the embedding-scoped endpoint wins")
        void embeddingScopedEndpointWins() {
            embedding.setBaseUrl("https://embeddings.internal");

            assertThat(EmbeddingModelConfig.resolveBaseUrl(connection, embedding))
                    .isEqualTo("https://embeddings.internal");
        }

        @Test
        @DisplayName("a missing endpoint fails naming the property")
        void missingEndpointFails() {
            connection.setBaseUrl("  ");

            assertThatIllegalStateException()
                    .isThrownBy(() -> EmbeddingModelConfig.resolveBaseUrl(connection, embedding))
                    .withMessageContaining("spring.ai.openai.base-url");
        }

        @Test
        @DisplayName("null arguments are rejected as programming errors")
        void nullArgumentsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> EmbeddingModelConfig.resolveBaseUrl(null, embedding));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> EmbeddingModelConfig.resolveBaseUrl(connection, null));
        }
    }

    @Nested
    @DisplayName("api key resolution")
    class ApiKeyResolution {

        @Test
        @DisplayName("falls back to the connection-wide credential")
        void fallsBackToConnectionCredential() {
            assertThat(EmbeddingModelConfig.resolveApiKey(connection, embedding)).isEqualTo(API_KEY);
        }

        @Test
        @DisplayName("the embedding-scoped credential wins")
        void embeddingScopedCredentialWins() {
            embedding.setApiKey("sk-embedding-key");

            assertThat(EmbeddingModelConfig.resolveApiKey(connection, embedding)).isEqualTo("sk-embedding-key");
        }

        @Test
        @DisplayName("a missing credential fails naming the property, never a value")
        void missingCredentialFails() {
            connection.setApiKey("");

            assertThatIllegalStateException()
                    .isThrownBy(() -> EmbeddingModelConfig.resolveApiKey(connection, embedding))
                    .withMessageContaining("spring.ai.openai.api-key")
                    .withMessageContaining("OPENAI_API_KEY");
        }

        @Test
        @DisplayName("null arguments are rejected as programming errors")
        void nullArgumentsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> EmbeddingModelConfig.resolveApiKey(null, embedding));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> EmbeddingModelConfig.resolveApiKey(connection, null));
        }
    }

    @Nested
    @DisplayName("embeddings path and headers")
    class PathAndHeaders {

        @Test
        @DisplayName("uses the official default path when unset")
        void defaultsToOfficialPath() {
            assertThat(EmbeddingModelConfig.resolveEmbeddingsPath(embedding))
                    .isEqualTo(OpenAiEmbeddingProperties.DEFAULT_EMBEDDINGS_PATH);
        }

        @Test
        @DisplayName("honours a gateway specific path")
        void honoursGatewayPath() {
            embedding.setEmbeddingsPath("/api/v1/embeddings");

            assertThat(EmbeddingModelConfig.resolveEmbeddingsPath(embedding)).isEqualTo("/api/v1/embeddings");
        }

        @Test
        @DisplayName("no tenancy header is sent when none is configured")
        void noHeadersWhenUnconfigured() {
            assertThat(EmbeddingModelConfig.resolveHeaders(connection, embedding)).isEmpty();
        }

        @Test
        @DisplayName("organization and project headers are taken from the most specific level")
        void headersUseMostSpecificLevel() {
            connection.setOrganizationId("org-hospital");
            connection.setProjectId("proj-connection");
            embedding.setProjectId("proj-embedding");

            MultiValueMap<String, String> headers = EmbeddingModelConfig.resolveHeaders(connection, embedding);

            assertThat(headers.getFirst(EmbeddingModelConfig.ORGANIZATION_HEADER)).isEqualTo("org-hospital");
            assertThat(headers.getFirst(EmbeddingModelConfig.PROJECT_HEADER)).isEqualTo("proj-embedding");
        }

        @Test
        @DisplayName("null arguments are rejected as programming errors")
        void nullArgumentsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> EmbeddingModelConfig.resolveEmbeddingsPath(null));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> EmbeddingModelConfig.resolveHeaders(null, embedding));
        }
    }

    @Nested
    @DisplayName("api client assembly")
    class ApiClientAssembly {

        @Test
        @DisplayName("carries endpoint, credential and path onto the official client")
        void carriesSettingsOntoClient() {
            embedding.setEmbeddingsPath("/api/v1/embeddings");
            connection.setOrganizationId("org-hospital");

            OpenAiApi api = EmbeddingModelConfig.buildOpenAiApi(connection, embedding,
                    RestClient.builder(), RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER);

            assertThat(ReflectionTestUtils.getField(api, "baseUrl")).isEqualTo(BASE_URL);
            assertThat(ReflectionTestUtils.getField(api, "embeddingsPath")).isEqualTo("/api/v1/embeddings");
            assertThat(((ApiKey) ReflectionTestUtils.getField(api, "apiKey")).getValue()).isEqualTo(API_KEY);
            @SuppressWarnings("unchecked")
            MultiValueMap<String, String> headers =
                    (MultiValueMap<String, String>) ReflectionTestUtils.getField(api, "headers");
            assertThat(headers.getFirst(EmbeddingModelConfig.ORGANIZATION_HEADER)).isEqualTo("org-hospital");
        }

        @Test
        @DisplayName("a missing credential aborts the assembly")
        void missingCredentialAbortsAssembly() {
            connection.setApiKey(null);

            assertThatIllegalStateException()
                    .isThrownBy(() -> EmbeddingModelConfig.buildOpenAiApi(connection, embedding,
                            RestClient.builder(), RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER))
                    .withMessageContaining("api-key");
        }

        @Test
        @DisplayName("null collaborators are rejected as programming errors")
        void nullCollaboratorsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> EmbeddingModelConfig.buildOpenAiApi(connection, embedding,
                            null, RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> EmbeddingModelConfig.buildOpenAiApi(connection, embedding,
                            RestClient.builder(), null));
        }
    }

    @Nested
    @DisplayName("embedding model assembly")
    class EmbeddingModelAssembly {

        private OpenAiApi api;

        @BeforeEach
        void createApi() {
            api = EmbeddingModelConfig.buildOpenAiApi(connection, embedding,
                    RestClient.builder(), RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER);
            embedding.getOptions().setModel("text-embedding-3-small");
            embedding.getOptions().setDimensions(1536);
        }

        @Test
        @DisplayName("hands the official options, metadata mode and retry template to the model")
        void handsOfficialSettingsToModel() {
            RetryTemplate retryTemplate = new RetryTemplate();
            embedding.setMetadataMode(MetadataMode.NONE);

            OpenAiEmbeddingModel model = EmbeddingModelConfig.buildEmbeddingModel(api, embedding,
                    medProperties, retryTemplate, ObservationRegistry.NOOP);

            assertThat(ReflectionTestUtils.getField(model, "openAiApi")).isSameAs(api);
            assertThat(ReflectionTestUtils.getField(model, "retryTemplate")).isSameAs(retryTemplate);
            assertThat(ReflectionTestUtils.getField(model, "metadataMode")).isEqualTo(MetadataMode.NONE);
            OpenAiEmbeddingOptions options =
                    (OpenAiEmbeddingOptions) ReflectionTestUtils.getField(model, "defaultOptions");
            assertThat(options.getModel()).isEqualTo("text-embedding-3-small");
            assertThat(options.getDimensions()).isEqualTo(1536);
        }

        @Test
        @DisplayName("embeds document text only when no metadata mode is configured")
        void defaultsToEmbedMetadataMode() {
            embedding.setMetadataMode(null);

            OpenAiEmbeddingModel model = EmbeddingModelConfig.buildEmbeddingModel(api, embedding,
                    medProperties, RetryUtils.DEFAULT_RETRY_TEMPLATE, ObservationRegistry.NOOP);

            assertThat(ReflectionTestUtils.getField(model, "metadataMode")).isEqualTo(MetadataMode.EMBED);
        }

        @Test
        @DisplayName("a blank model name aborts the assembly")
        void blankModelNameAborts() {
            embedding.getOptions().setModel(" ");

            assertThatIllegalStateException()
                    .isThrownBy(() -> EmbeddingModelConfig.buildEmbeddingModel(api, embedding,
                            medProperties, RetryUtils.DEFAULT_RETRY_TEMPLATE, ObservationRegistry.NOOP))
                    .withMessageContaining("options.model");
        }

        @Test
        @DisplayName("missing options abort the assembly")
        void missingOptionsAbort() {
            embedding.setOptions(null);

            assertThatIllegalStateException()
                    .isThrownBy(() -> EmbeddingModelConfig.buildEmbeddingModel(api, embedding,
                            medProperties, RetryUtils.DEFAULT_RETRY_TEMPLATE, ObservationRegistry.NOOP))
                    .withMessageContaining("options");
        }

        @Test
        @DisplayName("null collaborators are rejected as programming errors")
        void nullCollaboratorsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> EmbeddingModelConfig.buildEmbeddingModel(null, embedding,
                            medProperties, RetryUtils.DEFAULT_RETRY_TEMPLATE, ObservationRegistry.NOOP));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> EmbeddingModelConfig.buildEmbeddingModel(api, embedding,
                            medProperties, null, ObservationRegistry.NOOP));
        }
    }

    @Nested
    @DisplayName("index width agreement")
    class IndexWidthAgreement {

        @Test
        @DisplayName("agreeing widths pass")
        void agreeingWidthsPass() {
            OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder().dimensions(1536).build();

            EmbeddingModelConfig.verifyDimensions(medProperties, options);
        }

        @Test
        @DisplayName("an unset width leaves the model in charge")
        void unsetWidthIsAccepted() {
            OpenAiEmbeddingOptions options = new OpenAiEmbeddingOptions();

            EmbeddingModelConfig.verifyDimensions(medProperties, options);
        }

        @Test
        @DisplayName("a model producing vectors the index cannot store is refused")
        void conflictingWidthIsRefused() {
            OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder().dimensions(3072).build();

            assertThatIllegalStateException()
                    .isThrownBy(() -> EmbeddingModelConfig.verifyDimensions(medProperties, options))
                    .withMessageContaining(MedEmbeddingProperties.OPENAI_DIMENSIONS_PROPERTY)
                    .withMessageContaining("1536");
        }

        @Test
        @DisplayName("null arguments are rejected as programming errors")
        void nullArgumentsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> EmbeddingModelConfig.verifyDimensions(null, new OpenAiEmbeddingOptions()));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> EmbeddingModelConfig.verifyDimensions(medProperties, null));
        }
    }

    @Nested
    @DisplayName("batching strategy")
    class Batching {

        @Test
        @DisplayName("groups short documents into a single embedding request")
        void groupsShortDocuments() {
            TokenCountBatchingStrategy strategy = EmbeddingModelConfig.buildBatchingStrategy(medProperties);

            List<List<Document>> batches = strategy.batch(List.of(
                    new Document("patient reports intermittent chest pain"),
                    new Document("no known drug allergies")));

            assertThat(batches).hasSize(1);
            assertThat(batches.get(0)).hasSize(2);
        }

        @Test
        @DisplayName("a document larger than the whole budget is refused instead of truncated")
        void oversizedDocumentIsRefused() {
            medProperties.setMaxInputTokenCount(1);
            TokenCountBatchingStrategy strategy = EmbeddingModelConfig.buildBatchingStrategy(medProperties);
            List<Document> documents = List.of(new Document("a long medical discharge summary"));

            assertThatIllegalArgumentException().isThrownBy(() -> strategy.batch(documents));
        }

        @Test
        @DisplayName("null properties are rejected as programming errors")
        void nullPropertiesRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> EmbeddingModelConfig.buildBatchingStrategy(null));
        }
    }

    @Nested
    @DisplayName("bean methods")
    class BeanMethods {

        @Test
        @DisplayName("the api bean reuses the auto-configured rest client builder and error handler")
        void apiBeanReusesOfficialCollaborators() {
            RestClient.Builder builder = RestClient.builder();
            ResponseErrorHandler errorHandler = mock(ResponseErrorHandler.class);

            OpenAiApi api = config.medEmbeddingApi(connection, embedding,
                    provider(builder), provider(errorHandler));

            assertThat(ReflectionTestUtils.getField(api, "responseErrorHandler")).isSameAs(errorHandler);
            assertThat(ReflectionTestUtils.getField(api, "baseUrl")).isEqualTo(BASE_URL);
        }

        @Test
        @DisplayName("the api bean falls back to the official defaults when no bean is published")
        void apiBeanFallsBackToDefaults() {
            OpenAiApi api = config.medEmbeddingApi(connection, embedding, provider(null), provider(null));

            assertThat(ReflectionTestUtils.getField(api, "responseErrorHandler"))
                    .isSameAs(RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER);
        }

        @Test
        @DisplayName("the model bean is an OpenAI embedding model wired with the retry template")
        void modelBeanIsWired() {
            OpenAiApi api = EmbeddingModelConfig.buildOpenAiApi(connection, embedding,
                    RestClient.builder(), RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER);
            RetryTemplate retryTemplate = new RetryTemplate();

            EmbeddingModel model = config.medEmbeddingModel(api, embedding, medProperties,
                    provider(retryTemplate), provider(ObservationRegistry.NOOP));

            assertThat(model).isInstanceOf(OpenAiEmbeddingModel.class);
            assertThat(ReflectionTestUtils.getField(model, "retryTemplate")).isSameAs(retryTemplate);
        }

        @Test
        @DisplayName("the model bean refuses a width the index cannot store")
        void modelBeanRefusesWidthMismatch() {
            OpenAiApi api = EmbeddingModelConfig.buildOpenAiApi(connection, embedding,
                    RestClient.builder(), RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER);
            embedding.getOptions().setDimensions(768);

            assertThatIllegalStateException()
                    .isThrownBy(() -> config.medEmbeddingModel(api, embedding, medProperties,
                            provider(null), provider(null)))
                    .withMessageContaining("expected-dimensions");
        }

        @Test
        @DisplayName("the batching strategy bean is the official token count strategy")
        void batchingStrategyBeanIsOfficial() {
            BatchingStrategy strategy = config.medEmbeddingBatchingStrategy(medProperties);

            assertThat(strategy).isInstanceOf(TokenCountBatchingStrategy.class);
        }

        @Test
        @DisplayName("bean names are stable so other configurations can reference them")
        void beanNamesAreStable() {
            assertThat(EmbeddingModelConfig.EMBEDDING_API).isEqualTo("medEmbeddingApi");
            assertThat(EmbeddingModelConfig.EMBEDDING_MODEL).isEqualTo("medEmbeddingModel");
            assertThat(EmbeddingModelConfig.BATCHING_STRATEGY).isEqualTo("medEmbeddingBatchingStrategy");
        }
    }
}
