package com.med.qa.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests of {@link SessionArchiveExportReport} (D48).
 *
 * <p>The report is the only thing an operator sees from an unattended job, so its two traps are pinned
 * here: a run that stepped aside for another replica must not look like a run that found nothing, and
 * the mismatch counter must never be silently folded into a success.</p>
 */
class SessionArchiveExportReportTest {

    @Test
    @DisplayName("a run that stepped aside reports its own outcome and no measurement")
    void skippedRunIsDistinguishable() {
        SessionArchiveExportReport report = SessionArchiveExportReport.skipped(12L);

        assertThat(report.outcome()).isEqualTo(SessionArchiveExportReport.Outcome.SKIPPED_LOCK_HELD);
        assertThat(report.remaining()).isEqualTo(-1L);
        assertThat(report.exportedAnything()).isFalse();
        assertThat(report.sawMismatch()).isFalse();
        assertThat(report.durationMillis()).isEqualTo(12L);
        assertThat(report.summary()).contains("outcome=SKIPPED_LOCK_HELD", "remaining=unknown");
    }

    @Test
    @DisplayName("a completed run with zero candidates is not the same thing as a skipped run")
    void emptyRunIsNotASkippedRun() {
        SessionArchiveExportReport report = new SessionArchiveExportReport(
                SessionArchiveExportReport.Outcome.COMPLETED, false, 0, 0, 0, 0, 0, 0, 0L, 5L,
                List.of());

        assertThat(report.remaining()).isZero();
        assertThat(report.summary()).contains("outcome=COMPLETED", "remaining=0");
    }

    @Test
    @DisplayName("the flags follow the counters")
    void flagsFollowTheCounters() {
        assertThat(report(3, 0, 0, 0).exportedAnything()).isTrue();
        assertThat(report(3, 0, 0, 0).sawMismatch()).isFalse();
        assertThat(report(0, 2, 0, 0).exportedAnything()).isFalse();
        assertThat(report(0, 2, 0, 0).sawMismatch()).isTrue();
        assertThat(report(5, 0, 1, 0).sawMismatch()).isFalse();
    }

    @Test
    @DisplayName("the failure list is copied and normalised, so the report cannot be mutated afterwards")
    void failuresAreCopied() {
        SessionArchiveExportReport report = new SessionArchiveExportReport(
                SessionArchiveExportReport.Outcome.COMPLETED, false, 1, 0, 0, 1, 0, 1, 0L, 1L, null);
        assertThat(report.failures()).isEmpty();

        SessionArchiveExportReport withFailures = new SessionArchiveExportReport(
                SessionArchiveExportReport.Outcome.COMPLETED, false, 1, 0, 0, 1, 0, 1, 0L, 1L,
                Arrays.asList("s1: MISMATCH: x"));
        assertThat(withFailures.failures()).containsExactly("s1: MISMATCH: x");
        assertThatThrownBy(() -> withFailures.failures().add("y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("negative counters and a missing outcome are rejected")
    void invalidReportsAreRejected() {
        assertThatThrownBy(() -> new SessionArchiveExportReport(null, false, 0, 0, 0, 0, 0, 0, 0L, 0L,
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outcome must not be null");
        assertThatThrownBy(() -> report(-1, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");
        assertThatThrownBy(() -> new SessionArchiveExportReport(
                SessionArchiveExportReport.Outcome.COMPLETED, false, 0, 0, 0, 0, 0, 0, -2L, 0L,
                List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static SessionArchiveExportReport report(int exported, int mismatched, int failed,
                                                     int skipped) {
        return new SessionArchiveExportReport(SessionArchiveExportReport.Outcome.COMPLETED, false,
                exported + mismatched + failed + skipped, exported, skipped, mismatched, failed, 1, 0L,
                1L, List.of());
    }
}
