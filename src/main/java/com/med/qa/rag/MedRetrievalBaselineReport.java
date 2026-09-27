package com.med.qa.rag;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The outcome of evaluating a whole {@link MedRetrievalBaseline}: every case result plus the
 * aggregates a CI job or an operator needs in order to decide whether retrieval quality regressed.
 *
 * <h2>Two ways to read it</h2>
 * <ul>
 *   <li>{@link #passed()} is the single boolean an assertion should use — a baseline passes only when
 *       every case passes, because a set of frozen expectations is all-or-nothing by nature.</li>
 *   <li>{@link #meanRecall()} and {@link #meanReciprocalRank()} are the trend numbers. They exist so a
 *       drop can be seen <em>before</em> it crosses a case threshold: recall sliding from {@code 1.0}
 *       to {@code 0.95} while still meeting {@code minRecall} is the early warning that a filter or an
 *       index change is eating evidence.</li>
 * </ul>
 *
 * <h2>What the report never contains</h2>
 * <p>No question text and no document text — only case names, document identifiers and metrics. A
 * report is written to build logs and to alert payloads, and a golden set may well be pointed at real
 * consultation questions; {@link #failureSummary()} is therefore safe to print verbatim.</p>
 *
 * <p>Instances are immutable and safe to share.</p>
 */
public record MedRetrievalBaselineReport(
        String baselineName,
        String baselineVersion,
        List<MedRetrievalBaselineCaseResult> caseResults) {

    /**
     * Normalises a report.
     *
     * @throws IllegalArgumentException if the baseline name or version is blank, or the result list is
     *                                  {@code null}, empty or holds a {@code null} entry
     */
    public MedRetrievalBaselineReport {
        if (baselineName == null || baselineName.isBlank()) {
            throw new IllegalArgumentException("baseline name must not be blank");
        }
        if (baselineVersion == null || baselineVersion.isBlank()) {
            throw new IllegalArgumentException("baseline version must not be blank");
        }
        if (caseResults == null) {
            throw new IllegalArgumentException("caseResults must not be null");
        }
        if (caseResults.isEmpty()) {
            throw new IllegalArgumentException("caseResults must not be empty");
        }
        for (MedRetrievalBaselineCaseResult result : caseResults) {
            if (result == null) {
                throw new IllegalArgumentException("caseResults must not contain null entries");
            }
        }
        caseResults = List.copyOf(caseResults);
    }

    /**
     * Returns the number of evaluated cases.
     *
     * @return the size of {@link #caseResults()}, always {@code >= 1}
     */
    public int caseCount() {
        return caseResults.size();
    }

    /**
     * Returns the number of cases that met every expectation.
     *
     * @return the number of passing cases, between {@code 0} and {@link #caseCount()}
     */
    public int passedCount() {
        return (int) caseResults.stream().filter(MedRetrievalBaselineCaseResult::passed).count();
    }

    /**
     * Returns the number of cases that did not meet their expectations.
     *
     * @return the number of failing cases, between {@code 0} and {@link #caseCount()}
     */
    public int failedCount() {
        return caseCount() - passedCount();
    }

    /**
     * Tells whether the whole baseline passed.
     *
     * @return {@code true} only when every case passed
     */
    public boolean passed() {
        return failedCount() == 0;
    }

    /**
     * Returns the failing cases, in declaration order.
     *
     * @return a fresh immutable list of the cases that did not pass, never {@code null}; empty when
     *         {@link #passed()} is {@code true}
     */
    public List<MedRetrievalBaselineCaseResult> failedCases() {
        return caseResults.stream().filter(result -> !result.passed()).toList();
    }

    /**
     * Returns the mean recall over every case, including the negative ones.
     *
     * <p>A negative case contributes {@code 1.0} because there is nothing to find; leaving it out
     * would make the metric depend on how many negative cases the set happens to hold.</p>
     *
     * @return the arithmetic mean of {@link MedRetrievalBaselineCaseResult#recall()}, within
     *         {@code [0, 1]}
     */
    public double meanRecall() {
        return caseResults.stream()
                .mapToDouble(MedRetrievalBaselineCaseResult::recall)
                .average()
                .orElse(0.0d);
    }

    /**
     * Returns the mean reciprocal rank over the cases that expected something.
     *
     * <p>A negative case has no rank to be reciprocal to, so it is excluded rather than counted as a
     * zero — otherwise adding a leak check would appear to degrade ranking quality.</p>
     *
     * @return the arithmetic mean of {@link MedRetrievalBaselineCaseResult#reciprocalRank()} over the
     *         cases with expectations, within {@code [0, 1]}; {@code 0.0} when the set holds only
     *         negative cases
     */
    public double meanReciprocalRank() {
        return caseResults.stream()
                .filter(MedRetrievalBaselineCaseResult::hasExpectations)
                .mapToDouble(MedRetrievalBaselineCaseResult::reciprocalRank)
                .average()
                .orElse(0.0d);
    }

    /**
     * Renders the failing cases and their reasons as a multi-line text.
     *
     * <p>Built for assertion messages: when a CI job fails, this is the whole diagnosis — which cases
     * broke, what was missing, what leaked out of scope and what the ranking actually was.</p>
     *
     * @return the failure description, or a one-line confirmation when nothing failed; never
     *         {@code null}
     */
    public String failureSummary() {
        if (passed()) {
            return "baseline '" + baselineName + "' v" + baselineVersion + " passed: "
                    + caseCount() + " case(s), meanRecall=" + meanRecall()
                    + ", meanReciprocalRank=" + meanReciprocalRank();
        }
        return "baseline '" + baselineName + "' v" + baselineVersion + " failed: "
                + failedCount() + '/' + caseCount() + " case(s)" + System.lineSeparator()
                + failedCases().stream()
                .map(result -> "  - " + result.caseName() + ": "
                        + String.join("; ", result.failureReasons()))
                .collect(Collectors.joining(System.lineSeparator()));
    }

    /**
     * Renders the report without any question or document text.
     *
     * @return a description carrying the aggregates and the failing case names, never {@code null}
     */
    @Override
    public String toString() {
        return "MedRetrievalBaselineReport{baseline='" + baselineName + '\''
                + ", version='" + baselineVersion + '\''
                + ", cases=" + caseCount()
                + ", passed=" + passedCount()
                + ", failed=" + failedCount()
                + ", meanRecall=" + meanRecall()
                + ", meanReciprocalRank=" + meanReciprocalRank()
                + ", failedCases=" + failedCases().stream()
                .map(MedRetrievalBaselineCaseResult::caseName).toList() + '}';
    }
}
