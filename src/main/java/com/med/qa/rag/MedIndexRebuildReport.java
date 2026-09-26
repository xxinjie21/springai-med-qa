package com.med.qa.rag;

/**
 * Outcome of one RAG index rebuild.
 *
 * <p>The report is the contract of a rebuild: the operation is destructive, asynchronous in spirit
 * (it can run for minutes) and triggered by a human, so "did it work, and what did it do" has to be
 * answerable without reading logs. It therefore carries both the verdict
 * ({@link #outcome()}) and the evidence the verdict was based on — the document counts read from the
 * live index before and after, how many documents were written back and how long it took.</p>
 *
 * <p>No document text, identifier or query ever enters a report: counts, stage names and a
 * human-readable message only, so a report can be logged and alerted on without touching the
 * patient-data boundary.</p>
 *
 * @param indexName        index the rebuild targeted, never blank
 * @param mode             what the rebuild was allowed to touch, never {@code null}
 * @param outcome          the verdict, never {@code null}
 * @param documentsBefore  document count the probe read before the rebuild; {@code 0} when the
 *                         rebuild was refused or skipped before probing
 * @param documentsAfter   document count the probe read after the rebuild; {@code 0} when the
 *                         rebuild did not reach the verification step
 * @param documentsIngested documents written back through the ingestion path
 * @param batchesCompleted ingestion batches completed
 * @param indexDropped     {@code true} when an existing index was actually dropped
 * @param durationMillis   wall-clock duration of the rebuild
 * @param message          one-line operator-facing description of what happened, never blank
 */
public record MedIndexRebuildReport(String indexName,
                                    MedIndexRebuildMode mode,
                                    Outcome outcome,
                                    long documentsBefore,
                                    long documentsAfter,
                                    int documentsIngested,
                                    int batchesCompleted,
                                    boolean indexDropped,
                                    long durationMillis,
                                    String message) {

    /** Verdict of a rebuild. */
    public enum Outcome {

        /** The index was rebuilt and the probe confirms scoped retrieval is ready. */
        COMPLETED("completed"),

        /** The rebuild ran to the end but the index is still not usable — it is not a success. */
        VERIFICATION_FAILED("verification-failed"),

        /** A step threw; the index may be missing, empty or half built and needs a human. */
        FAILED("failed"),

        /** Another rebuild holds the cluster-wide mutex, so this one did nothing. */
        SKIPPED_LOCK_HELD("skipped-lock-held"),

        /** The deployment does not authorise the requested mode; Redis was never contacted. */
        REFUSED("refused");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }

        /**
         * Returns the operator-facing name of the outcome.
         *
         * @return a lower-case label, never blank
         */
        public String label() {
            return label;
        }

        /**
         * Tells whether the rebuild produced a usable index.
         *
         * @return {@code true} only for {@link #COMPLETED}
         */
        public boolean isSuccess() {
            return this == COMPLETED;
        }
    }

    /**
     * Validates the fields.
     *
     * @throws IllegalArgumentException if {@code indexName} or {@code message} is blank, a mode or
     *                                  outcome is {@code null}, a count is negative, or the duration
     *                                  is negative
     */
    public MedIndexRebuildReport {
        if (indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("indexName must not be blank");
        }
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
        if (documentsBefore < 0 || documentsAfter < 0 || documentsIngested < 0 || batchesCompleted < 0) {
            throw new IllegalArgumentException("counters must not be negative");
        }
        if (durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis must not be negative");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }

    /**
     * Tells whether the rebuild produced a usable index.
     *
     * @return {@code true} when {@link #outcome()} is {@link Outcome#COMPLETED}
     */
    public boolean isSuccess() {
        return outcome.isSuccess();
    }

    /**
     * Tells whether the index ended up holding a different number of documents than it started with.
     *
     * <p>A destructive rebuild is expected to change the count; a non-destructive one that loses
     * documents is the signal an operator needs, because it means the recreated index did not pick
     * up the existing keys.</p>
     *
     * @return {@code true} when the document count changed
     */
    public boolean documentCountChanged() {
        return documentsBefore != documentsAfter;
    }

    /**
     * Renders the report as one line, for a log entry or an alert message.
     *
     * @return a single-line description, never blank
     */
    public String describe() {
        return "index=" + indexName
                + ", mode=" + mode
                + ", outcome=" + outcome.label()
                + ", documents=" + documentsBefore + "->" + documentsAfter
                + ", ingested=" + documentsIngested
                + ", batches=" + batchesCompleted
                + ", dropped=" + indexDropped
                + ", durationMillis=" + durationMillis
                + ", message=" + message;
    }

    @Override
    public String toString() {
        return "MedIndexRebuildReport{" + describe() + '}';
    }
}
