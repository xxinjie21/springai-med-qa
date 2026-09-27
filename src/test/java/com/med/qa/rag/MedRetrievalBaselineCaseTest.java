package com.med.qa.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MedRetrievalBaselineCase}.
 *
 * <p>A baseline case is the contract the retrieval layer is measured against, so its validation is
 * part of the product: a case that is accepted but asserts nothing would turn the whole golden set
 * into a green build that proves nothing. Every rule is therefore pinned twice — once for the shape
 * that is legal, once for the shape that must be refused.</p>
 */
class MedRetrievalBaselineCaseTest {

    private static MedDocumentScope cardioPatientOne() {
        return MedDocumentScope.ofPatient("tenant-a", "dept-cardio", "patient-1");
    }

    private static MedRetrievalBaselineCase validCase() {
        return new MedRetrievalBaselineCase("own-record-and-guideline", cardioPatientOne(),
                "高血压 首选 药物", 10, true,
                List.of("doc-guide", "doc-record"), "doc-guide",
                List.of("doc-other-patient"), 1.0d);
    }

    @Test
    @DisplayName("a well-formed case exposes its expectations and the query it describes")
    void wellFormedCaseIsAccepted() {
        MedRetrievalBaselineCase baselineCase = validCase();

        assertThat(baselineCase.name()).isEqualTo("own-record-and-guideline");
        assertThat(baselineCase.scope()).isEqualTo(cardioPatientOne());
        assertThat(baselineCase.query()).isEqualTo("高血压 首选 药物");
        assertThat(baselineCase.topK()).isEqualTo(10);
        assertThat(baselineCase.includeSharedDocuments()).isTrue();
        assertThat(baselineCase.expectedDocumentIds()).containsExactly("doc-guide", "doc-record");
        assertThat(baselineCase.expectedTopDocumentId()).isEqualTo("doc-guide");
        assertThat(baselineCase.forbiddenDocumentIds()).containsExactly("doc-other-patient");
        assertThat(baselineCase.minRecall()).isEqualTo(1.0d);
        assertThat(baselineCase.expectedCount()).isEqualTo(2);
        assertThat(baselineCase.hasExpectations()).isTrue();
    }

    @Test
    @DisplayName("the case maps onto the retrieval query the production service accepts")
    void caseMapsOntoARetrievalQuery() {
        MedRetrievalQuery query = validCase().toRetrievalQuery();

        assertThat(query.getText()).isEqualTo("高血压 首选 药物");
        assertThat(query.getScope()).isEqualTo(cardioPatientOne());
        assertThat(query.getTopK()).isEqualTo(10);
        assertThat(query.isIncludeSharedDocuments()).isTrue();
        assertThat(query.getSimilarityThreshold()).isNull();
    }

