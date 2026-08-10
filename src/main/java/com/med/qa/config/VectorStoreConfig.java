package com.med.qa.config;

import com.med.qa.rag.MedDocumentIngestionProperties;
import com.med.qa.rag.MedRetrievalProperties;
import com.med.qa.rag.MedVectorStoreProperties;
import com.med.qa.rag.MedVectorStoreProperties.MetadataFieldSpec;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.search.Schema;

/**
 * Wiring of the Spring AI {@code RedisVectorStore} (Redis Stack / RediSearch) used by the medical
 * RAG layer.
 *
 * <h2>What this class does and does not do</h2>
 * <p>It only instantiates the official store: index name, key prefix, JSON field names, vector
 * algorithm and the indexed metadata tags are read from {@code med.rag.vector-store.*}. Similarity
 * computation, Top-K selection, filter expression translation and index maintenance are entirely
 * the responsibility of {@code RedisVectorStore}; no vector math is implemented in this project.</p>
 *
 * <h2>Why the starter's auto-configuration is excluded</h2>
 * <p>{@code RedisVectorStoreAutoConfiguration} declares its store as an eager singleton and
 * requires a {@code JedisConnectionFactory} bean. Two problems follow: the conversation memory cache
 * (D9) deliberately runs on Lettuce, so no Jedis connection factory exists, and an eager store calls
 * {@code afterPropertiesSet()} during context refresh, which issues {@code FT.CREATE} against Redis.
 * Both would break the project invariant that the context boots — and the whole unit test suite
 * runs — without any middleware. The auto-configuration is therefore excluded in
 * {@code application.yml}, and the store is declared here as a {@link Lazy} bean holding its own
 * {@link JedisPooled} client, so the index is only touched on first retrieval.</p>
 *
 * <p>The connection settings still come from Spring Boot's official {@code spring.data.redis.*}
 * namespace, so the vector index, the message cache and Redisson always target the same instance.</p>
 */
@Configuration
@EnableConfigurationProperties({
        MedVectorStoreProperties.class,
        MedDocumentIngestionProperties.class,
        MedRetrievalProperties.class,
        RedisProperties.class
})
public class VectorStoreConfig {

    /** Bean name of the Jedis client dedicated to the vector index. */
    public static final String VECTOR_STORE_JEDIS = "medVectorStoreJedis";

    /** Bean name of the medical document vector store. */
    public static final String VECTOR_STORE = "medVectorStore";

    /**
     * The only distance metric the official {@code RedisVectorStore} creates its index with; it is
     * a private constant upstream and cannot be overridden through the builder.
     */
    public static final MedVectorStoreProperties.DistanceMetric SUPPORTED_DISTANCE_METRIC =
            MedVectorStoreProperties.DistanceMetric.COSINE;

    /**
     * Creates the Jedis client used by the vector index.
     *
     * <p>Declared {@link Lazy} so that no pool is allocated on a middleware-less startup; injection
     * points must be annotated {@code @Lazy} as well.</p>
     *
     * @param redisProperties Boot's {@code spring.data.redis.*} settings, must not be {@code null}
     * @return a pooled Jedis client pointing at the configured instance, never {@code null}
     * @throws IllegalArgumentException if the configured host is blank
     */
    @Bean(name = VECTOR_STORE_JEDIS, destroyMethod = "close")
    @Lazy
    public JedisPooled medVectorStoreJedis(RedisProperties redisProperties) {
        return buildJedisPooled(redisProperties);
    }

