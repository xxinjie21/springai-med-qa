package com.med.qa.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests of {@link MedSessionPurgeProperties} (D50).
 *
 * <p>The defaults are the contract here. This is the only job in the project that deletes clinical
 * records, so all three conservative knobs have to default to "do not": {@code enabled=false},
 * {@code dry-run=true}, and a non-zero cooling-off window ({@code min-archived-age}) that keeps D47's
 * "a wrongly archived session can be moved back" promise from becoming an empty one.</p>
 *
 * <p>The rest of the class is validation, and its value is that a typo fails at startup rather than
 * turning the purge into a no-op ({@code batch-size: 0}) or into an unbounded walk of every archived
 * session ({@code max-batches: 0} meaning "no limit" in a naive implementation).</p>
 */
class MedSessionPurgePropertiesTest {

    private final MedSessionPurgeProperties properties = new MedSessionPurgeProperties();

    @Test
    @DisplayName("all three conservative knobs default to the safe side")
    void defaultsAreConservative() {
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isDryRun()).isTrue();
        assertThat(properties.getMinArchivedAge()).isEqualTo(Duration.ofDays(7));
        assertThat(properties.getMinArchivedAge()).isPositive();
    }

    @Test
    @DisplayName("the numeric and duration defaults are the documented ones")
    void defaultsAreTheDocumentedOnes() {
        assertThat(properties.getBatchSize()).isEqualTo(50);
        assertThat(properties.getMaxBatches()).isEqualTo(5);
        assertThat(properties.getCheckInterval()).isEqualTo(Duration.ofHours(12));
        assertThat(properties.getInitialDelay()).isEqualTo(Duration.ofMinutes(15));
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
        assertThat(MedSessionPurgeProperties.LOCK_KEY).isEqualTo("med:lock:session:purge");
        assertThat(MedSessionPurgeProperties.LOCK_KEY).startsWith("med:lock:");
        assertThat(MedSessionPurgeProperties.PREFIX).isEqualTo("med.session.purge");
    }

    @Test
    @DisplayName("a zero or negative cooling-off window is rejected, because it empties D47's promise")
    void coolingOffWindowMustBePositive() {
        assertThatThrownBy(() -> properties.setMinArchivedAge(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min-archived-age must be a positive duration");
        assertThatThrownBy(() -> properties.setMinArchivedAge(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min-archived-age must be a positive duration");
        assertThatThrownBy(() -> properties.setMinArchivedAge(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min-archived-age must be a positive duration");
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
    @DisplayName("the string form reports the switches, so a startup log answers 'is it armed?'")
    void toStringReportsTheSwitches() {
        assertThat(properties.toString())
                .contains("enabled=false", "dryRun=true", "minArchivedAge=PT168H", "batchSize=50");
    }
}
