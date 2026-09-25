package com.med.qa.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MedVectorIndexReport}.
 *
 * <p>The report is the judgement layer of the D37 probe: {@link MedVectorIndexReport#missingTagFields}
 * decides whether an existing index can still answer a scoped query. The boundary cases asserted here
 * are the ones that decide between a silent degradation and an alert — an index that exists but
 * declares none of the expected tags, and an index that does not exist at all.</p>
 */
class MedVectorIndexReportTest {

    private static final List<String> EXPECTED = List.of("tenant_id", "dept_id", "patient_id");

    /** A well-formed index declaring all three isolation tags. */
    private static MedVectorIndexReport readyIndex() {
        return new MedVectorIndexReport("med-doc-index", true, 42L,
                Set.of("med:doc:"), Set.of("tenant_id", "dept_id", "patient_id"));
    }

    @Test
    @DisplayName("a well-formed index is ready for scoped retrieval and misses nothing")
    void wellFormedIndexIsReady() {
        MedVectorIndexReport report = readyIndex();

        assertThat(report.indexName()).isEqualTo("med-doc-index");
        assertThat(report.exists()).isTrue();
        assertThat(report.documentCount()).isEqualTo(42L);
        assertThat(report.prefixesAsList()).containsExactly("med:doc:");
        assertThat(report.missingTagFields(EXPECTED)).isEmpty();
        assertThat(report.isScopedRetrievalReady(EXPECTED)).isTrue();
        assertThat(report.hasTagField("dept_id")).isTrue();
    }

    @Test
    @DisplayName("an index missing one expected tag field is not ready and names the field")
    void driftedSchemaNamesTheMissingField() {
        MedVectorIndexReport report = new MedVectorIndexReport("med-doc-index", true, 7L,
                Set.of("med:doc:"), Set.of("tenant_id", "dept_id"));

        assertThat(report.missingTagFields(EXPECTED)).containsExactly("patient_id");
        assertThat(report.isScopedRetrievalReady(EXPECTED)).isFalse();
        assertThat(report.hasTagField("patient_id")).isFalse();
    }

    @Test
    @DisplayName("boundary: an index with no TAG field at all reports every expected field missing")
    void indexWithoutTagFieldsMissesEverything() {
        MedVectorIndexReport report = new MedVectorIndexReport("med-doc-index", true, 0L,
                Set.of("med:doc:"), Set.of());

        assertThat(report.missingTagFields(EXPECTED))
                .containsExactly("tenant_id", "dept_id", "patient_id");
        assertThat(report.isScopedRetrievalReady(EXPECTED)).isFalse();
    }

    @Test
    @DisplayName("the missing-index factory reports a non-existent index with no tags")
    void missingIndexFactoryReportsAbsence() {
        MedVectorIndexReport report = MedVectorIndexReport.missing("med-doc-index");

        assertThat(report.indexName()).isEqualTo("med-doc-index");
        assertThat(report.exists()).isFalse();
        assertThat(report.documentCount()).isZero();
        assertThat(report.tagFields()).isEmpty();
        assertThat(report.prefixesAsList()).isEmpty();
        assertThat(report.isScopedRetrievalReady(EXPECTED)).isFalse();
    }

    @Test
    @DisplayName("boundary: an absent index reports every expected field missing rather than failing")
    void absentIndexMissesEveryExpectedField() {
        // This is the important distinction: "the index is gone" and "the index declares nothing"
        // both mean scoped retrieval cannot work, and both must be reportable without an exception.
        assertThat(MedVectorIndexReport.missing("med-doc-index").missingTagFields(EXPECTED))
                .containsExactly("tenant_id", "dept_id", "patient_id");
    }

    @Test
    @DisplayName("boundary: an empty or null expectation reports nothing missing")
    void emptyExpectationReportsNothingMissing() {
        MedVectorIndexReport report = new MedVectorIndexReport("med-doc-index", true, 1L,
                Set.of("med:doc:"), Set.of());

        assertThat(report.missingTagFields(List.of())).isEmpty();
        assertThat(report.missingTagFields(null)).isEmpty();
        assertThat(report.isScopedRetrievalReady(null)).isTrue();
    }

    @Test
    @DisplayName("boundary: a null entry inside the expectation is skipped, not reported missing")
    void nullExpectationEntryIsSkipped() {
        List<String> expected = new ArrayList<>();
        expected.add("tenant_id");
        expected.add(null);

        assertThat(readyIndex().missingTagFields(expected)).isEmpty();
    }

    @Test
    @DisplayName("the missing-field order follows the expectation, so the message is deterministic")
    void missingFieldOrderFollowsTheExpectation() {
        MedVectorIndexReport report = new MedVectorIndexReport("med-doc-index", true, 0L,
                Set.of(), Set.of("dept_id"));

        assertThat(report.missingTagFields(List.of("patient_id", "dept_id", "tenant_id")))
                .containsExactly("patient_id", "tenant_id");
    }

    @Test
    @DisplayName("an empty but correctly shaped index is still ready: an unpopulated corpus is not a defect")
    void emptyButWellFormedIndexIsReady() {
        MedVectorIndexReport report = new MedVectorIndexReport("med-doc-index", true, 0L,
                Set.of("med:doc:"), Set.of("tenant_id", "dept_id", "patient_id"));

        assertThat(report.isScopedRetrievalReady(EXPECTED)).isTrue();
        assertThat(report.documentCount()).isZero();
    }

    @Test
    @DisplayName("the sets are defensively copied and immutable")
    void setsAreDefensivelyCopied() {
        Set<String> tags = new LinkedHashSet<>(List.of("tenant_id"));
        MedVectorIndexReport report = new MedVectorIndexReport("med-doc-index", true, 1L, Set.of(), tags);

        tags.add("dept_id");

        assertThat(report.tagFields()).containsExactly("tenant_id");
        assertThatThrownBy(() -> report.tagFields().add("dept_id"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("boundary: a blank index name and a negative document count are rejected")
    void invalidFieldsAreRejected() {
        assertThatThrownBy(() -> new MedVectorIndexReport(" ", true, 0L, Set.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("indexName");
        assertThatThrownBy(() -> new MedVectorIndexReport("med-doc-index", true, -1L, Set.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("documentCount");
        assertThatThrownBy(() -> MedVectorIndexReport.missing(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("boundary: null collections read as empty rather than failing the constructor")
    void nullCollectionsReadAsEmpty() {
        MedVectorIndexReport report = new MedVectorIndexReport("med-doc-index", true, 0L, null, null);

        assertThat(report.tagFields()).isEmpty();
        assertThat(report.prefixes()).isEmpty();
    }

    @Test
    @DisplayName("boundary: a null tag field name is never reported as present")
    void nullTagFieldNameIsNeverPresent() {
        assertThat(readyIndex().hasTagField(null)).isFalse();
    }

    @Test
    @DisplayName("the string form carries the metadata an operator needs and nothing clinical")
    void toStringCarriesOnlyIndexMetadata() {
        assertThat(readyIndex().toString())
                .contains("med-doc-index")
                .contains("documentCount=42")
                .contains("tenant_id");
    }
}
