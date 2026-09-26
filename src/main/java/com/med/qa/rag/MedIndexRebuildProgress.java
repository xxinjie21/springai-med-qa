package com.med.qa.rag;

/**
 * Snapshot of an in-flight (or finished) index rebuild, for progress reporting.
 *
 * <p>A rebuild of a real corpus takes minutes: the index is dropped, recreated and then every
 * document is re-embedded. Without a readable progress object the only two observable states are
 * "nothing happened" and "the report appeared", and an operator watching a rebuild that is stuck on
 * a slow embedding gateway cannot tell it apart from one that has already died. The rebuilder
 * therefore publishes a new snapshot at every stage transition and after every ingestion batch, and
 * exposes the latest one through {@code currentProgress()}.</p>
 *
 * <p>Only counts and stage names are carried: no document text, no identifiers, no query. A progress
 * snapshot is safe to log.</p>
 *
 * @param indexName            index being rebuilt, never blank
 * @param stage                stage the rebuild is in, never {@code null}
 * @param documentsTotal       documents the request asked to write back; {@code 0} for a rebuild
 *                             that keeps the existing documents
 * @param documentsProcessed   documents written back so far, never negative and never greater than
 *                             {@code documentsTotal}
 * @param batchesTotal         number of ingestion batches the corpus was split into
 * @param batchesCompleted     batches written back so far
 * @param startedAtEpochMillis wall-clock start of the rebuild
 * @param updatedAtEpochMillis wall-clock instant of this snapshot
 */