    /**
     * Creates the medical document vector store.
     *
     * <p>Declared {@link Lazy} for two reasons: {@code RedisVectorStore#afterPropertiesSet()} talks
     * to Redis, and the {@link EmbeddingModel} it depends on is contributed by a later iteration.
     * Until then the bean simply is never instantiated, which keeps the context bootable.</p>
     *
     * @param jedis                   lazily created Jedis client, must not be {@code null}
     * @param embeddingModelProvider  provider of the embedding model; resolution is deferred so a
     *                                missing model fails on first retrieval with a clear message
     *                                instead of breaking context startup
     * @param properties              {@code med.rag.vector-store.*} settings, must not be {@code null}
     * @param batchingStrategyProvider optional embedding batching strategy; the store falls back to
     *                                its own default when absent
     * @return the configured vector store, never {@code null}
     * @throws IllegalStateException if no {@link EmbeddingModel} bean is available
     */
    @Bean(name = VECTOR_STORE)
    @Lazy
    public VectorStore medVectorStore(@Lazy JedisPooled jedis,
                                      ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                      MedVectorStoreProperties properties,
                                      ObjectProvider<BatchingStrategy> batchingStrategyProvider) {
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            throw new IllegalStateException(
                    "No EmbeddingModel bean is available; the medical vector store cannot embed "
                            + "documents. Configure an embedding model before using RAG retrieval.");
        }
        return buildVectorStore(jedis, embeddingModel, properties, batchingStrategyProvider.getIfAvailable());
    }

    /**
     * Builds the official {@code RedisVectorStore} from the externalized index settings.
     *
     * <p>Exposed as a static method so the wiring can be asserted in unit tests without a Redis
     * Stack instance: the store only connects when {@code afterPropertiesSet()} runs.</p>
     *
     * @param jedis            Jedis client, must not be {@code null}
     * @param embeddingModel   embedding model used to vectorize documents, must not be {@code null}
     * @param properties       index settings, must not be {@code null}
     * @param batchingStrategy optional embedding batching strategy, may be {@code null}
     * @return the configured store, never {@code null}
     * @throws IllegalArgumentException if a required argument is {@code null}, or if the content and
     *                                  embedding field names collide (the index would overwrite the
     *                                  document text with its vector)
     * @throws IllegalStateException    if a distance metric other than
     *                                  {@link #SUPPORTED_DISTANCE_METRIC} is configured, which the
     *                                  official store cannot honour
     */
    public static RedisVectorStore buildVectorStore(JedisPooled jedis,
                                                    EmbeddingModel embeddingModel,
                                                    MedVectorStoreProperties properties,
                                                    @Nullable BatchingStrategy batchingStrategy) {
        if (jedis == null) {
            throw new IllegalArgumentException("jedis must not be null");
        }
        if (embeddingModel == null) {
            throw new IllegalArgumentException("embeddingModel must not be null");
        }
        if (properties == null) {
            throw new IllegalArgumentException("properties must not be null");
        }
        if (properties.getContentFieldName().equals(properties.getEmbeddingFieldName())) {
            throw new IllegalArgumentException(
                    "content-field-name and embedding-field-name must differ, both are '"
                            + properties.getContentFieldName() + "'");
        }
        if (properties.getDistanceMetric() != SUPPORTED_DISTANCE_METRIC) {
            throw new IllegalStateException(
                    "RedisVectorStore creates its index with " + SUPPORTED_DISTANCE_METRIC
                            + " distance only, but " + MedVectorStoreProperties.PREFIX
                            + ".distance-metric is " + properties.getDistanceMetric());
        }

        RedisVectorStore.Builder builder = RedisVectorStore.builder(jedis, embeddingModel)
                .indexName(properties.getIndexName())
                .prefix(properties.getPrefix())
                .contentFieldName(properties.getContentFieldName())
                .embeddingFieldName(properties.getEmbeddingFieldName())
                .vectorAlgorithm(toRedisAlgorithm(properties.getVectorAlgorithm()))
                .metadataFields(toRedisMetadataFields(properties.getMetadataFields()))
                .initializeSchema(properties.isInitializeSchema());
        if (batchingStrategy != null) {
            builder.batchingStrategy(batchingStrategy);
        }
        return builder.build();
    }

    /**
     * Maps the configured algorithm onto the enum of the official store.
     *
     * <p>The upstream constant for the graph index is spelled {@code HSNW}; the configuration
     * property keeps the conventional {@code HNSW} spelling and is translated here.</p>
     *
     * @param algorithm configured algorithm, must not be {@code null}
     * @return the matching {@code RedisVectorStore.Algorithm}, never {@code null}
     * @throws IllegalArgumentException if {@code algorithm} is {@code null}
     */
    public static RedisVectorStore.Algorithm toRedisAlgorithm(MedVectorStoreProperties.VectorAlgorithm algorithm) {
        if (algorithm == null) {
            throw new IllegalArgumentException("algorithm must not be null");
        }
        return switch (algorithm) {
            case HNSW -> RedisVectorStore.Algorithm.HSNW;
            case FLAT -> RedisVectorStore.Algorithm.FLAT;
        };
    }

    /**
     * Maps the configured metadata attributes onto the store's field descriptors.
     *
     * @param specs configured attributes, must not be {@code null} nor contain {@code null} entries
     * @return the matching {@code MetadataField} list, never {@code null}
     * @throws IllegalArgumentException if {@code specs} is {@code null} or holds a {@code null} entry
     */
    public static List<RedisVectorStore.MetadataField> toRedisMetadataFields(List<MetadataFieldSpec> specs) {
        if (specs == null) {
            throw new IllegalArgumentException("metadataFields must not be null");
        }
        List<RedisVectorStore.MetadataField> fields = new ArrayList<>(specs.size());
        for (MetadataFieldSpec spec : specs) {
            if (spec == null) {
                throw new IllegalArgumentException("metadataFields must not contain null entries");
            }
            fields.add(new RedisVectorStore.MetadataField(spec.getName(), toRedisFieldType(spec.getType())));
        }
        return fields;
    }

    /**
     * Maps a configured metadata field type onto the RediSearch schema field type.
     *
     * @param type configured type, must not be {@code null}
     * @return the matching RediSearch field type, never {@code null}
     * @throws IllegalArgumentException if {@code type} is {@code null}
     */
    public static Schema.FieldType toRedisFieldType(MedVectorStoreProperties.MetadataFieldType type) {
        if (type == null) {
            throw new IllegalArgumentException("metadata field type must not be null");
        }
        return switch (type) {
            case TAG -> Schema.FieldType.TAG;
            case TEXT -> Schema.FieldType.TEXT;
            case NUMERIC -> Schema.FieldType.NUMERIC;
        };
    }

    /**
     * Creates the pooled Jedis client from Boot's Redis settings.
     *
     * @param redisProperties Boot's {@code spring.data.redis.*} settings, must not be {@code null}
     * @return a pooled client, never {@code null}
     * @throws IllegalArgumentException if {@code redisProperties} is {@code null} or its host is blank
     */
    public static JedisPooled buildJedisPooled(RedisProperties redisProperties) {
        if (redisProperties == null) {
            throw new IllegalArgumentException("redisProperties must not be null");
        }
        if (!StringUtils.hasText(redisProperties.getHost())) {
            throw new IllegalArgumentException("spring.data.redis.host must not be blank");
        }
        return new JedisPooled(new HostAndPort(redisProperties.getHost(), redisProperties.getPort()),
                buildJedisClientConfig(redisProperties));
    }

    /**
     * Translates Boot's Redis settings into a Jedis client configuration.
     *
     * <p>Exposed as a static method so the mapping can be asserted without opening a connection.</p>
     *
     * @param redisProperties Boot's {@code spring.data.redis.*} settings, must not be {@code null}
     * @return the equivalent Jedis client configuration, never {@code null}
     * @throws IllegalArgumentException if {@code redisProperties} is {@code null}
     */
    public static JedisClientConfig buildJedisClientConfig(RedisProperties redisProperties) {
        if (redisProperties == null) {
            throw new IllegalArgumentException("redisProperties must not be null");
        }
        DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
                .database(redisProperties.getDatabase())
                .ssl(redisProperties.getSsl() != null && redisProperties.getSsl().isEnabled());
        if (StringUtils.hasText(redisProperties.getUsername())) {
            builder.user(redisProperties.getUsername());
        }
        if (StringUtils.hasText(redisProperties.getPassword())) {
            builder.password(redisProperties.getPassword());
        }
        if (StringUtils.hasText(redisProperties.getClientName())) {
            builder.clientName(redisProperties.getClientName());
        }
        applyIfPositive(redisProperties.getConnectTimeout(), builder::connectionTimeoutMillis);
        applyIfPositive(redisProperties.getTimeout(), builder::socketTimeoutMillis);
        return builder.build();
    }

    private static void applyIfPositive(Duration duration, java.util.function.IntConsumer setter) {
        if (duration != null && !duration.isZero() && !duration.isNegative()) {
            setter.accept((int) duration.toMillis());
        }
    }
}
