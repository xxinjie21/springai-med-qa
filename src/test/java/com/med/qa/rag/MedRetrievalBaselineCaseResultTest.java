package com.med.qa.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests for {@link MedRetrievalBaselineCaseResult}, the arithmetic that decides whether a case
 * passed.
 *
 * <p>These are the assertions that make the golden set trustworthy: recall is a fraction of the
 * <em>expected</em> set rather than a count of returned documents, a leaked document fails the case no
 * matter how good the recall is, and a ranking expectation is checked separately from presence.
 * Getting any of them wrong would produce a baseline that is green while retrieval is broken.</p>
 */
class MedRetrievalBaselineCaseResultTest {

    private static MedDocumentScope scope() {
        return MedDocumentScope.ofPatient("tenant-a", "dept-cardio", "patient-1");
    }

    private static MedRetrievalBaselineCase caseWith(List<String> expected, String expectedTop,
                                                     List<String> forbidden, double minRecall) {
        return new MedRetrievalBaselineCase("case", scope(), "高血压 首选 药物", 10, true,
                expected, expectedTop, forbidden, minRecall);
    }

    @Test
    @DisplayName("a retrieval that returns every expected document in the right order passes")
    void perfectRetrievalPasses() {
        MedRetrievalBaselineCase baselineCase = caseWith(List.of("doc-guide", "doc-record"), "doc-guide",
                List.of("doc-other"), 1.0d);

        MedRetrievalBaselineCaseResult result =
                MedRetrievalBaselineCaseResult.of(baselineCase, List.of("doc-guide", "doc-record"));

        assertThat(result.passed()).isTrue();
        assertThat(result.failureReasons()).isEmpty();
        assertThat(result.recall()).isEqualTo(1.0d);
        assertThat(result.firstHitRank()).isEqualTo(1);
        assertThat(result.reciprocalRank()).isEqualTo(1.0d);
        assertThat(result.topDocumentId()).isEqualTo("doc-guide");
        assertThat(result.hitDocumentIds()).containsExactly("doc-guide", "doc-record");
        assertThat(result.missingDocumentIds()).isEmpty();
        assertThat(result.forbiddenHitDocumentIds()).isEmpty();
        assertThat(result.expectedCount()).isEqualTo(2);
        assertThat(result.hasExpectations()).isTrue();
    }

    @Test
    @DisplayName("a missing document fails the case when full recall is required, and names what is missing")
    void missingDocumentFailsFullRecall() {
        MedRetrievalBaselineCase baselineCase = caseWith(List.of("doc-guide", "doc-record"), null,
                List.of(), 1.0d);

        MedRetrievalBaselineCaseResult result =
                MedRetrievalBaselineCaseResult.of(baselineCase, List.of("doc-guide"));

        assertThat(result.passed()).isFalse();
        assertThat(result.recall()).isEqualTo(0.5d);
        assertThat(result.missingDocumentIds()).containsExactly("doc-record");
        assertThat(result.failureReasons()).hasSize(1);
        assertThat(result.failureReasons().get(0)).contains("recall 0.5").contains("doc-record");
    }

    @Test
    @DisplayName("a partial hit is accepted when the case only requires that fraction")
    void partialRecallIsAcceptedWhenRequired() {
        MedRetrievalBaselineCase baselineCase = caseWith(List.of("doc-guide", "doc-record"), null,
                List.of(), 0.5d);

        MedRetrievalBaselineCaseResult result =
                MedRetrievalBaselineCaseResult.of(baselineCase, List.of("doc-record"));

        assertThat(result.passed()).isTrue();
        assertThat(result.recall()).isEqualTo(0.5d);
        assertThat(result.firstHitRank()).isEqualTo(1);
    }

    @Test
    @DisplayName("a document returned out of scope fails the case even with perfect recall")
    void forbiddenHitFailsEvenWithPerfectRecall() {
        MedRetrievalBaselineCase baselineCase = caseWith(List.of("doc-guide"), "doc-guide",
                List.of("doc-other-patient"), 1.0d);

        MedRetrievalBaselineCaseResult result =
                MedRetrievalBaselineCaseResult.of(baselineCase, List.of("doc-guide", "doc-other-patient"));

        assertThat(result.passed()).isFalse();
        assertThat(result.recall()).isEqualTo(1.0d);
        assertThat(result.forbiddenHitDocumentIds()).containsExactly("doc-other-patient");
        assertThat(result.failureReasons())
                .anySatisfy(reason -> assertThat(reason).contains("out-of-scope"));
    }

    @Test
    @DisplayName("returning the wrong document first fails the ranking expectation")
    void wrongTopDocumentFails() {
        MedRetrievalBaselineCase baselineCase = caseWith(List.of("doc-guide", "doc-record"), "doc-guide",
                List.of(), 1.0d);

        MedRetrievalBaselineCaseResult result =
                MedRetrievalBaselineCaseResult.of(baselineCase, List.of("doc-record", "doc-guide"));

        assertThat(result.passed()).isFalse();
        assertThat(result.recall()).isEqualTo(1.0d);
        assertThat(result.topDocumentId()).isEqualTo("doc-record");
        assertThat(result.firstHitRank()).isEqualTo(1);
        assertThat(result.failureReasons())
                .anySatisfy(reason -> assertThat(reason).contains("best-ranked document is 'doc-record'"));
    }

