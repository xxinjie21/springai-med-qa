package com.med.qa.rag;

import com.med.qa.rag.MedIndexRebuildProgress.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests for {@link MedIndexRebuildProgress}.
 *
 * <p>A progress object is only useful if it cannot lie: a snapshot that reports more documents done
 * than planned, or an update that predates the start, would make the one signal an operator has
 * during a long rebuild untrustworthy. The compact constructor therefore rejects both, and these
 * tests pin that.</p>
 */
class MedIndexRebuildProgressTest {

    private static final long START = 1_700_000_000_000L;

    @Test
    @DisplayName("a pending snapshot starts at zero progress and names its stage")
    void pendingStartsAtZero() {
        MedIndexRebuildProgress progress = MedIndexRebuildProgress.pending("med-doc-index", 10, 2, START);

        assertThat(progress.indexName()).isEqualTo("med-doc-index");
        assertThat(progress.stage()).isEqualTo(Stage.PENDING);
        assertThat(progress.documentsProcessed()).isZero();
        assertThat(progress.batchesTotal()).isEqualTo(2);
        assertThat(progress.batchesCompleted()).isZero();
        assertThat(progress.percentComplete()).isZero();
        assertThat(progress.startedAtEpochMillis()).isEqualTo(START);
    }

    @Test
    @DisplayName("batches are derived from the corpus size and the configured batch size")
    void batchesAreDerivedFromTheCorpus() {
        assertThat(MedIndexRebuildProgress.batchesOf(0, 50)).isZero();
        assertThat(MedIndexRebuildProgress.batchesOf(1, 50)).isEqualTo(1);
        assertThat(MedIndexRebuildProgress.batchesOf(50, 50)).isEqualTo(1);
        assertThat(MedIndexRebuildProgress.batchesOf(51, 50)).isEqualTo(2);
        assertThat(MedIndexRebuildProgress.batchesOf(5, 2)).isEqualTo(3);
    }

    @Test
    @DisplayName("a rebuild with nothing to write back reports itself complete")
    void emptyCorpusIsComplete() {
        MedIndexRebuildProgress progress = MedIndexRebuildProgress.pending("med-doc-index", 0, 0, START);

        assertThat(progress.percentComplete()).isEqualTo(100);
    }

    @Test
    @DisplayName("advancing by a batch moves both counters and keeps the stage")
    void advanceMovesTheCounters() {
        MedIndexRebuildProgress progress = MedIndexRebuildProgress
                .pending("med-doc-index", 5, 3, START)
                .withStage(Stage.REINGESTING, START + 10);

        MedIndexRebuildProgress afterFirstBatch = progress.advance(2, START + 20);

        assertThat(afterFirstBatch.documentsProcessed()).isEqualTo(2);
        assertThat(afterFirstBatch.batchesCompleted()).isEqualTo(1);
        assertThat(afterFirstBatch.stage()).isEqualTo(Stage.REINGESTING);
        assertThat(afterFirstBatch.percentComplete()).isEqualTo(40);
        assertThat(afterFirstBatch.updatedAtEpochMillis()).isEqualTo(START + 20);
        assertThat(afterFirstBatch.startedAtEpochMillis()).isEqualTo(START);
    }

    @Test
    @DisplayName("the stage decides whether a rebuild is still running")
    void terminalStagesEndTheRebuild() {
        assertThat(Stage.PENDING.isTerminal()).isFalse();
        assertThat(Stage.DROPPING.isTerminal()).isFalse();
        assertThat(Stage.REINGESTING.isTerminal()).isFalse();
        assertThat(Stage.VERIFYING.isTerminal()).isFalse();
        assertThat(Stage.COMPLETED.isTerminal()).isTrue();
        assertThat(Stage.FAILED.isTerminal()).isTrue();
        assertThat(Stage.SKIPPED.isTerminal()).isTrue();
        assertThat(Stage.DROPPING.label()).isEqualTo("dropping");
    }

    @Test
    @DisplayName("boundary: a snapshot claiming more work done than planned is rejected")
    void impossibleProgressIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedIndexRebuildProgress("med-doc-index", Stage.REINGESTING,
                        5, 6, 1, 0, START, START))
                .withMessageContaining("documentsProcessed");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedIndexRebuildProgress("med-doc-index", Stage.REINGESTING,
                        5, 1, 1, 2, START, START))
                .withMessageContaining("batchesCompleted");
    }

    @Test
    @DisplayName("boundary: a blank index, a null stage, negative counters or a time travel are rejected")
    void invalidSnapshotsAreRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedIndexRebuildProgress(" ", Stage.PENDING, 0, 0, 0, 0, START, START))
                .withMessageContaining("indexName");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedIndexRebuildProgress("med-doc-index", null, 0, 0, 0, 0,
                        START, START))
                .withMessageContaining("stage");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedIndexRebuildProgress("med-doc-index", Stage.PENDING, -1, 0,
                        0, 0, START, START))
                .withMessageContaining("negative");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedIndexRebuildProgress("med-doc-index", Stage.PENDING, 0, 0,
                        0, 0, START, START - 1))
                .withMessageContaining("predate");
    }

    @Test
    @DisplayName("boundary: deriving batches from a negative corpus or a zero batch size is rejected")
    void invalidBatchInputsAreRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MedIndexRebuildProgress.batchesOf(-1, 10))
                .withMessageContaining("documentsTotal");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MedIndexRebuildProgress.batchesOf(10, 0))
                .withMessageContaining("batchSize");
    }

    @Test
    @DisplayName("the rendering carries counts and stage only, never document content")
    void toStringIsSafeToLog() {
        MedIndexRebuildProgress progress = MedIndexRebuildProgress
                .pending("med-doc-index", 4, 2, START)
                .withStage(Stage.REINGESTING, START)
                .advance(2, START + 5);

        assertThat(progress.toString())
                .contains("stage=re-ingesting")
                .contains("documents=2/4")
                .contains("batches=1/2")
                .contains("percent=50%");
    }
}
