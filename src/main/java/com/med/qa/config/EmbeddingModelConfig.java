package com.med.qa.config;

import com.med.qa.rag.MedEmbeddingProperties;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingProperties;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;

/**
 * Wiring of the OpenAI-compatible {@link EmbeddingModel} that vectorizes medical documents and
 * queries for the RAG layer.
 *
 * <h2>Everything comes from official components and official properties</h2>
 * <ul>
 *   <li>the model is Spring AI's {@link OpenAiEmbeddingModel}; no HTTP payload, tokenizer or vector
 *       math is written here;</li>
 *   <li>endpoint and credentials are read from the canonical {@code spring.ai.openai.*} namespace
 *       ({@link OpenAiConnectionProperties} / {@link OpenAiEmbeddingProperties}), with the
 *       embedding-scoped values overriding the connection-wide ones exactly like upstream;</li>
 *   <li>connect / read timeouts come from Boot's {@code spring.http.client.*} because the
 *       auto-configured {@link RestClient.Builder} is reused;</li>
 *   <li>retries and backoff come from {@code spring.ai.retry.*} through the {@link RetryTemplate}
 *       published by Spring AI's retry auto-configuration;</li>
 *   <li>request batching is Spring AI's {@link TokenCountBatchingStrategy}, tuned by
 *       {@link MedEmbeddingProperties}.</li>
 * </ul>
 *
 * <p>Because the endpoint is only a base URL, an on-premise OpenAI-compatible gateway (the usual
 * deployment for hospital data) is a pure configuration change.</p>
 *
 * <h2>Why the model auto-configuration is switched off</h2>
 * <p>{@code OpenAiEmbeddingAutoConfiguration} builds its model eagerly and throws when no API key is
 * present, which would make the context — and therefore the whole offline test suite — unbootable
 * on a developer machine. {@code application.yml} sets {@code spring.ai.model.*: none} (the official
 * opt-out switch) and the beans below are declared {@link Lazy} instead: a missing key surfaces as a
 * precise {@link IllegalStateException} on first embedding call, never as a startup crash.</p>
 */
@Configuration
@EnableConfigurationProperties({
        OpenAiConnectionProperties.class,
        OpenAiEmbeddingProperties.class,
        MedEmbeddingProperties.class
})
public class EmbeddingModelConfig {

    /** Bean name of the OpenAI-compatible HTTP client used for embeddings. */
    public static final String EMBEDDING_API = "medEmbeddingApi";

    /** Bean name of the embedding model consumed by the vector store. */
    public static final String EMBEDDING_MODEL = "medEmbeddingModel";

    /** Bean name of the token-aware batching strategy. */
    public static final String BATCHING_STRATEGY = "medEmbeddingBatchingStrategy";

    /** Header carrying the OpenAI organization id, when configured. */
    public static final String ORGANIZATION_HEADER = "OpenAI-Organization";

    /** Header carrying the OpenAI project id, when configured. */
    public static final String PROJECT_HEADER = "OpenAI-Project";

    /**
     * Creates the OpenAI-compatible API client used by the embedding model.
     *
     * @param connectionProperties         connection-wide {@code spring.ai.openai.*} settings, must
     *                                     not be {@code null}
     * @param embeddingProperties          embedding-scoped {@code spring.ai.openai.embedding.*}
     *                                     settings, must not be {@code null}
     * @param restClientBuilderProvider    provider of Boot's auto-configured builder, so
     *                                     {@code spring.http.client.*} timeouts apply
     * @param responseErrorHandlerProvider provider of the error handler published by Spring AI's
     *                                     retry auto-configuration
     * @return the configured client, never {@code null}
     * @throws IllegalStateException if no base URL or no API key is configured
     */
    @Bean(name = EMBEDDING_API)
    @Lazy
    public OpenAiApi medEmbeddingApi(OpenAiConnectionProperties connectionProperties,
                                     OpenAiEmbeddingProperties embeddingProperties,
                                     ObjectProvider<RestClient.Builder> restClientBuilderProvider,
                                     ObjectProvider<ResponseErrorHandler> responseErrorHandlerProvider) {
        return buildOpenAiApi(connectionProperties, embeddingProperties,
                restClientBuilderProvider.getIfUnique(RestClient::builder),
                responseErrorHandlerProvider.getIfUnique(() -> RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER));
    }

