package com.med.qa.config;

import com.med.qa.alert.MedAlertNotifier;
import com.med.qa.rag.MedDocumentIngestionProperties;
import com.med.qa.rag.MedDocumentService;
import com.med.qa.rag.MedIndexRebuildProperties;
import com.med.qa.rag.MedRagIndexProperties;
import com.med.qa.rag.MedVectorIndexRebuilder;
import com.med.qa.rag.MedVectorStoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.reflect.Method;

import redis.clients.jedis.JedisPooled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;

/**
 * Wiring tests for the D38 rebuild capability in {@link VectorStoreConfig}.
 *
 * <p>Two decisions are pinned here because both are invisible until they are wrong. First, the
 * rebuilder bean must be <em>absent</em> unless {@code med.rag.index.rebuild.enabled=true}: the
 * default has to be "this deployment cannot drop its search index", not "it can, but nobody calls
 * it". Second, the two cross-configuration contradictions have to fail at startup — a rebuild batch
 * larger than the ingestion limit would be rejected by the ingestion path on the first batch of a
 * live rebuild, and a rebuild with schema initialization switched off would drop the index and have
 * nothing recreate it. Both are configuration errors, so both must be discovered before the index is
 * touched, not after.</p>
 */
class VectorStoreRebuildWiringTest {

    private final VectorStoreConfig config = new VectorStoreConfig();

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MedAlertNotifier> emptyNotifierProvider() {
        return mock(ObjectProvider.class);
    }

    private static Method rebuildFactory() throws NoSuchMethodException {
        return VectorStoreConfig.class.getMethod("medVectorIndexRebuilder",
                RedisVectorStore.class, JedisPooled.class, MedVectorStoreProperties.class,
                MedRagIndexProperties.class, MedIndexRebuildProperties.class,
                MedDocumentIngestionProperties.class, MedDocumentService.class,
                ObjectProvider.class, RedissonClient.class);
    }

    private MedVectorIndexRebuilder invoke(MedVectorStoreProperties storeProperties,
                                          MedIndexRebuildProperties rebuildProperties,
                                          MedDocumentIngestionProperties ingestionProperties) {
        return config.medVectorIndexRebuilder(mock(RedisVectorStore.class), mock(JedisPooled.class),
                storeProperties, new MedRagIndexProperties(), rebuildProperties,
                ingestionProperties, mock(MedDocumentService.class), emptyNotifierProvider(),
                mock(RedissonClient.class));
    }

    @Test
    @DisplayName("the rebuilder is opt-in: the switch must be true and has no default")
    void rebuilderIsOptIn() throws NoSuchMethodException {
        ConditionalOnProperty condition = rebuildFactory().getAnnotation(ConditionalOnProperty.class);

        assertThat(condition).isNotNull();
        assertThat(condition.prefix()).isEqualTo(MedIndexRebuildProperties.PREFIX);
        assertThat(condition.name()).containsExactly("enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        // The opposite of the monitoring switches: absent configuration must mean "cannot rebuild".
        assertThat(condition.matchIfMissing())
                .as("a rebuild is the only code path that can drop a search index")
                .isFalse();
        assertThat(VectorStoreConfig.INDEX_REBUILDER).isEqualTo("medVectorIndexRebuilder");
    }

    @Test
    @DisplayName("with the switch on, the rebuilder is built from the configured collaborators")
    void rebuilderIsBuiltWhenEnabled() {
        MedVectorIndexRebuilder rebuilder = invoke(new MedVectorStoreProperties(),
                new MedIndexRebuildProperties(), new MedDocumentIngestionProperties());

        assertThat(rebuilder).isNotNull();
        assertThat(rebuilder.lockKey()).isEqualTo("med:lock:rag:index:rebuild:med-doc-index");
        assertThat(rebuilder.currentProgress()).isNull();
        assertThat(rebuilder.lastReport()).isNull();
    }

    @Test
    @DisplayName("boundary: a rebuild batch larger than the ingestion limit fails at startup")
    void batchSizeAboveTheIngestionLimitFailsAtStartup() {
        MedIndexRebuildProperties rebuildProperties = new MedIndexRebuildProperties();
        rebuildProperties.setBatchSize(50);
        MedDocumentIngestionProperties ingestionProperties = new MedDocumentIngestionProperties();
        ingestionProperties.setMaxDocumentsPerRequest(10);

        assertThatIllegalStateException()
                .isThrownBy(() -> invoke(new MedVectorStoreProperties(), rebuildProperties,
                        ingestionProperties))
                .withMessageContaining("batch-size")
                .withMessageContaining("max-documents-per-request");
    }

    @Test
    @DisplayName("boundary: a rebuild with schema initialization switched off fails at startup")
    void schemaInitializationOffFailsAtStartup() {
        MedVectorStoreProperties storeProperties = new MedVectorStoreProperties();
        storeProperties.setInitializeSchema(false);

        assertThatIllegalStateException()
                .isThrownBy(() -> invoke(storeProperties, new MedIndexRebuildProperties(),
                        new MedDocumentIngestionProperties()))
                .withMessageContaining("initialize-schema");
    }

    @Test
    @DisplayName("the store bean is declared as the concrete store, so the rebuilder needs no cast")
    void storeBeanIsTypedAsTheConcreteStore() throws NoSuchMethodException {
        Method factory = VectorStoreConfig.class.getMethod("medVectorStore", JedisPooled.class,
                ObjectProvider.class, MedVectorStoreProperties.class, ObjectProvider.class);

        assertThat(factory.getReturnType())
                .as("the rebuild calls the store's own afterPropertiesSet() to recreate the index; "
                        + "an interface-typed bean would need a cast and could fail at runtime")
                .isEqualTo(RedisVectorStore.class);
        assertThat(VectorStoreConfig.class.getMethod("medVectorStore", JedisPooled.class,
                ObjectProvider.class, MedVectorStoreProperties.class, ObjectProvider.class)
                .isAnnotationPresent(org.springframework.context.annotation.Lazy.class)).isTrue();
    }
}
