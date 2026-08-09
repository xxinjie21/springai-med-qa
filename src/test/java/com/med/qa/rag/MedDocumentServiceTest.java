package com.med.qa.rag;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests of the medical document ingestion service.
 *
 * <p>The vector store is mocked: the service must never depend on a running Redis Stack or on a
 * reachable embedding endpoint to be verifiable.</p>
 */
class MedDocumentServiceTest {

    private static final long FIXED_MILLIS = 1_723_000_000_000L;

    private static final MedDocumentScope DEPT_SCOPE =
            MedDocumentScope.ofDepartment("hosp-1", "cardiology");

    private static final MedDocumentScope PATIENT_SCOPE =
            MedDocumentScope.ofPatient("hosp-1", "cardiology", "P-2048");

    private VectorStore vectorStore;

    private MedVectorStoreProperties storeProperties;

    private MedDocumentIngestionProperties ingestionProperties;

    private MedDocumentService service;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        storeProperties = new MedVectorStoreProperties();
        ingestionProperties = new MedDocumentIngestionProperties();
        service = newService();
    }

    private MedDocumentService newService() {
        return new MedDocumentService(vectorStore, storeProperties, ingestionProperties,
                Clock.fixed(Instant.ofEpochMilli(FIXED_MILLIS), ZoneOffset.UTC));
    }

    @SuppressWarnings("unchecked")
    private List<List<Document>> captureBatches(int expectedCalls) {
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(expectedCalls)).add(captor.capture());
        return new ArrayList<>(captor.getAllValues());
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("rejects missing collaborators instead of failing later at ingestion time")
        void rejectsNullCollaborators() {
            Clock clock = Clock.systemUTC();

            assertThatIllegalArgumentException().isThrownBy(() ->
                    new MedDocumentService(null, storeProperties, ingestionProperties, clock));
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new MedDocumentService(vectorStore, null, ingestionProperties, clock));
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new MedDocumentService(vectorStore, storeProperties, null, clock));
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new MedDocumentService(vectorStore, storeProperties, ingestionProperties, null));
        }

        @Test
        @DisplayName("the spring constructor defaults to the system clock")
        void springConstructorUsesSystemClock() {
            MedDocumentService springWired =
                    new MedDocumentService(vectorStore, storeProperties, ingestionProperties);

            Document document = springWired.toDocument(MedDocumentRequest.of("triage", DEPT_SCOPE));

            assertThat((Long) document.getMetadata().get(MedDocumentService.METADATA_INGESTED_AT))
                    .isPositive();
        }
    }

    @Nested
    @DisplayName("document assembly")
    class DocumentAssembly {

        @Test
        @DisplayName("attaches the isolation tags and the ingestion timestamp")
        void attachesScopeTags() {
            Document document = service.toDocument(MedDocumentRequest.of("beta blockers", PATIENT_SCOPE,
                    Map.of("title", "Beta blockers")));

            assertThat(document.getText()).isEqualTo("beta blockers");
            assertThat(document.getMetadata())
                    .containsEntry(MedDocumentScope.METADATA_TENANT_ID, "hosp-1")
                    .containsEntry(MedDocumentScope.METADATA_DEPT_ID, "cardiology")
                    .containsEntry(MedDocumentScope.METADATA_PATIENT_ID, "P-2048")
                    .containsEntry("title", "Beta blockers")
                    .containsEntry(MedDocumentService.METADATA_INGESTED_AT, FIXED_MILLIS);
        }

        @Test
        @DisplayName("tags a department-wide document with the shared sentinel")
        void tagsSharedDocuments() {
            Document document = service.toDocument(MedDocumentRequest.of("triage protocol", DEPT_SCOPE));

            assertThat(document.getMetadata())
                    .containsEntry(MedDocumentScope.METADATA_PATIENT_ID, MedDocumentScope.SHARED_PATIENT_TAG);
        }

        @Test
        @DisplayName("keeps the caller identifier and generates one otherwise")
        void honoursIdentifiers() {
            Document withId = service.toDocument(
                    new MedDocumentRequest("guideline-2024", "text", DEPT_SCOPE, null));
            Document generated = service.toDocument(MedDocumentRequest.of("text", DEPT_SCOPE));

            assertThat(withId.getId()).isEqualTo("guideline-2024");
            assertThat(generated.getId()).isNotBlank().isNotEqualTo("guideline-2024");
        }

        @Test
        @DisplayName("indexes the text verbatim, without splitting or normalizing it")
        void keepsTextVerbatim() {
            String text = "  ST-elevation ≥ 1mm in two contiguous leads.\n\nEscalate to PCI.  ";

            Document document = service.toDocument(MedDocumentRequest.of(text, DEPT_SCOPE));

            assertThat(document.getText()).isEqualTo(text);
        }

        @Test
        @DisplayName("rejects a document longer than the configured limit")
        void rejectsOversizedDocument() {
            ingestionProperties.setMaxContentLength(16);

            assertThatThrownBy(() -> service.toDocument(
                    MedDocumentRequest.of("x".repeat(17), DEPT_SCOPE)))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
            verifyNoInteractions(vectorStore);
        }

        @Test
        @DisplayName("accepts a document exactly at the limit")
        void acceptsDocumentAtTheLimit() {
            ingestionProperties.setMaxContentLength(16);

            assertThat(service.toDocument(MedDocumentRequest.of("x".repeat(16), DEPT_SCOPE)).getText())
                    .hasSize(16);
        }

        @Test
        @DisplayName("rejects metadata colliding with the content or embedding json field")
        void rejectsReservedIndexFields() {
            MedDocumentRequest clashingContent = MedDocumentRequest.of("text", DEPT_SCOPE,
                    Map.of(storeProperties.getContentFieldName(), "shadow"));
            MedDocumentRequest clashingEmbedding = MedDocumentRequest.of("text", DEPT_SCOPE,
                    Map.of(storeProperties.getEmbeddingFieldName(), "shadow"));

            assertThatThrownBy(() -> service.toDocument(clashingContent))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining(storeProperties.getContentFieldName());
            assertThatThrownBy(() -> service.toDocument(clashingEmbedding))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining(storeProperties.getEmbeddingFieldName());
        }

        @Test
        @DisplayName("rejects a null request")
        void rejectsNullRequest() {
            assertThatIllegalArgumentException().isThrownBy(() -> service.toDocument(null));
        }
    }

    @Nested
    @DisplayName("single document ingestion")
    class SingleIngestion {

        @Test
        @DisplayName("writes the document once and returns its identifier")
        void writesSingleDocument() {
            String id = service.ingest(new MedDocumentRequest("guideline-2024", "text", DEPT_SCOPE, null));

            assertThat(id).isEqualTo("guideline-2024");
            List<List<Document>> batches = captureBatches(1);
            assertThat(batches).singleElement().satisfies(batch ->
                    assertThat(batch).singleElement()
                            .extracting(Document::getId).isEqualTo("guideline-2024"));
        }

        @Test
        @DisplayName("rejects a null request without touching the store")
        void rejectsNullRequest() {
            assertThatIllegalArgumentException().isThrownBy(() -> service.ingest(null));
            verifyNoInteractions(vectorStore);
        }
    }

    @Nested
    @DisplayName("bulk ingestion")
    class BulkIngestion {

        @Test
        @DisplayName("splits the corpus into batches of the configured size, in submission order")
        void splitsIntoBatches() {
            ingestionProperties.setBatchSize(2);
            service = newService();
            List<MedDocumentRequest> requests = List.of(
                    new MedDocumentRequest("d1", "a", DEPT_SCOPE, null),
                    new MedDocumentRequest("d2", "b", DEPT_SCOPE, null),
                    new MedDocumentRequest("d3", "c", DEPT_SCOPE, null),
                    new MedDocumentRequest("d4", "d", DEPT_SCOPE, null),
                    new MedDocumentRequest("d5", "e", PATIENT_SCOPE, null));

            List<String> ids = service.ingestAll(requests);

            assertThat(ids).containsExactly("d1", "d2", "d3", "d4", "d5");
            List<List<Document>> batches = captureBatches(3);
            assertThat(batches).extracting(List::size).containsExactly(2, 2, 1);
            assertThat(batches.get(2)).singleElement()
                    .extracting(document -> document.getMetadata().get(MedDocumentScope.METADATA_PATIENT_ID))
                    .isEqualTo("P-2048");
        }

        @Test
        @DisplayName("writes a single batch when the corpus fits")
        void writesSingleBatch() {
            List<String> ids = service.ingestAll(List.of(
                    new MedDocumentRequest("d1", "a", DEPT_SCOPE, null),
                    new MedDocumentRequest("d2", "b", DEPT_SCOPE, null)));

            assertThat(ids).containsExactly("d1", "d2");
            assertThat(captureBatches(1)).singleElement().satisfies(batch ->
                    assertThat(batch).hasSize(2));
        }

        @Test
        @DisplayName("an empty corpus never contacts the store, so no index is created for nothing")
        void emptyCorpusIsNoOp() {
            assertThat(service.ingestAll(List.of())).isEmpty();

            verifyNoInteractions(vectorStore);
        }

        @Test
        @DisplayName("rejects a null list or a null entry")
        void rejectsNullInput() {
            assertThatIllegalArgumentException().isThrownBy(() -> service.ingestAll(null));

            List<MedDocumentRequest> withNull = new ArrayList<>();
            withNull.add(MedDocumentRequest.of("a", DEPT_SCOPE));
            withNull.add(null);
            assertThatIllegalArgumentException().isThrownBy(() -> service.ingestAll(withNull));
            verifyNoInteractions(vectorStore);
        }

        @Test
        @DisplayName("rejects a call carrying more documents than allowed")
        void rejectsOversizedCall() {
            ingestionProperties.setMaxDocumentsPerRequest(2);
            service = newService();
            List<MedDocumentRequest> requests = List.of(
                    MedDocumentRequest.of("a", DEPT_SCOPE),
                    MedDocumentRequest.of("b", DEPT_SCOPE),
                    MedDocumentRequest.of("c", DEPT_SCOPE));

            assertThatThrownBy(() -> service.ingestAll(requests))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
            verifyNoInteractions(vectorStore);
        }

        @Test
        @DisplayName("validates the whole corpus before writing anything")
        void validatesBeforeWriting() {
            ingestionProperties.setMaxContentLength(4);
            service = newService();
            List<MedDocumentRequest> requests = List.of(
                    MedDocumentRequest.of("ok", DEPT_SCOPE),
                    MedDocumentRequest.of("far too long", DEPT_SCOPE));

            assertThatThrownBy(() -> service.ingestAll(requests)).isInstanceOf(BizException.class);
            verifyNoInteractions(vectorStore);
        }
    }

    @Nested
    @DisplayName("failure translation")
    class FailureTranslation {

        @Test
        @DisplayName("an index write failure surfaces as a storage error and is never swallowed")
        void indexFailureIsStorageError() {
            doThrow(new IllegalStateException("redis down")).when(vectorStore).add(anyList());

            assertThatThrownBy(() -> service.ingest(MedDocumentRequest.of("a", DEPT_SCOPE)))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("med-doc-index")
                    .hasRootCauseMessage("redis down")
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.STORAGE_ERROR);
        }

        @Test
        @DisplayName("an embedding endpoint failure surfaces as an llm error")
        void embeddingFailureIsLlmError() {
            doThrow(new NonTransientAiException("invalid api key")).when(vectorStore).add(anyList());

            assertThatThrownBy(() -> service.ingest(MedDocumentRequest.of("a", DEPT_SCOPE)))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.LLM_SERVICE_ERROR);
        }

        @Test
        @DisplayName("a business exception raised by the store is propagated unchanged")
        void businessExceptionIsPropagated() {
            BizException original = new BizException(ErrorCode.RATE_LIMITED, "slow down");
            doThrow(original).when(vectorStore).add(anyList());

            assertThatThrownBy(() -> service.ingest(MedDocumentRequest.of("a", DEPT_SCOPE)))
                    .isSameAs(original);
        }

        @Test
        @DisplayName("reports the offset of the batch that failed")
        void reportsFailingBatchOffset() {
            ingestionProperties.setBatchSize(1);
            service = newService();
            doThrow(new IllegalStateException("redis down")).when(vectorStore).add(anyList());

            assertThatThrownBy(() -> service.ingestAll(List.of(MedDocumentRequest.of("a", DEPT_SCOPE))))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("offset 0");
        }

        @Test
        @DisplayName("classifies nested ai exceptions and defaults to storage failures")
        void classifiesCauseChain() {
            assertThat(MedDocumentService.classifyFailure(
                    new IllegalStateException(new TransientAiException("429"))))
                    .isEqualTo(ErrorCode.LLM_SERVICE_ERROR);
            assertThat(MedDocumentService.classifyFailure(new RuntimeException("boom")))
                    .isEqualTo(ErrorCode.STORAGE_ERROR);
            assertThat(MedDocumentService.classifyFailure(null)).isEqualTo(ErrorCode.STORAGE_ERROR);
        }

        @Test
        @DisplayName("survives a self referencing cause chain")
        void survivesCyclicCauseChain() {
            RuntimeException cyclic = new RuntimeException("loop") {
                @Override
                public synchronized Throwable getCause() {
                    return this;
                }
            };

            assertThat(MedDocumentService.classifyFailure(cyclic)).isEqualTo(ErrorCode.STORAGE_ERROR);
        }
    }
}
