package com.med.qa.config;

import com.med.qa.alert.LoggingMedAlertSink;
import com.med.qa.alert.MedAlertNotifier;
import com.med.qa.alert.MedAlertProperties;
import com.med.qa.alert.MedAlertSink;
import com.med.qa.alert.MedStorageAlertMonitor;
import com.med.qa.alert.MedVectorIndexAlertMonitor;
import com.med.qa.alert.MetricsMedAlertSink;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring tests for {@link MedAlertConfig}.
 *
 * <p>The alert chain is the one part of the service that must never take the application down with
 * it, so the interesting assertions are the negative ones: the chain disappears entirely when the
 * master switch is off, it starts without a Redis connection (the Redisson dependency is lazy), and
 * each monitor degrades to a no-op when its probe is absent. The D37 vector-index monitor adds a
 * second switch ({@code med.rag.index.enabled}) that must remove only its own half of the chain.</p>
 */
class MedAlertConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MedAlertConfig.class, MeterRegistryTestConfiguration.class);

    @Test
    @DisplayName("the default policy wires both sinks, the notifier and the monitor")
    void wiresTheWholeChain() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MedAlertProperties.class);
            assertThat(context).hasSingleBean(MedAlertNotifier.class);
            assertThat(context).hasSingleBean(MedStorageAlertMonitor.class);
            assertThat(context.getBeanNamesForType(MedAlertSink.class)).hasSize(2);
            assertThat(context.getBean(MedAlertConfig.LOGGING_SINK)).isInstanceOf(LoggingMedAlertSink.class);
            assertThat(context.getBean(MedAlertConfig.METRICS_SINK)).isInstanceOf(MetricsMedAlertSink.class);
        });
    }

    @Test
    @DisplayName("the notifier receives every sink bean on the context")
    void notifierReceivesEverySink() {
        runner.run(context -> assertThat(context.getBean(MedAlertNotifier.class).sinkCount()).isEqualTo(2));
    }

    @Test
    @DisplayName("the metrics sink publishes on the application registry")
    void metricsSinkUsesTheApplicationRegistry() {
        runner.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            MedAlertSink metricsSink = (MedAlertSink) context.getBean(MedAlertConfig.METRICS_SINK);

            metricsSink.publish(com.med.qa.alert.MedAlert.of(
                    com.med.qa.alert.MedAlertSeverity.CRITICAL, "storage-down", "mysql", "down"));

            assertThat(registry.get(MetricsMedAlertSink.ALERT_COUNTER).counter().count()).isEqualTo(1.0d);
        });
    }

    @Test
    @DisplayName("bound properties drive the policy the notifier applies")
    void propertiesAreBound() {
        runner.withPropertyValues(
                        "med.alert.check-interval=15s",
                        "med.alert.cooldown=0s",
                        "med.alert.minimum-severity=WARNING",
                        "med.alert.key-prefix=med:alert:test:")
                .run(context -> {
                    MedAlertProperties properties = context.getBean(MedAlertProperties.class);
                    assertThat(properties.getCheckInterval()).isEqualTo(Duration.ofSeconds(15));
                    assertThat(properties.isDeduplicationEnabled()).isFalse();
                    assertThat(properties.getMinimumSeverity())
                            .isEqualTo(com.med.qa.alert.MedAlertSeverity.WARNING);
                    assertThat(properties.getKeyPrefix()).isEqualTo("med:alert:test:");
                });
    }

    @Test
    @DisplayName("the chain starts without any Redis or MySQL bean, because the client is lazy")
    void startsWithoutMiddleware() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.containsBean("redissonClient")).isFalse();
            // The monitor must degrade to a no-op rather than fail the refresh.
            assertThat(context.getBean(MedStorageAlertMonitor.class).checkNow()).isEmpty();
        });
    }

    @Test
    @DisplayName("boundary: med.alert.enabled=false removes the whole chain")
    void disabledChainContributesNothing() {
        runner.withPropertyValues("med.alert.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MedAlertNotifier.class);
            assertThat(context).doesNotHaveBean(MedStorageAlertMonitor.class);
            assertThat(context).doesNotHaveBean(MedAlertProperties.class);
            assertThat(context.getBeanNamesForType(MedAlertSink.class)).isEmpty();
        });
    }

    // ---------------------------------------------------------------- RAG vector-index monitor (D37)

    @Test
    @DisplayName("the D37 vector-index monitor is wired alongside the storage monitor")
    void wiresTheVectorIndexMonitor() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MedAlertConfig.VECTOR_INDEX_MONITOR))
                    .isInstanceOf(MedVectorIndexAlertMonitor.class);
            assertThat(context.getBean(MedVectorIndexAlertMonitor.class)).isNotNull();
        });
    }

    @Test
    @DisplayName("the vector-index monitor is a no-op when its probe was not contributed")
    void vectorIndexMonitorDegradesToANoop() {
        // No MedVectorIndexHealthIndicator bean exists in this slice, which is the situation on a
        // deployment that switched the index monitoring off. The monitor must not fail the refresh.
        runner.run(context -> assertThat(
                context.getBean(MedVectorIndexAlertMonitor.class).checkNow()).isFalse());
    }

    @Test
    @DisplayName("boundary: med.rag.index.enabled=false removes the monitor without touching the storage chain")
    void disablingTheIndexProbeRemovesItsMonitor() {
        runner.withPropertyValues("med.rag.index.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MedVectorIndexAlertMonitor.class);
            // The storage half of the chain is governed by its own switch and must survive.
            assertThat(context).hasSingleBean(MedStorageAlertMonitor.class);
            assertThat(context).hasSingleBean(MedAlertNotifier.class);
        });
    }

    @Test
    @DisplayName("the monitor's schedule is driven by the med.rag.index properties, not a literal")
    void vectorIndexMonitorScheduleIsConfigurable() throws NoSuchMethodException {
        Method scheduled = MedVectorIndexAlertMonitor.class.getMethod("scheduledCheck");
        Scheduled annotation = scheduled.getAnnotation(Scheduled.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.fixedDelayString()).contains("med.rag.index.check-interval");
        assertThat(annotation.initialDelayString()).contains("med.rag.index.initial-delay");
    }

    /**
     * Supplies the meter registry that {@code spring-boot-starter-actuator} would provide in a real
     * application context; {@link ApplicationContextRunner} does not run auto-configuration.
     */
    @Configuration(proxyBeanMethods = false)
    static class MeterRegistryTestConfiguration {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
