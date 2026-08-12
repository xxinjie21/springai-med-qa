package com.med.qa.controller;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.common.result.ApiResult;
import com.med.qa.controller.dto.RagDeleteRequest;
import com.med.qa.controller.dto.RagDeleteResponse;
import com.med.qa.controller.dto.RagIngestItem;
import com.med.qa.controller.dto.RagIngestRequest;
import com.med.qa.controller.dto.RagIngestResponse;
import com.med.qa.controller.dto.RagSearchPreviewItem;
import com.med.qa.controller.dto.RagSearchPreviewRequest;
import com.med.qa.controller.dto.RagSearchPreviewResponse;
import com.med.qa.rag.MedDocumentService;
import com.med.qa.rag.MedDocumentScope;
import com.med.qa.rag.MedRetrievalQuery;
import com.med.qa.rag.MedRetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the RAG admin controller.
 *
 * <p>The two underlying services are mocked, so the controller is exercised in isolation: parsing,
 * boundary validation, scope assembly and response shaping. No Redis, embedding endpoint or vector
 * store is contacted.</p>
 */
class RagAdminControllerTest {

    private MedDocumentService documentService;

    private MedRetrievalService retrievalService;

    private RagAdminController controller;

    @BeforeEach
    void setUp() {
        documentService = mock(MedDocumentService.class);
        retrievalService = mock(MedRetrievalService.class);
        controller = new RagAdminController(documentService, retrievalService);
    }

    @Nested
    @DisplayName("document ingestion")
    class Ingestion {

        @Test
        @DisplayName("indexes a batch and echoes the assigned identifiers")
        void ingestsBatch() {
            when(documentService.ingestAll(anyList())).thenReturn(List.of("doc-1", "doc-2"));

            ApiResult<RagIngestResponse> result = controller.ingest(new RagIngestRequest(List.of(
                    new RagIngestItem(null, "triage protocol", "hosp-1", "cardiology", null, null),
                    new RagIngestItem(null, "discharge summary", "hosp-1", "cardiology", "P-2048", null))));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getData().ingested()).isEqualTo(2);
            assertThat(result.getData().ids()).containsExactly("doc-1", "doc-2");
            verify(documentService).ingestAll(anyList());
        }

        @Test
        @DisplayName("rejects a null request")
        void rejectsNullRequest() {
            assertThatThrownBy(() -> controller.ingest(null))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }

        @Test
        @DisplayName("rejects an empty document batch")
        void rejectsEmptyBatch() {
            assertThatThrownBy(() -> controller.ingest(new RagIngestRequest(List.of())))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }

        @Test
        @DisplayName("rejects an item with blank text")
        void rejectsBlankText() {
            assertThatThrownBy(() -> controller.ingest(new RagIngestRequest(List.of(
                    new RagIngestItem(null, "   ", "hosp-1", "cardiology", null, null)))))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }

        @Test
        @DisplayName("rejects an item missing tenant or department")
        void rejectsMissingScope() {
            assertThatThrownBy(() -> controller.ingest(new RagIngestRequest(List.of(
                    new RagIngestItem(null, "text", "", "cardiology", null, null)))))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }

        @Test
        @DisplayName("rejects metadata that collides with an isolation tag")
        void rejectsReservedMetadata() {
            assertThatThrownBy(() -> controller.ingest(new RagIngestRequest(List.of(
                    new RagIngestItem(null, "text", "hosp-1", "cardiology", null,
                            Map.of(MedDocumentScope.METADATA_TENANT_ID, "evil"))))))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("document deletion")
    class Deletion {

        @Test
        @DisplayName("deletes by identifier and reports the ids")
        void deletesByIds() {
            ApiResult<RagDeleteResponse> result = controller.delete(
                    new RagDeleteRequest(List.of("a", "b"), null, null, null));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getData().byId()).isTrue();
            assertThat(result.getData().ids()).containsExactly("a", "b");
            verify(documentService).deleteByIds(List.of("a", "b"));
        }

        @Test
        @DisplayName("deletes by isolation scope when no ids are given")
        void deletesByScope() {
            ApiResult<RagDeleteResponse> result = controller.delete(
                    new RagDeleteRequest(null, "hosp-1", "cardiology", "P-2048"));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getData().scope()).contains("hosp-1").contains("P-2048");
            verify(documentService).deleteByScope(any(MedDocumentScope.class));
        }

