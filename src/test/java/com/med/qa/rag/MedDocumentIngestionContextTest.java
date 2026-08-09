package com.med.qa.rag;

import com.med.qa.config.VectorStoreConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context-level guard tests of the document ingestion wiring.
 *
 * <p>The service is an eager singleton so it can be injected by the RAG admin endpoints, but the
 * vector store it writes to opens a Redis connection and creates the search index on first use.
 * These tests pin that wiring the service does not drag the store into the startup path, which is
 * what keeps the whole suite runnable without middleware.</p>
 */
@SpringBootTest
@TestPropertySource(properties = "spring.flyway.enabled=false")
class MedDocumentIngestionContextTest {

    @Autowired
    private ConfigurableApplicationContext context;

    @Autowired
    private MedDocumentService documentService;

    @Test
    @DisplayName("the ingestion service is available to the application")
    void serviceIsWired() {
        assertThat(documentService).isNotNull();
        assertThat(context.getBeanNamesForType(MedDocumentService.class)).hasSize(1);
    }

    @Test
    @DisplayName("wiring the service does not instantiate the vector store")
    void vectorStoreStaysUninstantiated() {
        assertThat(context.getBeanFactory().containsSingleton(VectorStoreConfig.VECTOR_STORE)).isFalse();
        assertThat(context.getBeanFactory().containsSingleton(VectorStoreConfig.VECTOR_STORE_JEDIS)).isFalse();
    }

    @Test
    @DisplayName("documents can be assembled offline, without embedding or Redis")
    void documentsAreAssembledOffline() {
        var document = documentService.toDocument(MedDocumentRequest.of("triage protocol",
                MedDocumentScope.ofDepartment("hosp-1", "cardiology")));

        assertThat(document.getText()).isEqualTo("triage protocol");
        assertThat(document.getMetadata())
                .containsEntry(MedDocumentScope.METADATA_TENANT_ID, "hosp-1")
                .containsEntry(MedDocumentScope.METADATA_DEPT_ID, "cardiology")
                .containsEntry(MedDocumentScope.METADATA_PATIENT_ID, MedDocumentScope.SHARED_PATIENT_TAG);
        assertThat(context.getBeanFactory().containsSingleton(VectorStoreConfig.VECTOR_STORE)).isFalse();
    }

    @Test
    @DisplayName("the ingestion guard rails are bound from application.yml")
    void ingestionPropertiesAreBound() {
        MedDocumentIngestionProperties properties = context.getBean(MedDocumentIngestionProperties.class);

        assertThat(properties.getBatchSize()).isEqualTo(25);
        assertThat(properties.getMaxContentLength()).isEqualTo(20_000);
        assertThat(properties.getMaxDocumentsPerRequest()).isEqualTo(500);
    }

    @Test
    @DisplayName("the indexed tag fields match the tags the ingestion service writes")
    void indexedTagsMatchIngestedTags() {
        MedVectorStoreProperties storeProperties = context.getBean(MedVectorStoreProperties.class);

        assertThat(storeProperties.getMetadataFields())
                .extracting(MedVectorStoreProperties.MetadataFieldSpec::getName)
                .containsAll(MedDocumentScope.RESERVED_METADATA_KEYS);
    }
}
