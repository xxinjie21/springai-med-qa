package com.med.qa.actuator;

import com.med.qa.config.ActuatorObservabilityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import javax.sql.DataSource;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link ActuatorObservabilityConfig}.
 *
 * <p>The two beans are trivial factories but they carry one important decision: the storage probe
 * must only be contributed when both stores are actually configured, otherwise offline test slices
 * would fail to refresh. These tests pin that condition as well as the bean names that become the
 * health component key.</p>
 */
class ActuatorObservabilityConfigTest {

    private final ActuatorObservabilityConfig config = new ActuatorObservabilityConfig();

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
}
