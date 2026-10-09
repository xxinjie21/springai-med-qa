package com.med.qa.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests of {@link MedSessionArchiveProperties} (D48).
 *
 * <p>The defaults are the contract here: this job copies clinical records nobody asked about, so both
 * switches have to default to "do not". The rest of the class is validation, and its value is that a
 * typo fails at startup rather than turning the export into a no-op ({@code batch-size: 0}) or into an
 * unbounded walk of every archived session ({@code max-batches: 0} meaning "no limit" in a naive
 * implementation).</p>
 */
class MedSessionArchivePropertiesTest {

    private final MedSessionArchiveProperties properties = new MedSessionArchiveProperties();

    @Test
    @DisplayName("both switches default to the conservative side")
    void switchesDefaultOffAndDry() {
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isDryRun()).isTrue();
    }

    @Test
    @DisplayName("the numeric and duration defaults are the documented ones")
    void defaultsAreTheDocumentedOnes() {
        assertThat(properties.getBatchSize()).isEqualTo(50);
        assertThat(properties.getMaxBatches()).isEqualTo(5);
        assertThat(properties.getCheckInterval()).isEqualTo(Duration.ofHours(6));
        assertThat(properties.getInitialDelay()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.getLockWaitTime()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.getLockLeaseTime()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.isLockWatchdogEnabled()).isFalse();
        assertThat(properties.maxSessionsPerRun()).isEqualTo(250);
    }

    @Test
    @DisplayName("a zero lease hands renewal to the Redisson watchdog")
    void zeroLeaseEnablesTheWatchdog() {
        properties.setLockLeaseTime(Duration.ZERO);

        assertThat(properties.isLockWatchdogEnabled()).isTrue();
    }

    @Test
    @DisplayName("the mutex key stays in the med:lock: namespace and is not configurable")
    void lockKeyIsNamespaced() {
        assertThat(MedSessionArchiveProperties.LOCK_KEY).isEqualTo("med:lock:session:archive:export");
        assertThat(MedSessionArchiveProperties.PREFIX).isEqualTo("med.session.archive");
    }

    @Test
    @DisplayName("invalid values are rejected at bind time")
    void invalidValuesAreRejected() {
        assertThatThrownBy(() -> properties.setBatchSize(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch-size must be positive");
        assertThatThrownBy(() -> properties.setMaxBatches(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-batches must be positive");
        assertThatThrownBy(() -> properties.setCheckInterval(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("check-interval must be a positive duration");
        assertThatThrownBy(() -> properties.setInitialDelay(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initial-delay must not be negative");
        assertThatThrownBy(() -> properties.setLockWaitTime(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lock-wait-time must not be negative");
        assertThatThrownBy(() -> properties.setLockLeaseTime(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lock-lease-time must not be negative");
    }

    @Test
    @DisplayName("the string form reports both switches, so a startup log answers 'is it armed?'")
    void toStringReportsBothSwitches() {
        assertThat(properties.toString())
                .contains("enabled=false", "dryRun=true", "batchSize=50");
    }
}
