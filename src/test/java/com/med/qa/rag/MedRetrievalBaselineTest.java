package com.med.qa.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MedRetrievalBaseline}.
 *
 * <p>The set is the unit a CI job asserts on, and its identity is its case names: a duplicated name
 * would make a report ambiguous and hide one of the two cases behind the other, so the constructor
 * refuses it rather than letting a "passing" baseline cover fewer cases than it appears to.</p>
 */
class MedRetrievalBaselineTest {

    private static MedRetrievalBaselineCase caseNamed(String name) {
        return new MedRetrievalBaselineCase(name,
                MedDocumentScope.ofPatient("tenant-a", "dept-cardio", "patient-1"),
                "高血压 首选 药物", 10, true, List.of("doc-guide"), "doc-guide", List.of(), 1.0d);
    }

    @Test
    @DisplayName("a well-formed set exposes its cases in declaration order")
    void wellFormedSetIsAccepted() {
        MedRetrievalBaseline baseline = new MedRetrievalBaseline("rag-retrieval-baseline", "1",
                List.of(caseNamed("first"), caseNamed("second")));

        assertThat(baseline.name()).isEqualTo("rag-retrieval-baseline");
        assertThat(baseline.version()).isEqualTo("1");
        assertThat(baseline.caseCount()).isEqualTo(2);
        assertThat(baseline.caseNames()).containsExactly("first", "second");
        assertThat(baseline.findCase("second")).contains(caseNamed("second"));
        assertThat(baseline.findCase("missing")).isEmpty();
        assertThat(baseline.findCase(null)).isEmpty();
    }

    @Test
    @DisplayName("boundary: a blank name or version is refused")
    void blankIdentityIsRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedRetrievalBaseline(" ", "1", List.of(caseNamed("first"))))
                .withMessageContaining("baseline name must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedRetrievalBaseline("baseline", "", List.of(caseNamed("first"))))
                .withMessageContaining("baseline version must not be blank");
    }

    @Test
    @DisplayName("boundary: a set without cases, or holding a null case, is refused")
    void caseListIsValidated() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedRetrievalBaseline("baseline", "1", null))
                .withMessageContaining("cases must not be null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedRetrievalBaseline("baseline", "1", List.of()))
                .withMessageContaining("at least one case");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedRetrievalBaseline("baseline", "1",
                        java.util.Arrays.asList(caseNamed("first"), null)))
                .withMessageContaining("must not contain null entries");
    }

    @Test
    @DisplayName("two cases cannot share a name, because a report has to be unambiguous")
    void duplicateCaseNamesAreRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedRetrievalBaseline("baseline", "1",
                        List.of(caseNamed("duplicate"), caseNamed("duplicate"))))
                .withMessageContaining("duplicate case name: duplicate");
    }

    @Test
    @DisplayName("the set is copied defensively and rendered without any question text")
    void immutabilityAndRendering() {
        List<MedRetrievalBaselineCase> cases = new ArrayList<>(List.of(caseNamed("first")));
        MedRetrievalBaseline baseline = new MedRetrievalBaseline("baseline", "2", cases);
        cases.add(caseNamed("second"));

        assertThat(baseline.caseNames()).containsExactly("first");
        assertThatThrownBy(() -> baseline.cases().add(caseNamed("third")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(baseline.toString())
                .startsWith("MedRetrievalBaseline{")
                .contains("name='baseline'")
                .contains("version='2'")
                .contains("first")
                .doesNotContain("高血压");
    }
}
