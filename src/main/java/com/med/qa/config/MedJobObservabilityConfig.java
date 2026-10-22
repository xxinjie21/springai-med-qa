package com.med.qa.config;

import com.med.qa.job.MedJobRunLedger;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contributes the background job run ledger (D53).
 *
 * <h2>Why this configuration has no switch</h2>
 * <p>Every other capability in this project is gated by a property, and the maintenance jobs are
 * gated twice ({@code enabled=false} and {@code dry-run=true}). This one deliberately is not. The
 * ledger is the only artefact in the system that can say "the retention sweep has not run since March",
 * and the situation it exists for is precisely the one where a job produces nothing at all -- no alert,
 * no log, no metric. An operator who could switch the ledger off would be switching off the
 * observability of every job at once (five since D55), and the deployment would look healthy while
 * doing it. The
 * Prometheus rules are scoped by the {@code job} label instead, so a job that is switched off simply
 * stops publishing its series and stops being paged.</p>
 *
 * <h2>Why it is not gated on any job's switch either</h2>
 * <p>The ledger must exist before any job does: the four schedulers take it as a constructor
 * dependency and register themselves while they are being built. Declaring it here, unconditionally,
 * keeps that dependency acyclic and keeps the ledger available to whichever subset of jobs a
 * deployment arms -- including the empty subset, which is what the offline test slices want.</p>
 */
@Configuration
public class MedJobObservabilityConfig {

    /** Bean name of the run ledger. */
    public static final String LEDGER = "medJobRunLedger";

    /**
     * Contributes the ledger the four maintenance schedulers record their runs into.
     *
     * @param meterRegistry the application meter registry backing the published gauges and counter
     * @return the ledger
     */
    @Bean(LEDGER)
    public MedJobRunLedger medJobRunLedger(MeterRegistry meterRegistry) {
        return new MedJobRunLedger(meterRegistry);
    }
}
