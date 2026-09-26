package com.med.qa.rag;

import com.med.qa.rag.MedIndexRebuildReport.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests for {@link MedIndexRebuildReport}.
 *
 * <p>The report is what an operator reads after a destructive operation, so its verdict helpers have
 * to be exact: {@code VERIFICATION_FAILED} and {@code FAILED} are both "not success", and a
 * non-destructive rebuild that loses documents must be visible even though the outcome says
 * {@code COMPLETED}.</p>
 */
class MedIndexRebuildReportTest {

    private static MedIndexRebuildReport report(Outcome outcome, long before, long after) {
        return new MedIndexRebuildReport("med-doc-index", MedIndexRebuildMode.INDEX_ONLY, outcome,
                before, after, 0, 0, true, 42L, "index dropped and recreated");
    }

    @Test
    @DisplayName("only COMPLETED counts as a success")
    void onlyCompletedIsSuccess() {
        assertThat(report(Outcome.COMPLETED, 3, 3).isSuccess()).isTrue();
        assertThat(report(Outcome.VERIFICATION_FAILED, 3, 0).isSuccess()).isFalse();
        assertThat(report(Outcome.FAILED, 3, 0).isSuccess()).isFalse();
        assertThat(report(Outcome.SKIPPED_LOCK_HELD, 0, 0).isSuccess()).isFalse();
        assertThat(report(Outcome.REFUSED, 0, 0).isSuccess()).isFalse();
        assertThat(Outcome.COMPLETED.label()).isEqualTo("completed");
        assertThat(Outcome.SKIPPED_LOCK_HELD.label()).isEqualTo("skipped-lock-held");
    }

    @Test
    @DisplayName("a changed document count is visible, which is how a lost corpus is spotted")
    void documentCountChangeIsVisible() {
        assertThat(report(Outcome.COMPLETED, 3, 3).documentCountChanged()).isFalse();
        assertThat(report(Outcome.COMPLETED, 3, 0).documentCountChanged()).isTrue();
        assertThat(report(Outcome.COMPLETED, 0, 5).documentCountChanged()).isTrue();
    }

    @Test
    @DisplayName("the rendering carries counts, mode and outcome, so it can be logged and alerted")
    void describeIsOperatorFacing() {
        MedIndexRebuildReport report = new MedIndexRebuildReport("med-doc-index",
                MedIndexRebuildMode.DROP_AND_REINGEST, Outcome.COMPLETED, 12, 5, 5, 2, true, 1500L,
                "corpus replaced (requested by ops-oncall)");

        assertThat(report.describe())
                .contains("index=med-doc-index")
                .contains("mode=DROP_AND_REINGEST")
                .contains("outcome=completed")
                .contains("documents=12->5")
                .contains("ingested=5")
                .contains("batches=2")
                .contains("dropped=true")
                .contains("durationMillis=1500")
                .contains("ops-oncall");
        assertThat(report.toString()).startsWith("MedIndexRebuildReport{");
    }

    @Test
    @DisplayName("boundary: negative counters, a negative duration or a blank message are rejected")
    void invalidReportsAreRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedIndexRebuildReport("med-doc-index", MedIndexRebuildMode.INDEX_ONLY,
                        Outcome.COMPLETED, -1, 0, 0, 0, false, 0, "ok"))
                .withMessageContaining("counters");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedIndexRebuildReport("med-doc-index", MedIndexRebuildMode.INDEX_ONLY,
                        Outcome.COMPLETED, 0, 0, 0, 0, false, -1, "ok"))
                .withMessageContaining("durationMillis");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedIndexRebuildReport("med-doc-index", MedIndexRebuildMode.INDEX_ONLY,
                        Outcome.COMPLETED, 0, 0, 0, 0, false, 0, " "))
                .withMessageContaining("message");
    }

    @Test
    @DisplayName("boundary: a blank index, a null mode or a null outcome are rejected")
    void invalidIdentityIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedIndexRebuildReport(" ", MedIndexRebuildMode.INDEX_ONLY,
                        Outcome.COMPLETED, 0, 0, 0, 0, false, 0, "ok"))
                .withMessageContaining("indexName");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedIndexRebuildReport("med-doc-index", null,
                        Outcome.COMPLETED, 0, 0, 0, 0, false, 0, "ok"))
                .withMessageContaining("mode");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedIndexRebuildReport("med-doc-index", MedIndexRebuildMode.INDEX_ONLY,
                        null, 0, 0, 0, 0, false, 0, "ok"))
                .withMessageContaining("outcome");
    }
}
