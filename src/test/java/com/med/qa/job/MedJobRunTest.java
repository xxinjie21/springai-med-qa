package com.med.qa.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests of {@link MedJobRun}, the record the ledger accepts.
 *
 * <p>Validation is eager for the same reason the project's configuration properties validate eagerly: a
 * blank job identifier or a negative duration is a programming error, and letting it through would create
 * a series whose label set or value an operator cannot trust.</p>
 */
class MedJobRunTest {

    private static final String JOB = "session-retention";
    private static final Instant STARTED_AT = Instant.parse("2026-10-14T09:00:00Z");

    @Test
    @DisplayName("a completed run carries its job, instant and duration")
    void completedRunCarriesItsFields() {
        MedJobRun run = MedJobRun.completed(JOB, STARTED_AT, 1_234L);

        assertThat(run.job()).isEqualTo(JOB);
        assertThat(run.outcome()).isEqualTo(MedJobOutcome.COMPLETED);
        assertThat(run.startedAt()).isEqualTo(STARTED_AT);
        assertThat(run.durationMillis()).isEqualTo(1_234L);
        assertThat(run.completed()).isTrue();
    }

    @Test
    @DisplayName("a run that stepped aside is not a completed run")
    void skippedRunIsNotCompleted() {
        MedJobRun run = MedJobRun.skippedLockHeld(JOB, STARTED_AT, 5L);

        assertThat(run.outcome()).isEqualTo(MedJobOutcome.SKIPPED_LOCK_HELD);
        assertThat(run.completed()).isFalse();
    }

    @Test
    @DisplayName("a failed run is not a completed run")
    void failedRunIsNotCompleted() {
        MedJobRun run = MedJobRun.failed(JOB, STARTED_AT, 5L);

        assertThat(run.outcome()).isEqualTo(MedJobOutcome.FAILED);
        assertThat(run.completed()).isFalse();
    }

    @Test
    @DisplayName("boundary: a zero duration is legal, an instantaneous run is still a run")
    void zeroDurationIsLegal() {
        assertThat(MedJobRun.completed(JOB, STARTED_AT, 0L).durationMillis()).isZero();
    }

    @Test
    @DisplayName("a blank job identifier is rejected, because it would produce an unusable label value")
    void blankJobIsRejected() {
        assertThatThrownBy(() -> MedJobRun.completed("   ", STARTED_AT, 1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("job");
    }

    @Test
    @DisplayName("a null job identifier is rejected")
    void nullJobIsRejected() {
        assertThatThrownBy(() -> MedJobRun.completed(null, STARTED_AT, 1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("job");
    }

    @Test
    @DisplayName("a null outcome is rejected")
    void nullOutcomeIsRejected() {
        assertThatThrownBy(() -> new MedJobRun(JOB, null, STARTED_AT, 1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outcome");
    }

    @Test
    @DisplayName("a null start instant is rejected")
    void nullStartedAtIsRejected() {
        assertThatThrownBy(() -> new MedJobRun(JOB, MedJobOutcome.COMPLETED, null, 1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("startedAt");
    }

    @Test
    @DisplayName("a negative duration is rejected")
    void negativeDurationIsRejected() {
        assertThatThrownBy(() -> MedJobRun.completed(JOB, STARTED_AT, -1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("durationMillis");
    }

    @Nested
    @DisplayName("MedJobOutcome")
    class OutcomeTest {

        @Test
        @DisplayName("every outcome the ledger distinguishes is declared, and only those")
        void theOutcomeSetIsDeliberate() {
            assertThat(MedJobOutcome.values())
                    .containsExactly(MedJobOutcome.COMPLETED, MedJobOutcome.SKIPPED_LOCK_HELD,
                            MedJobOutcome.FAILED);
        }
    }
}
