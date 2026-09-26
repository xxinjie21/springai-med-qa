package com.med.qa.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests for {@link MedIndexRebuildRequest}.
 *
 * <p>The interesting assertions are the two factory methods: they are how a caller picks between the
 * repair that keeps clinical documents and the one that erases them, so a mistake there is not a
 * validation nicety but the difference between a repair and a data-loss incident.</p>
 */
class MedIndexRebuildRequestTest {

    private static MedDocumentRequest document(String id) {
        return new MedDocumentRequest(id, "高血压 首选 药物",
                MedDocumentScope.ofDepartment("tenant-a", "dept-cardio"), Map.of());
    }

    @Test
    @DisplayName("the index-only factory keeps documents and carries no corpus")
    void indexOnlyFactory() {
        MedIndexRebuildRequest request = MedIndexRebuildRequest.indexOnly("ops-oncall");

        assertThat(request.mode()).isEqualTo(MedIndexRebuildMode.INDEX_ONLY);
        assertThat(request.documents()).isEmpty();
        assertThat(request.documentCount()).isZero();
        assertThat(request.deletesDocuments()).isFalse();
        assertThat(request.requestedBy()).isEqualTo("ops-oncall");
    }

    @Test
    @DisplayName("the destructive factory requires a corpus, so it can never erase silently")
    void destructiveFactoryRequiresCorpus() {
        MedIndexRebuildRequest request =
                MedIndexRebuildRequest.dropAndReingest(List.of(document("doc-1")), "ops-oncall");

        assertThat(request.mode()).isEqualTo(MedIndexRebuildMode.DROP_AND_REINGEST);
        assertThat(request.deletesDocuments()).isTrue();
        assertThat(request.documentCount()).isEqualTo(1);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> MedIndexRebuildRequest.dropAndReingest(List.of(), "ops-oncall"))
                .withMessageContaining("erase the corpus");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MedIndexRebuildRequest.dropAndReingest(null, "ops-oncall"))
                .withMessageContaining("erase the corpus");
    }

    @Test
    @DisplayName("the corpus is copied, so a later mutation cannot change a running instruction")
    void corpusIsDefensivelyCopied() {
        List<MedDocumentRequest> mutable = new ArrayList<>();
        mutable.add(document("doc-1"));

        MedIndexRebuildRequest request =
                MedIndexRebuildRequest.dropAndReingest(mutable, "ops-oncall");
        mutable.add(document("doc-2"));

        assertThat(request.documentCount()).isEqualTo(1);
        assertThat(request.documents()).hasSize(1);
    }

    @Test
    @DisplayName("boundary: a null mode, a null corpus, a null entry or a blank requester is rejected")
    void invalidInstructionsAreRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedIndexRebuildRequest(null, List.of(), "ops"))
                .withMessageContaining("mode");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedIndexRebuildRequest(MedIndexRebuildMode.INDEX_ONLY, null, "ops"))
                .withMessageContaining("documents");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedIndexRebuildRequest(MedIndexRebuildMode.DROP_AND_REINGEST,
                        java.util.Arrays.asList(document("doc-1"), null), "ops"))
                .withMessageContaining("null entries");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedIndexRebuildRequest(MedIndexRebuildMode.INDEX_ONLY, List.of(), " "))
                .withMessageContaining("requestedBy");
    }

    @Test
    @DisplayName("the request renders as mode, corpus size and requester, never as document content")
    void toStringCarriesNoClinicalContent() {
        MedIndexRebuildRequest request =
                MedIndexRebuildRequest.dropAndReingest(List.of(document("doc-1")), "ops-oncall");

        assertThat(request.toString())
                .contains("DROP_AND_REINGEST")
                .contains("documents=1")
                .contains("ops-oncall")
                .doesNotContain("高血压");
    }

    @Test
    @DisplayName("the two modes report what they are allowed to touch")
    void modesReportTheirDestructiveness() {
        assertThat(MedIndexRebuildMode.INDEX_ONLY.deletesDocuments()).isFalse();
        assertThat(MedIndexRebuildMode.DROP_AND_REINGEST.deletesDocuments()).isTrue();
    }
}
