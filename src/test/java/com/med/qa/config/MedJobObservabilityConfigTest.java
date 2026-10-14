package com.med.qa.config;

import com.med.qa.job.MedJobRunLedger;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring tests for {@link MedJobObservabilityConfig} (D53).
 *
 * <p>The interesting assertion here is the negative one. Every other capability in this project is gated by
 * a property, and the four maintenance jobs are gated twice, so the natural instinct is to gate the ledger
 * too. That would be wrong: the ledger is the only artefact that can say "the retention sweep has not run
 * since March", and the situation it exists for is exactly the one where a job produces nothing at all. An
 * operator who could switch it off would be switching off the observability of all four jobs at once, so
 * this suite pins that the ledger survives with every job switched off.</p>
 */
class MedJobObservabilityConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MedJobObservabilityConfig.class, MeterRegistryConfig.class);

    @Test
    @DisplayName("the ledger exists with every maintenance job switched off, because it is their only evidence")
    void theLedgerExistsWithoutAnyJob() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MedJobRunLedger.class);
            assertThat(context.getBean(MedJobRunLedger.class).registeredJobs())
                    .as("no job has registered itself, and the ledger must not invent one")
                    .isEmpty();
        });
    }

    @Test
    @DisplayName("disabling all four jobs explicitly changes nothing: the ledger has no switch of its own")
    void theLedgerIgnoresTheJobSwitches() {
        runner.withPropertyValues(
                        "med.session.retention.enabled=false",
                        "med.session.archive.enabled=false",
                        "med.session.purge.enabled=false",
                        "med.session.reclaim.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MedJobRunLedger.class);
                });
    }

    @Test
    @DisplayName("the ledger is a singleton, so the four schedulers share one view of their runs")
    void theLedgerIsASingleton() {
        runner.run(context -> {
            assertThat(context.getBeanNamesForType(MedJobRunLedger.class)).hasSize(1);
            assertThat(context).hasBean(MedJobObservabilityConfig.LEDGER);
        });
    }

    @Test
    @DisplayName("without a meter registry the context fails rather than quietly losing the ledger")
    void withoutARegistryTheContextFails() {
        // The ledger publishes into Micrometer and nothing else; a context that cannot provide a registry
        // has no way to make its runs observable, and starting anyway would recreate exactly the blind spot
        // this component exists to remove.
        new ApplicationContextRunner()
                .withUserConfiguration(MedJobObservabilityConfig.class)
                .run(context -> assertThat(context).hasFailed());
    }

    /** The registry the production context gets from the Actuator starter, replaced by a plain one here. */
    @Configuration(proxyBeanMethods = false)
    static class MeterRegistryConfig {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
