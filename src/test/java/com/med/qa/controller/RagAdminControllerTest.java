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
import com.med.qa.rag.MedDocumentRequest;
import com.med.qa.rag.MedDocumentScope;
import com.med.qa.rag.MedDocumentService;
import com.med.qa.rag.MedRetrievalQuery;
import com.med.qa.rag.MedRetrievalService;
import com.med.qa.security.MedPrincipal;
import com.med.qa.security.MedRole;
import com.med.qa.security.MedSecurityContext;
import com.med.qa.security.RequestIdentityGuard;
import org.junit.jupiter.api.AfterEach;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the RAG admin controller (D42).
 *
 * <p>The two underlying services are mocked, so the controller is exercised in isolation: parsing,
 * boundary validation, scope resolution against the authenticated principal, and response shaping. No
 * Redis, embedding endpoint or vector store is contacted.</p>
 *
 * <p>The <strong>real</strong> {@link RequestIdentityGuard} is used rather than a mock. It is a pure
 * function of (principal, claims) with no IO, and mocking it would hide the one thing these tests exist
 * to pin: that the scope which reaches the services is the principal's. The principal itself is placed
 * in {@link MedSecurityContext} exactly as {@code ApiKeyAuthFilter} would.</p>
 */
class RagAdminControllerTest {

    private static final String TENANT = "hosp-1";

    private static final String DEPT = "dept-cardio";

    private static final String PATIENT = "pat-2048";

    private static final MedPrincipal STAFF = new MedPrincipal(TENANT, DEPT, MedRole.STAFF, null);

    private static final MedPrincipal PATIENT_PRINCIPAL =
            new MedPrincipal(TENANT, DEPT, MedRole.PATIENT, PATIENT);

    private MedDocumentService documentService;

    private MedRetrievalService retrievalService;

    private RagAdminController controller;

    @BeforeEach
    void setUp() {
        documentService = mock(MedDocumentService.class);
        retrievalService = mock(MedRetrievalService.class);
        controller = new RagAdminController(documentService, retrievalService, new RequestIdentityGuard());
        MedSecurityContext.setPrincipal(STAFF);
    }

    @AfterEach
    void tearDown() {
        MedSecurityContext.clear();
    }

    private static MedDocumentScope patientScope() {
        return MedDocumentScope.ofPatient(TENANT, DEPT, PATIENT);
    }

    @Nested
    @DisplayName("document ingestion")
    class Ingestion {

        @Test
        @DisplayName("indexes a batch in the principal's scope when the body claims nothing")
        void ingestsInThePrincipalsScope() {
            when(documentService.ingestAll(anyList())).thenReturn(List.of("doc-1", "doc-2"));

            ApiResult<RagIngestResponse> result = controller.ingest(new RagIngestRequest(List.of(
                    new RagIngestItem(null, "triage protocol", null, null, null, null),
                    new RagIngestItem(null, "discharge summary", null, null, PATIENT, null))));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getData().ingested()).isEqualTo(2);
            assertThat(result.getData().ids()).containsExactly("doc-1", "doc-2");

            ArgumentCaptor<List<MedDocumentRequest>> captor = ArgumentCaptor.forClass(List.class);
            verify(documentService).ingestAll(captor.capture());
            assertThat(captor.getValue()).extracting(MedDocumentRequest::getScope)
                    .containsExactly(MedDocumentScope.ofDepartment(TENANT, DEPT), patientScope());
        }

        @Test
        @DisplayName("accepts claims that match the principal")
        void acceptsMatchingClaims() {
            when(documentService.ingestAll(anyList())).thenReturn(List.of("doc-1"));

            controller.ingest(new RagIngestRequest(List.of(
                    new RagIngestItem(null, "text", TENANT, DEPT, PATIENT, null))));

            ArgumentCaptor<List<MedDocumentRequest>> captor = ArgumentCaptor.forClass(List.class);
            verify(documentService).ingestAll(captor.capture());
            assertThat(captor.getValue().get(0).getScope()).isEqualTo(patientScope());
        }

        @Test
        @DisplayName("refuses an item claiming another tenant before anything is written")
        void refusesForeignTenantClaim() {
            assertThatThrownBy(() -> controller.ingest(new RagIngestRequest(List.of(
                    new RagIngestItem(null, "text", "hosp-2", DEPT, null, null)))))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
            verifyNoInteractions(documentService);
        }

        @Test
        @DisplayName("refuses an item claiming another department before anything is written")
        void refusesForeignDepartmentClaim() {
            assertThatThrownBy(() -> controller.ingest(new RagIngestRequest(List.of(
                    new RagIngestItem(null, "text", TENANT, "dept-onco", null, null)))))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
            verifyNoInteractions(documentService);
        }

        @Test
        @DisplayName("a foreign claim on the second item stops the whole batch")
        void refusesForeignClaimOnALaterItem() {
            assertThatThrownBy(() -> controller.ingest(new RagIngestRequest(List.of(
                    new RagIngestItem(null, "fine", null, null, null, null),
                    new RagIngestItem(null, "not fine", TENANT, "dept-onco", null, null)))))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
            verifyNoInteractions(documentService);
        }

        @Test
        @DisplayName("refuses a patient principal even though the interceptor is configurable")
        void refusesPatientPrincipal() {
            MedSecurityContext.setPrincipal(PATIENT_PRINCIPAL);

            assertThatThrownBy(() -> controller.ingest(new RagIngestRequest(List.of(
                    new RagIngestItem(null, "text", null, null, null, null)))))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
            verifyNoInteractions(documentService);
        }

