package com.med.qa.rag;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Guards the shipped retrieval-quality baseline itself.
 *
 * <p>The golden set is data, which means nothing in the compiler protects it. This suite is what
 * does: it loads the real resource the CI job evaluates and asserts that the set is structurally
 * worth running — it has cases, every case can actually fail, the isolation dimensions are all
 * covered, and no case was accidentally reduced to an assertion that passes no matter what retrieval
 * does.</p>
 *
 * <p>It runs offline (no Redis, no Docker): the retriever seam of
 * {@link MedRetrievalBaselineEvaluator} is used to prove the set fails when nothing is returned,
 * which is exactly the property a golden set can silently lose when a case is edited carelessly.</p>
 */
class MedRetrievalBaselineResourceTest {

    private static final String RESOURCE = "rag/retrieval-baseline.json";

    private static MedRetrievalBaseline baseline;

    @BeforeAll
    static void loadGoldenSet() throws IOException {
        baseline = new MedRetrievalBaselineLoader().load(new ClassPathResource(RESOURCE));
    }

    @Test
    @DisplayName("the shipped golden set is named, versioned and holds several uniquely named cases")
    void theSetIsWellFormed() {
        assertThat(baseline.name()).isNotBlank();
        assertThat(baseline.version()).isNotBlank();
        assertThat(baseline.caseCount()).isGreaterThanOrEqualTo(5);

        List<String> names = baseline.caseNames();
        assertThat(names).doesNotContainNull().allSatisfy(name -> assertThat(name).isNotBlank());
        assertThat(Set.copyOf(names)).hasSize(names.size());
    }

    @Test
    @DisplayName("every case can fail: each one expects something or forbids something, and all forbid a leak")
    void everyCaseCanFail() {
        assertThat(baseline.cases()).allSatisfy(baselineCase -> assertThat(
                baselineCase.hasExpectations() || !baselineCase.forbiddenDocumentIds().isEmpty())
                .as("case '%s' would assert nothing", baselineCase.name())
                .isTrue());

        // A case that cannot detect a leak would not have caught the D36 tag-escaping defect, so the
        // golden set is required to forbid at least one document everywhere.
        assertThat(baseline.cases()).allSatisfy(baselineCase ->
                assertThat(baselineCase.forbiddenDocumentIds())
                        .as("case '%s' must be able to detect an out-of-scope document",
                                baselineCase.name())
                        .isNotEmpty());
    }

    @Test
    @DisplayName("the set covers every isolation dimension and both retrieval scopes")
    void theSetCoversEveryIsolationDimension() {
        assertThat(baseline.cases()).anySatisfy(baselineCase ->
                assertThat(baselineCase.scope().isPatientScoped() && baselineCase.includeSharedDocuments())
                        .isTrue());
        assertThat(baseline.cases()).anySatisfy(baselineCase ->
                assertThat(baselineCase.scope().isPatientScoped() && !baselineCase.includeSharedDocuments())
                        .isTrue());
        assertThat(baseline.cases()).anySatisfy(baselineCase ->
                assertThat(baselineCase.scope().isPatientScoped()).isFalse());
        assertThat(baseline.cases()).anySatisfy(baselineCase ->
                assertThat(baselineCase.hasExpectations()).isFalse());
        assertThat(baseline.cases()).anySatisfy(baselineCase ->
                assertThat(baselineCase.expectedTopDocumentId()).isNotNull());

        // At least one case has to separate tenants and one has to separate departments, otherwise the
        // set could be green while isolation is broken.
        Set<String> tenants = tenants();
        Set<String> departments = departments();
        assertThat(tenants).as("tenant isolation is not covered").hasSizeGreaterThan(1);
        assertThat(departments).as("department isolation is not covered").hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("the golden set is not vacuous: returning nothing fails every case that expects something")
    void theGoldenSetIsNotVacuous() {
        MedRetrievalBaselineEvaluator evaluator = new MedRetrievalBaselineEvaluator(
                mock(MedRetrievalService.class));

        MedRetrievalBaselineReport empty = evaluator.evaluate(baseline, entry -> List.of());
        assertThat(empty.passed()).isFalse();
        assertThat(empty.failedCount()).isEqualTo(baseline.cases().stream()
                .filter(MedRetrievalBaselineCase::hasExpectations).count());
        assertThat(empty.failureSummary()).contains(baseline.name()).contains("failed");

        // Returning everything the corpus holds must break the negative case, which is the case that
        // detects a tag filter that stopped filtering.
        Set<String> everyDocument = baseline.cases().stream()
                .flatMap(baselineCase -> baselineCase.forbiddenDocumentIds().stream())
                .collect(Collectors.toSet());
        MedRetrievalBaselineReport leaky = evaluator.evaluate(baseline, entry -> List.copyOf(everyDocument));
        assertThat(leaky.passed()).isFalse();
        assertThat(leaky.failedCases()).extracting(MedRetrievalBaselineCaseResult::caseName)
                .contains("unknown-scope-returns-nothing");
    }

    /**
     * Collects the tenants the golden set asks about.
     *
     * @return the distinct tenant identifiers, never {@code null}
     */
    private static Set<String> tenants() {
        return baseline.cases().stream()
                .map(baselineCase -> baselineCase.scope().getTenantId())
                .collect(Collectors.toSet());
    }

    /**
     * Collects the departments the golden set asks about.
     *
     * @return the distinct department identifiers, never {@code null}
     */
    private static Set<String> departments() {
        return baseline.cases().stream()
                .map(baselineCase -> baselineCase.scope().getDeptId())
                .collect(Collectors.toSet());
    }
}
