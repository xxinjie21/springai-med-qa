package com.med.qa.job;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests of {@link MedJobRunLedger}.
 *
 * <p>Every assertion is made against a real {@code SimpleMeterRegistry}, so what is pinned is the meter a
 * Prometheus scrape would read -- its name, its label set and its value -- rather than the fact that a
 * method was invoked. That matters because the whole point of this class is that four separate schedulers
 * and one YAML file agree on a vocabulary: a test that only verified the ledger's own fields would stay
 * green while the rule selected a series nobody publishes.</p>
 */
class MedJobRunLedgerTest {

    private static final Instant BOOT = Instant.parse("2026-10-14T09:00:00Z");

    private static final long BOOT_EPOCH_SECONDS = BOOT.getEpochSecond();

    private static final String JOB = "session-retention";

    private static final String OTHER_JOB = "session-purge";

    private static final Duration ONE_HOUR = Duration.ofHours(1);

    private SimpleMeterRegistry registry;

    private MedJobRunLedger ledger;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        ledger = new MedJobRunLedger(registry, Clock.fixed(BOOT, ZoneOffset.UTC));
    }

    // ------------------------------------------------------------------ registration

    @Test
    @DisplayName("registering a job publishes its three gauges under the component label")
    void registrationPublishesTheGauges() {
        ledger.register(JOB, ONE_HOUR);

        assertThat(gauge(MedJobRunLedger.LAST_RUN_GAUGE)).isEqualTo(BOOT_EPOCH_SECONDS);
        assertThat(gauge(MedJobRunLedger.LAST_SUCCESS_GAUGE)).isEqualTo(BOOT_EPOCH_SECONDS);
        assertThat(gauge(MedJobRunLedger.EXPECTED_INTERVAL_GAUGE)).isEqualTo(3600d);
        assertThat(ledger.registeredJobs()).containsExactly(JOB);
    }

    @Test
    @DisplayName("a freshly registered job is not stale, so a restart does not page for the whole initial delay")
    void aFreshlyRegisteredJobIsNotStale() {
        // This is the executable form of the "both timestamps start at the registration instant" decision.
        // Starting at zero would make every rule that compares against time() fire after every restart,
        // which is the fastest way to teach an on-call engineer to ignore the alert.
        ledger.register(JOB, ONE_HOUR);

        assertThat(ledger.lastRunEpochSeconds(JOB)).isEqualTo(BOOT_EPOCH_SECONDS);
        assertThat(ledger.lastSuccessEpochSeconds(JOB)).isEqualTo(BOOT_EPOCH_SECONDS);
    }

    @Test
    @DisplayName("a second registration is a no-op: it neither re-seeds the timestamps nor changes the interval")
    void registrationIsIdempotent() {
        ledger.register(JOB, ONE_HOUR);
        ledger.record(MedJobRun.completed(JOB, BOOT.plusSeconds(600), 12L));

        ledger.register(JOB, Duration.ofDays(7));

        // Re-seeding here would make "register the job again" a way to make a stalled job look alive, and
        // taking the second interval would tune the rule to a schedule the job was not armed with.
        assertThat(ledger.lastRunEpochSeconds(JOB)).isEqualTo(BOOT.plusSeconds(600).getEpochSecond());
        assertThat(ledger.expectedIntervalSeconds(JOB)).isEqualTo(3600d);
    }

    @Test
    @DisplayName("boundary: a sub-second interval keeps its precision instead of collapsing to zero")
    void subSecondIntervalKeepsItsPrecision() {
        ledger.register(JOB, Duration.ofMillis(500));

        assertThat(ledger.expectedIntervalSeconds(JOB)).isEqualTo(0.5d);
    }

    @Test
    @DisplayName("registration rejects a blank identifier and a non-positive interval")
    void registrationRejectsInvalidArguments() {
        assertThatThrownBy(() -> ledger.register("  ", ONE_HOUR))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("job");
        assertThatThrownBy(() -> ledger.register(null, ONE_HOUR))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("job");
        assertThatThrownBy(() -> ledger.register(JOB, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expectedInterval");
        assertThatThrownBy(() -> ledger.register(JOB, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expectedInterval");
        assertThatThrownBy(() -> ledger.register(JOB, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expectedInterval");
        assertThat(ledger.registeredJobs()).isEmpty();
    }

    // ------------------------------------------------------------------ recording

    @Test
    @DisplayName("a completed run advances both the attempt and the success timestamp")
    void completedRunAdvancesBoth() {
        ledger.register(JOB, ONE_HOUR);

        ledger.record(MedJobRun.completed(JOB, BOOT.plusSeconds(3_600), 42L));

        assertThat(gauge(MedJobRunLedger.LAST_RUN_GAUGE))
                .isEqualTo(BOOT.plusSeconds(3_600).getEpochSecond());
        assertThat(gauge(MedJobRunLedger.LAST_SUCCESS_GAUGE))
                .isEqualTo(BOOT.plusSeconds(3_600).getEpochSecond());
        assertThat(counter(MedJobOutcome.COMPLETED)).isEqualTo(1d);
    }

    @Test
    @DisplayName("a run that stepped aside counts as an attempt but never as a success")
    void skippedRunAdvancesOnlyTheAttempt() {
        // Losing the cluster mutex is the mutex working, not this instance being stalled, so the attempt
        // stamp has to move. Counting it as a success would hide a leaked lock key that has turned every
        // replica into a permanent no-op.
        ledger.register(JOB, ONE_HOUR);

        ledger.record(MedJobRun.skippedLockHeld(JOB, BOOT.plusSeconds(3_600), 3L));

        assertThat(gauge(MedJobRunLedger.LAST_RUN_GAUGE))
                .isEqualTo(BOOT.plusSeconds(3_600).getEpochSecond());
        assertThat(gauge(MedJobRunLedger.LAST_SUCCESS_GAUGE)).isEqualTo(BOOT_EPOCH_SECONDS);
        assertThat(counter(MedJobOutcome.SKIPPED_LOCK_HELD)).isEqualTo(1d);
    }

    @Test
    @DisplayName("a failed run counts as an attempt but never as a success")
    void failedRunAdvancesOnlyTheAttempt() {
        ledger.register(JOB, ONE_HOUR);

        ledger.record(MedJobRun.failed(JOB, BOOT.plusSeconds(3_600), 3L));

        assertThat(gauge(MedJobRunLedger.LAST_RUN_GAUGE))
                .isEqualTo(BOOT.plusSeconds(3_600).getEpochSecond());
        assertThat(gauge(MedJobRunLedger.LAST_SUCCESS_GAUGE)).isEqualTo(BOOT_EPOCH_SECONDS);
        assertThat(counter(MedJobOutcome.FAILED)).isEqualTo(1d);
    }

    @Test
    @DisplayName("every outcome is counted separately, so the dominant one can be read off a dashboard")
    void everyOutcomeIsCountedSeparately() {
        ledger.register(JOB, ONE_HOUR);

        ledger.record(MedJobRun.completed(JOB, BOOT.plusSeconds(10), 1L));
        ledger.record(MedJobRun.completed(JOB, BOOT.plusSeconds(20), 1L));
        ledger.record(MedJobRun.skippedLockHeld(JOB, BOOT.plusSeconds(30), 1L));
        ledger.record(MedJobRun.failed(JOB, BOOT.plusSeconds(40), 1L));

        assertThat(counter(MedJobOutcome.COMPLETED)).isEqualTo(2d);
        assertThat(counter(MedJobOutcome.SKIPPED_LOCK_HELD)).isEqualTo(1d);
        assertThat(counter(MedJobOutcome.FAILED)).isEqualTo(1d);
    }

    @Test
    @DisplayName("boundary: a run recorded out of order never rewinds a timestamp")
    void anOlderRunNeverRewindsATimestamp() {
        // A clock that stepped backwards, or a record that arrives late, must not be able to make a healthy
        // job look stalled.
        ledger.register(JOB, ONE_HOUR);
        ledger.record(MedJobRun.completed(JOB, BOOT.plusSeconds(600), 1L));

        ledger.record(MedJobRun.completed(JOB, BOOT.plusSeconds(60), 1L));

        assertThat(gauge(MedJobRunLedger.LAST_RUN_GAUGE))
                .isEqualTo(BOOT.plusSeconds(600).getEpochSecond());
        assertThat(gauge(MedJobRunLedger.LAST_SUCCESS_GAUGE))
                .isEqualTo(BOOT.plusSeconds(600).getEpochSecond());
    }

    @Test
    @DisplayName("two jobs never bleed into each other's series")
    void twoJobsStayIsolated() {
        ledger.register(JOB, ONE_HOUR);
        ledger.register(OTHER_JOB, Duration.ofHours(12));

        ledger.record(MedJobRun.completed(JOB, BOOT.plusSeconds(600), 1L));

        assertThat(ledger.lastRunEpochSeconds(OTHER_JOB)).isEqualTo(BOOT_EPOCH_SECONDS);
        assertThat(ledger.expectedIntervalSeconds(OTHER_JOB)).isEqualTo(43_200d);
        assertThat(registry.get(MedJobRunLedger.LAST_RUN_GAUGE)
                .tag(MedJobRunLedger.COMPONENT_TAG, JOB).gauge().value())
                .isEqualTo(BOOT.plusSeconds(600).getEpochSecond());
    }

    @Test
    @DisplayName("recording a run for a job nobody registered is refused outright")
    void recordingForAnUnregisteredJobIsRefused() {
        // Failing here keeps the ledger's contents deliberate: only a job a deployment armed may appear in
        // it, so a stray series can never be mistaken for a job that is supposed to be running.
        assertThatThrownBy(() -> ledger.record(MedJobRun.completed(JOB, BOOT, 1L)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(JOB);
    }

    @Test
    @DisplayName("recording null is refused")
    void recordingNullIsRefused() {
        assertThatThrownBy(() -> ledger.record(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("run");
    }

    // ------------------------------------------------------------------ accessors

    @Test
    @DisplayName("querying a job that was never registered returns zero rather than throwing")
    void unknownJobQueriesReturnZero() {
        assertThat(ledger.lastRunEpochSeconds("nope")).isZero();
        assertThat(ledger.lastSuccessEpochSeconds("nope")).isZero();
        assertThat(ledger.expectedIntervalSeconds("nope")).isZero();
    }

    @Test
    @DisplayName("registeredJobs returns an unmodifiable snapshot")
    void registeredJobsIsUnmodifiable() {
        ledger.register(JOB, ONE_HOUR);

        assertThat(ledger.registeredJobs()).isUnmodifiable();
    }

    @Test
    @DisplayName("the constructor refuses a null registry or clock")
    void constructorRejectsNulls() {
        assertThatThrownBy(() -> new MedJobRunLedger(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("meterRegistry");
        assertThatThrownBy(() -> new MedJobRunLedger(registry, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("clock");
    }

    @Nested
    @DisplayName("metric vocabulary")
    class VocabularyTest {

        @Test
        @DisplayName("the label is `component`, never `job`, because Prometheus reserves the latter")
        void theLabelAvoidsTheNamePrometheusReserves() {
            // With the default honor_labels: false a scraped label called `job` is renamed to exported_job
            // and the scrape target's own job name takes its place, so a rule selecting job=~"session-.*"
            // would parse, scrape and match nothing. AlertingStackConfigTest pins the other half: that the
            // scrape config stays on the default.
            assertThat(MedJobRunLedger.COMPONENT_TAG).isEqualTo("component");
            assertThat(MedJobRunLedger.COMPONENT_TAG).isNotEqualTo("job");
            assertThat(MedJobRunLedger.OUTCOME_TAG).isEqualTo("outcome");
        }

        @Test
        @DisplayName("every published meter name lives in the med_qa_job_ namespace")
        void everyMeterNameIsNamespaced() {
            assertThat(MedJobRunLedger.LAST_RUN_GAUGE).startsWith("med_qa_job_");
            assertThat(MedJobRunLedger.LAST_SUCCESS_GAUGE).startsWith("med_qa_job_");
            assertThat(MedJobRunLedger.EXPECTED_INTERVAL_GAUGE).startsWith("med_qa_job_");
            assertThat(MedJobRunLedger.RUNS_COUNTER).startsWith("med_qa_job_");
        }

        @Test
        @DisplayName("the outcome tag carries the enum name, so it is stable across renames of the label")
        void theOutcomeTagCarriesTheEnumName() {
            ledger.register(JOB, ONE_HOUR);
            ledger.record(MedJobRun.failed(JOB, BOOT, 1L));

            assertThat(registry.get(MedJobRunLedger.RUNS_COUNTER)
                    .tag(MedJobRunLedger.OUTCOME_TAG, MedJobOutcome.FAILED.name())
                    .counter().count()).isEqualTo(1d);
        }
    }

    private double gauge(String name) {
        return registry.get(name).tag(MedJobRunLedger.COMPONENT_TAG, JOB).gauge().value();
    }

    private double counter(MedJobOutcome outcome) {
        return registry.get(MedJobRunLedger.RUNS_COUNTER)
                .tag(MedJobRunLedger.COMPONENT_TAG, JOB)
                .tag(MedJobRunLedger.OUTCOME_TAG, outcome.name())
                .counter().count();
    }
}
