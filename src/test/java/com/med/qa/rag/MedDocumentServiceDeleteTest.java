package com.med.qa.rag;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests of the document-deletion path (D42).
 *
 * <p>The vector store is mocked; the tests assert that deletion is expressed as the isolation predicate
 * of the scope — including whether the department-wide shared documents take part — and that failures
 * are classified exactly like ingestion failures.</p>
 *
 * <p>The last test of the class is a reflection guard: it pins that
 * {@link MedDocumentService} exposes <strong>no</strong> id-based deletion method. That is the D42 fix
 * itself, and a behavioural test cannot express it — "the method is absent" is the property that has to
 * hold, because an id-based delete cannot be scope-bounded with this index schema (the store keys
 * documents by id, the isolation tags live inside the JSON value, and RediSearch does not index the
 * key).</p>
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
                new MedDocumentIngestionProperties(),
                Clock.fixed(Instant.ofEpochMilli(1_723_000_000_000L), ZoneOffset.UTC));
    }

    @Nested
    @DisplayName("delete by scope")
    class DeleteByScope {

        @Test
        @DisplayName("a patient delete removes that patient's documents and leaves the shared ones alone")
        void deletesPatientScope() {
            service.deleteByScope(PATIENT_SCOPE);

            verify(vectorStore).delete(MedRetrievalFilters.scope(PATIENT_SCOPE, false));
        }

        @Test
        @DisplayName("a department delete removes the department's shared documents as well")
        void deletesDepartmentScope() {
            service.deleteByScope(DEPT_SCOPE);

            verify(vectorStore).delete(MedRetrievalFilters.scope(DEPT_SCOPE, true));
        }

        @Test
        @DisplayName("rejects a null scope without contacting the store")
        void rejectsNullScope() {
            assertThatThrownBy(() -> service.deleteByScope(null))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
            verify(vectorStore, org.mockito.Mockito.never()).delete(any(Filter.Expression.class));
        }
    }

    @Nested
    @DisplayName("failure classification")
    class FailureTranslation {

        @Test
        @DisplayName("an index delete failure surfaces as a storage error")
        void storageError() {
            doThrow(new IllegalStateException("redis down")).when(vectorStore).delete(any(Filter.Expression.class));

            assertThatThrownBy(() -> service.deleteByScope(PATIENT_SCOPE))
                    .isInstanceOf(BizException.class)
                    .hasRootCauseMessage("redis down")
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.STORAGE_ERROR);
        }

        @Test
        @DisplayName("a department delete failure is classified the same way")
        void departmentStorageError() {
            doThrow(new IllegalStateException("redis down")).when(vectorStore).delete(any(Filter.Expression.class));

            assertThatThrownBy(() -> service.deleteByScope(DEPT_SCOPE))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.STORAGE_ERROR);
        }
    }

    @Nested
    @DisplayName("the id-based deletion primitive is gone (D42)")
    class NoIdBasedDeletion {

        @Test
        @DisplayName("deleteByScope is the only public deletion method on the service")
        void deleteByScopeIsTheOnlyDeletionMethod() {
            List<String> deletionMethods = Arrays.stream(MedDocumentService.class.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .map(Method::getName)
                    .filter(name -> name.startsWith("delete"))
                    .toList();

            assertThat(deletionMethods)
                    .as("an id-based delete can never be scope-bounded, so it must not exist")
                    .containsExactly("deleteByScope");
        }

        @Test
        @DisplayName("deleteByScope takes an isolation scope, not identifiers")
        void deleteByScopeTakesAScope() {
            List<Class<?>> parameterTypes = Arrays.stream(MedDocumentService.class.getDeclaredMethods())
                    .filter(method -> "deleteByScope".equals(method.getName()))
                    .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                    .toList();

            assertThat(parameterTypes).containsExactly(MedDocumentScope.class);
        }
    }
}
