package com.med.qa.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests of the isolation scope carried by every indexed medical document.
 */
class MedDocumentScopeTest {

    @Nested
    @DisplayName("department-wide scope")
    class DepartmentScope {

        @Test
        @DisplayName("carries the shared patient sentinel so it stays reachable by patient queries")
        void departmentScopeUsesSharedSentinel() {
            MedDocumentScope scope = MedDocumentScope.ofDepartment("hosp-1", "cardiology");

            assertThat(scope.getTenantId()).isEqualTo("hosp-1");
            assertThat(scope.getDeptId()).isEqualTo("cardiology");
            assertThat(scope.getPatientId()).isNull();
            assertThat(scope.isPatientScoped()).isFalse();
            assertThat(scope.getPatientTag()).isEqualTo(MedDocumentScope.SHARED_PATIENT_TAG);
        }

        @Test
        @DisplayName("renders the three indexed tags in specification order")
        void rendersMetadataTags() {
            Map<String, Object> metadata = MedDocumentScope.ofDepartment("hosp-1", "cardiology").toMetadata();

            assertThat(metadata).containsExactly(
                    Map.entry(MedDocumentScope.METADATA_TENANT_ID, "hosp-1"),
                    Map.entry(MedDocumentScope.METADATA_DEPT_ID, "cardiology"),
                    Map.entry(MedDocumentScope.METADATA_PATIENT_ID, MedDocumentScope.SHARED_PATIENT_TAG));
        }

        @Test
        @DisplayName("returns a fresh mutable map so callers cannot corrupt the scope")
        void metadataIsDefensiveCopy() {
            MedDocumentScope scope = MedDocumentScope.ofDepartment("hosp-1", "cardiology");

            Map<String, Object> first = scope.toMetadata();
            first.put(MedDocumentScope.METADATA_DEPT_ID, "oncology");

            assertThat(scope.toMetadata()).containsEntry(MedDocumentScope.METADATA_DEPT_ID, "cardiology");
        }

        @Test
        @DisplayName("rejects a blank tenant or department")
        void rejectsBlankTags() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MedDocumentScope.ofDepartment("  ", "cardiology"))
                    .withMessageContaining(MedDocumentScope.METADATA_TENANT_ID);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MedDocumentScope.ofDepartment("hosp-1", null))
                    .withMessageContaining(MedDocumentScope.METADATA_DEPT_ID);
        }
    }

    @Nested
    @DisplayName("patient-scoped documents")
    class PatientScope {

        @Test
        @DisplayName("tags the document with the patient identifier")
        void patientScopeKeepsIdentifier() {
            MedDocumentScope scope = MedDocumentScope.ofPatient("hosp-1", "cardiology", "P-2048");

            assertThat(scope.isPatientScoped()).isTrue();
            assertThat(scope.getPatientId()).isEqualTo("P-2048");
            assertThat(scope.getPatientTag()).isEqualTo("P-2048");
            assertThat(scope.toMetadata())
                    .containsEntry(MedDocumentScope.METADATA_PATIENT_ID, "P-2048");
        }

        @Test
        @DisplayName("rejects a null patient identifier instead of silently sharing the document")
        void rejectsNullPatient() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MedDocumentScope.ofPatient("hosp-1", "cardiology", null))
                    .withMessageContaining(MedDocumentScope.METADATA_PATIENT_ID);
        }

        @Test
        @DisplayName("rejects the reserved shared sentinel as a patient identifier")
        void rejectsSharedSentinelAsPatient() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MedDocumentScope.ofPatient("hosp-1", "cardiology",
                            MedDocumentScope.SHARED_PATIENT_TAG))
                    .withMessageContaining(MedDocumentScope.SHARED_PATIENT_TAG);
        }
    }

    @Nested
    @DisplayName("tag validation")
    class TagValidation {

        @ParameterizedTest(name = "rejects \"{0}\"")
        @ValueSource(strings = {"dept,other", "dept other", "dept\tid", "dept\nid"})
        @DisplayName("rejects values RediSearch would split or fail to escape")
        void rejectsUnsafeTagValues(String value) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MedDocumentScope.requireTag(value, MedDocumentScope.METADATA_DEPT_ID));
        }

        @Test
        @DisplayName("accepts identifiers made of the usual safe characters")
        void acceptsSafeTagValues() {
            assertThat(MedDocumentScope.requireTag("dept-01:ward_A.2", MedDocumentScope.METADATA_DEPT_ID))
                    .isEqualTo("dept-01:ward_A.2");
        }

        @Test
        @DisplayName("declares exactly the three tags a caller may never override")
        void reservedKeysAreTheIsolationTags() {
            assertThat(MedDocumentScope.RESERVED_METADATA_KEYS)
                    .containsExactlyInAnyOrder("tenant_id", "dept_id", "patient_id");
        }
    }

    @Nested
    @DisplayName("value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("equal scopes share identity and hash code")
        void equalityIsByValue() {
            MedDocumentScope first = MedDocumentScope.ofPatient("hosp-1", "cardiology", "P-1");
            MedDocumentScope second = MedDocumentScope.ofPatient("hosp-1", "cardiology", "P-1");

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first).isEqualTo(first);
        }

        @Test
        @DisplayName("a department-wide scope never equals a patient-scoped one")
        void differentScopesAreNotEqual() {
            MedDocumentScope shared = MedDocumentScope.ofDepartment("hosp-1", "cardiology");
            MedDocumentScope owned = MedDocumentScope.ofPatient("hosp-1", "cardiology", "P-1");

            assertThat(shared).isNotEqualTo(owned).isNotEqualTo("hosp-1");
        }

        @Test
        @DisplayName("prints the effective tags for troubleshooting")
        void toStringExposesTags() {
            assertThat(MedDocumentScope.ofDepartment("hosp-1", "cardiology").toString())
                    .contains("hosp-1", "cardiology", MedDocumentScope.SHARED_PATIENT_TAG);
        }
    }
}
