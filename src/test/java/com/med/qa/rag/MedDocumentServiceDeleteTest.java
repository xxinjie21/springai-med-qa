package com.med.qa.rag;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests of the document-deletion methods added alongside the RAG admin controller.
 *
 * <p>The vector store is mocked; the tests assert that the right delete call is issued with the
 * right argument (ids vs isolation filter) and that failures are classified exactly like ingestion
 * failures.</p>
 */
class MedDocumentServiceDeleteTest {

    private static final MedDocumentScope DEPT_SCOPE = MedDocumentScope.ofDepartment("hosp-1", "cardiology");

    private static final MedDocumentScope PATIENT_SCOPE =
            MedDocumentScope.ofPatient("hosp-1", "cardiology", "P-2048");

    private VectorStore vectorStore;

    private MedDocumentService service;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        service = new MedDocumentService(vectorStore, new MedVectorStoreProperties(),
                new MedDocumentIngestionProperties(), Clock.fixed(Instant.ofEpochMilli(1_723_000_000_000L), ZoneOffset.UTC));
    }

    @Nested
    @DisplayName("delete by identifier")
    class DeleteById {

        @Test
        @DisplayName("removes the single document by id")
        void deletesSingle() {
            service.deleteById("doc-42");

            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(vectorStore).delete(captor.capture());
            assertThat(captor.getValue()).containsExactly("doc-42");
        }

        @Test
        @DisplayName("rejects a blank id")
        void rejectsBlankId() {
            assertThatThrownBy(() -> service.deleteById("  "))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }

        @Test
        @DisplayName("removes a batch of documents by id")
        void deletesBatch() {
            service.deleteByIds(List.of("a", "b", "c"));

            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(vectorStore).delete(captor.capture());
            assertThat(captor.getValue()).containsExactly("a", "b", "c");
        }

        @Test
        @DisplayName("rejects an empty / null id list")
        void rejectsEmptyList() {
            assertThatThrownBy(() -> service.deleteByIds(List.of()))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
            assertThatThrownBy(() -> service.deleteByIds(null))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }

        @Test
        @DisplayName("rejects a list carrying a blank id")
        void rejectsBlankEntry() {
            assertThatThrownBy(() -> service.deleteByIds(List.of("a", "  ")))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("delete by scope")
    class DeleteByScope {

        @Test
        @DisplayName("removes every document of a patient scope via an isolation filter")
        void deletesPatientScope() {
            service.deleteByScope(PATIENT_SCOPE);

            ArgumentCaptor<Filter.Expression> captor = ArgumentCaptor.forClass(Filter.Expression.class);
            verify(vectorStore).delete(captor.capture());
            assertThat(captor.getValue()).isNotNull();
        }

        @Test
        @DisplayName("removes every document of a department scope via an isolation filter")
        void deletesDepartmentScope() {
            service.deleteByScope(DEPT_SCOPE);

            ArgumentCaptor<Filter.Expression> captor = ArgumentCaptor.forClass(Filter.Expression.class);
            verify(vectorStore).delete(captor.capture());
            assertThat(captor.getValue()).isNotNull();
        }

        @Test
        @DisplayName("rejects a null scope")
        void rejectsNullScope() {
            assertThatThrownBy(() -> service.deleteByScope(null))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("failure classification")
    class FailureTranslation {

        @Test
        @DisplayName("an index delete failure surfaces as a storage error")
        void storageError() {
            doThrow(new IllegalStateException("redis down")).when(vectorStore).delete(anyList());

            assertThatThrownBy(() -> service.deleteByIds(List.of("a")))
                    .isInstanceOf(BizException.class)
                    .hasRootCauseMessage("redis down")
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.STORAGE_ERROR);
        }

        @Test
        @DisplayName("an embedding endpoint failure surfaces as an llm error")
        void llmError() {
            doThrow(new NonTransientAiException("invalid api key")).when(vectorStore).delete(anyList());

            assertThatThrownBy(() -> service.deleteByIds(List.of("a")))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.LLM_SERVICE_ERROR);
        }

        @Test
        @DisplayName("a scope delete failure also surfaces as a storage error")
        void scopeStorageError() {
            doThrow(new IllegalStateException("redis down")).when(vectorStore).delete(any(Filter.Expression.class));

            assertThatThrownBy(() -> service.deleteByScope(PATIENT_SCOPE))
                    .isInstanceOf(BizException.class)
                    .hasRootCauseMessage("redis down")
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.STORAGE_ERROR);
        }
    }
}
