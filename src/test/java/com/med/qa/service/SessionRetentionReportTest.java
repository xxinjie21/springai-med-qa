package com.med.qa.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests of {@link SessionRetentionReport}.
 *
 * <p>This is the only thing an operator sees from an unattended job, so the value object is pinned for
 * both halves of its contract: the counters it renders, and the {@code -1} sentinel that distinguishes
 * "no backlog" from "not measured" — the two cases that must never be conflated, because a run that
 * never reached the database reporting a backlog of zero is exactly the silent success this iteration
 * exists to prevent.</p>
 */
class SessionRetentionReportTest {

    @Test
    @DisplayName("a completed run exposes every counter and the failure list")
    void exposesEveryCounter() {
        SessionRetentionReport report = new SessionRetentionReport(
                SessionRetentionReport.Outcome.COMPLETED, false, 1_000L, 5, 3, 1, 1, 2, 4L, 12L,
                List.of("session-x: STORAGE_ERROR: redis down"));

        assertThat(report.outcome()).isEqualTo(SessionRetentionReport.Outcome.COMPLETED);
        assertThat(report.dryRun()).isFalse();
        assertThat(report.idleBeforeEpochMillis()).isEqualTo(1_000L);
        assertThat(report.candidates()).isEqualTo(5);
        assertThat(report.archived()).isEqualTo(3);
        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.batches()).isEqualTo(2);
        assertThat(report.remaining()).isEqualTo(4L);
        assertThat(report.durationMillis()).isEqualTo(12L);
        assertThat(report.failures()).containsExactly("session-x: STORAGE_ERROR: redis down");
        assertThat(report.archivedAnything()).isTrue();
        assertThat(report.summary()).contains("outcome=COMPLETED", "archived=3", "remaining=4");
    }

    @Test
    @DisplayName("a run that archived nothing is not 'archived anything'")
    void archivedAnythingIsFalseForAQuietRun() {
        SessionRetentionReport report = report(0, 0, 0);

        assertThat(report.archivedAnything()).isFalse();
        assertThat(report.summary()).contains("archived=0");
    }

    @Test
    @DisplayName("a skipped run reports an unmeasured backlog instead of a zero backlog")
    void skippedRunReportsAnUnmeasuredBacklog() {
        SessionRetentionReport report = SessionRetentionReport.skipped(1_000L, 3L);

        assertThat(report.outcome()).isEqualTo(SessionRetentionReport.Outcome.SKIPPED_LOCK_HELD);
        assertThat(report.remaining()).isEqualTo(-1L);
        assertThat(report.candidates()).isZero();
        assertThat(report.dryRun()).isFalse();
        assertThat(report.summary()).contains("remaining=unknown");
    }

    @Test
    @DisplayName("a negative counter other than the sentinel is rejected")
    void negativeCountersAreRejected() {
        assertThatThrownBy(() -> new SessionRetentionReport(SessionRetentionReport.Outcome.COMPLETED,
                false, 0L, -1, 0, 0, 0, 0, 0L, 0L, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("counters");
        assertThatThrownBy(() -> new SessionRetentionReport(SessionRetentionReport.Outcome.COMPLETED,
                false, 0L, 0, 0, 0, 0, 0, -2L, 0L, List.of()))
                .as("only -1 means 'not measured'")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionRetentionReport(null, false, 0L, 0, 0, 0, 0, 0, 0L, 0L,
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outcome");
    }

    @Test
    @DisplayName("a null failure list normalises to an empty immutable one")
    void nullFailuresNormaliseToEmpty() {
        SessionRetentionReport report = new SessionRetentionReport(SessionRetentionReport.Outcome.COMPLETED,
                false, 0L, 0, 0, 0, 0, 0, 0L, 0L, null);

        assertThat(report.failures()).isEmpty();
        assertThatThrownBy(() -> report.failures().add("nope"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("the failure list is copied, so a later mutation of the source cannot rewrite history")
    void failuresAreDefensivelyCopied() {
        List<String> failures = new ArrayList<>();
        failures.add("session-a: STORAGE_ERROR: mysql down");

        SessionRetentionReport report = new SessionRetentionReport(SessionRetentionReport.Outcome.COMPLETED,
                false, 0L, 1, 0, 0, 1, 1, 0L, 1L, failures);
        failures.add("session-b: late mutation");

        assertThat(report.failures()).containsExactly("session-a: STORAGE_ERROR: mysql down");
    }

    private static SessionRetentionReport report(int archived, int skipped, int failed) {
        return new SessionRetentionReport(SessionRetentionReport.Outcome.COMPLETED, false, 1_000L,
                archived + skipped + failed, archived, skipped, failed, 1, 0L, 1L, List.of());
    }
}
