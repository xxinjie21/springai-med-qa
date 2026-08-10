package com.med.qa.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests of the retrieval query value object.
 *
 * <p>The query is the only place a caller can express what it wants to reach, so the tests focus on
 * the two things that must not be possible: building a query whose knobs are outside their domain,
 * and building a department-wide query that excludes the very documents it could match.</p>
 */
class MedRetrievalQueryTest {

    private static final MedDocumentScope PATIENT_SCOPE =
            MedDocumentScope.ofPatient("hosp1", "cardio", "p9001");

    private static final MedDocumentScope DEPARTMENT_SCOPE =
            MedDocumentScope.ofDepartment("hosp1", "cardio");

    @Test
    @DisplayName("a minimal query leaves both knobs unset so the configured defaults apply")
    void minimalQueryLeavesKnobsUnset() {
        MedRetrievalQuery query = MedRetrievalQuery.of("chest pain follow-up", PATIENT_SCOPE);

        assertThat(query.getText()).isEqualTo("chest pain follow-up");
        assertThat(query.getScope()).isEqualTo(PATIENT_SCOPE);
        assertThat(query.getTopK()).isNull();
        assertThat(query.getSimilarityThreshold()).isNull();
        assertThat(query.isIncludeSharedDocuments()).isTrue();
        assertThat(query.getAdditionalFilter()).isNull();
    }

    @Test
    @DisplayName("every knob can be set explicitly")
    void builderSetsEveryKnob() {
        Filter.Expression extra = new FilterExpressionBuilder().eq("doc_type", "guideline").build();

        MedRetrievalQuery query = MedRetrievalQuery.builder("beta blocker dosage", PATIENT_SCOPE)
                .topK(9)
                .similarityThreshold(0.75d)
                .includeSharedDocuments(false)
                .additionalFilter(extra)
                .build();

        assertThat(query.getTopK()).isEqualTo(9);
        assertThat(query.getSimilarityThreshold()).isEqualTo(0.75d);
        assertThat(query.isIncludeSharedDocuments()).isFalse();
        assertThat(query.getAdditionalFilter()).isSameAs(extra);
    }

    @ParameterizedTest(name = "blank text \"{0}\" is rejected")
    @ValueSource(strings = {"", " ", "\t"})
    @DisplayName("a blank query text is a programming error")
    void blankTextIsRejected(String candidate) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MedRetrievalQuery.of(candidate, PATIENT_SCOPE))
                .withMessageContaining("must not be blank");
    }

    @Test
    @DisplayName("a null query text is a programming error")
    void nullTextIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MedRetrievalQuery.of(null, PATIENT_SCOPE))
                .withMessageContaining("must not be blank");
    }

    @Test
    @DisplayName("a null scope is a programming error")
    void nullScopeIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MedRetrievalQuery.of("chest pain", null))
                .withMessageContaining("scope must not be null");
    }

    @ParameterizedTest(name = "topK {0} is rejected")
    @ValueSource(ints = {0, -3})
    @DisplayName("a non-positive topK is rejected at build time")
    void nonPositiveTopKIsRejected(int candidate) {
        var builder = MedRetrievalQuery.builder("chest pain", PATIENT_SCOPE).topK(candidate);

        assertThatIllegalArgumentException()
                .isThrownBy(builder::build)
                .withMessageContaining("topK must be positive");
    }

    @ParameterizedTest(name = "threshold {0} is rejected")
    @ValueSource(doubles = {-0.2d, 1.5d, Double.NaN})
    @DisplayName("a similarity threshold outside the unit interval is rejected at build time")
    void outOfRangeThresholdIsRejected(double candidate) {
        var builder = MedRetrievalQuery.builder("chest pain", PATIENT_SCOPE)
                .similarityThreshold(candidate);

        assertThatIllegalArgumentException()
                .isThrownBy(builder::build)
                .withMessageContaining("similarityThreshold must be within [0, 1]");
    }

    @Test
    @DisplayName("a department query cannot exclude shared documents: it would match nothing")
    void departmentQueryCannotExcludeSharedDocuments() {
        var builder = MedRetrievalQuery.builder("triage protocol", DEPARTMENT_SCOPE)
                .includeSharedDocuments(false);

        assertThatIllegalArgumentException()
                .isThrownBy(builder::build)
                .withMessageContaining("would match nothing");
    }

    @Test
    @DisplayName("a department query including shared documents is the normal case")
    void departmentQueryIsAccepted() {
        MedRetrievalQuery query = MedRetrievalQuery.of("triage protocol", DEPARTMENT_SCOPE);

        assertThat(query.isIncludeSharedDocuments()).isTrue();
        assertThat(query.getScope().isPatientScoped()).isFalse();
    }

    @Test
    @DisplayName("value semantics hold for equals and hashCode")
    void valueSemantics() {
        MedRetrievalQuery left = MedRetrievalQuery.builder("chest pain", PATIENT_SCOPE).topK(5).build();
        MedRetrievalQuery right = MedRetrievalQuery.builder("chest pain", PATIENT_SCOPE).topK(5).build();
        MedRetrievalQuery other = MedRetrievalQuery.builder("chest pain", PATIENT_SCOPE).topK(6).build();

        assertThat(left).isEqualTo(right).hasSameHashCodeAs(right);
        assertThat(left).isNotEqualTo(other).isNotEqualTo(null).isNotEqualTo("chest pain");
        assertThat(left).isEqualTo(left);
    }

    @Test
    @DisplayName("toString reports the shape of the query but never the question itself")
    void toStringHidesTheQuestion() {
        MedRetrievalQuery query = MedRetrievalQuery.builder("patient reports crushing chest pain",
                        PATIENT_SCOPE)
                .topK(3)
                .build();

        assertThat(query.toString())
                .doesNotContain("crushing chest pain")
                .contains("textLength=" + "patient reports crushing chest pain".length())
                .contains("topK=3")
                .contains("additionalFilter=false");
    }
}