    /**
     * Creates the embedding model injected into the vector store.
     *
     * <p>Declared {@link Lazy}: no credential is required until RAG is actually exercised.</p>
     *
     * @param openAiApi                  the API client, must not be {@code null}
     * @param embeddingProperties        embedding options ({@code model}, {@code dimensions}, ...),
     *                                   must not be {@code null}
     * @param medProperties              project-side embedding tuning, must not be {@code null}
     * @param retryTemplateProvider      provider of the {@code spring.ai.retry.*} retry template
     * @param observationRegistryProvider provider of the Micrometer registry used for model metrics
     * @return the configured embedding model, never {@code null}
     * @throws IllegalStateException if the endpoint, credentials, model name or vector width are
     *                               inconsistent with the configured index
     */
    @Bean(name = EMBEDDING_MODEL)
    @Lazy
    public EmbeddingModel medEmbeddingModel(OpenAiApi openAiApi,
                                            OpenAiEmbeddingProperties embeddingProperties,
                                            MedEmbeddingProperties medProperties,
                                            ObjectProvider<RetryTemplate> retryTemplateProvider,
                                            ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        return buildEmbeddingModel(openAiApi, embeddingProperties, medProperties,
                retryTemplateProvider.getIfUnique(() -> RetryUtils.DEFAULT_RETRY_TEMPLATE),
                observationRegistryProvider.getIfUnique(() -> ObservationRegistry.NOOP));
    }

    /**
     * Creates the batching strategy the vector store uses to split large ingestions into
     * token-bounded embedding requests.
     *
     * @param properties batching tuning, must not be {@code null}
     * @return the official token-count batching strategy, never {@code null}
     * @throws IllegalArgumentException if {@code properties} is {@code null}
     */
    @Bean(name = BATCHING_STRATEGY)
    @Lazy
    public BatchingStrategy medEmbeddingBatchingStrategy(MedEmbeddingProperties properties) {
        return buildBatchingStrategy(properties);
    }

    /**
     * Builds the OpenAI-compatible client from the official properties.
     *
     * <p>Exposed as a static method so the mapping can be asserted without a running server: the
     * client performs no I/O until an embedding request is issued.</p>
     *
     * @param connectionProperties connection-wide settings, must not be {@code null}
     * @param embeddingProperties  embedding-scoped settings, must not be {@code null}
     * @param restClientBuilder    HTTP client builder carrying Boot's timeout settings, must not be
     *                             {@code null}
     * @param responseErrorHandler error handler classifying retryable responses, must not be
     *                             {@code null}
     * @return the configured client, never {@code null}
     * @throws IllegalArgumentException if an argument is {@code null}
     * @throws IllegalStateException    if no base URL or API key is configured
     */
    public static OpenAiApi buildOpenAiApi(OpenAiConnectionProperties connectionProperties,
                                           OpenAiEmbeddingProperties embeddingProperties,
                                           RestClient.Builder restClientBuilder,
                                           ResponseErrorHandler responseErrorHandler) {
        requireNonNull(connectionProperties, "connectionProperties");
        requireNonNull(embeddingProperties, "embeddingProperties");
        requireNonNull(restClientBuilder, "restClientBuilder");
        requireNonNull(responseErrorHandler, "responseErrorHandler");

        return OpenAiApi.builder()
                .baseUrl(resolveBaseUrl(connectionProperties, embeddingProperties))
                .apiKey(resolveApiKey(connectionProperties, embeddingProperties))
                .headers(resolveHeaders(connectionProperties, embeddingProperties))
                .embeddingsPath(resolveEmbeddingsPath(embeddingProperties))
                .restClientBuilder(restClientBuilder)
                .responseErrorHandler(responseErrorHandler)
                .build();
    }