    @Test
    @DisplayName("a department-wide case can only be declared with shared documents included")
    void departmentWideCaseRequiresSharedDocuments() {
        MedRetrievalBaselineCase departmentWide = new MedRetrievalBaselineCase("guidelines-only",
                MedDocumentScope.ofDepartment("tenant-a", "dept-cardio"), "糖尿病 指南", 5, true,
                List.of("doc-guide"), null, List.of(), 1.0d);
        assertThat(departmentWide.scope().isPatientScoped()).isFalse();
        assertThat(departmentWide.toRetrievalQuery().isIncludeSharedDocuments()).isTrue();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedRetrievalBaselineCase("guidelines-only",
                        MedDocumentScope.ofDepartment("tenant-a", "dept-cardio"), "糖尿病 指南", 5, false,
                        List.of("doc-guide"), null, List.of(), 1.0d))
                .withMessageContaining("would match nothing");
    }

    @Test
    @DisplayName("boundary: a blank name, query or an empty scope is refused")
    void blankFieldsAreRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedRetrievalBaselineCase(" ", cardioPatientOne(), "问诊", 5, true,
                        List.of("doc"), null, List.of(), 1.0d))
                .withMessageContaining("name must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedRetrievalBaselineCase("case", null, "问诊", 5, true,
                        List.of("doc"), null, List.of(), 1.0d))
                .withMessageContaining("scope must not be null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedRetrievalBaselineCase("case", cardioPatientOne(), "", 5, true,
                        List.of("doc"), null, List.of(), 1.0d))
                .withMessageContaining("query must not be blank");
    }

    @Test
    @DisplayName("boundary: a non-positive Top-K is refused, a Top-K of one is accepted")
    void topKIsValidated() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedRetrievalBaselineCase("case", cardioPatientOne(), "问诊", 0, true,
                        List.of("doc"), null, List.of(), 1.0d))
                .withMessageContaining("topK must be positive");
        assertThat(new MedRetrievalBaselineCase("case", cardioPatientOne(), "问诊", 1, true,
                List.of("doc"), null, List.of(), 1.0d).topK()).isEqualTo(1);
    }

    @Test
    @DisplayName("boundary: blank and duplicated document identifiers are refused")
    void identifierListsAreValidated() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedRetrievalBaselineCase("case", cardioPatientOne(), "问诊", 5, true,
                        List.of("doc", " "), null, List.of(), 1.0d))
                .withMessageContaining("must not contain blank identifiers");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedRetrievalBaselineCase("case", cardioPatientOne(), "问诊", 5, true,
                        List.of("doc", "doc"), null, List.of(), 1.0d))
                .withMessageContaining("duplicate identifier: doc");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedRetrievalBaselineCase("case", cardioPatientOne(), "问诊", 5, true,
                        List.of("doc"), null, null, 1.0d))
                .withMessageContaining("forbiddenDocumentIds must not be null");
    }

    @Test
    @DisplayName("a document cannot be expected and forbidden at the same time")
    void expectedAndForbiddenAreDisjoint() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedRetrievalBaselineCase("case", cardioPatientOne(), "问诊", 5, true,
                        List.of("doc"), null, List.of("doc"), 1.0d))
                .withMessageContaining("both expected and forbidden");
    }

    @Test
    @DisplayName("the expected top document has to be one of the expected documents")
    void expectedTopDocumentMustBeExpected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedRetrievalBaselineCase("case", cardioPatientOne(), "问诊", 5, true,
                        List.of("doc-a", "doc-b"), "doc-c", List.of(), 1.0d))
                .withMessageContaining("is not part of expectedDocumentIds");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedRetrievalBaselineCase("case", cardioPatientOne(), "问诊", 5, true,
                        List.of("doc-a"), " ", List.of(), 1.0d))
                .withMessageContaining("must be null or non-blank");
    }

    @Test
    @DisplayName("boundary: minRecall must stay inside the unit interval")
    void minRecallStaysInsideTheUnitInterval() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedRetrievalBaselineCase("case", cardioPatientOne(), "问诊", 5, true,
                        List.of("doc"), null, List.of(), 1.5d))
                .withMessageContaining("minRecall must be within [0, 1]");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedRetrievalBaselineCase("case", cardioPatientOne(), "问诊", 5, true,
                        List.of("doc"), null, List.of(), Double.NaN))
                .withMessageContaining("minRecall must be within [0, 1]");
    }

    @Test
    @DisplayName("a case that expects documents must require at least one of them")
    void aCaseThatExpectsDocumentsCannotAcceptZeroRecall() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedRetrievalBaselineCase("case", cardioPatientOne(), "问诊", 5, true,
                        List.of("doc"), null, List.of(), 0.0d))
                .withMessageContaining("so minRecall must be above 0.0");
    }

    @Test
    @DisplayName("a negative case asserts absence, so it must declare zero recall and forbid something")
    void negativeCaseIsAcceptedButCannotAssertNothing() {
        MedRetrievalBaselineCase negative = new MedRetrievalBaselineCase("unknown-scope",
                MedDocumentScope.ofPatient("tenant-a", "dept-oncology", "patient-1"), "高血压 首选 药物",
                10, true, List.of(), null, List.of("doc-guide"), 0.0d);
        assertThat(negative.expectedCount()).isZero();
        assertThat(negative.hasExpectations()).isFalse();
        assertThat(negative.toRetrievalQuery().getText()).isEqualTo("高血压 首选 药物");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedRetrievalBaselineCase("unknown-scope",
                        MedDocumentScope.ofPatient("tenant-a", "dept-oncology", "patient-1"), "问诊", 10,
                        true, List.of(), null, List.of("doc-guide"), 1.0d))
                .withMessageContaining("minRecall must be 0.0");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedRetrievalBaselineCase("unknown-scope",
                        MedDocumentScope.ofPatient("tenant-a", "dept-oncology", "patient-1"), "问诊", 10,
                        true, List.of(), null, List.of(), 0.0d))
                .withMessageContaining("would assert nothing");
    }

    @Test
    @DisplayName("the case is copied defensively and rendered without its question text")
    void immutabilityAndRendering() {
        List<String> expected = new ArrayList<>(List.of("doc-guide"));
        MedRetrievalBaselineCase baselineCase = new MedRetrievalBaselineCase("case", cardioPatientOne(),
                "问诊", 5, true, expected, null, List.of(), 1.0d);
        expected.add("doc-late");

        assertThat(baselineCase.expectedDocumentIds()).containsExactly("doc-guide");
        assertThatThrownBy(() -> baselineCase.expectedDocumentIds().add("doc-more"))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThat(baselineCase.toString())
                .startsWith("MedRetrievalBaselineCase{")
                .contains("name='case'")
                .contains("queryLength=2")
                .contains("tenant-a")
                .contains("patient-1")
                .doesNotContain("问诊");
    }
}