public record MedIndexRebuildProgress(String indexName,
                                      Stage stage,
                                      int documentsTotal,
                                      int documentsProcessed,
                                      int batchesTotal,
                                      int batchesCompleted,
                                      long startedAtEpochMillis,
                                      long updatedAtEpochMillis) {

    /** Stage of a rebuild, in the order the orchestrator passes through them. */
    public enum Stage {

        /** The instruction was accepted and nothing has been touched yet. */
        PENDING("pending"),

        /** The existing index is being dropped. */
        DROPPING("dropping"),

        /** The index has been recreated; the corpus is being written back. */
        REINGESTING("re-ingesting"),

        /** Everything was written; the live index is being re-read to verify the outcome. */
        VERIFYING("verifying"),

        /** The rebuild finished and the index is usable. */
        COMPLETED("completed"),

        /** The rebuild ran but the index is not usable, or it threw. */
        FAILED("failed"),

        /** Nothing was done: the mutex was held, or the deployment refused the mode. */
        SKIPPED("skipped");

        private final String label;

        Stage(String label) {
            this.label = label;
        }

        /**
         * Returns the operator-facing name of the stage.
         *
         * @return a lower-case label, never blank
         */
        public String label() {
            return label;
        }

        /**
         * Tells whether the rebuild is over, whatever its outcome.
         *
         * @return {@code true} for {@link #COMPLETED}, {@link #FAILED} and {@link #SKIPPED}
         */
        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED || this == SKIPPED;
        }
    }

    /**
     * Validates the counters and the timestamps.
     *
     * @throws IllegalArgumentException if {@code indexName} is blank, {@code stage} is {@code null},
     *                                  a counter is negative, more documents/batches are reported
     *                                  done than planned, or the update predates the start
     */
    public MedIndexRebuildProgress {
        if (indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("indexName must not be blank");
        }
        if (stage == null) {
            throw new IllegalArgumentException("stage must not be null");
        }
        if (documentsTotal < 0 || documentsProcessed < 0) {
            throw new IllegalArgumentException("document counters must not be negative");
        }
        if (documentsProcessed > documentsTotal) {
            throw new IllegalArgumentException(
                    "documentsProcessed (" + documentsProcessed + ") must not exceed documentsTotal ("
                            + documentsTotal + ")");
        }
        if (batchesTotal < 0 || batchesCompleted < 0) {
            throw new IllegalArgumentException("batch counters must not be negative");
        }
        if (batchesCompleted > batchesTotal) {
            throw new IllegalArgumentException(
                    "batchesCompleted (" + batchesCompleted + ") must not exceed batchesTotal ("
                            + batchesTotal + ")");
        }
        if (updatedAtEpochMillis < startedAtEpochMillis) {
            throw new IllegalArgumentException(
                    "updatedAtEpochMillis must not predate startedAtEpochMillis");
        }
    }

    /**
     * Builds the snapshot of a rebuild that has been accepted but has not touched Redis yet.
     *
     * @param indexName            index to rebuild, must not be blank
     * @param documentsTotal       documents the request will write back
     * @param batchesTotal         batches the corpus was split into
     * @param startedAtEpochMillis wall-clock start
     * @return the initial snapshot, never {@code null}
     * @throws IllegalArgumentException if {@code indexName} is blank or a counter is negative
     */
    public static MedIndexRebuildProgress pending(String indexName, int documentsTotal,
                                                  int batchesTotal, long startedAtEpochMillis) {
        return new MedIndexRebuildProgress(indexName, Stage.PENDING, documentsTotal, 0,
                batchesTotal, 0, startedAtEpochMillis, startedAtEpochMillis);
    }

    /**
     * Derives the number of batches a corpus is split into.
     *
     * @param documentsTotal corpus size, must not be negative
     * @param batchSize      configured batch size, must be strictly positive
     * @return the batch count, {@code 0} for an empty corpus
     * @throws IllegalArgumentException if {@code documentsTotal} is negative or {@code batchSize} is
     *                                  not positive
     */
    public static int batchesOf(int documentsTotal, int batchSize) {
        if (documentsTotal < 0) {
            throw new IllegalArgumentException("documentsTotal must not be negative");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        return (documentsTotal + batchSize - 1) / batchSize;
    }

    /**
     * Returns the percentage of the corpus written back so far.
     *
     * @return a value in {@code [0, 100]}; {@code 100} for a rebuild with nothing to write back
     */
    public int percentComplete() {
        if (documentsTotal == 0) {
            return 100;
        }
        return (int) Math.min(100L, Math.round(documentsProcessed * 100.0 / documentsTotal));
    }

    /**
     * Returns the same snapshot moved to another stage, with a fresh update timestamp.
     *
     * @param nextStage     stage to move to, must not be {@code null}
     * @param updatedAt     wall-clock instant of the new snapshot
     * @return the new snapshot, never {@code null}
     * @throws IllegalArgumentException if {@code nextStage} is {@code null}
     */
    public MedIndexRebuildProgress withStage(Stage nextStage, long updatedAt) {
        return new MedIndexRebuildProgress(indexName, nextStage, documentsTotal, documentsProcessed,
                batchesTotal, batchesCompleted, startedAtEpochMillis, updatedAt);
    }

    /**
     * Returns the same snapshot with the corpus counters advanced by one batch.
     *
     * @param documentsInBatch documents the batch contained, must not be negative
     * @param updatedAt        wall-clock instant of the new snapshot
     * @return the new snapshot, never {@code null}
     * @throws IllegalArgumentException if the counters would exceed their totals
     */
    public MedIndexRebuildProgress advance(int documentsInBatch, long updatedAt) {
        return new MedIndexRebuildProgress(indexName, stage, documentsTotal,
                documentsProcessed + documentsInBatch, batchesTotal, batchesCompleted + 1,
                startedAtEpochMillis, updatedAt);
    }

    @Override
    public String toString() {
        return "MedIndexRebuildProgress{index='" + indexName
                + "', stage=" + stage.label()
                + ", documents=" + documentsProcessed + '/' + documentsTotal
                + ", batches=" + batchesCompleted + '/' + batchesTotal
                + ", percent=" + percentComplete() + '%'
                + ", elapsedMillis=" + (updatedAtEpochMillis - startedAtEpochMillis) + '}';
    }
}
