package com.med.qa.rag;

import com.med.qa.config.VectorStoreConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context-level guard tests of the tag-filtered retrieval wiring.
 *
 * <p>The retrieval service is an eager singleton so the RAG advisor of the next iteration can inject
 * it directly, but the vector store it queries opens a Redis connection and creates the search index
 * on first use. These tests pin that wiring the service does not drag the store into the startup
 * path, which is what keeps the whole suite runnable without middleware. They also assert the
 * retrieval guard rails bind from {@code application.yml} and that a {@link SearchRequest} can be
 * assembled offline — the request construction path never touches the store.</p>
 */
@SpringBootTest
@TestPropertySource(properties = "spring.flyway.enabled=false")
class MedRetrievalContextTest {

    @Autowired
    private ConfigurableApplicationContext context;

    @Autowired
    private MedRetrievalService retrievalService;

    @Test
    @DisplayName("the retrieval service is available to the application")
    void serviceIsWired() {
        assertThat(retrievalService).isNotNull();
        assertThat(context.getBeanNamesForType(MedRetrievalService.class)).hasSize(1);
    }

    @Test
    @DisplayName("wiring the service does not instantiate the vector store")
    void vectorStoreStaysUninstantiated() {
        assertThat(context.getBeanFactory().containsSingleton(VectorStoreConfig.VECTOR_STORE)).isFalse();
        assertThat(context.getBeanFactory().containsSingleton(VectorStoreConfig.VECTOR_STORE_JEDIS)).isFalse();
    }

    @Test
    @DisplayName("the retrieval guard rails are bound from application.yml")
    void retrievalPropertiesAreBound() {
        MedRetrievalProperties properties = context.getBean(MedRetrievalProperties.class);

        assertThat(properties.getTopK()).isEqualTo(4);
        assertThat(properties.getMaxTopK()).isEqualTo(50);
        assertThat(properties.getSimilarityThreshold()).isZero();
        assertThat(properties.getMaxQueryLength()).isEqualTo(1_000);
    }

    @Test
    @DisplayName("a search request is assembled offline without instantiating the store")
    void searchRequestAssembledOffline() {
        SearchRequest request = retrievalService.toSearchRequest(
                MedRetrievalQuery.of("chest pain management",
                        MedDocumentScope.ofPatient("hosp-1", "cardiology", "patient-9")));

        assertThat(request.getQuery()).isEqualTo("chest pain management");
        assertThat(request.getTopK()).isEqualTo(4);
        assertThat(request.getSimilarityThreshold()).isZero();
        assertThat(request.getFilterExpression()).isNotNull();
        assertThat(context.getBeanFactory().containsSingleton(VectorStoreConfig.VECTOR_STORE)).isFalse();
    }

    @Test
    @DisplayName("the assembled filter enforces the isolation scope of the query")
    void assembledFilterEnforcesScope() {
        MedDocumentScope scope = MedDocumentScope.ofDepartment("hosp-7", "oncology");
        SearchRequest request = retrievalService.toSearchRequest(MedRetrievalQuery.of("dosing schedule", scope));

        assertThat(MedRetrievalFilters.matches(
                Map.of(
                        MedDocumentScope.METADATA_TENANT_ID, "hosp-7",
                        MedDocumentScope.METADATA_DEPT_ID, "oncology",
                        MedDocumentScope.METADATA_PATIENT_ID, MedDocumentScope.SHARED_PATIENT_TAG),
                scope, true)).isTrue();
        assertThat(request.getFilterExpression()).isNotNull();
        assertThat(context.getBeanFactory().containsSingleton(VectorStoreConfig.VECTOR_STORE)).isFalse();
    }
}
