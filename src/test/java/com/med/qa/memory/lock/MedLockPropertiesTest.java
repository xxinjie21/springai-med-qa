package com.med.qa.memory.lock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MedLockPropertiesTest {

    @Test
    @DisplayName("defaults keep the watchdog in charge with a bounded wait window")
    void defaultsFavourWatchdog() {
        MedLockProperties properties = new MedLockProperties();

        assertThat(properties.getWaitTime()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.getLeaseTime()).isEqualTo(Duration.ZERO);
        assertThat(properties.getWatchdogTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.isWatchdogEnabled()).isTrue();
    }

    @Test
    @DisplayName("wait time accepts any non-negative duration, zero meaning fail fast")
    void waitTimeAcceptsNonNegative() {
        MedLockProperties properties = new MedLockProperties();

        properties.setWaitTime(Duration.ofMillis(500));
        assertThat(properties.getWaitTime()).isEqualTo(Duration.ofMillis(500));

        properties.setWaitTime(Duration.ZERO);
        assertThat(properties.getWaitTime()).isZero();
    }

    @Test
    @DisplayName("negative or null wait time is rejected instead of meaning 'wait forever'")
    void waitTimeRejectsInvalid() {
        MedLockProperties properties = new MedLockProperties();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setWaitTime(Duration.ofSeconds(-1)))
                .withMessageContaining("med.lock.wait-time");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setWaitTime(null))
                .withMessageContaining("med.lock.wait-time");
        assertThat(properties.getWaitTime()).isEqualTo(MedLockProperties.DEFAULT_WAIT_TIME);
    }

    @Test
    @DisplayName("a positive lease time switches the watchdog off")
    void leaseTimeDisablesWatchdog() {
        MedLockProperties properties = new MedLockProperties();

        properties.setLeaseTime(Duration.ofSeconds(10));

        assertThat(properties.getLeaseTime()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.isWatchdogEnabled()).isFalse();
    }

    @Test
    @DisplayName("negative or null lease time is rejected")
    void leaseTimeRejectsInvalid() {
        MedLockProperties properties = new MedLockProperties();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setLeaseTime(Duration.ofMillis(-1)))
                .withMessageContaining("med.lock.lease-time");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setLeaseTime(null))
                .withMessageContaining("med.lock.lease-time");
        assertThat(properties.isWatchdogEnabled()).isTrue();
    }

    @Test
    @DisplayName("watchdog timeout accepts a positive duration")
    void watchdogTimeoutAcceptsPositive() {
        MedLockProperties properties = new MedLockProperties();

        properties.setWatchdogTimeout(Duration.ofSeconds(45));

        assertThat(properties.getWatchdogTimeout()).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    @DisplayName("zero, negative or null watchdog timeout is rejected")
    void watchdogTimeoutRejectsInvalid() {
        MedLockProperties properties = new MedLockProperties();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setWatchdogTimeout(Duration.ZERO))
                .withMessageContaining("med.lock.watchdog-timeout");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setWatchdogTimeout(Duration.ofSeconds(-5)))
                .withMessageContaining("med.lock.watchdog-timeout");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setWatchdogTimeout(null))
                .withMessageContaining("med.lock.watchdog-timeout");
        assertThat(properties.getWatchdogTimeout()).isEqualTo(MedLockProperties.DEFAULT_WATCHDOG_TIMEOUT);
    }

    @Test
    @DisplayName("toString exposes the effective timings for startup diagnostics")
    void toStringExposesTimings() {
        MedLockProperties properties = new MedLockProperties();
        properties.setWaitTime(Duration.ofSeconds(2));
        properties.setLeaseTime(Duration.ofSeconds(8));

        assertThat(properties.toString())
                .contains("waitTime=PT2S")
                .contains("leaseTime=PT8S")
                .contains("watchdogTimeout=PT30S");
    }
}
