package com.med.qa.config;

import com.med.qa.job.MedColdStoreMetrics;
import com.med.qa.mapper.SessionArchiveMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Wiring tests for {@link MedColdStoreMetricsConfig} (D54).
 *
 * <h2>Why the negative half matters here</h2>
 * <p>Every other capability in this project is gated by a property, and the four maintenance jobs are
 * gated twice, so the natural instinct is to gate the cold-store gauges too. That would be wrong in a
 * subtler way than it is for the ledger: the gauges are registered lazily on the first measurement, so a
 * switched-off deployment already publishes nothing — a switch could only remove the gauges of a
 * deployment that <em>does</em> run the cold-tier jobs, i.e. exactly the signal an operator arming those
 * jobs needs. This suite pins that there is no such switch and that the publisher survives with every
 * job off.</p>
 *
 * <h2>Why the publisher is inert until a job calls it</h2>
 * <p>Asserted through the registry rather than through a counter: a context that starts the publisher
 * without any scheduler must not produce a single {@code med_qa_cold_store_*} meter. An eagerly
 * registered gauge would export a zero, and a zero reads as "the cold store is empty" — a claim nothing
 * has verified.</p>
 */
class MedColdStoreMetricsConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MedColdStoreMetricsConfig.class, Collaborators.class);

    @Test
    @DisplayName("the publisher exists with every maintenance job switched off")
    void thePublisherExistsWithoutAnyJob() {
        runner.withPropertyValues(
                        "med.session.retention.enabled=false",
                        "med.session.archive.enabled=false",
                        "med.session.purge.enabled=false",
                        "med.session.reclaim.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MedColdStoreMetrics.class);
                    assertThat(context).hasBean(MedColdStoreMetricsConfig.METRICS);
                });
    }

    @Test
    @DisplayName("the publisher exists with every job switch absent, which is the repository default")
    void thePublisherExistsWithNoPropertiesAtAll() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MedColdStoreMetrics.class);
        });
    }

    @Test
    @DisplayName("a context that never runs a job publishes no cold-store meter at all")
    void anIdleContextPublishesNoMeter() {
        runner.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            assertThat(registry.getMeters())
                    .as("an unmeasured cold store must not export a zero")
                    .noneMatch(meter -> meter.getId().getName().startsWith("med_qa_cold_store_"));
        });
    }

    @Test
    @DisplayName("the cold-store gauges have no switch of their own in application.yml")
    void thereIsNoSwitch() throws IOException {
        // The absence is the contract, asserted the way D53 asserts the ledger's: a deployment that could
        // switch the gauges off could hide the only size signal the cold store has, and the lazily
        // registered meters already make "disabled" publish nothing.
        String applicationYml = Files.readString(Path.of(System.getProperty("user.dir"),
                "src/main/resources/application.yml"));
        assertThat(applicationYml).doesNotContain("MED_COLD_STORE_");
        assertThat(applicationYml).doesNotContain("med.cold-store.enabled");
        assertThat(applicationYml).doesNotContain("med.cold-store:");
    }

    @Test
    @DisplayName("without a meter registry the context fails rather than quietly losing the gauges")
    void withoutARegistryTheContextFails() {
        new ApplicationContextRunner()
                .withUserConfiguration(MedColdStoreMetricsConfig.class,
                        MapperOnlyCollaborator.class)
                .run(context -> assertThat(context).hasFailed());
    }

    /** The registry the production context gets from the Actuator starter, replaced by a plain one here. */
    @Configuration(proxyBeanMethods = false)
    static class Collaborators {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        SessionArchiveMapper sessionArchiveMapper() {
            return mock(SessionArchiveMapper.class);
        }
    }

    /** Same collaborators minus the registry, for the failing-context case. */
    @Configuration(proxyBeanMethods = false)
    static class MapperOnlyCollaborator {

        @Bean
        SessionArchiveMapper sessionArchiveMapper() {
            return mock(SessionArchiveMapper.class);
        }
    }
}
