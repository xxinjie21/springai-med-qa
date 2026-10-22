package com.med.qa.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests of {@link AuditLogRetentionReport} (D55).
 *
 * <p>The report is the artefact an operator reads before arming the only job whose deletions have no
 * second copy, so the counters that must never be negative — and the one value that may, because
 * {@code -1} means "not measured" — are pinned here.</p>
 */
class AuditLogRetentionReportTest {

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("negative counters are rejected, except a remaining of -1 meaning 'not measured'")
        void negativeCountersAreRejected() {
            assertThatThrownBy(() -> report(-1L, 0L, 0L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("retention counters must not be negative");
            assertThatThrownBy(() -> report(0L, -1L, 0L))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> report(0L, 0L, -2L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a null outcome is rejected")
        void nullOutcomeIsRejected() {
            assertThatThrownBy(() -> new AuditLogRetentionReport(null, false, 365, 0L, 0L, 0L, 0, 0L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outcome must not be null");
        }

        @Test
        @DisplayName("a non-positive retention window is rejected, mirroring the property floor")
        void retentionWindowMustBePositive() {
            assertThatThrownBy(() -> new AuditLogRetentionReport(
                    AuditLogRetentionReport.Outcome.COMPLETED, false, 0, 0L, 0L, 0L, 0, 0L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("retentionDays must be positive");
        }

        @Test
        @DisplayName("the skipped factory method reports 'not measured' rather than zero")
        void skippedFactoryMethod() {
            AuditLogRetentionReport report = AuditLogRetentionReport.skipped(365, 7L);

            assertThat(report.outcome())
                    .isEqualTo(AuditLogRetentionReport.Outcome.SKIPPED_LOCK_HELD);
            assertThat(report.remaining()).isEqualTo(-1L);
            assertThat(report.expired()).isZero();
            assertThat(report.deleted()).isZero();
            assertThat(report.batches()).isZero();
            assertThat(report.durationMillis()).isEqualTo(7L);
            assertThat(report.deletedAnything()).isFalse();
        }
    }

    @Nested
    @DisplayName("the summary")
    class Summary {

        @Test
        @DisplayName("renders every counter, and 'unknown' rather than -1 for an unmeasured backlog")
        void rendersEveryCounter() {
            AuditLogRetentionReport report = new AuditLogRetentionReport(
                    AuditLogRetentionReport.Outcome.COMPLETED, true, 365, 1_200L, 1_200L, 0L, 3, 42L);

            assertThat(report.summary())
                    .isEqualTo("outcome=COMPLETED, dryRun=true, retentionDays=365, expired=1200, "
                            + "deleted=1200, remaining=0, batches=3, durationMs=42");
        }

        @Test
        @DisplayName("a run that stepped aside says 'unknown' instead of a negative remaining")
        void unmeasuredRemainingReadsAsUnknown() {
            assertThat(AuditLogRetentionReport.skipped(365, 0L).summary())
                    .contains("remaining=unknown");
        }
    }

    private static AuditLogRetentionReport report(long expired, long deleted, long remaining) {
        return new AuditLogRetentionReport(AuditLogRetentionReport.Outcome.COMPLETED, false, 365,
                expired, deleted, remaining, 1, 1L);
    }
}
