package com.med.qa.rag;

import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The measured outcome of one {@link MedRetrievalBaselineCase} against what a retrieval actually
 * returned.
 *
 * <h2>What is measured</h2>
 * <p>Only the identifiers of the returned documents are used. No score, no distance and no text is
 * inspected, because the retrieval's ranking is produced by the official {@code RedisVectorStore}
 * and is not something this project re-derives. From the ordered identifier list the result computes
 * four numbers an operator can act on:</p>
 * <ul>
 *   <li>{@link #recall()} — fraction of the expected documents that came back;</li>
 *   <li>{@link #firstHitRank()} — 1-based rank of the first expected document, {@code 0} when none
 *       came back;</li>
 *   <li>{@link #reciprocalRank()} — {@code 1 / firstHitRank}, the per-case term of a mean reciprocal
 *       rank;</li>
 *   <li>{@link #forbiddenHitDocumentIds()} — documents that must never have been returned.</li>
 * </ul>
 *
 * <h2>Why a forbidden hit is not just a recall miss</h2>
 * <p>Returning too little and returning something out of scope are different failures with different
 * severities. The first degrades an answer; the second is a data-isolation breach, which is why it is
 * reported separately and can never be compensated by a good recall.</p>
 *
 * <h2>A negative case and recall</h2>
 * <p>When the case expects nothing, recall is defined as {@code 1.0}: there is nothing to find, so
 * the only thing that can go wrong is a forbidden document coming back. {@link #hasExpectations()}
 * lets an aggregate leave such a case out of its mean reciprocal rank instead of counting a
 * meaningless zero.</p>
 *
 * <p>Instances are immutable and safe to share.</p>
 */
public record MedRetrievalBaselineCaseResult(
        String caseName,
        List<String> retrievedDocumentIds,
        List<String> hitDocumentIds,
        List<String> missingDocumentIds,
        List<String> forbiddenHitDocumentIds,
        @Nullable String topDocumentId,
        int firstHitRank,
        double recall,
        double reciprocalRank,
        boolean passed,
        List<String> failureReasons) {

    /**
     * Normalises a case result.
     *
     * @throws IllegalArgumentException if the case name is blank or a list argument is {@code null}
     */
    public MedRetrievalBaselineCaseResult {
        if (caseName == null || caseName.isBlank()) {
            throw new IllegalArgumentException("case name must not be blank");
        }
        retrievedDocumentIds = copy(retrievedDocumentIds, "retrievedDocumentIds");
        hitDocumentIds = copy(hitDocumentIds, "hitDocumentIds");
        missingDocumentIds = copy(missingDocumentIds, "missingDocumentIds");
        forbiddenHitDocumentIds = copy(forbiddenHitDocumentIds, "forbiddenHitDocumentIds");
        failureReasons = copy(failureReasons, "failureReasons");
    }

    /**
     * Compares a case with the documents a retrieval returned.
     *
     * <p>The order of {@code retrievedDocumentIds} is the order the vector store ranked them in and is
     * significant: it is what makes {@link #firstHitRank()} and {@link #topDocumentId()} meaningful.</p>
     *
     * @param baselineCase          the case to check, must not be {@code null}
     * @param retrievedDocumentIds  identifiers returned by the retrieval, best first; may be
     *                              {@code null}, which is treated as "nothing came back"
     * @return the measured outcome, never {@code null}
     * @throws IllegalArgumentException if {@code baselineCase} is {@code null} or the identifier list
     *                                  holds a {@code null} or blank entry
     */
    public static MedRetrievalBaselineCaseResult of(MedRetrievalBaselineCase baselineCase,
                                                    @Nullable List<String> retrievedDocumentIds) {
        if (baselineCase == null) {
            throw new IllegalArgumentException("baselineCase must not be null");
        }
        List<String> retrieved = new ArrayList<>();
        if (retrievedDocumentIds != null) {
            for (String identifier : retrievedDocumentIds) {
                if (identifier == null || identifier.isBlank()) {
                    throw new IllegalArgumentException("retrieval returned a blank document identifier "
                            + "for baseline case '" + baselineCase.name() + '\'');
                }
                retrieved.add(identifier);
            }
        }

        Set<String> expected = new LinkedHashSet<>(baselineCase.expectedDocumentIds());
        Set<String> forbidden = new LinkedHashSet<>(baselineCase.forbiddenDocumentIds());
        Set<String> hits = new LinkedHashSet<>();
        Set<String> forbiddenHits = new LinkedHashSet<>();
        int firstHitRank = 0;
        for (int index = 0; index < retrieved.size(); index++) {
            String identifier = retrieved.get(index);
            if (expected.contains(identifier)) {
                hits.add(identifier);
                if (firstHitRank == 0) {
                    firstHitRank = index + 1;
                }
            }
            if (forbidden.contains(identifier)) {
                forbiddenHits.add(identifier);
            }
        }
        List<String> missing = baselineCase.expectedDocumentIds().stream()
                .filter(identifier -> !hits.contains(identifier))
                .toList();

        double recall = expected.isEmpty() ? 1.0d : (double) hits.size() / expected.size();
        double reciprocalRank = firstHitRank == 0 ? 0.0d : 1.0d / firstHitRank;
        String topDocumentId = retrieved.isEmpty() ? null : retrieved.get(0);

        List<String> reasons = new ArrayList<>(3);
        if (recall < baselineCase.minRecall()) {
            reasons.add("recall " + recall + " is below the required " + baselineCase.minRecall()
                    + "; missing " + missing);
        }
        if (!forbiddenHits.isEmpty()) {
            reasons.add("out-of-scope documents were returned: " + forbiddenHits);
        }
        if (baselineCase.expectedTopDocumentId() != null
                && !baselineCase.expectedTopDocumentId().equals(topDocumentId)) {
            reasons.add("the best-ranked document is '" + topDocumentId + "' but '"
                    + baselineCase.expectedTopDocumentId() + "' was expected");
        }

        return new MedRetrievalBaselineCaseResult(baselineCase.name(), retrieved, List.copyOf(hits),
                missing, List.copyOf(forbiddenHits), topDocumentId, firstHitRank, recall,
                reciprocalRank, reasons.isEmpty(), List.copyOf(reasons));
    }

    /**
     * Tells whether the case required anything to be returned.
     *
     * <p>Reconstructed from the result alone: the expected set is exactly the hits plus the misses.</p>
     *
     * @return {@code true} when at least one document was expected
     */
    public boolean hasExpectations() {
        return !hitDocumentIds.isEmpty() || !missingDocumentIds.isEmpty();
    }

    /**
     * Returns the number of documents that were expected.
     *
     * @return the size of the expected set, {@code 0} for a negative case
     */
    public int expectedCount() {
        return hitDocumentIds.size() + missingDocumentIds.size();
    }

    /**
     * Renders the outcome without any document text.
     *
     * @return a description carrying identifiers and metrics only, never {@code null}
     */
    @Override
    public String toString() {
        return "MedRetrievalBaselineCaseResult{case='" + caseName + '\''
                + ", retrieved=" + retrievedDocumentIds.size()
                + ", hits=" + hitDocumentIds.size()
                + ", missing=" + missingDocumentIds
                + ", forbiddenHits=" + forbiddenHitDocumentIds
                + ", top='" + topDocumentId + '\''
                + ", firstHitRank=" + firstHitRank
                + ", recall=" + recall
                + ", reciprocalRank=" + reciprocalRank
                + ", passed=" + passed
                + ", reasons=" + failureReasons + '}';
    }

    /**
     * Copies a list of strings, rejecting {@code null} entries.
     *
     * @param values values to copy, must not be {@code null}
     * @param field  field name used in error messages
     * @return a fresh immutable list, never {@code null}
     * @throws IllegalArgumentException if the list is {@code null} or holds a {@code null} entry
     */
    private static List<String> copy(List<String> values, String field) {
        if (values == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        for (String value : values) {
            if (value == null) {
                throw new IllegalArgumentException(field + " must not contain null entries");
            }
        }
        return List.copyOf(values);
    }
}
