package com.med.qa.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MedAuditLogRetentionProperties} (D55).
 *
 * <p>The property that deserves its own suite is {@code retention-days}: it is the only guard this job
 * has, because an audit row has no second copy. A window that can be configured to zero is not a guard,
 * so the floor is asserted here as behaviour, not as documentation.</p>
 */
class MedAuditLogRetentionPropertiesTest {

    @Nested
    @DisplayName("the retention window")
    class RetentionWindow {

        @Test
        @DisplayName("defaults to a year, comfortably above the floor")
        void defaultsToAYear() {
            assertThat(new MedAuditLogRetentionProperties().getRetentionDays()).isEqualTo(365);
        }

        @Test
        @DisplayName("the floor itself is accepted")
        void theFloorIsAccepted() {
            MedAuditLogRetentionProperties properties = new MedAuditLogRetentionProperties();
            properties.setRetentionDays(MedAuditLogRetentionProperties.MIN_RETENTION_DAYS);
            assertThat(properties.getRetentionDays())
                    .isEqualTo(MedAuditLogRetentionProperties.MIN_RETENTION_DAYS);
        }

        @Test
        @DisplayName("a window below the floor is rejected, because it would trim recent compliance records")
        void belowTheFloorIsRejected() {
            MedAuditLogRetentionProperties properties = new MedAuditLogRetentionProperties();
            assertThatThrownBy(() -> properties.setRetentionDays(
                    MedAuditLogRetentionProperties.MIN_RETENTION_DAYS - 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("retention-days must be at least")
                    .hasMessageContaining("the retention window is the guard");
        }

        @Test
        @DisplayName("zero and negative windows are rejected for the same reason")
        void zeroAndNegativeAreRejected() {
            MedAuditLogRetentionProperties properties = new MedAuditLogRetentionProperties();
            assertThatThrownBy(() -> properties.setRetentionDays(0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> properties.setRetentionDays(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the previous value survives a rejected assignment")
        void aRejectedAssignmentKeepsThePreviousValue() {
            MedAuditLogRetentionProperties properties = new MedAuditLogRetentionProperties();
            assertThatThrownBy(() -> properties.setRetentionDays(1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(properties.getRetentionDays()).isEqualTo(365);
        }
    }

    @Nested
    @DisplayName("the batching knobs")
    class Batching {

        @Test
        @DisplayName("a non-positive batch size is rejected")
        void batchSizeMustBePositive() {
            MedAuditLogRetentionProperties properties = new MedAuditLogRetentionProperties();
            assertThatThrownBy(() -> properties.setBatchSize(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("batch-size must be positive");
        }

        @Test
        @DisplayName("a non-positive pass count is rejected")
        void maxBatchesMustBePositive() {
            MedAuditLogRetentionProperties properties = new MedAuditLogRetentionProperties();
            assertThatThrownBy(() -> properties.setMaxBatches(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("max-batches must be positive");
        }

        @Test
        @DisplayName("the per-run ceiling is the product of the two")
        void maxRowsPerRunIsTheProduct() {
            MedAuditLogRetentionProperties properties = new MedAuditLogRetentionProperties();
            properties.setBatchSize(7);
            properties.setMaxBatches(3);
            assertThat(properties.maxRowsPerRun()).isEqualTo(21);
        }
    }

    @Nested
    @DisplayName("the timing knobs")
    class Timing {

        @Test
        @DisplayName("a zero or negative interval is rejected, because the ledger publishes it as a threshold")
        void intervalMustBePositive() {
            MedAuditLogRetentionProperties properties = new MedAuditLogRetentionProperties();
            assertThatThrownBy(() -> properties.setCheckInterval(Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("check-interval must be a positive duration");
            assertThatThrownBy(() -> properties.setCheckInterval(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a zero initial delay is legal: a short-lived test wants to start immediately")
        void initialDelayMayBeZero() {
            MedAuditLogRetentionProperties properties = new MedAuditLogRetentionProperties();
            properties.setInitialDelay(Duration.ZERO);
            assertThat(properties.getInitialDelay()).isEqualTo(Duration.ZERO);
            assertThatThrownBy(() -> properties.setInitialDelay(Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a negative lock wait or lease is rejected")
        void lockKnobsMustNotBeNegative() {
            MedAuditLogRetentionProperties properties = new MedAuditLogRetentionProperties();
            assertThatThrownBy(() -> properties.setLockWaitTime(Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> properties.setLockLeaseTime(Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a zero lease hands renewal to the watchdog")
        void zeroLeaseMeansWatchdog() {
            MedAuditLogRetentionProperties properties = new MedAuditLogRetentionProperties();
            properties.setLockLeaseTime(Duration.ZERO);
            assertThat(properties.isLockWatchdogEnabled()).isTrue();
            properties.setLockLeaseTime(Duration.ofMinutes(5));
            assertThat(properties.isLockWatchdogEnabled()).isFalse();
        }
    }

    @Test
    @DisplayName("both switches default to the conservative side, like every other deleting job")
    void switchesDefaultConservatively() {
        MedAuditLogRetentionProperties properties = new MedAuditLogRetentionProperties();
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isDryRun()).isTrue();
    }
}