    @Test
    @DisplayName("an empty retrieval fails every case that expected something, and a null list counts as empty")
    void emptyRetrievalFails() {
        MedRetrievalBaselineCase baselineCase = caseWith(List.of("doc-guide"), "doc-guide", List.of(), 1.0d);

        for (List<String> retrieved : Arrays.<List<String>>asList(List.of(), null)) {
            MedRetrievalBaselineCaseResult result =
                    MedRetrievalBaselineCaseResult.of(baselineCase, retrieved);

            assertThat(result.passed()).isFalse();
            assertThat(result.recall()).isZero();
            assertThat(result.firstHitRank()).isZero();
            assertThat(result.reciprocalRank()).isZero();
            assertThat(result.topDocumentId()).isNull();
            assertThat(result.retrievedDocumentIds()).isEmpty();
            assertThat(result.missingDocumentIds()).containsExactly("doc-guide");
        }
    }

    @Test
    @DisplayName("the rank is one-based, so a hit in third position scores a third")
    void rankIsOneBased() {
        MedRetrievalBaselineCase baselineCase = caseWith(List.of("doc-guide"), null, List.of(), 1.0d);

        MedRetrievalBaselineCaseResult result = MedRetrievalBaselineCaseResult.of(baselineCase,
                List.of("doc-a", "doc-b", "doc-guide"));

        assertThat(result.passed()).isTrue();
        assertThat(result.firstHitRank()).isEqualTo(3);
        assertThat(result.reciprocalRank()).isCloseTo(1.0d / 3.0d, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("a duplicated identifier cannot push recall above one")
    void duplicateIdentifiersDoNotInflateRecall() {
        MedRetrievalBaselineCase baselineCase = caseWith(List.of("doc-guide", "doc-record"), null,
                List.of(), 1.0d);

        MedRetrievalBaselineCaseResult result = MedRetrievalBaselineCaseResult.of(baselineCase,
                List.of("doc-guide", "doc-guide"));

        assertThat(result.recall()).isEqualTo(0.5d);
        assertThat(result.hitDocumentIds()).containsExactly("doc-guide");
        assertThat(result.missingDocumentIds()).containsExactly("doc-record");
    }

    @Test
    @DisplayName("a negative case passes when nothing comes back and fails on the first leak")
    void negativeCaseAssertsAbsenceOnly() {
        MedRetrievalBaselineCase baselineCase = caseWith(List.of(), null, List.of("doc-leak"), 0.0d);

        MedRetrievalBaselineCaseResult quiet = MedRetrievalBaselineCaseResult.of(baselineCase, List.of());
        assertThat(quiet.passed()).isTrue();
        assertThat(quiet.recall()).isEqualTo(1.0d);
        assertThat(quiet.hasExpectations()).isFalse();
        assertThat(quiet.expectedCount()).isZero();
        assertThat(quiet.firstHitRank()).isZero();

        MedRetrievalBaselineCaseResult leaking =
                MedRetrievalBaselineCaseResult.of(baselineCase, List.of("doc-leak"));
        assertThat(leaking.passed()).isFalse();
        assertThat(leaking.forbiddenHitDocumentIds()).containsExactly("doc-leak");
    }

    @Test
    @DisplayName("boundary: a null case or a blank returned identifier is refused")
    void invalidInputsAreRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MedRetrievalBaselineCaseResult.of(null, List.of("doc")))
                .withMessageContaining("baselineCase must not be null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MedRetrievalBaselineCaseResult.of(
                        caseWith(List.of("doc-guide"), null, List.of(), 1.0d),
                        Arrays.asList("doc-guide", " ")))
                .withMessageContaining("blank document identifier")
                .withMessageContaining("case");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MedRetrievalBaselineCaseResult.of(
                        caseWith(List.of("doc-guide"), null, List.of(), 1.0d),
                        Arrays.asList("doc-guide", null)))
                .withMessageContaining("blank document identifier");
    }

    @Test
    @DisplayName("the rendering carries identifiers and metrics, never document text")
    void renderingIsIdentifierOnly() {
        MedRetrievalBaselineCase baselineCase = caseWith(List.of("doc-guide"), "doc-guide",
                List.of("doc-leak"), 1.0d);

        String rendered = MedRetrievalBaselineCaseResult
                .of(baselineCase, List.of("doc-guide", "doc-leak")).toString();

        assertThat(rendered)
                .startsWith("MedRetrievalBaselineCaseResult{")
                .contains("case='case'")
                .contains("forbiddenHits=[doc-leak]")
                .contains("firstHitRank=1")
                .contains("passed=false");
    }
}