    /**
     * Builds the embedding model and validates it against the vector index topology.
     *
     * @param openAiApi           API client, must not be {@code null}
     * @param embeddingProperties embedding options, must not be {@code null}
     * @param medProperties       project-side tuning, must not be {@code null}
     * @param retryTemplate       retry policy, must not be {@code null}
     * @param observationRegistry observation registry, must not be {@code null}
     * @return the configured model, never {@code null}
     * @throws IllegalArgumentException if an argument is {@code null}
     * @throws IllegalStateException    if the model name is blank, or the requested vector width
     *                                  disagrees with the width of the configured index
     */
    public static OpenAiEmbeddingModel buildEmbeddingModel(OpenAiApi openAiApi,
                                                           OpenAiEmbeddingProperties embeddingProperties,
                                                           MedEmbeddingProperties medProperties,
                                                           RetryTemplate retryTemplate,
                                                           ObservationRegistry observationRegistry) {
        requireNonNull(openAiApi, "openAiApi");
        requireNonNull(embeddingProperties, "embeddingProperties");
        requireNonNull(medProperties, "medProperties");
        requireNonNull(retryTemplate, "retryTemplate");
        requireNonNull(observationRegistry, "observationRegistry");

        OpenAiEmbeddingOptions options = embeddingProperties.getOptions();
        if (options == null) {
            throw new IllegalStateException(
                    OpenAiEmbeddingProperties.CONFIG_PREFIX + ".options must be configured");
        }
        if (!StringUtils.hasText(options.getModel())) {
            throw new IllegalStateException(
                    OpenAiEmbeddingProperties.CONFIG_PREFIX + ".options.model must not be blank");
        }
        verifyDimensions(medProperties, options);

        MetadataMode metadataMode = embeddingProperties.getMetadataMode() != null
                ? embeddingProperties.getMetadataMode()
                : MetadataMode.EMBED;
        return new OpenAiEmbeddingModel(openAiApi, metadataMode, options, retryTemplate, observationRegistry);
    }

    /**
     * Fails fast when the model is asked for vectors the RediSearch index cannot store.
     *
     * <p>The index is created once with a fixed width; a later model swap that changes the width
     * would otherwise be discovered as rejected writes at ingestion time.</p>
     *
     * @param medProperties project-side tuning holding the index width, must not be {@code null}
     * @param options       the official embedding options, must not be {@code null}
     * @throws IllegalArgumentException if an argument is {@code null}
     * @throws IllegalStateException    if both widths are set and disagree
     */
    public static void verifyDimensions(MedEmbeddingProperties medProperties, OpenAiEmbeddingOptions options) {
        requireNonNull(medProperties, "medProperties");
        requireNonNull(options, "options");

        Integer requested = options.getDimensions();
        if (requested != null && requested != medProperties.getExpectedDimensions()) {
            throw new IllegalStateException(
                    MedEmbeddingProperties.OPENAI_DIMENSIONS_PROPERTY + " is " + requested
                            + " but the vector index is built for "
                            + medProperties.getExpectedDimensions() + " dimensions ("
                            + MedEmbeddingProperties.PREFIX + ".expected-dimensions); "
                            + "align both values or rebuild the index");
        }
    }

    /**
     * Builds the token-aware batching strategy.
     *
     * @param properties batching tuning, must not be {@code null}
     * @return the official strategy, never {@code null}
     * @throws IllegalArgumentException if {@code properties} is {@code null}
     */
    public static TokenCountBatchingStrategy buildBatchingStrategy(MedEmbeddingProperties properties) {
        requireNonNull(properties, "properties");
        return new TokenCountBatchingStrategy(properties.getEncodingType(),
                properties.getMaxInputTokenCount(), properties.getReservePercentage());
    }