        @Test
        @DisplayName("refuses an anonymous caller")
        void refusesAnonymousCaller() {
            MedSecurityContext.clear();

            assertThatThrownBy(() -> controller.ingest(new RagIngestRequest(List.of(
                    new RagIngestItem(null, "text", null, null, null, null)))))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
            verifyNoInteractions(documentService);
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
                    new RagIngestItem(null, "   ", null, null, null, null)))))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }

        @Test
        @DisplayName("rejects metadata that collides with an isolation tag")
        void rejectsReservedMetadata() {
            assertThatThrownBy(() -> controller.ingest(new RagIngestRequest(List.of(
                    new RagIngestItem(null, "text", null, null, null,
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
        @DisplayName("deletes the patient scope named by the body without any confirmation")
        void deletesPatientScope() {
            ApiResult<RagDeleteResponse> result = controller.delete(
                    new RagDeleteRequest(null, null, PATIENT, false));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getData().patientScoped()).isTrue();
            assertThat(result.getData().scope()).contains(TENANT).contains(DEPT).contains(PATIENT);
            verify(documentService).deleteByScope(patientScope());
        }

        @Test
        @DisplayName("deletes the department scope only when it has been confirmed")
        void deletesDepartmentScopeWhenConfirmed() {
            ApiResult<RagDeleteResponse> result = controller.delete(
                    new RagDeleteRequest(null, null, null, true));

            assertThat(result.getData().patientScoped()).isFalse();
            verify(documentService).deleteByScope(MedDocumentScope.ofDepartment(TENANT, DEPT));
        }

        @Test
        @DisplayName("refuses an unconfirmed department-wide delete and touches nothing")
        void refusesUnconfirmedDepartmentWideDelete() {
            assertThatThrownBy(() -> controller.delete(new RagDeleteRequest(null, null, null, false)))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
            verifyNoInteractions(documentService);
        }

        @Test
        @DisplayName("refuses a delete that claims another department, even with confirmation")
        void refusesForeignDepartmentClaim() {
            assertThatThrownBy(() -> controller.delete(
                    new RagDeleteRequest(TENANT, "dept-onco", null, true)))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
            verifyNoInteractions(documentService);
        }

        @Test
        @DisplayName("refuses a delete that claims another tenant")
        void refusesForeignTenantClaim() {
            assertThatThrownBy(() -> controller.delete(
                    new RagDeleteRequest("hosp-2", DEPT, null, true)))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
            verifyNoInteractions(documentService);
        }

        @Test
        @DisplayName("refuses a patient principal")
        void refusesPatientPrincipal() {
            MedSecurityContext.setPrincipal(PATIENT_PRINCIPAL);

            assertThatThrownBy(() -> controller.delete(new RagDeleteRequest(null, null, null, true)))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
            verifyNoInteractions(documentService);
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
                    new RagSearchPreviewRequest("what aspirin dose", null, null, PATIENT,
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
        @DisplayName("the search scope is the principal's, not the body's")
        void scopeComesFromThePrincipal() {
            when(retrievalService.search(any(MedRetrievalQuery.class))).thenReturn(List.of());
            ArgumentCaptor<MedRetrievalQuery> captor = ArgumentCaptor.forClass(MedRetrievalQuery.class);

            // No tenant, no dept: only the authenticated principal can have supplied them.
            controller.searchPreview(new RagSearchPreviewRequest("q", null, null, PATIENT,
                    null, null, null));

            verify(retrievalService).search(captor.capture());
            assertThat(captor.getValue().getScope()).isEqualTo(patientScope());
        }

        @Test
        @DisplayName("forwards explicit topK, threshold and shared-toggle to the search service")
        void forwardsSearchParameters() {
            when(retrievalService.search(any(MedRetrievalQuery.class))).thenReturn(List.of());
            ArgumentCaptor<MedRetrievalQuery> captor = ArgumentCaptor.forClass(MedRetrievalQuery.class);

            controller.searchPreview(new RagSearchPreviewRequest("q", TENANT, DEPT, PATIENT,
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

            controller.searchPreview(new RagSearchPreviewRequest("q", null, null, PATIENT,
                    null, null, null));

            verify(retrievalService).search(captor.capture());
            assertThat(captor.getValue().isIncludeSharedDocuments()).isTrue();
        }

        @Test
        @DisplayName("refuses a query that claims another department and never searches")
        void refusesForeignDepartmentClaim() {
            assertThatThrownBy(() -> controller.searchPreview(
                    new RagSearchPreviewRequest("q", TENANT, "dept-onco", null, null, null, null)))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
            verifyNoInteractions(retrievalService);
        }

        @Test
        @DisplayName("rejects a null or blank query")
        void rejectsBlankQuery() {
            assertThatThrownBy(() -> controller.searchPreview(
                    new RagSearchPreviewRequest("  ", null, null, null, null, null, null)))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }

        @Test
        @DisplayName("rejects an out-of-range topK as a bad request, not a server error")
        void rejectsInvalidTopK() {
            assertThatThrownBy(() -> controller.searchPreview(
                    new RagSearchPreviewRequest("q", null, null, PATIENT, 0, null, null)))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }
    }
}
