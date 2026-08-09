package com.med.qa.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests of the ingestion request value object.
 */
class MedDocumentRequestTest {

    private static final MedDocumentScope SCOPE = MedDocumentScope.ofDepartment("hosp-1", "cardiology");

    @Test
    @DisplayName("keeps the caller supplied identifier so re-ingestion overwrites the same record")
    void keepsSuppliedIdentifier() {
        MedDocumentRequest request = new MedDocumentRequest("guideline-2024", "beta blocker protocol",
                SCOPE, Map.of("title", "Beta blockers"));

        assertThat(request.getId()).isEqualTo("guideline-2024");
        assertThat(request.getText()).isEqualTo("beta blocker protocol");
        assertThat(request.getScope()).isEqualTo(SCOPE);
        assertThat(request.getMetadata()).containsExactly(Map.entry("title", "Beta blockers"));
    }

    @Test
    @DisplayName("leaves the identifier unset when the store should generate one")
    void allowsGeneratedIdentifier() {
        MedDocumentRequest request = MedDocumentRequest.of("chest pain triage", SCOPE);

        assertThat(request.getId()).isNull();
        assertThat(request.getMetadata()).isEmpty();
    }

    @Test
    @DisplayName("accepts string, number and boolean metadata values")
    void acceptsScalarMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", "Triage");
        metadata.put("revision", 3);
        metadata.put("approved", true);

        MedDocumentRequest request = MedDocumentRequest.of("chest pain triage", SCOPE, metadata);

        assertThat(request.getMetadata()).containsExactlyEntriesOf(metadata);
    }

    @Test
    @DisplayName("copies the metadata defensively and exposes it unmodifiable")
    void metadataIsImmutableSnapshot() {
        Map<String, Object> source = new HashMap<>();
        source.put("title", "Triage");
        MedDocumentRequest request = MedDocumentRequest.of("chest pain triage", SCOPE, source);

        source.put("title", "tampered");

        assertThat(request.getMetadata()).containsEntry("title", "Triage");
        assertThatThrownBy(() -> request.getMetadata().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("rejects a blank document text")
    void rejectsBlankText() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MedDocumentRequest.of("   ", SCOPE))
                .withMessageContaining("text");
    }

    @Test
    @DisplayName("rejects a missing scope, because an untagged document escapes isolation")
    void rejectsMissingScope() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MedDocumentRequest.of("chest pain triage", null))
                .withMessageContaining("scope");
    }

    @Test
    @DisplayName("rejects a blank identifier, which would be an unusable key")
    void rejectsBlankIdentifier() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedDocumentRequest("  ", "chest pain triage", SCOPE, null))
                .withMessageContaining("id");
    }

    @Test
    @DisplayName("rejects metadata trying to override an isolation tag")
    void rejectsReservedMetadataKeys() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MedDocumentRequest.of("chest pain triage", SCOPE,
                        Map.of(MedDocumentScope.METADATA_DEPT_ID, "oncology")))
                .withMessageContaining(MedDocumentScope.METADATA_DEPT_ID);
    }

    @Test
    @DisplayName("rejects blank metadata keys and non scalar values")
    void rejectsUnusableMetadata() {
        Map<String, Object> blankKey = new LinkedHashMap<>();
        blankKey.put(" ", "value");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MedDocumentRequest.of("text", SCOPE, blankKey))
                .withMessageContaining("keys");

        Map<String, Object> nullValue = new LinkedHashMap<>();
        nullValue.put("title", null);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MedDocumentRequest.of("text", SCOPE, nullValue))
                .withMessageContaining("title");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> MedDocumentRequest.of("text", SCOPE, Map.of("tags", java.util.List.of("a"))))
                .withMessageContaining("string, number or boolean");
    }

    @Test
    @DisplayName("compares by value")
    void equalityIsByValue() {
        MedDocumentRequest first = MedDocumentRequest.of("chest pain triage", SCOPE, Map.of("title", "T"));
        MedDocumentRequest second = MedDocumentRequest.of("chest pain triage", SCOPE, Map.of("title", "T"));

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first).isNotEqualTo(MedDocumentRequest.of("other", SCOPE)).isNotEqualTo("text");
    }

    @Test
    @DisplayName("never prints the clinical content")
    void toStringHidesContent() {
        String secret = "patient reports crushing chest pain";
        MedDocumentRequest request = new MedDocumentRequest("doc-1", secret, SCOPE, Map.of("title", "T"));

        assertThat(request.toString())
                .doesNotContain(secret)
                .contains("doc-1", "textLength=" + secret.length(), "title");
    }
}
