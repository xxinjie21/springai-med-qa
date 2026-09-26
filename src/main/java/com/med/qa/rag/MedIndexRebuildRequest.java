package com.med.qa.rag;

import org.springframework.util.StringUtils;

import java.util.List;

/**
 * One rebuild instruction: which mode to run, for whom, and — for a destructive rebuild — the corpus
 * to write back.
 *
 * <p>The corpus is carried in the request rather than read from a repository because the service has
 * no corpus table: documents live only in the vector index, which is exactly the thing being
 * dropped. Whoever triggers a {@link MedIndexRebuildMode#DROP_AND_REINGEST} therefore owns the
 * source of truth and has to hand the documents over. A
 * {@link MedIndexRebuildMode#INDEX_ONLY} rebuild needs no documents at all — that is the whole
 * point of the safe mode: RediSearch re-indexes the existing keys when the index is recreated.</p>
 *
 * <p>Instances are immutable and safe to share.</p>
 *
 * @param mode        what the rebuild may touch, never {@code null}
 * @param documents   corpus to write back; may be empty for {@link MedIndexRebuildMode#INDEX_ONLY}
 *                    and must not contain {@code null} entries, never {@code null}
 * @param requestedBy identity of the operator or job that asked for the rebuild; carried into the
 *                    report and the alert so an incident review can tell who dropped the index,
 *                    never blank
 */
public record MedIndexRebuildRequest(MedIndexRebuildMode mode,
                                     List<MedDocumentRequest> documents,
                                     String requestedBy) {

    /**
     * Validates the instruction and takes an immutable copy of the corpus.
     *
     * @throws IllegalArgumentException if {@code mode} is {@code null}, {@code documents} is
     *                                  {@code null} or holds a {@code null} entry, or
     *                                  {@code requestedBy} is blank
     */
    public MedIndexRebuildRequest {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        if (documents == null) {
            throw new IllegalArgumentException("documents must not be null");
        }
        for (MedDocumentRequest document : documents) {
            if (document == null) {
                throw new IllegalArgumentException("documents must not contain null entries");
            }
        }
        if (!StringUtils.hasText(requestedBy)) {
            throw new IllegalArgumentException("requestedBy must not be blank");
        }
        documents = List.copyOf(documents);
    }

    /**
     * Builds the safe, non-destructive rebuild of a drifted or missing index.
     *
     * @param requestedBy identity of the requester, must not be blank
     * @return an {@link MedIndexRebuildMode#INDEX_ONLY} instruction, never {@code null}
     * @throws IllegalArgumentException if {@code requestedBy} is blank
     */
    public static MedIndexRebuildRequest indexOnly(String requestedBy) {
        return new MedIndexRebuildRequest(MedIndexRebuildMode.INDEX_ONLY, List.of(), requestedBy);
    }

    /**
     * Builds a destructive rebuild that drops the index with its documents and re-ingests a corpus.
     *
     * @param documents   corpus to write back, must not be {@code null} and must not be empty
     * @param requestedBy identity of the requester, must not be blank
     * @return a {@link MedIndexRebuildMode#DROP_AND_REINGEST} instruction, never {@code null}
     * @throws IllegalArgumentException if the corpus is {@code null}/empty, holds a {@code null}
     *                                  entry, or {@code requestedBy} is blank
     */
    public static MedIndexRebuildRequest dropAndReingest(List<MedDocumentRequest> documents,
                                                        String requestedBy) {
        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException(
                    "documents must not be empty: a destructive rebuild that writes nothing back "
                            + "would simply erase the corpus");
        }
        return new MedIndexRebuildRequest(MedIndexRebuildMode.DROP_AND_REINGEST, documents, requestedBy);
    }

    /**
     * Tells whether this instruction deletes the indexed documents.
     *
     * @return {@code true} for {@link MedIndexRebuildMode#DROP_AND_REINGEST}
     */
    public boolean deletesDocuments() {
        return mode.deletesDocuments();
    }

    /**
     * Returns how many documents the rebuild will write back.
     *
     * @return the corpus size, never negative
     */
    public int documentCount() {
        return documents.size();
    }

    @Override
    public String toString() {
        return "MedIndexRebuildRequest{mode=" + mode
                + ", documents=" + documents.size()
                + ", requestedBy='" + requestedBy + "'}";
    }
}
