package com.med.qa.rag;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MedRetrievalBaselineEvaluator}.
 *
 * <p>The evaluator is the only place that decides whether a golden set passes, so the tests pin the
 * two things that could make it worthless: it must hand each case to the production
 * {@link MedRetrievalService} unchanged (no test-only retrieval shortcut), and the verdict must
 * actually be able to fail. The second is asserted explicitly with retriever stubs that return
 * nothing and that return everything — a baseline that cannot fail is a green build that proves
 * nothing.</p>
 *
 * <p>{@link MedRetrievalService} is mocked here because this is an offline test: the real service is
 * exercised against a real Redis Stack by
 * {@code MedRetrievalBaselineIntegrationTest}.</p>
 */
class MedRetrievalBaselineEvaluatorTest {

    private static Document document(String id) {
        return Document.builder().id(id).text("content of " + id).build();
    }

    private static MedDocumentScope scope() {
        return MedDocumentScope.ofPatient("tenant-a", "dept-cardio", "patient-1");
    }

    private static MedRetrievalBaselineCase baselineCase(String name, List<String> expected,
                                                         String expectedTop, List<String> forbidden,
                                                         double minRecall, boolean includeShared) {
        return new MedRetrievalBaselineCase(name, scope(), "高血压 首选 药物", 10, includeShared,
                expected, expectedTop, forbidden, minRecall);
    }

    private static MedRetrievalBaseline baseline() {
        return new MedRetrievalBaseline("rag-retrieval-baseline", "1", List.of(
                baselineCase("own-record", List.of("doc-record"), "doc-record", List.of(), 1.0d, true),
                baselineCase("no-guidelines", List.of("doc-record"), "doc-record", List.of("doc-guide"),
                        1.0d, false)));
    }

    @Test
    @DisplayName("every case is executed by the production retrieval service, with its own scope and Top-K")
    void everyCaseGoesThroughTheProductionService() {
        MedRetrievalService retrievalService = mock(MedRetrievalService.class);
        when(retrievalService.search(any(MedRetrievalQuery.class)))
                .thenReturn(List.of(document("doc-record")));

        MedRetrievalBaselineReport report = new MedRetrievalBaselineEvaluator(retrievalService)
                .evaluate(baseline());

        assertThat(report.passed()).isTrue();
        assertThat(report.caseCount()).isEqualTo(2);

        ArgumentCaptor<MedRetrievalQuery> queries = ArgumentCaptor.forClass(MedRetrievalQuery.class);
        verify(retrievalService, times(2)).search(queries.capture());
        assertThat(queries.getAllValues()).extracting(MedRetrievalQuery::getText)
                .containsExactly("高血压 首选 药物", "高血压 首选 药物");
        assertThat(queries.getAllValues()).extracting(MedRetrievalQuery::getTopK)
                .containsExactly(10, 10);
        assertThat(queries.getAllValues()).extracting(MedRetrievalQuery::isIncludeSharedDocuments)
                .containsExactly(true, false);
        assertThat(queries.getAllValues()).allSatisfy(
                query -> assertThat(query.getScope()).isEqualTo(scope()));
    }

    @Test
    @DisplayName("a retrieval that drops an expected document fails the baseline")
    void aMissingDocumentFailsTheBaseline() {
        MedRetrievalService retrievalService = mock(MedRetrievalService.class);
        when(retrievalService.search(any(MedRetrievalQuery.class))).thenReturn(List.of());

        MedRetrievalBaselineReport report = new MedRetrievalBaselineEvaluator(retrievalService)
                .evaluate(baseline());

        assertThat(report.passed()).isFalse();
        assertThat(report.failedCount()).isEqualTo(2);
        assertThat(report.failureSummary()).contains("2/2");
    }

