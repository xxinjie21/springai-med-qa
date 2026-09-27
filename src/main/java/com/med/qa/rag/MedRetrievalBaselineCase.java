package com.med.qa.rag;

import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One entry of the retrieval-quality regression baseline: a fixed question, the scope it is asked
 * in, and what a correct retrieval must return.
 *
 * <h2>What this is, and what it is not</h2>
 * <p>A baseline case is <strong>data</strong>, not an algorithm. It carries no expected score, no
 * expected ranking formula and no notion of relevance beyond "these identifiers must come back, in
 * this order of importance, and those must not come back at all". The ranking itself is still
 * produced by the official {@code RedisVectorStore} inside Redis; a case only states the outcome a
 * correct retrieval has to reach.</p>
 *
 * <h2>Why a fixed golden set exists</h2>
 * <p>The D36 tag-escaping defect and the D37/D38 index failures shared one property: the service
 * stayed healthy and simply returned <em>less</em>. Nothing in the offline suite noticed, because
 * every one of those tests asserted on a single component with a hand-picked fixture. A golden set
 * is the cheapest thing that turns "retrieval quality" into an assertion that can regress in CI:
 * the corpus and the questions are frozen, so any change to the index topology, the filter
 * expressions or the ingestion metadata that makes retrieval worse fails the build.</p>
 *
 * <h2>The three assertions a case can make</h2>
 * <ul>
 *   <li><b>Recall</b> — {@link #expectedDocumentIds()} is the set that must come back;
 *       {@link #minRecall()} is the fraction of it that has to be present.</li>
 *   <li><b>Ranking</b> — {@link #expectedTopDocumentId()} pins the best-ranked document, because a
 *       baseline that only checks presence would accept a retrieval that returns the right documents
 *       in a useless order.</li>
 *   <li><b>Isolation</b> — {@link #forbiddenDocumentIds()} are documents a correct scope must never
 *       surface. This is the assertion that catches the whole D36 family of defects, where the
 *       filter is accepted but does not do what it says.</li>
 * </ul>
 *
 * <h2>A negative case is legal and useful</h2>
 * <p>When {@link #expectedDocumentIds()} is empty the case asserts absence only: nothing may be
 * returned for that scope. Such a case has to declare {@code minRecall == 0} and at least one
 * forbidden identifier — otherwise it would assert nothing at all and pass no matter what the
 * retrieval does.</p>
 *
 * <h2>The question text is never rendered</h2>
 * <p>{@link #toString()} reports the length of {@link #query()}, not the text, mirroring
 * {@link MedRetrievalQuery}. A baseline can be pointed at real consultation questions, and a
 * question is patient data the moment it is written down; the case name is what identifies a case
 * in a report.</p>
 *
 * <p>Instances are immutable and safe to share.</p>
 */
public record MedRetrievalBaselineCase(
        String name,
        MedDocumentScope scope,
        String query,
        int topK,
        boolean includeSharedDocuments,
        List<String> expectedDocumentIds,
        @Nullable String expectedTopDocumentId,
        List<String> forbiddenDocumentIds,
        double minRecall) {

    /**
     * Validates and normalises a baseline case.
     *
     * @throws IllegalArgumentException if a name or the query is blank, the scope is {@code null},
     *                                  {@code topK} is not positive, a document identifier is blank
     *                                  or repeated, an identifier is both expected and forbidden, the
     *                                  expected top document is not part of the expected set, the
     *                                  recall requirement is outside {@code (0, 1]} for a case that
     *                                  expects something, or a case that expects nothing does not
     *                                  declare {@code minRecall == 0} together with at least one
     *                                  forbidden identifier
     */
    public MedRetrievalBaselineCase {
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("baseline case name must not be blank");
        }
        if (scope == null) {
            throw new IllegalArgumentException("baseline case scope must not be null");
        }
        if (!StringUtils.hasText(query)) {
            throw new IllegalArgumentException("baseline case query must not be blank");
        }
        if (topK < 1) {
            throw new IllegalArgumentException("baseline case topK must be positive but is " + topK);
        }
        if (!scope.isPatientScoped() && !includeSharedDocuments) {
            throw new IllegalArgumentException(
                    "a department-wide baseline case cannot exclude shared documents; it would match "
                            + "nothing");
        }
        expectedDocumentIds = copyIdentifiers(expectedDocumentIds, "expectedDocumentIds");
        forbiddenDocumentIds = copyIdentifiers(forbiddenDocumentIds, "forbiddenDocumentIds");
        Set<String> expected = new LinkedHashSet<>(expectedDocumentIds);
        for (String forbidden : forbiddenDocumentIds) {
            if (expected.contains(forbidden)) {
                throw new IllegalArgumentException("document '" + forbidden
                        + "' is both expected and forbidden in baseline case '" + name + '\'');
            }
        }
        if (expectedTopDocumentId != null) {
            if (!StringUtils.hasText(expectedTopDocumentId)) {
                throw new IllegalArgumentException(
                        "expectedTopDocumentId must be null or non-blank in baseline case '" + name + '\'');
            }
            if (!expected.contains(expectedTopDocumentId)) {
                throw new IllegalArgumentException("expectedTopDocumentId '" + expectedTopDocumentId
                        + "' is not part of expectedDocumentIds in baseline case '" + name + '\'');
            }
        }
        if (Double.isNaN(minRecall) || minRecall < 0.0d || minRecall > 1.0d) {
            throw new IllegalArgumentException(
                    "minRecall must be within [0, 1] but is " + minRecall);
        }
        if (expected.isEmpty()) {
            if (minRecall != 0.0d) {
                throw new IllegalArgumentException("baseline case '" + name
                        + "' expects no document, so minRecall must be 0.0 but is " + minRecall);
            }
            if (forbiddenDocumentIds.isEmpty()) {
                throw new IllegalArgumentException("baseline case '" + name
                        + "' expects nothing and forbids nothing, so it would assert nothing");
            }
        } else if (minRecall == 0.0d) {
            throw new IllegalArgumentException("baseline case '" + name
                    + "' expects " + expected.size() + " document(s), so minRecall must be above 0.0");
        }
    }

    /**
     * Returns the number of documents this case requires.
     *
     * @return the size of {@link #expectedDocumentIds()}, {@code 0} for a negative case
     */
    public int expectedCount() {
        return expectedDocumentIds.size();
    }

    /**
     * Tells whether the case asserts that something is returned.
     *
     * @return {@code false} for a negative case, which asserts absence only
     */
    public boolean hasExpectations() {
        return !expectedDocumentIds.isEmpty();
    }

    /**
     * Turns the case into the retrieval it describes.
     *
     * <p>The mapping is direct and total: the question is passed verbatim (never analysed), the
     * scope becomes the isolation tags, and the Top-K guard rail of
     * {@link MedRetrievalService} still applies on top of {@link #topK()}.</p>
     *
     * @return the query to hand to {@link MedRetrievalService}, never {@code null}
     */
    public MedRetrievalQuery toRetrievalQuery() {
        return MedRetrievalQuery.builder(query, scope)
                .topK(topK)
                .includeSharedDocuments(includeSharedDocuments)
                .build();
    }

    /**
     * Renders the case without its question text.
     *
     * @return a privacy-safe description, never {@code null}
     */
    @Override
    public String toString() {
        return "MedRetrievalBaselineCase{name='" + name + '\''
                + ", scope=" + scope
                + ", queryLength=" + query.length()
                + ", topK=" + topK
                + ", includeSharedDocuments=" + includeSharedDocuments
                + ", expected=" + expectedDocumentIds.size()
                + ", expectedTop='" + expectedTopDocumentId + '\''
                + ", forbidden=" + forbiddenDocumentIds.size()
                + ", minRecall=" + minRecall + '}';
    }

    /**
     * Copies and validates a list of document identifiers.
     *
     * @param identifiers identifiers to copy, must not be {@code null}
     * @param field       field name used in error messages
     * @return a fresh immutable list holding the identifiers in their original order
     * @throws IllegalArgumentException if the list is {@code null}, holds a blank or repeated entry
     */
    private static List<String> copyIdentifiers(List<String> identifiers, String field) {
        if (identifiers == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        List<String> copy = new ArrayList<>(identifiers.size());
        Set<String> seen = new LinkedHashSet<>();
        for (String identifier : identifiers) {
            if (!StringUtils.hasText(identifier)) {
                throw new IllegalArgumentException(field + " must not contain blank identifiers");
            }
            if (!seen.add(identifier)) {
                throw new IllegalArgumentException(field + " contains duplicate identifier: " + identifier);
            }
            copy.add(identifier);
        }
        return List.copyOf(copy);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MedRetrievalBaselineCase that)) {
            return false;
        }
        return topK == that.topK
                && includeSharedDocuments == that.includeSharedDocuments
                && Double.compare(minRecall, that.minRecall) == 0
                && name.equals(that.name)
                && scope.equals(that.scope)
                && query.equals(that.query)
                && expectedDocumentIds.equals(that.expectedDocumentIds)
                && Objects.equals(expectedTopDocumentId, that.expectedTopDocumentId)
                && forbiddenDocumentIds.equals(that.forbiddenDocumentIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, scope, query, topK, includeSharedDocuments, expectedDocumentIds,
                expectedTopDocumentId, forbiddenDocumentIds, minRecall);
    }
}
