package com.med.qa.config;

import com.med.qa.rag.MedVectorStoreProperties;
import com.med.qa.rag.MedVectorStoreProperties.MetadataFieldSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context-level guard tests for the vector store wiring.
 *
 * <p>Adding the Redis vector store starter puts Jedis on the classpath and contributes an
 * auto-configuration that would eagerly create the search index. These tests pin the two
 * invariants that keeps the suite runnable without middleware: the store bean stays lazy, and
 * Spring Data Redis keeps using Lettuce for the conversation memory cache.</p>
 */
@SpringBootTest
@TestPropertySource(properties = "spring.flyway.enabled=false")
class VectorStoreContextTest {

    @Autowired
    private ConfigurableApplicationContext context;

    @Test
    @DisplayName("the vector store bean is declared but not instantiated during context refresh")
    void vectorStoreBeanIsLazy() {
        assertThat(context.getBeanFactory().getBeanDefinition(VectorStoreConfig.VECTOR_STORE).isLazyInit())
                .isTrue();
        assertThat(context.getBeanFactory().containsSingleton(VectorStoreConfig.VECTOR_STORE)).isFalse();
    }

    @Test
    @DisplayName("the dedicated jedis client is declared but not instantiated either")
    void jedisClientBeanIsLazy() {
        assertThat(context.getBeanFactory()
                .getBeanDefinition(VectorStoreConfig.VECTOR_STORE_JEDIS).isLazyInit()).isTrue();
        assertThat(context.getBeanFactory().containsSingleton(VectorStoreConfig.VECTOR_STORE_JEDIS)).isFalse();
    }

    @Test
    @DisplayName("the starter's auto-configuration is excluded, so no eager index creation happens")
    void redisVectorStoreAutoConfigurationExcluded() {
        List<String> excluded = Binder.get(context.getEnvironment())
                .bind("spring.autoconfigure.exclude", Bindable.listOf(String.class))
                .orElseGet(List::of);

        assertThat(excluded)
                .as("application.yml must exclude the eager Redis vector store auto-configuration")
                .contains("org.springframework.ai.vectorstore.redis.autoconfigure.RedisVectorStoreAutoConfiguration");
        assertThat(context.containsBean("vectorStore"))
                .as("the auto-configured 'vectorStore' bean must not be present")
                .isFalse();
    }

    @Test
    @DisplayName("the message cache keeps its Lettuce connection factory despite Jedis on the classpath")
    void redisConnectionFactoryStaysLettuce() {
        assertThat(context.getBean(RedisConnectionFactory.class)).isInstanceOf(LettuceConnectionFactory.class);
    }

    @Test
    @DisplayName("the index topology is bound from application.yml")
    void indexTopologyIsBound() {
        MedVectorStoreProperties properties = context.getBean(MedVectorStoreProperties.class);

        assertThat(properties.getIndexName()).isEqualTo("med-doc-index");
        assertThat(properties.getPrefix()).isEqualTo("med:doc:");
        assertThat(properties.getVectorAlgorithm())
                .isEqualTo(MedVectorStoreProperties.VectorAlgorithm.HNSW);
        assertThat(properties.getDistanceMetric())
                .isEqualTo(MedVectorStoreProperties.DistanceMetric.COSINE);
        assertThat(properties.getMetadataFields())
                .extracting(MetadataFieldSpec::getName)
                .containsExactly("tenant_id", "dept_id", "patient_id");
    }

    @Test
    @DisplayName("the configured key prefix never overlaps the conversation cache namespace")
    void prefixDoesNotOverlapChatNamespace() {
        MedVectorStoreProperties properties = context.getBean(MedVectorStoreProperties.class);

        assertThat(properties.getPrefix())
                .doesNotStartWith(MedVectorStoreProperties.RESERVED_CHAT_PREFIX);
    }
}
