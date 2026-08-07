package com.med.qa.config;

import com.med.qa.rag.MedVectorStoreProperties;
import com.med.qa.rag.MedVectorStoreProperties.MetadataFieldSpec;
import com.med.qa.rag.MedVectorStoreProperties.MetadataFieldType;
import com.med.qa.rag.MedVectorStoreProperties.VectorAlgorithm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.search.Schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link VectorStoreConfig}.
 *
 * <p>The store is built with a mocked Jedis client and a mocked embedding model: the official
 * {@code RedisVectorStore} only reaches Redis in {@code afterPropertiesSet()}, so the whole wiring
 * can be asserted without a Redis Stack instance.</p>
 */
class VectorStoreConfigTest {

    private static MedVectorStoreProperties defaultProperties() {
        return new MedVectorStoreProperties();
    }

    private static RedisProperties redisProperties() {
        RedisProperties properties = new RedisProperties();
        properties.setHost("redis.internal");
        properties.setPort(6380);
        properties.setDatabase(3);
        properties.setClientName("med-qa");
        properties.setTimeout(Duration.ofSeconds(4));
        properties.setConnectTimeout(Duration.ofSeconds(2));
        return properties;
    }

    // ---------------------------------------------------------------- buildVectorStore

    @Test
    @DisplayName("the store is built from the externalized index topology")
    void buildVectorStoreAppliesProperties() {
        MedVectorStoreProperties properties = defaultProperties();
        properties.setIndexName("cardiology-index");
        properties.setPrefix("med:kb:");
        properties.setContentFieldName("body");
        properties.setEmbeddingFieldName("vector");
        properties.setVectorAlgorithm(VectorAlgorithm.FLAT);
        properties.setInitializeSchema(false);

        RedisVectorStore store = VectorStoreConfig.buildVectorStore(
                Mockito.mock(JedisPooled.class), Mockito.mock(EmbeddingModel.class), properties, null);

        assertThat(ReflectionTestUtils.getField(store, "indexName")).isEqualTo("cardiology-index");
        assertThat(ReflectionTestUtils.getField(store, "prefix")).isEqualTo("med:kb:");
        assertThat(ReflectionTestUtils.getField(store, "contentFieldName")).isEqualTo("body");
        assertThat(ReflectionTestUtils.getField(store, "embeddingFieldName")).isEqualTo("vector");
        assertThat(ReflectionTestUtils.getField(store, "vectorAlgorithm"))
                .isEqualTo(RedisVectorStore.Algorithm.FLAT);
        assertThat(ReflectionTestUtils.getField(store, "initializeSchema")).isEqualTo(false);
    }

    @Test
    @DisplayName("defaults produce the medical document index with the isolation tags indexed")
    @SuppressWarnings("unchecked")
    void buildVectorStoreIndexesIsolationTags() {
        RedisVectorStore store = VectorStoreConfig.buildVectorStore(
                Mockito.mock(JedisPooled.class), Mockito.mock(EmbeddingModel.class), defaultProperties(), null);

        assertThat(ReflectionTestUtils.getField(store, "indexName")).isEqualTo("med-doc-index");
        assertThat(ReflectionTestUtils.getField(store, "prefix")).isEqualTo("med:doc:");
        List<RedisVectorStore.MetadataField> fields =
                (List<RedisVectorStore.MetadataField>) ReflectionTestUtils.getField(store, "metadataFields");
        assertThat(fields)
                .extracting(RedisVectorStore.MetadataField::name)
                .containsExactly("tenant_id", "dept_id", "patient_id");
        assertThat(fields)
                .extracting(RedisVectorStore.MetadataField::fieldType)
                .containsOnly(Schema.FieldType.TAG);
    }

    @Test
    @DisplayName("an explicit batching strategy is handed to the store")
    void buildVectorStoreAcceptsBatchingStrategy() {
        BatchingStrategy batchingStrategy = Mockito.mock(BatchingStrategy.class);

        RedisVectorStore store = VectorStoreConfig.buildVectorStore(
                Mockito.mock(JedisPooled.class), Mockito.mock(EmbeddingModel.class),
                defaultProperties(), batchingStrategy);

        assertThat(ReflectionTestUtils.getField(store, "batchingStrategy")).isSameAs(batchingStrategy);
    }

