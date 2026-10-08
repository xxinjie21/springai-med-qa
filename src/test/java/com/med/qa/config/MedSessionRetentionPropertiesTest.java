package com.med.qa.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests of {@link MedSessionRetentionProperties}.
 *
 * <p>The interesting half is the defaults: this is the only capability in the project that both
 * defaults to <em>off</em> and defaults to a dry run, so a regression that flips either one would turn
 * a reporting deployment into one that silently changes the lifecycle state of clinical records. The
 * setters are pinned for the same reason they exist — a typo must fail the context at startup instead
 * of producing a sweep that scans the whole table or archives everything instantly.</p>
 */
class MedSessionRetentionPropertiesTest {

    private final MedSessionRetentionProperties properties = new MedSessionRetentionProperties();

    @Test
    @DisplayName("the capability is off and dry by default, with conservative bounds")
    void defaultsAreConservative() {
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isDryRun()).isTrue();
        assertThat(properties.getIdleThreshold()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.getBatchSize()).isEqualTo(100);
        assertThat(properties.getMaxBatches()).isEqualTo(10);
        assertThat(properties.getCheckInterval()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.getInitialDelay()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.getLockWaitTime()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.getLockLeaseTime()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.maxSessionsPerRun()).isEqualTo(1000);
    }

    @Test
    @DisplayName("the sweep mutex lives in the med:lock: namespace and is not configurable")
    void lockKeyIsPinned() {
        assertThat(MedSessionRetentionProperties.LOCK_KEY).isEqualTo("med:lock:session:retention");
        assertThat(MedSessionRetentionProperties.PREFIX).isEqualTo("med.session.retention");
    }

    @Test
    @DisplayName("a zero or negative idle threshold is rejected, because it would archive everything")
    void idleThresholdMustBePositive() {
        assertThatThrownBy(() -> properties.setIdleThreshold(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idle-threshold");
        assertThatThrownBy(() -> properties.setIdleThreshold(Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setIdleThreshold(null))
                .isInstanceOf(IllegalArgumentException.class);

        properties.setIdleThreshold(Duration.ofMinutes(30));
        assertThat(properties.getIdleThreshold()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("batch size and max batches must be positive")
    void batchBoundsMustBePositive() {
        assertThatThrownBy(() -> properties.setBatchSize(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch-size");
        assertThatThrownBy(() -> properties.setMaxBatches(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-batches");

        properties.setBatchSize(5);
        properties.setMaxBatches(3);
        assertThat(properties.maxSessionsPerRun()).isEqualTo(15);
    }

    @Test
    @DisplayName("the check interval must be positive, so the job can never spin")
    void checkIntervalMustBePositive() {
        assertThatThrownBy(() -> properties.setCheckInterval(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("check-interval");
        assertThatThrownBy(() -> properties.setCheckInterval(null))
                .isInstanceOf(IllegalArgumentException.class);

        properties.setCheckInterval(Duration.ofMinutes(15));
        assertThat(properties.getCheckInterval()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("the initial delay may be zero but never negative")
    void initialDelayMayBeZero() {
        properties.setInitialDelay(Duration.ZERO);
        assertThat(properties.getInitialDelay()).isZero();

        assertThatThrownBy(() -> properties.setInitialDelay(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initial-delay");
    }

    @Test
    @DisplayName("the lock wait may be zero — a replica that lost the race should step aside")
    void lockWaitMayBeZero() {
        properties.setLockWaitTime(Duration.ZERO);
        assertThat(properties.getLockWaitTime()).isZero();

        assertThatThrownBy(() -> properties.setLockWaitTime(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lock-wait-time");
    }

    @Test
    @DisplayName("a zero lease hands renewal to the Redisson watchdog, a negative one is rejected")
    void leaseTimeZeroEnablesTheWatchdog() {
        assertThat(properties.isLockWatchdogEnabled()).isFalse();

        properties.setLockLeaseTime(Duration.ZERO);
        assertThat(properties.isLockWatchdogEnabled()).isTrue();

        assertThatThrownBy(() -> properties.setLockLeaseTime(Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lock-lease-time");
    }

    @Test
    @DisplayName("the switches round-trip, so a deployment can enable and then arm the sweep")
    void switchesRoundTrip() {
        properties.setEnabled(true);
        assertThat(properties.isEnabled()).isTrue();

        properties.setDryRun(false);
        assertThat(properties.isDryRun()).isFalse();

        assertThat(properties.toString()).contains("enabled=true").contains("dryRun=false");
    }
}
