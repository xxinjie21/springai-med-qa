package com.med.qa.actuator;

import com.med.qa.config.ActuatorObservabilityConfig;
import com.med.qa.rag.MedRagIndexProperties;
import com.med.qa.rag.MedVectorStoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import redis.clients.jedis.JedisPooled;

import javax.sql.DataSource;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link ActuatorObservabilityConfig}.
 *
 * <p>The beans are trivial factories but they carry two important decisions. The storage probe must
 * only be contributed when both stores are actually configured, otherwise offline test slices would
 * fail to refresh. The vector-index probe must be removable through {@code med.rag.index.enabled},
 * because a deployment running its cache on a plain Redis build genuinely has no RediSearch index —
 * and an unremovable component would report that pod as unhealthy forever. These tests pin both
 * conditions as well as the bean names that become the health component keys.</p>
 */
class ActuatorObservabilityConfigTest {

    private final ActuatorObservabilityConfig config = new ActuatorObservabilityConfig();

    /** Context slice over the configuration under test, with the Jedis client it needs. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ActuatorObservabilityConfig.class)
            .withBean(JedisPooled.class, () -> mock(JedisPooled.class))
            .withBean(MedVectorStoreProperties.class, MedVectorStoreProperties::new)
            .withBean(MedRagIndexProperties.class, MedRagIndexProperties::new);

    @Test
    @DisplayName("the storage probe is a health indicator bean with a stable bean name")
    void contributesStorageHealthIndicator() {
        HealthIndicator indicator = config.medStorageHealthIndicator(
                mock(RedisConnectionFactory.class), mock(DataSource.class));

        assertThat(indicator).isInstanceOf(MedStorageHealthIndicator.class);
        assertThat(ActuatorObservabilityConfig.STORAGE_HEALTH_INDICATOR).isEqualTo("medStorageHealthIndicator");
    }

    @Test
    @DisplayName("the version matrix is contributed as an info contributor")
    void contributesComponentInfoContributor() {
        InfoContributor contributor = config.medComponentInfoContributor();

        assertThat(contributor).isInstanceOf(MedComponentInfoContributor.class);
        assertThat(ActuatorObservabilityConfig.COMPONENT_INFO_CONTRIBUTOR)
                .isEqualTo("medComponentInfoContributor");
    }

    @Test
    @DisplayName("the storage probe requires both stores to be configured")
    void storageProbeIsConditionalOnBothStores() throws NoSuchMethodException {
        Method factory = ActuatorObservabilityConfig.class.getMethod(
                "medStorageHealthIndicator", RedisConnectionFactory.class, DataSource.class);

        ConditionalOnBean condition = factory.getAnnotation(ConditionalOnBean.class);

        assertThat(condition).isNotNull();
        assertThat(condition.value())
                .contains(RedisConnectionFactory.class, DataSource.class);
    }

    // ---------------------------------------------------------------- vector-index probe (D37)

    @Test
    @DisplayName("the vector-index probe is contributed by default and becomes a health component")
    void contributesVectorIndexHealthIndicator() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ActuatorObservabilityConfig.VECTOR_INDEX_HEALTH_INDICATOR))
                    .isInstanceOf(MedVectorIndexHealthIndicator.class);
            assertThat(context.getBean(MedVectorIndexHealthIndicator.class))
                    .isNotNull();
            assertThat(ActuatorObservabilityConfig.VECTOR_INDEX_HEALTH_INDICATOR)
                    .isEqualTo("medVectorIndexHealthIndicator");
        });
    }

    @Test
    @DisplayName("the vector-index probe is gated by med.rag.index.enabled, defaulting to on")
    void vectorIndexProbeIsGatedByItsSwitch() throws NoSuchMethodException {
        Method factory = ActuatorObservabilityConfig.class.getMethod(
                "medVectorIndexHealthIndicator", JedisPooled.class,
                MedVectorStoreProperties.class, MedRagIndexProperties.class);

        ConditionalOnProperty condition = factory.getAnnotation(ConditionalOnProperty.class);

        assertThat(condition).isNotNull();
        assertThat(condition.prefix()).isEqualTo(MedRagIndexProperties.PREFIX);
        assertThat(condition.name()).containsExactly("enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        // Default on: RAG is a core capability, so silence has to be the deliberate choice.
        assertThat(condition.matchIfMissing()).isTrue();
    }

    @Test
    @DisplayName("med.rag.index.enabled=false removes the component, for a deployment without Redis Stack")
    void disablingTheSwitchRemovesTheComponent() {
        runner.withPropertyValues("med.rag.index.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MedVectorIndexHealthIndicator.class);
            // The rest of the actuator contributions must survive: the switch is scoped to the index.
            assertThat(context.getBean(ActuatorObservabilityConfig.COMPONENT_INFO_CONTRIBUTOR))
                    .isInstanceOf(MedComponentInfoContributor.class);
        });
    }

    @Test
    @DisplayName("boundary: the storage probe stays absent in a slice without a Redis factory or a DataSource")
    void storageProbeStaysAbsentWithoutTheStores() {
        // The runner supplies neither, which is exactly the offline-slice situation. The context must
        // still refresh, and the vector-index probe must be unaffected by the storage probe's absence.
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MedStorageHealthIndicator.class);
            assertThat(context).hasSingleBean(MedVectorIndexHealthIndicator.class);
        });
    }
}
