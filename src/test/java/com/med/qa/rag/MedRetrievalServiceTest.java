package com.med.qa.rag;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the tag-filtered similarity search wrapper.
 *
 * <p>The vector store is mocked throughout: the point of these tests is the request the service
 * builds, the guard rails it enforces and the way it classifies failures — the similarity search
 * itself belongs to Spring AI and needs no test here. No Redis, no embedding endpoint, no
 * Testcontainers.</p>
 */
@ExtendWith(MockitoExtension.class)
class MedRetrievalServiceTest {

    private static final String TENANT = "hosp1";

    private static final String DEPT = "cardio";

    private static final String PATIENT = "p9001";

    private static final MedDocumentScope PATIENT_SCOPE =
            MedDocumentScope.ofPatient(TENANT, DEPT, PATIENT);

    private static final MedDocumentScope DEPARTMENT_SCOPE =
            MedDocumentScope.ofDepartment(TENANT, DEPT);

    @Mock
    private VectorStore vectorStore;

    private MedRetrievalProperties properties;

    private MedRetrievalService service;

    @BeforeEach
    void setUp() {
        properties = new MedRetrievalProperties();
        service = new MedRetrievalService(vectorStore, properties);
    }

    private static Document document(String id, String tenant, String dept, String patient) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(MedDocumentScope.METADATA_TENANT_ID, tenant);
        metadata.put(MedDocumentScope.METADATA_DEPT_ID, dept);
        metadata.put(MedDocumentScope.METADATA_PATIENT_ID, patient);
        return Document.builder().id(id).text("clinical text of " + id).metadata(metadata).build();
    }

    private SearchRequest captureRequest() {
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("a consistent configuration wires the service")
        void validConfigurationIsAccepted() {
            assertThat(new MedRetrievalService(vectorStore, new MedRetrievalProperties())).isNotNull();
        }

        @Test
        @DisplayName("a null vector store is a programming error")
        void nullVectorStoreIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MedRetrievalService(null, new MedRetrievalProperties()))
                    .withMessageContaining("vectorStore must not be null");
        }

        @Test
        @DisplayName("null properties are a programming error")
        void nullPropertiesAreRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MedRetrievalService(vectorStore, null))
                    .withMessageContaining("properties must not be null");
        }

        @Test
        @DisplayName("a default top-k above the maximum fails fast instead of at the first query")
        void inconsistentConfigurationIsRejected() {
            MedRetrievalProperties inconsistent = new MedRetrievalProperties();
            inconsistent.setMaxTopK(3);
            inconsistent.setTopK(10);

            assertThatIllegalStateException()
                    .isThrownBy(() -> new MedRetrievalService(vectorStore, inconsistent))
                    .withMessageContaining("must not exceed");
        }
    }

    @Nested
    @DisplayName("resolveTopK")
    class ResolveTopK {

        @Test
        @DisplayName("no preference falls back to the configured default")
        void nullFallsBackToDefault() {
            assertThat(service.resolveTopK(null)).isEqualTo(properties.getTopK());
        }

        @Test
        @DisplayName("a preference within the ceiling is honoured")
        void preferenceIsHonoured() {
            assertThat(service.resolveTopK(20)).isEqualTo(20);
            assertThat(service.resolveTopK(properties.getMaxTopK())).isEqualTo(properties.getMaxTopK());
        }

        @Test
        @DisplayName("a preference above the ceiling is a bad request, not a silent clamp")
        void preferenceAboveCeilingIsRejected() {
            assertThatExceptionOfType(BizException.class)
                    .isThrownBy(() -> service.resolveTopK(properties.getMaxTopK() + 1))
                    .satisfies(ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST))
                    .withMessageContaining("must not exceed");
        }

        @Test
        @DisplayName("a non-positive preference is a bad request")
        void nonPositivePreferenceIsRejected() {
            assertThatExceptionOfType(BizException.class)
                    .isThrownBy(() -> service.resolveTopK(0))
                    .satisfies(ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        }
    }

    @Nested
    @DisplayName("resolveSimilarityThreshold")
    class ResolveSimilarityThreshold {

        @Test
        @DisplayName("no preference falls back to the configured default")
        void nullFallsBackToDefault() {
            properties.setSimilarityThreshold(0.25d);

            assertThat(service.resolveSimilarityThreshold(null)).isEqualTo(0.25d);
        }

        @Test
        @DisplayName("a preference inside the unit interval is honoured")
        void preferenceIsHonoured() {
            assertThat(service.resolveSimilarityThreshold(0.8d)).isEqualTo(0.8d);
            assertThat(service.resolveSimilarityThreshold(0.0d)).isEqualTo(0.0d);
            assertThat(service.resolveSimilarityThreshold(1.0d)).isEqualTo(1.0d);
        }

        @Test
        @DisplayName("a preference outside the unit interval is a bad request")
        void outOfRangePreferenceIsRejected() {
            assertThatExceptionOfType(BizException.class)
                    .isThrownBy(() -> service.resolveSimilarityThreshold(1.2d))
                    .satisfies(ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
            assertThatExceptionOfType(BizException.class)
                    .isThrownBy(() -> service.resolveSimilarityThreshold(Double.NaN))
                    .satisfies(ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        }
    }

    @Nested
    @DisplayName("toFilterExpression")
    class ToFilterExpression {

        @Test
        @DisplayName("a plain query yields the isolation filter of its scope")
        void plainQueryYieldsIsolationFilter() {
            MedRetrievalQuery query = MedRetrievalQuery.of("chest pain", PATIENT_SCOPE);

            assertThat(service.toFilterExpression(query))
                    .isEqualTo(MedRetrievalFilters.scope(PATIENT_SCOPE, true));
        }

        @Test
        @DisplayName("an extra filter is conjoined with the isolation filter, never replacing it")
        void extraFilterIsConjoined() {
            Filter.Expression extra = new FilterExpressionBuilder().eq("doc_type", "guideline").build();
            MedRetrievalQuery query = MedRetrievalQuery.builder("chest pain", PATIENT_SCOPE)
                    .additionalFilter(extra)
                    .build();

            Filter.Expression expression = service.toFilterExpression(query);

            assertThat(expression.type()).isEqualTo(Filter.ExpressionType.AND);
            assertThat(expression.left()).isEqualTo(MedRetrievalFilters.scope(PATIENT_SCOPE, true));
            assertThat(expression.right()).isEqualTo(extra);
        }

        @Test
        @DisplayName("a null query is a programming error")
        void nullQueryIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.toFilterExpression(null))
                    .withMessageContaining("query must not be null");
        }
    }

    @Nested
    @DisplayName("toSearchRequest")
    class ToSearchRequest {

        @Test
        @DisplayName("the request carries the query text, the defaults and the isolation filter")
        void requestCarriesDefaults() {
            SearchRequest request = service.toSearchRequest(
                    MedRetrievalQuery.of("chest pain follow-up", PATIENT_SCOPE));

            assertThat(request.getQuery()).isEqualTo("chest pain follow-up");
            assertThat(request.getTopK()).isEqualTo(properties.getTopK());
            assertThat(request.getSimilarityThreshold()).isEqualTo(properties.getSimilarityThreshold());
            assertThat(request.hasFilterExpression()).isTrue();
            assertThat(request.getFilterExpression())
                    .isEqualTo(MedRetrievalFilters.scope(PATIENT_SCOPE, true));
        }

        @Test
        @DisplayName("explicit knobs win over the configured defaults")
        void explicitKnobsWin() {
            SearchRequest request = service.toSearchRequest(
                    MedRetrievalQuery.builder("chest pain", PATIENT_SCOPE)
                            .topK(11)
                            .similarityThreshold(0.6d)
                            .build());

            assertThat(request.getTopK()).isEqualTo(11);
            assertThat(request.getSimilarityThreshold()).isEqualTo(0.6d);
        }

        @Test
        @DisplayName("a department query is restricted to shared documents")
        void departmentQueryIsSharedOnly() {
            SearchRequest request = service.toSearchRequest(
                    MedRetrievalQuery.of("triage protocol", DEPARTMENT_SCOPE));

            assertThat(request.getFilterExpression())
                    .isEqualTo(MedRetrievalFilters.scope(DEPARTMENT_SCOPE, true));
        }

        @Test
        @DisplayName("a query longer than the configured limit is a bad request")
        void oversizedQueryIsRejected() {
            properties.setMaxQueryLength(10);
            MedRetrievalQuery query = MedRetrievalQuery.of("a".repeat(11), PATIENT_SCOPE);

            assertThatExceptionOfType(BizException.class)
                    .isThrownBy(() -> service.toSearchRequest(query))
                    .satisfies(ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST))
                    .withMessageContaining("must not exceed 10 characters");
        }

        @Test
        @DisplayName("a query exactly at the limit is accepted")
        void queryAtLimitIsAccepted() {
            properties.setMaxQueryLength(10);

            SearchRequest request = service.toSearchRequest(
                    MedRetrievalQuery.of("a".repeat(10), PATIENT_SCOPE));

            assertThat(request.getQuery()).hasSize(10);
        }

        @Test
        @DisplayName("a null query is a programming error")
        void nullQueryIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.toSearchRequest(null))
                    .withMessageContaining("query must not be null");
        }

        @Test
        @DisplayName("assembling a request never touches the vector store")
        void assemblyDoesNotTouchTheStore() {
            service.toSearchRequest(MedRetrievalQuery.of("chest pain", PATIENT_SCOPE));

            verifyNoInteractions(vectorStore);
        }
    }

    @Nested
    @DisplayName("search")
    class Search {

        @Test
        @DisplayName("in-scope documents are returned in the order the store ranked them")
        void inScopeDocumentsAreReturned() {
            List<Document> hits = List.of(
                    document("d1", TENANT, DEPT, PATIENT),
                    document("d2", TENANT, DEPT, MedDocumentScope.SHARED_PATIENT_TAG));
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(hits);

            List<Document> result = service.search(MedRetrievalQuery.of("chest pain", PATIENT_SCOPE));

            assertThat(result).containsExactlyElementsOf(hits);
        }

        @Test
        @DisplayName("the convenience overload builds a default query for the scope")
        void convenienceOverloadUsesDefaults() {
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

            List<Document> result = service.search("triage protocol", DEPARTMENT_SCOPE);

            assertThat(result).isEmpty();
            SearchRequest request = captureRequest();
            assertThat(request.getQuery()).isEqualTo("triage protocol");
            assertThat(request.getTopK()).isEqualTo(properties.getTopK());
            assertThat(request.getFilterExpression())
                    .isEqualTo(MedRetrievalFilters.scope(DEPARTMENT_SCOPE, true));
        }

        @Test
        @DisplayName("the convenience overload rejects a blank question")
        void convenienceOverloadRejectsBlankText() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.search("  ", DEPARTMENT_SCOPE))
                    .withMessageContaining("must not be blank");
            verifyNoInteractions(vectorStore);
        }

        @Test
        @DisplayName("an empty corpus yields an empty result, not an error")
        void emptyResultIsNotAnError() {
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

            assertThat(service.search(MedRetrievalQuery.of("chest pain", PATIENT_SCOPE))).isEmpty();
        }

        @Test
        @DisplayName("a store returning null is treated as an empty result")
        void nullResultIsTreatedAsEmpty() {
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(null);

            assertThat(service.search(MedRetrievalQuery.of("chest pain", PATIENT_SCOPE))).isEmpty();
        }

        @Test
        @DisplayName("a document from another patient aborts the retrieval instead of reaching a prompt")
        void foreignPatientDocumentAbortsRetrieval() {
            when(vectorStore.similaritySearch(any(SearchRequest.class)))
                    .thenReturn(List.of(document("leak", TENANT, DEPT, "p9002")));
            MedRetrievalQuery query = MedRetrievalQuery.of("chest pain", PATIENT_SCOPE);

            assertThatExceptionOfType(BizException.class)
                    .isThrownBy(() -> service.search(query))
                    .satisfies(ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR))
                    .withMessageContaining("outside the requested scope");
        }

        @Test
        @DisplayName("a document from another department aborts the retrieval")
        void foreignDepartmentDocumentAbortsRetrieval() {
            when(vectorStore.similaritySearch(any(SearchRequest.class)))
                    .thenReturn(List.of(document("leak", TENANT, "neuro",
                            MedDocumentScope.SHARED_PATIENT_TAG)));
            MedRetrievalQuery query = MedRetrievalQuery.of("triage protocol", DEPARTMENT_SCOPE);

            assertThatExceptionOfType(BizException.class)
                    .isThrownBy(() -> service.search(query))
                    .satisfies(ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        }

        @Test
        @DisplayName("an untagged document aborts the retrieval: verification fails closed")
        void untaggedDocumentAbortsRetrieval() {
            when(vectorStore.similaritySearch(any(SearchRequest.class)))
                    .thenReturn(List.of(Document.builder().id("orphan").text("no tags").build()));
            MedRetrievalQuery query = MedRetrievalQuery.of("chest pain", PATIENT_SCOPE);

            assertThatExceptionOfType(BizException.class)
                    .isThrownBy(() -> service.search(query))
                    .satisfies(ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        }

        @Test
        @DisplayName("a shared document is rejected when the caller excluded shared documents")
        void sharedDocumentAbortsNarrowedRetrieval() {
            when(vectorStore.similaritySearch(any(SearchRequest.class)))
                    .thenReturn(List.of(document("guideline", TENANT, DEPT,
                            MedDocumentScope.SHARED_PATIENT_TAG)));
            MedRetrievalQuery query = MedRetrievalQuery.builder("chart summary", PATIENT_SCOPE)
                    .includeSharedDocuments(false)
                    .build();

            assertThatExceptionOfType(BizException.class)
                    .isThrownBy(() -> service.search(query))
                    .satisfies(ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        }

        @Test
        @DisplayName("an embedding failure surfaces as an llm service error")
        void embeddingFailureIsClassified() {
            when(vectorStore.similaritySearch(any(SearchRequest.class)))
                    .thenThrow(new TransientAiException("embedding endpoint timed out"));
            MedRetrievalQuery query = MedRetrievalQuery.of("chest pain", PATIENT_SCOPE);

            assertThatExceptionOfType(BizException.class)
                    .isThrownBy(() -> service.search(query))
                    .satisfies(ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.LLM_SERVICE_ERROR));
        }

        @Test
        @DisplayName("a wrapped non-transient ai failure is still an llm service error")
        void wrappedAiFailureIsClassified() {
            when(vectorStore.similaritySearch(any(SearchRequest.class)))
                    .thenThrow(new IllegalStateException("search failed",
                            new NonTransientAiException("invalid api key")));
            MedRetrievalQuery query = MedRetrievalQuery.of("chest pain", PATIENT_SCOPE);

            assertThatExceptionOfType(BizException.class)
                    .isThrownBy(() -> service.search(query))
                    .satisfies(ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.LLM_SERVICE_ERROR));
        }

        @Test
        @DisplayName("an index failure surfaces as a storage error")
        void indexFailureIsClassified() {
            when(vectorStore.similaritySearch(any(SearchRequest.class)))
                    .thenThrow(new IllegalStateException("JedisConnectionException"));
            MedRetrievalQuery query = MedRetrievalQuery.of("chest pain", PATIENT_SCOPE);

            assertThatExceptionOfType(BizException.class)
                    .isThrownBy(() -> service.search(query))
                    .satisfies(ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.STORAGE_ERROR))
                    .withMessageContaining("failed to retrieve documents");
        }

        @Test
        @DisplayName("a business exception raised by the store is propagated unchanged")
        void bizExceptionIsPropagated() {
            BizException original = new BizException(ErrorCode.BAD_REQUEST, "index not initialized");
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenThrow(original);
            MedRetrievalQuery query = MedRetrievalQuery.of("chest pain", PATIENT_SCOPE);

            assertThatExceptionOfType(BizException.class)
                    .isThrownBy(() -> service.search(query))
                    .isSameAs(original);
        }

        @Test
        @DisplayName("a policy violation is caught before the store is contacted")
        void policyViolationShortCircuits() {
            properties.setMaxQueryLength(5);
            MedRetrievalQuery query = MedRetrievalQuery.of("a".repeat(6), PATIENT_SCOPE);

            assertThatExceptionOfType(BizException.class).isThrownBy(() -> service.search(query));
            verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
        }

        @Test
        @DisplayName("a null query is a programming error")
        void nullQueryIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.search((MedRetrievalQuery) null))
                    .withMessageContaining("query must not be null");
            verifyNoInteractions(vectorStore);
        }
    }
}
