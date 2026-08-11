package com.med.qa.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MedRagAdvisorFactory} with the vector store and retrieval service mocked,
 * so the advisor assembly is verified without any middleware.
 */
@DisplayName("MedRagAdvisorFactory")
class MedRagAdvisorFactoryTest {

    private VectorStore vectorStore;

    private MedRetrievalService retrievalService;

    private MedRagAdvisorProperties properties;

    private MedRagAdvisorFactory factory;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        retrievalService = mock(MedRetrievalService.class);
        properties = new MedRagAdvisorProperties();
        when(retrievalService.resolveTopK(isNull())).thenReturn(4);
        when(retrievalService.resolveSimilarityThreshold(isNull())).thenReturn(0.0d);
        factory = new MedRagAdvisorFactory(vectorStore, retrievalService, properties);
    }

    @Nested
    @DisplayName("advisor construction")
    class AdvisorConstruction {

        @Test
        @DisplayName("a patient scope yields a QuestionAnswerAdvisor at the configured order")
        void patientScopeAdvisor() {
            MedDocumentScope scope = MedDocumentScope.ofPatient("tenant-1", "dept-cardio", "patient-9");

            Advisor advisor = factory.createAdvisor(scope);

            assertThat(advisor).isInstanceOf(QuestionAnswerAdvisor.class);
            assertThat(advisor.getOrder()).isEqualTo(1);
        }

        @Test
        @DisplayName("a department-wide scope yields an advisor that includes shared documents")
        void departmentScopeAdvisor() {
            MedDocumentScope scope = MedDocumentScope.ofDepartment("tenant-1", "dept-cardio");

            Advisor advisor = factory.createAdvisor(scope);

            assertThat(advisor).isInstanceOf(QuestionAnswerAdvisor.class);
        }

        @Test
        @DisplayName("a retrieval query reuses the retrieval service to build the request")
        void advisorFromQueryReusesService() {
            MedDocumentScope scope = MedDocumentScope.ofPatient("tenant-1", "dept-cardio", "patient-9");
            MedRetrievalQuery query = MedRetrievalQuery.of("服用阿司匹林的注意事项", scope);
            SearchRequest built = SearchRequest.builder()
                    .query("服用阿司匹林的注意事项")
                    .topK(4)
                    .filterExpression(MedRetrievalFilters.scope(scope, true))
                    .build();
            when(retrievalService.toSearchRequest(query)).thenReturn(built);

            Advisor advisor = factory.createAdvisor(query);

            assertThat(advisor).isInstanceOf(QuestionAnswerAdvisor.class);
            verify(retrievalService).toSearchRequest(query);
        }
    }

    @Nested
    @DisplayName("search request assembly")
    class SearchRequestAssembly {

        @Test
        @DisplayName("the request carries the isolation filter expression and the resolved Top-K")
        void carriesIsolationAndTopK() {
            MedDocumentScope scope = MedDocumentScope.ofPatient("tenant-1", "dept-cardio", "patient-9");

            SearchRequest request = factory.toSearchRequest(scope, true);

            assertThat(request.getFilterExpression())
                    .isEqualTo(MedRetrievalFilters.scope(scope, true));
            assertThat(request.getTopK()).isEqualTo(4);
            assertThat(request.getSimilarityThreshold()).isZero();
            verify(retrievalService).resolveTopK(null);
            verify(retrievalService).resolveSimilarityThreshold(null);
        }

        @Test
        @DisplayName("excluding shared documents narrows the patient predicate")
        void excludesSharedDocuments() {
            MedDocumentScope scope = MedDocumentScope.ofPatient("tenant-1", "dept-cardio", "patient-9");

            SearchRequest request = factory.toSearchRequest(scope, false);

            assertThat(request.getFilterExpression())
                    .isEqualTo(MedRetrievalFilters.scope(scope, false));
        }
    }

    @Nested
    @DisplayName("boundary and error cases")
    class Boundaries {

        @Test
        @DisplayName("a null scope is rejected on every entry point")
        void nullScopeRejected() {
            assertThatThrownBy(() -> factory.createAdvisor((MedDocumentScope) null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> factory.createAdvisor(null, true))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> factory.toSearchRequest(null, true))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a null retrieval query is rejected")
        void nullQueryRejected() {
            assertThatThrownBy(() -> factory.createAdvisor((MedRetrievalQuery) null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a department-wide scope excluding shared documents fails closed")
        void departmentScopeExcludingSharedRejected() {
            MedDocumentScope scope = MedDocumentScope.ofDepartment("tenant-1", "dept-cardio");

            assertThatThrownBy(() -> factory.createAdvisor(scope, false))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> factory.toSearchRequest(scope, false))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
