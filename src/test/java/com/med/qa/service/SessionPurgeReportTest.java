package com.med.qa.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests of {@link SessionPurgeReport} (D50).
 *
 * <p>The report is the only artefact an operator gets from a job that deletes clinical data, so its
 * contract is pinned here: the counters may not go negative, {@code remaining = -1} is the one legal
 * negative and means "not measured", the failure list is defensively copied, and the two predicates an
 * operator reads first ({@link SessionPurgeReport#purgedAnything()} and
 * {@link SessionPurgeReport#sawRefusal()}) agree with the counters.</p>
 */
class SessionPurgeReportTest {

    private static final long CUTOFF = 1_735_000_000_000L;

    @Test
    @DisplayName("a completed report exposes its counters and predicates")
    void completedReport() {
        SessionPurgeReport report = new SessionPurgeReport(SessionPurgeReport.Outcome.COMPLETED, false,
                CUTOFF, 9, 5, 1, 2, 1, 3, 42, 1_500, List.of("s-2: REFUSED: cold copy differs"));

        assertThat(report.outcome()).isEqualTo(SessionPurgeReport.Outcome.COMPLETED);
        assertThat(report.dryRun()).isFalse();
        assertThat(report.archivedBeforeEpochMillis()).isEqualTo(CUTOFF);
        assertThat(report.candidates()).isEqualTo(9);
        assertThat(report.purged()).isEqualTo(5);
        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.refused()).isEqualTo(2);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.batches()).isEqualTo(3);
        assertThat(report.remaining()).isEqualTo(42);
        assertThat(report.durationMillis()).isEqualTo(1_500);
        assertThat(report.failures()).hasSize(1);
        assertThat(report.purgedAnything()).isTrue();
        assertThat(report.sawRefusal()).isTrue();
    }

    @Test
    @DisplayName("a run that stepped aside reports -1 for the unmeasured backlog")
    void skippedReportLeavesTheBacklogUnmeasured() {
        SessionPurgeReport report = SessionPurgeReport.skipped(CUTOFF, 12);

        assertThat(report.outcome()).isEqualTo(SessionPurgeReport.Outcome.SKIPPED_LOCK_HELD);
        assertThat(report.dryRun()).isFalse();
        assertThat(report.archivedBeforeEpochMillis()).isEqualTo(CUTOFF);
        assertThat(report.remaining()).isEqualTo(-1);
        assertThat(report.candidates()).isZero();
        assertThat(report.purged()).isZero();
        assertThat(report.failures()).isEmpty();
        assertThat(report.purgedAnything()).isFalse();
        assertThat(report.sawRefusal()).isFalse();
    }

    @Test
    @DisplayName("the failure list is defensively copied, so a caller cannot mutate the report")
    void failureListIsCopied() {
        List<String> mutable = new ArrayList<>(List.of("s-1: REFUSED: no manifest"));
        SessionPurgeReport report = new SessionPurgeReport(SessionPurgeReport.Outcome.COMPLETED, true,
                CUTOFF, 1, 0, 0, 1, 0, 1, 0, 1, mutable);

        mutable.add("s-2: injected");

        assertThat(report.failures()).containsExactly("s-1: REFUSED: no manifest");
        assertThatThrownBy(() -> report.failures().add("nope"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a null failure list becomes an empty one rather than a null")
    void nullFailureListBecomesEmpty() {
        SessionPurgeReport report = new SessionPurgeReport(SessionPurgeReport.Outcome.COMPLETED, false,
                CUTOFF, 0, 0, 0, 0, 0, 0, 0, 1, null);

        assertThat(report.failures()).isEmpty();
    }

    @Test
    @DisplayName("a negative counter is rejected, and only remaining may be -1")
    void negativeCountersAreRejected() {
        assertThatThrownBy(() -> new SessionPurgeReport(null, false, CUTOFF, 0, 0, 0, 0, 0, 0, 0, 0,
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outcome must not be null");
        assertThatThrownBy(() -> new SessionPurgeReport(SessionPurgeReport.Outcome.COMPLETED, false,
                CUTOFF, -1, 0, 0, 0, 0, 0, 0, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");
        assertThatThrownBy(() -> new SessionPurgeReport(SessionPurgeReport.Outcome.COMPLETED, false,
                CUTOFF, 0, 0, 0, 0, 0, 0, -2, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");
    }

    @Test
    @DisplayName("the summary renders every counter and spells out an unmeasured backlog")
    void summaryRendersEveryCounter() {
        SessionPurgeReport report = new SessionPurgeReport(SessionPurgeReport.Outcome.COMPLETED, true,
                CUTOFF, 4, 0, 0, 1, 0, 1, 7, 25, List.of());

        assertThat(report.summary())
                .contains("outcome=COMPLETED", "dryRun=true", "candidates=4", "purged=0", "skipped=0",
                        "refused=1", "failed=0", "batches=1", "remaining=7", "durationMs=25");
        assertThat(SessionPurgeReport.skipped(CUTOFF, 5).summary()).contains("remaining=unknown");
    }
}