    /**
     * Resolves the endpoint, letting the embedding-scoped value win over the connection-wide one.
     *
     * @param connectionProperties connection-wide settings, must not be {@code null}
     * @param embeddingProperties  embedding-scoped settings, must not be {@code null}
     * @return the base URL to call, never blank
     * @throws IllegalArgumentException if an argument is {@code null}
     * @throws IllegalStateException    if neither level configures a base URL
     */
    public static String resolveBaseUrl(OpenAiConnectionProperties connectionProperties,
                                        OpenAiEmbeddingProperties embeddingProperties) {
        requireNonNull(connectionProperties, "connectionProperties");
        requireNonNull(embeddingProperties, "embeddingProperties");

        String baseUrl = StringUtils.hasText(embeddingProperties.getBaseUrl())
                ? embeddingProperties.getBaseUrl()
                : connectionProperties.getBaseUrl();
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException(
                    "No embedding endpoint configured: set " + OpenAiConnectionProperties.CONFIG_PREFIX
                            + ".base-url (or " + OpenAiEmbeddingProperties.CONFIG_PREFIX + ".base-url)");
        }
        return baseUrl;
    }

    /**
     * Resolves the credential, letting the embedding-scoped value win over the connection-wide one.
     *
     * @param connectionProperties connection-wide settings, must not be {@code null}
     * @param embeddingProperties  embedding-scoped settings, must not be {@code null}
     * @return the API key to send, never blank
     * @throws IllegalArgumentException if an argument is {@code null}
     * @throws IllegalStateException    if neither level configures a key; the message names the
     *                                  property to set rather than leaking any value
     */
    public static String resolveApiKey(OpenAiConnectionProperties connectionProperties,
                                       OpenAiEmbeddingProperties embeddingProperties) {
        requireNonNull(connectionProperties, "connectionProperties");
        requireNonNull(embeddingProperties, "embeddingProperties");

        String apiKey = StringUtils.hasText(embeddingProperties.getApiKey())
                ? embeddingProperties.getApiKey()
                : connectionProperties.getApiKey();
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException(
                    "No embedding API key configured: set " + OpenAiConnectionProperties.CONFIG_PREFIX
                            + ".api-key (or " + OpenAiEmbeddingProperties.CONFIG_PREFIX + ".api-key), "
                            + "typically through the OPENAI_API_KEY environment variable");
        }
        return apiKey;
    }

    /**
     * Resolves the embeddings path of the OpenAI-compatible endpoint.
     *
     * @param embeddingProperties embedding-scoped settings, must not be {@code null}
     * @return the configured path, or the official default when unset
     * @throws IllegalArgumentException if {@code embeddingProperties} is {@code null}
     */
    public static String resolveEmbeddingsPath(OpenAiEmbeddingProperties embeddingProperties) {
        requireNonNull(embeddingProperties, "embeddingProperties");
        return StringUtils.hasText(embeddingProperties.getEmbeddingsPath())
                ? embeddingProperties.getEmbeddingsPath()
                : OpenAiEmbeddingProperties.DEFAULT_EMBEDDINGS_PATH;
    }

    /**
     * Builds the tenancy headers understood by OpenAI-compatible gateways.
     *
     * @param connectionProperties connection-wide settings, must not be {@code null}
     * @param embeddingProperties  embedding-scoped settings, must not be {@code null}
     * @return headers to attach to every embedding call, never {@code null} (possibly empty)
     * @throws IllegalArgumentException if an argument is {@code null}
     */
    public static MultiValueMap<String, String> resolveHeaders(OpenAiConnectionProperties connectionProperties,
                                                               OpenAiEmbeddingProperties embeddingProperties) {
        requireNonNull(connectionProperties, "connectionProperties");
        requireNonNull(embeddingProperties, "embeddingProperties");

        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        String organizationId = StringUtils.hasText(embeddingProperties.getOrganizationId())
                ? embeddingProperties.getOrganizationId()
                : connectionProperties.getOrganizationId();
        if (StringUtils.hasText(organizationId)) {
            headers.add(ORGANIZATION_HEADER, organizationId);
        }
        String projectId = StringUtils.hasText(embeddingProperties.getProjectId())
                ? embeddingProperties.getProjectId()
                : connectionProperties.getProjectId();
        if (StringUtils.hasText(projectId)) {
            headers.add(PROJECT_HEADER, projectId);
        }
        return headers;
    }

    private static void requireNonNull(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
    }
}
