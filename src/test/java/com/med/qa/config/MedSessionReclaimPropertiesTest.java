package com.med.qa.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests of {@link MedSessionReclaimProperties} (D52).
 *
 * <p>The defaults are the contract here. This is the second job in the project that deletes clinical
 * records, so both conservative knobs have to default to "do not": {@code enabled=false} and
 * {@code dry-run=true}.</p>
 *
 * <p>What is equally part of the contract is what the class does <em>not</em> have: there is no
 * cooling-off window. The D50 purge needs one because it destroys the ability to move a session back;
 * this job destroys nothing, so a window here would delay the release of space while pretending to
 * protect something. A future iteration that adds one "for symmetry" would be adding a knob that means
 * nothing, which is why the absence is asserted.</p>
 */
class MedSessionReclaimPropertiesTest {

    private final MedSessionReclaimProperties properties = new MedSessionReclaimProperties();

    @Test
    @DisplayName("both conservative knobs default to the safe side")
    void defaultsAreConservative() {
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isDryRun()).isTrue();
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
        assertThat(MedSessionReclaimProperties.LOCK_KEY).isEqualTo("med:lock:session:reclaim");
        assertThat(MedSessionReclaimProperties.LOCK_KEY).startsWith("med:lock:");
        assertThat(MedSessionReclaimProperties.PREFIX).isEqualTo("med.session.reclaim");
        assertThat(MedSessionReclaimProperties.LOCK_KEY)
                .as("a per-deployment key would silently remove the cluster-wide mutual exclusion")
                .isNotEqualTo(MedSessionPurgeProperties.LOCK_KEY);
    }

    @Test
    @DisplayName("invalid values are rejected at bind time")
    void invalidValuesAreRejected() {
        assertThatThrownBy(() -> properties.setBatchSize(0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("batch-size");
        assertThatThrownBy(() -> properties.setMaxBatches(0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-batches");
        assertThatThrownBy(() -> properties.setCheckInterval(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("check-interval");
        assertThatThrownBy(() -> properties.setCheckInterval(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("check-interval");
        assertThatThrownBy(() -> properties.setInitialDelay(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("initial-delay");
        assertThatThrownBy(() -> properties.setLockWaitTime(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lock-wait-time");
        assertThatThrownBy(() -> properties.setLockLeaseTime(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lock-lease-time");
    }

    @Test
    @DisplayName("a non-negative initial delay and wait time are accepted, including zero")
    void zeroIsLegalWhereItMeansSomething() {
        properties.setInitialDelay(Duration.ZERO);
        properties.setLockWaitTime(Duration.ZERO);

        assertThat(properties.getInitialDelay()).isZero();
        assertThat(properties.getLockWaitTime()).isZero();
    }

    @Test
    @DisplayName("the toString names every knob, so a startup log line is readable")
    void toStringNamesEveryKnob() {
        assertThat(properties.toString())
                .contains("enabled=false")
                .contains("dryRun=true")
                .contains("batchSize=50")
                .contains("maxBatches=5")
                .contains("lockLeaseTime=PT5M");
    }
}
