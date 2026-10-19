package com.med.qa.config;

import com.med.qa.job.MedColdStoreMetrics;
import com.med.qa.mapper.SessionArchiveMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contributes the cold transcript store's size and age gauges (D54).
 *
 * <h2>Why this configuration has no switch</h2>
 * <p>The same argument as {@link MedJobObservabilityConfig}, one level down: the cold store has exactly
 * one size signal, and the situation it exists for is a cold table that silently grows. But unlike the
 * ledger, this component does not have to exist for a job to be observable — it is inert until a
 * scheduler calls it, and the gauges are registered on the <em>first measurement</em> rather than at
 * construction. A deployment whose maintenance jobs are all switched off therefore publishes no
 * cold-store series at all, which is the correct answer: "deliberately disabled" is an operator
 * decision, not a zero.</p>
 *
 * <h2>Why it is not gated on any job's switch either</h2>
 * <p>Both cold-tier jobs (the D48 export and the D52 reclaim) take it as a constructor dependency, and
 * they are gated independently. Declaring it unconditionally keeps that dependency acyclic and available
 * to whichever subset a deployment arms — including the empty subset, which is what the offline test
 * slices want. The mapper is a plain mandatory collaborator: MyBatis scans it in every configuration.</p>
 */
@Configuration
public class MedColdStoreMetricsConfig {

    /** Bean name of the cold-store metrics publisher. */
    public static final String METRICS = "medColdStoreMetrics";

    /**
     * Contributes the publisher the two cold-tier schedulers refresh their gauges through.
     *
     * @param meterRegistry the application meter registry backing the published gauges
     * @param archiveMapper MyBatis mapper over the cold archive tables, source of the two aggregates
     * @return the publisher
     */
    @Bean(METRICS)
    public MedColdStoreMetrics medColdStoreMetrics(MeterRegistry meterRegistry,
                                                   SessionArchiveMapper archiveMapper) {
        return new MedColdStoreMetrics(archiveMapper, meterRegistry);
    }
}
