package com.med.qa.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests for {@link MedRetrievalBaselineReport}.
 *
 * <p>The report is what a CI job prints and what an operator reads, so both of its readings are
 * pinned here: the all-or-nothing verdict, and the two aggregate metrics that are supposed to show a
 * slide <em>before</em> a case threshold is crossed. The metric definitions matter — a negative case
 * contributes to mean recall but must not drag the mean reciprocal rank down, because it has no rank
 * to be reciprocal to.</p>
 */
class MedRetrievalBaselineReportTest {

    private static MedDocumentScope scope() {
        return MedDocumentScope.ofPatient("tenant-a", "dept-cardio", "patient-1");
    }

    private static MedRetrievalBaselineCaseResult result(String name, List<String> expected,
                                                         String expectedTop, List<String> forbidden,
                                                         double minRecall, List<String> retrieved) {
        return MedRetrievalBaselineCaseResult.of(
                new MedRetrievalBaselineCase(name, scope(), "高血压 首选 药物", 10, true, expected,
                        expectedTop, forbidden, minRecall),
                retrieved);
    }

    @Test
    @DisplayName("the verdict is all-or-nothing and the counts add up")
    void verdictIsAllOrNothing() {
        MedRetrievalBaselineReport passing = new MedRetrievalBaselineReport("baseline", "1",
                List.of(result("a", List.of("doc-a"), "doc-a", List.of(), 1.0d, List.of("doc-a")),
                        result("b", List.of("doc-b"), null, List.of(), 1.0d, List.of("doc-b"))));
        assertThat(passing.passed()).isTrue();
        assertThat(passing.caseCount()).isEqualTo(2);
        assertThat(passing.passedCount()).isEqualTo(2);
        assertThat(passing.failedCount()).isZero();
        assertThat(passing.failedCases()).isEmpty();

        MedRetrievalBaselineReport mixed = new MedRetrievalBaselineReport("baseline", "1",
                List.of(result("a", List.of("doc-a"), "doc-a", List.of(), 1.0d, List.of("doc-a")),
                        result("b", List.of("doc-b"), null, List.of(), 1.0d, List.of())));
        assertThat(mixed.passed()).isFalse();
        assertThat(mixed.passedCount()).isEqualTo(1);
        assertThat(mixed.failedCount()).isEqualTo(1);
        assertThat(mixed.failedCases()).extracting(MedRetrievalBaselineCaseResult::caseName)
                .containsExactly("b");
    }

    @Test
    @DisplayName("mean recall averages every case, while mean reciprocal rank ignores the negative ones")
    void aggregatesUseTheDocumentedDenominators() {
        MedRetrievalBaselineReport report = new MedRetrievalBaselineReport("baseline", "1",
                List.of(result("perfect", List.of("doc-a"), "doc-a", List.of(), 1.0d, List.of("doc-a")),
                        result("half", List.of("doc-a", "doc-b"), null, List.of(), 0.5d, List.of("doc-a")),
                        result("negative", List.of(), null, List.of("doc-leak"), 0.0d, List.of())));

        // 1.0, 0.5 and the vacuous 1.0 of the negative case
        assertThat(report.meanRecall()).isCloseTo(2.5d / 3.0d,
                org.assertj.core.data.Offset.offset(1e-9));
        // only the two cases that expected something contribute a reciprocal rank
        assertThat(report.meanReciprocalRank()).isCloseTo(1.0d,
                org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("a baseline made only of negative cases has no ranking metric to report")
    void meanReciprocalRankIsZeroWithoutRankedCases() {
        MedRetrievalBaselineReport report = new MedRetrievalBaselineReport("baseline", "1",
                List.of(result("negative", List.of(), null, List.of("doc-leak"), 0.0d, List.of())));

        assertThat(report.meanReciprocalRank()).isZero();
        assertThat(report.meanRecall()).isEqualTo(1.0d);
        assertThat(report.passed()).isTrue();
    }

    @Test
    @DisplayName("the failure summary names every failing case and its reasons, and confirms a pass")
    void failureSummaryIsTheWholeDiagnosis() {
        MedRetrievalBaselineReport failing = new MedRetrievalBaselineReport("baseline", "2",
                List.of(result("leaky", List.of("doc-a"), "doc-a", List.of("doc-leak"), 1.0d,
                                List.of("doc-a", "doc-leak")),
                        result("empty", List.of("doc-b"), null, List.of(), 1.0d, List.of())));
        assertThat(failing.failureSummary())
                .contains("baseline 'baseline' v2 failed: 2/2")
                .contains("- leaky: ")
                .contains("out-of-scope documents were returned: [doc-leak]")
                .contains("- empty: ")
                .contains("recall 0.0");

        MedRetrievalBaselineReport passing = new MedRetrievalBaselineReport("baseline", "2",
                List.of(result("fine", List.of("doc-a"), "doc-a", List.of(), 1.0d, List.of("doc-a"))));
        assertThat(passing.failureSummary())
                .contains("baseline 'baseline' v2 passed: 1 case(s)")
                .doesNotContain("failed");
    }

    @Test
    @DisplayName("boundary: a blank identity or an empty result list is refused")
    void invalidReportsAreRefused() {
        MedRetrievalBaselineCaseResult single =
                result("a", List.of("doc-a"), "doc-a", List.of(), 1.0d, List.of("doc-a"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedRetrievalBaselineReport(" ", "1", List.of(single)))
                .withMessageContaining("baseline name must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedRetrievalBaselineReport("baseline", " ", List.of(single)))
                .withMessageContaining("baseline version must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedRetrievalBaselineReport("baseline", "1", null))
                .withMessageContaining("caseResults must not be null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedRetrievalBaselineReport("baseline", "1", List.of()))
                .withMessageContaining("caseResults must not be empty");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedRetrievalBaselineReport("baseline", "1",
                        Arrays.asList(single, null)))
                .withMessageContaining("must not contain null entries");
    }

    @Test
    @DisplayName("the rendering summarises the verdict without any question or document text")
    void renderingIsASummary() {
        MedRetrievalBaselineReport report = new MedRetrievalBaselineReport("baseline", "3",
                List.of(result("leaky", List.of("doc-a"), "doc-a", List.of("doc-leak"), 1.0d,
                        List.of("doc-a", "doc-leak"))));

        assertThat(report.toString())
                .startsWith("MedRetrievalBaselineReport{")
                .contains("baseline='baseline'")
                .contains("version='3'")
                .contains("cases=1")
                .contains("failed=1")
                .contains("failedCases=[leaky]")
                .doesNotContain("高血压");
    }
}