    @Test
    @DisplayName("a retrieval that leaks an out-of-scope document fails even with the right document first")
    void aLeakedDocumentFailsTheBaseline() {
        MedRetrievalService retrievalService = mock(MedRetrievalService.class);
        when(retrievalService.search(any(MedRetrievalQuery.class)))
                .thenReturn(List.of(document("doc-record"), document("doc-guide")));

        MedRetrievalBaselineReport report = new MedRetrievalBaselineEvaluator(retrievalService)
                .evaluate(baseline());

        assertThat(report.passed()).isFalse();
        assertThat(report.failedCases()).extracting(MedRetrievalBaselineCaseResult::caseName)
                .containsExactly("no-guidelines");
        assertThat(report.failureSummary()).contains("out-of-scope documents were returned: [doc-guide]");
    }

    @Test
    @DisplayName("the retriever seam can prove the baseline is not vacuous")
    void theBaselineCanActuallyFail() {
        MedRetrievalBaselineEvaluator evaluator = new MedRetrievalBaselineEvaluator(
                mock(MedRetrievalService.class));

        // Nothing at all: every case that expects something has to fail.
        assertThat(evaluator.evaluate(baseline(), entry -> List.of()).failedCount()).isEqualTo(2);

        // Everything at all: the case that forbids the guideline has to fail on the leak.
        MedRetrievalBaselineReport leaky = evaluator.evaluate(baseline(),
                entry -> List.of("doc-record", "doc-guide"));
        assertThat(leaky.passed()).isFalse();
        assertThat(leaky.failedCases()).hasSize(1);
    }

    @Test
    @DisplayName("a negative case passes when the retriever returns nothing and fails on a leak")
    void negativeCasesAreEvaluated() {
        MedRetrievalBaselineEvaluator evaluator = new MedRetrievalBaselineEvaluator(
                mock(MedRetrievalService.class));
        MedRetrievalBaseline negativeOnly = new MedRetrievalBaseline("negative", "1",
                List.of(baselineCase("unknown-scope", List.of(), null, List.of("doc-leak"), 0.0d, true)));

        assertThat(evaluator.evaluate(negativeOnly, entry -> List.of()).passed()).isTrue();
        assertThat(evaluator.evaluate(negativeOnly, entry -> List.of("doc-leak")).passed()).isFalse();
    }

    @Test
    @DisplayName("a retrieval failure aborts the evaluation instead of being reported as zero recall")
    void retrievalFailuresPropagate() {
        MedRetrievalService retrievalService = mock(MedRetrievalService.class);
        when(retrievalService.search(any(MedRetrievalQuery.class)))
                .thenThrow(new BizException(ErrorCode.STORAGE_ERROR, "redis is down"));

        MedRetrievalBaselineEvaluator evaluator = new MedRetrievalBaselineEvaluator(retrievalService);

        assertThatThrownBy(() -> evaluator.evaluate(baseline()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("redis is down");
        assertThatThrownBy(() -> evaluator.evaluateCase(baseline().cases().get(0)))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("a single case can be evaluated on its own")
    void singleCaseEvaluation() {
        MedRetrievalService retrievalService = mock(MedRetrievalService.class);
        when(retrievalService.search(any(MedRetrievalQuery.class)))
                .thenReturn(List.of(document("doc-record")));

        MedRetrievalBaselineCaseResult result = new MedRetrievalBaselineEvaluator(retrievalService)
                .evaluateCase(baselineCase("own-record", List.of("doc-record"), "doc-record",
                        List.of(), 1.0d, true));

        assertThat(result.passed()).isTrue();
        assertThat(result.retrievedDocumentIds()).containsExactly("doc-record");
    }

    @Test
    @DisplayName("boundary: null collaborators and a null case are refused before any retrieval happens")
    void nullArgumentsAreRefused() {
        MedRetrievalService retrievalService = mock(MedRetrievalService.class);
        MedRetrievalBaselineEvaluator evaluator = new MedRetrievalBaselineEvaluator(retrievalService);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedRetrievalBaselineEvaluator(null))
                .withMessageContaining("retrievalService must not be null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> evaluator.evaluate(null))
                .withMessageContaining("baseline must not be null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> evaluator.evaluate(baseline(), null))
                .withMessageContaining("retriever must not be null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> evaluator.evaluateCase(null))
                .withMessageContaining("baselineCase must not be null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> evaluator.retrieve(null))
                .withMessageContaining("baselineCase must not be null");

        verifyNoInteractions(retrievalService);
    }
}
