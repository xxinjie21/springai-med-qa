package com.med.qa.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests of {@link SessionReclaimReport} (D52).
 *
 * <p>The report is one of only two artefacts an operator gets from a job that deletes clinical data, so
 * its contract is pinned here: the counters may not go negative, {@code remaining = -1} is the one legal
 * negative and means "not measured", the failure list is defensively copied, and the two predicates an
 * operator reads first ({@link SessionReclaimReport#reclaimedAnything()} and
 * {@link SessionReclaimReport#sawRefusal()}) agree with the counters.</p>
 *
 * <p>{@link SessionReclaimReport#reclaimedMessages()} is pinned separately, because it is the field this
 * report has and its sibling does not: the reclaim frees space, and a run that retired three sessions
 * holding forty rows is not the same operation as one that retired three single-row copies.</p>
 */
class SessionReclaimReportTest {

    @Test
    @DisplayName("a completed report exposes its counters and predicates")
    void completedReport() {
        SessionReclaimReport report = new SessionReclaimReport(SessionReclaimReport.Outcome.COMPLETED,
                false, 9, 5, 42L, 1, 2, 1, 3, 7, 1_500, List.of("s-2: REFUSED: not in the live transcript"));

        assertThat(report.outcome()).isEqualTo(SessionReclaimReport.Outcome.COMPLETED);
        assertThat(report.dryRun()).isFalse();
        assertThat(report.candidates()).isEqualTo(9);
        assertThat(report.reclaimed()).isEqualTo(5);
        assertThat(report.reclaimedMessages()).isEqualTo(42);
        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.refused()).isEqualTo(2);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.batches()).isEqualTo(3);
        assertThat(report.remaining()).isEqualTo(7);
        assertThat(report.durationMillis()).isEqualTo(1_500);
        assertThat(report.failures()).hasSize(1);
        assertThat(report.reclaimedAnything()).isTrue();
        assertThat(report.sawRefusal()).isTrue();
    }

    @Test
    @DisplayName("a run that stepped aside reports -1 for the unmeasured backlog")
    void skippedReportLeavesTheBacklogUnmeasured() {
        SessionReclaimReport report = SessionReclaimReport.skipped(12);

        assertThat(report.outcome()).isEqualTo(SessionReclaimReport.Outcome.SKIPPED_LOCK_HELD);
        assertThat(report.dryRun()).isFalse();
        assertThat(report.remaining()).isEqualTo(-1);
        assertThat(report.candidates()).isZero();
        assertThat(report.reclaimed()).isZero();
        assertThat(report.reclaimedMessages()).isZero();
        assertThat(report.failures()).isEmpty();
        assertThat(report.reclaimedAnything()).isFalse();
        assertThat(report.sawRefusal()).isFalse();
        assertThat(report.summary()).contains("remaining=unknown");
    }

    @Test
    @DisplayName("a quiet run reports zeroes and neither predicate fires")
    void quietRun() {
        SessionReclaimReport report = new SessionReclaimReport(SessionReclaimReport.Outcome.COMPLETED,
                false, 0, 0, 0L, 0, 0, 0, 0, 0, 3, List.of());

        assertThat(report.reclaimedAnything()).isFalse();
        assertThat(report.sawRefusal()).isFalse();
        assertThat(report.summary()).contains("candidates=0").contains("remaining=0");
    }

    @Test
    @DisplayName("a refusal alone does not count as having reclaimed anything")
    void refusalAlone() {
        SessionReclaimReport report = new SessionReclaimReport(SessionReclaimReport.Outcome.COMPLETED,
                false, 1, 0, 0L, 0, 1, 0, 1, 1, 5, List.of("s-1: REFUSED: 2 archived message(s)"));

        assertThat(report.reclaimedAnything()).isFalse();
        assertThat(report.sawRefusal()).isTrue();
        assertThat(report.failures()).hasSize(1);
    }

    @Test
    @DisplayName("the failure list is defensively copied")
    void failureListIsCopied() {
        List<String> failures = new ArrayList<>();
        failures.add("s-1: REFUSED: x");
        SessionReclaimReport report = new SessionReclaimReport(SessionReclaimReport.Outcome.COMPLETED,
                false, 1, 0, 0L, 0, 1, 0, 1, 0, 1, failures);

        failures.add("s-2: REFUSED: y");

        assertThat(report.failures()).containsExactly("s-1: REFUSED: x");
    }

    @Test
    @DisplayName("a null failure list becomes an empty one rather than a null")
    void nullFailureListBecomesEmpty() {
        SessionReclaimReport report = new SessionReclaimReport(SessionReclaimReport.Outcome.COMPLETED,
                false, 0, 0, 0L, 0, 0, 0, 0, 0, 0, null);

        assertThat(report.failures()).isEmpty();
    }

    @Test
    @DisplayName("negative counters and a null outcome are rejected")
    void invalidCountersAreRejected() {
        assertThatThrownBy(() -> new SessionReclaimReport(null, false, 0, 0, 0L, 0, 0, 0, 0, 0, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outcome");
        assertThatThrownBy(() -> new SessionReclaimReport(SessionReclaimReport.Outcome.COMPLETED, false,
                -1, 0, 0L, 0, 0, 0, 0, 0, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must not be negative");
        assertThatThrownBy(() -> new SessionReclaimReport(SessionReclaimReport.Outcome.COMPLETED, false,
                0, 0, -1L, 0, 0, 0, 0, 0, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must not be negative");
        assertThatThrownBy(() -> new SessionReclaimReport(SessionReclaimReport.Outcome.COMPLETED, false,
                0, 0, 0L, 0, 0, 0, 0, -2, 0, List.of()))
                .as("remaining = -1 means 'not measured'; anything below it is a bug")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must not be negative");
    }
}