        @Test
        @DisplayName("rejects a request that names neither ids nor a scope")
        void rejectsEmptyDelete() {
            assertThatThrownBy(() -> controller.delete(new RagDeleteRequest(null, null, null, null)))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }

        @Test
        @DisplayName("rejects a null delete request")
        void rejectsNullDelete() {
            assertThatThrownBy(() -> controller.delete(null))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("retrieval preview")
    class RetrievalPreview {

        @Test
        @DisplayName("returns the documents matched by the scoped search")
        void previewsMatches() {
            Document document = Document.builder()
                    .text("use 325mg aspirin").score(0.91)
                    .metadata(Map.of("title", "aspirin")).build();
            when(retrievalService.search(any(MedRetrievalQuery.class))).thenReturn(List.of(document));

            ApiResult<RagSearchPreviewResponse> result = controller.searchPreview(
                    new RagSearchPreviewRequest("what aspirin dose", "hosp-1", "cardiology", "P-2048",
                            null, null, null));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getData().total()).isEqualTo(1);
            RagSearchPreviewItem item = result.getData().documents().get(0);
            assertThat(item.id()).isNotNull();
            assertThat(item.score()).isEqualTo(0.91);
            assertThat(item.content()).isEqualTo("use 325mg aspirin");
            verify(retrievalService).search(any(MedRetrievalQuery.class));
        }

        @Test
        @DisplayName("forwards explicit topK, threshold and shared-toggle to the search service")
        void forwardsSearchParameters() {
            when(retrievalService.search(any(MedRetrievalQuery.class))).thenReturn(List.of());
            ArgumentCaptor<MedRetrievalQuery> captor = ArgumentCaptor.forClass(MedRetrievalQuery.class);

            controller.searchPreview(new RagSearchPreviewRequest("q", "hosp-1", "cardiology", "P-2048",
                    3, 0.5, false));

            verify(retrievalService).search(captor.capture());
            MedRetrievalQuery query = captor.getValue();
            assertThat(query.getTopK()).isEqualTo(3);
            assertThat(query.getSimilarityThreshold()).isEqualTo(0.5);
            assertThat(query.isIncludeSharedDocuments()).isFalse();
        }

        @Test
        @DisplayName("defaults includeSharedDocuments to true when omitted")
        void defaultsShared() {
            when(retrievalService.search(any(MedRetrievalQuery.class))).thenReturn(List.of());
            ArgumentCaptor<MedRetrievalQuery> captor = ArgumentCaptor.forClass(MedRetrievalQuery.class);

            controller.searchPreview(new RagSearchPreviewRequest("q", "hosp-1", "cardiology", "P-2048",
                    null, null, null));

            verify(retrievalService).search(captor.capture());
            assertThat(captor.getValue().isIncludeSharedDocuments()).isTrue();
        }

        @Test
        @DisplayName("rejects a null or blank query")
        void rejectsBlankQuery() {
            assertThatThrownBy(() -> controller.searchPreview(
                    new RagSearchPreviewRequest("  ", "hosp-1", "cardiology", null, null, null, null)))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }

        @Test
        @DisplayName("rejects a query without tenant and department")
        void rejectsMissingScope() {
            assertThatThrownBy(() -> controller.searchPreview(
                    new RagSearchPreviewRequest("q", "hosp-1", "", null, null, null, null)))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }

        @Test
        @DisplayName("rejects an out-of-range topK as a bad request, not a server error")
        void rejectsInvalidTopK() {
            assertThatThrownBy(() -> controller.searchPreview(
                    new RagSearchPreviewRequest("q", "hosp-1", "cardiology", "P-2048", 0, null, null)))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }
    }
}