    @Test
    @DisplayName("null arguments are rejected before any store is created")
    void buildVectorStoreRejectsNullArguments() {
        JedisPooled jedis = Mockito.mock(JedisPooled.class);
        EmbeddingModel model = Mockito.mock(EmbeddingModel.class);
        MedVectorStoreProperties properties = defaultProperties();

        assertThatThrownBy(() -> VectorStoreConfig.buildVectorStore(null, model, properties, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jedis");
        assertThatThrownBy(() -> VectorStoreConfig.buildVectorStore(jedis, null, properties, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embeddingModel");
        assertThatThrownBy(() -> VectorStoreConfig.buildVectorStore(jedis, model, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("properties");
    }

    @Test
    @DisplayName("colliding content and embedding field names are rejected")
    void buildVectorStoreRejectsCollidingFieldNames() {
        MedVectorStoreProperties properties = defaultProperties();
        properties.setContentFieldName("payload");
        properties.setEmbeddingFieldName("payload");

        assertThatThrownBy(() -> VectorStoreConfig.buildVectorStore(
                Mockito.mock(JedisPooled.class), Mockito.mock(EmbeddingModel.class), properties, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ");
    }

    @Test
    @DisplayName("a distance metric the official store cannot create is rejected at build time")
    void buildVectorStoreRejectsUnsupportedDistanceMetric() {
        MedVectorStoreProperties properties = defaultProperties();
        properties.setDistanceMetric(MedVectorStoreProperties.DistanceMetric.L2);

        assertThatThrownBy(() -> VectorStoreConfig.buildVectorStore(
                Mockito.mock(JedisPooled.class), Mockito.mock(EmbeddingModel.class), properties, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COSINE")
                .hasMessageContaining("distance-metric");
    }

    // ---------------------------------------------------------------- enum mapping

    @Test
    @DisplayName("HNSW maps onto the upstream constant, which is spelled HSNW")
    void algorithmMapping() {
        assertThat(VectorStoreConfig.toRedisAlgorithm(VectorAlgorithm.HNSW))
                .isEqualTo(RedisVectorStore.Algorithm.HSNW);
        assertThat(VectorStoreConfig.toRedisAlgorithm(VectorAlgorithm.FLAT))
                .isEqualTo(RedisVectorStore.Algorithm.FLAT);
    }

    @Test
    @DisplayName("a null algorithm is rejected")
    void algorithmMappingRejectsNull() {
        assertThatThrownBy(() -> VectorStoreConfig.toRedisAlgorithm(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("algorithm");
    }

    @Test
    @DisplayName("every metadata field type maps onto a RediSearch schema type")
    void fieldTypeMapping() {
        assertThat(VectorStoreConfig.toRedisFieldType(MetadataFieldType.TAG)).isEqualTo(Schema.FieldType.TAG);
        assertThat(VectorStoreConfig.toRedisFieldType(MetadataFieldType.TEXT)).isEqualTo(Schema.FieldType.TEXT);
        assertThat(VectorStoreConfig.toRedisFieldType(MetadataFieldType.NUMERIC))
                .isEqualTo(Schema.FieldType.NUMERIC);
    }

    @Test
    @DisplayName("a null metadata field type is rejected")
    void fieldTypeMappingRejectsNull() {
        assertThatThrownBy(() -> VectorStoreConfig.toRedisFieldType(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
    }

    @Test
    @DisplayName("metadata specs are translated preserving order and type")
    void metadataFieldMapping() {
        List<RedisVectorStore.MetadataField> fields = VectorStoreConfig.toRedisMetadataFields(List.of(
                new MetadataFieldSpec("dept_id", MetadataFieldType.TAG),
                new MetadataFieldSpec("age", MetadataFieldType.NUMERIC)));

        assertThat(fields).hasSize(2);
        assertThat(fields.get(0).name()).isEqualTo("dept_id");
        assertThat(fields.get(0).fieldType()).isEqualTo(Schema.FieldType.TAG);
        assertThat(fields.get(1).name()).isEqualTo("age");
        assertThat(fields.get(1).fieldType()).isEqualTo(Schema.FieldType.NUMERIC);
    }

    @Test
    @DisplayName("an empty spec list produces an empty field list, null input is rejected")
    void metadataFieldMappingBoundaries() {
        assertThat(VectorStoreConfig.toRedisMetadataFields(List.of())).isEmpty();

        assertThatThrownBy(() -> VectorStoreConfig.toRedisMetadataFields(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metadataFields");
        assertThatThrownBy(() -> VectorStoreConfig.toRedisMetadataFields(
                Arrays.asList((MetadataFieldSpec) null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null entries");
    }

    // ---------------------------------------------------------------- jedis client config

    @Test
    @DisplayName("Boot's redis settings are translated into the jedis client config")
    void jedisClientConfigMapsBootSettings() {
        JedisClientConfig config = VectorStoreConfig.buildJedisClientConfig(redisProperties());

        assertThat(config.getDatabase()).isEqualTo(3);
        assertThat(config.getClientName()).isEqualTo("med-qa");
        assertThat(config.getSocketTimeoutMillis()).isEqualTo(4000);
        assertThat(config.getConnectionTimeoutMillis()).isEqualTo(2000);
        assertThat(config.isSsl()).isFalse();
    }

    @Test
    @DisplayName("credentials and ssl are propagated when configured")
    void jedisClientConfigMapsCredentialsAndSsl() {
        RedisProperties properties = redisProperties();
        properties.setUsername("med");
        properties.setPassword("s3cr3t");
        properties.getSsl().setEnabled(true);

        JedisClientConfig config = VectorStoreConfig.buildJedisClientConfig(properties);

        assertThat(config.getUser()).isEqualTo("med");
        assertThat(config.getPassword()).isEqualTo("s3cr3t");
        assertThat(config.isSsl()).isTrue();
    }

    @Test
    @DisplayName("absent optional settings leave the jedis defaults untouched")
    void jedisClientConfigKeepsDefaultsForAbsentSettings() {
        RedisProperties properties = new RedisProperties();
        properties.setHost("localhost");
        properties.setTimeout(null);
        properties.setConnectTimeout(null);

        JedisClientConfig config = VectorStoreConfig.buildJedisClientConfig(properties);

        assertThat(config.getUser()).isNull();
        assertThat(config.getPassword()).isNull();
        assertThat(config.getClientName()).isNull();
        assertThat(config.getDatabase()).isZero();
    }

    @Test
    @DisplayName("a non-positive timeout is ignored rather than producing a zero socket timeout")
    void jedisClientConfigIgnoresNonPositiveTimeouts() {
        RedisProperties properties = redisProperties();
        properties.setTimeout(Duration.ZERO);
        properties.setConnectTimeout(Duration.ofSeconds(-1));

        JedisClientConfig config = VectorStoreConfig.buildJedisClientConfig(properties);

        assertThat(config.getSocketTimeoutMillis()).isNotZero();
        assertThat(config.getConnectionTimeoutMillis()).isNotZero();
    }

    @Test
    @DisplayName("null redis properties are rejected")
    void jedisClientConfigRejectsNull() {
        assertThatThrownBy(() -> VectorStoreConfig.buildJedisClientConfig(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redisProperties");
    }

    @Test
    @DisplayName("a pooled client is created without opening a connection")
    void buildJedisPooledCreatesClient() {
        try (JedisPooled jedis = VectorStoreConfig.buildJedisPooled(redisProperties())) {
            assertThat(jedis).isNotNull();
        }
    }

    @Test
    @DisplayName("a blank host is rejected instead of silently falling back to localhost")
    void buildJedisPooledRejectsBlankHost() {
        RedisProperties properties = redisProperties();
        properties.setHost("  ");

        assertThatThrownBy(() -> VectorStoreConfig.buildJedisPooled(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
        assertThatThrownBy(() -> VectorStoreConfig.buildJedisPooled(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redisProperties");
    }

    // ---------------------------------------------------------------- bean methods

    @Test
    @DisplayName("the bean method builds the store from the available embedding model")
    void beanMethodBuildsStore() {
        VectorStoreConfig config = new VectorStoreConfig();
        EmbeddingModel embeddingModel = Mockito.mock(EmbeddingModel.class);

        VectorStore store = config.medVectorStore(Mockito.mock(JedisPooled.class),
                provider(embeddingModel), defaultProperties(), provider(null));

        assertThat(store).isInstanceOf(RedisVectorStore.class);
        assertThat(ReflectionTestUtils.getField(store, "indexName")).isEqualTo("med-doc-index");
    }

    @Test
    @DisplayName("a missing embedding model fails with an actionable message, not a NPE")
    void beanMethodRequiresEmbeddingModel() {
        VectorStoreConfig config = new VectorStoreConfig();

        assertThatThrownBy(() -> config.medVectorStore(Mockito.mock(JedisPooled.class),
                provider(null), defaultProperties(), provider(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EmbeddingModel");
    }

    @Test
    @DisplayName("the jedis bean method delegates to the validated factory")
    void jedisBeanMethodDelegates() {
        VectorStoreConfig config = new VectorStoreConfig();

        try (JedisPooled jedis = config.medVectorStoreJedis(redisProperties())) {
            assertThat(jedis).isNotNull();
        }

        RedisProperties blankHost = redisProperties();
        blankHost.setHost("");
        assertThatThrownBy(() -> config.medVectorStoreJedis(blankHost))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
