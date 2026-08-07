package com.med.qa.rag;

import com.med.qa.rag.MedVectorStoreProperties.MetadataFieldSpec;
import com.med.qa.rag.MedVectorStoreProperties.MetadataFieldType;
import com.med.qa.rag.MedVectorStoreProperties.VectorAlgorithm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MedVectorStoreProperties}.
 *
 * <p>The properties bean is the only place where an operator can point the RediSearch index at the
 * wrong keys, so every setter is covered with a valid value and with the boundary value it must
 * reject at startup.</p>
 */
class MedVectorStorePropertiesTest {

    @Test
    @DisplayName("defaults describe the medical document index, not the Spring AI sample index")
    void defaultsAreMedicalSpecific() {
        MedVectorStoreProperties properties = new MedVectorStoreProperties();

        assertThat(properties.getIndexName()).isEqualTo("med-doc-index");
        assertThat(properties.getPrefix()).isEqualTo("med:doc:");
        assertThat(properties.getContentFieldName()).isEqualTo("content");
        assertThat(properties.getEmbeddingFieldName()).isEqualTo("embedding");
        assertThat(properties.getVectorAlgorithm()).isEqualTo(VectorAlgorithm.HNSW);
        assertThat(properties.getDistanceMetric()).isEqualTo(MedVectorStoreProperties.DistanceMetric.COSINE);
        assertThat(properties.isInitializeSchema()).isTrue();
    }

    @Test
    @DisplayName("default metadata fields are the tenant/dept/patient isolation tags")
    void defaultMetadataFieldsAreIsolationTags() {
        MedVectorStoreProperties properties = new MedVectorStoreProperties();

        assertThat(properties.getMetadataFields())
                .extracting(MetadataFieldSpec::getName)
                .containsExactly("tenant_id", "dept_id", "patient_id");
        assertThat(properties.getMetadataFields())
                .extracting(MetadataFieldSpec::getType)
                .containsOnly(MetadataFieldType.TAG);
    }

    @Test
    @DisplayName("defaultMetadataFields returns a fresh list on every call")
    void defaultMetadataFieldsAreNotShared() {
        List<MetadataFieldSpec> first = MedVectorStoreProperties.defaultMetadataFields();
        List<MetadataFieldSpec> second = MedVectorStoreProperties.defaultMetadataFields();
        first.clear();

        assertThat(second).hasSize(3);
    }

    @Test
    @DisplayName("index name accepts a custom value")
    void indexNameAcceptsCustomValue() {
        MedVectorStoreProperties properties = new MedVectorStoreProperties();

        properties.setIndexName("cardiology-index");

        assertThat(properties.getIndexName()).isEqualTo("cardiology-index");
    }

    @Test
    @DisplayName("blank index name is rejected")
    void blankIndexNameRejected() {
        MedVectorStoreProperties properties = new MedVectorStoreProperties();

        assertThatThrownBy(() -> properties.setIndexName("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index-name");
        assertThatThrownBy(() -> properties.setIndexName(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("prefix accepts a custom document namespace")
    void prefixAcceptsCustomNamespace() {
        MedVectorStoreProperties properties = new MedVectorStoreProperties();

        properties.setPrefix("med:kb:");

        assertThat(properties.getPrefix()).isEqualTo("med:kb:");
    }

    @Test
    @DisplayName("prefix overlapping the conversation cache namespace is rejected")
    void prefixOverlappingChatNamespaceRejected() {
        MedVectorStoreProperties properties = new MedVectorStoreProperties();

        assertThatThrownBy(() -> properties.setPrefix("med:chat:"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("med:chat:");
        assertThatThrownBy(() -> properties.setPrefix("med:chat:tenantA:"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setPrefix(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prefix");
    }

    @Test
    @DisplayName("content field name accepts a custom value and rejects a blank one")
    void contentFieldName() {
        MedVectorStoreProperties properties = new MedVectorStoreProperties();

        properties.setContentFieldName("body");
        assertThat(properties.getContentFieldName()).isEqualTo("body");

        assertThatThrownBy(() -> properties.setContentFieldName(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content-field-name");
    }

    @Test
    @DisplayName("embedding field name accepts a custom value and rejects a blank one")
    void embeddingFieldName() {
        MedVectorStoreProperties properties = new MedVectorStoreProperties();

        properties.setEmbeddingFieldName("vector");
        assertThat(properties.getEmbeddingFieldName()).isEqualTo("vector");

        assertThatThrownBy(() -> properties.setEmbeddingFieldName(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embedding-field-name");
    }

    @Test
    @DisplayName("vector algorithm accepts FLAT and rejects null")
    void vectorAlgorithm() {
        MedVectorStoreProperties properties = new MedVectorStoreProperties();

        properties.setVectorAlgorithm(VectorAlgorithm.FLAT);
        assertThat(properties.getVectorAlgorithm()).isEqualTo(VectorAlgorithm.FLAT);

        assertThatThrownBy(() -> properties.setVectorAlgorithm(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vector-algorithm");
    }

    @Test
    @DisplayName("distance metric accepts a value and rejects null")
    void distanceMetric() {
        MedVectorStoreProperties properties = new MedVectorStoreProperties();

        properties.setDistanceMetric(MedVectorStoreProperties.DistanceMetric.L2);
        assertThat(properties.getDistanceMetric()).isEqualTo(MedVectorStoreProperties.DistanceMetric.L2);

        assertThatThrownBy(() -> properties.setDistanceMetric(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("distance-metric");
    }

    @Test
    @DisplayName("schema initialization can be switched off")
    void initializeSchemaToggle() {
        MedVectorStoreProperties properties = new MedVectorStoreProperties();

        properties.setInitializeSchema(false);

        assertThat(properties.isInitializeSchema()).isFalse();
    }

    @Test
    @DisplayName("metadata fields can be replaced and are defensively copied")
    void metadataFieldsAreCopied() {
        MedVectorStoreProperties properties = new MedVectorStoreProperties();
        List<MetadataFieldSpec> supplied = new ArrayList<>(
                List.of(new MetadataFieldSpec("dept_id", MetadataFieldType.TAG)));

        properties.setMetadataFields(supplied);
        supplied.clear();

        assertThat(properties.getMetadataFields())
                .extracting(MetadataFieldSpec::getName)
                .containsExactly("dept_id");
    }

    @Test
    @DisplayName("an empty metadata field list is allowed (index without filterable tags)")
    void emptyMetadataFieldsAllowed() {
        MedVectorStoreProperties properties = new MedVectorStoreProperties();

        properties.setMetadataFields(List.of());

        assertThat(properties.getMetadataFields()).isEmpty();
    }

    @Test
    @DisplayName("null, null-entry and duplicate metadata fields are rejected")
    void invalidMetadataFieldsRejected() {
        MedVectorStoreProperties properties = new MedVectorStoreProperties();

        assertThatThrownBy(() -> properties.setMetadataFields(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metadata-fields");

        assertThatThrownBy(() -> properties.setMetadataFields(Arrays.asList((MetadataFieldSpec) null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null entries");

        List<MetadataFieldSpec> duplicates = List.of(
                new MetadataFieldSpec("dept_id", MetadataFieldType.TAG),
                new MetadataFieldSpec("dept_id", MetadataFieldType.TEXT));
        assertThatThrownBy(() -> properties.setMetadataFields(duplicates))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    @DisplayName("metadata field spec validates its own name and type")
    void metadataFieldSpecValidation() {
        MetadataFieldSpec spec = new MetadataFieldSpec("patient_id", MetadataFieldType.NUMERIC);
        assertThat(spec.getName()).isEqualTo("patient_id");
        assertThat(spec.getType()).isEqualTo(MetadataFieldType.NUMERIC);
        assertThat(spec.toString()).contains("patient_id").contains("NUMERIC");

        assertThatThrownBy(() -> new MetadataFieldSpec(" ", MetadataFieldType.TAG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
        assertThatThrownBy(() -> new MetadataFieldSpec("dept_id", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
    }

    @Test
    @DisplayName("a default-constructed spec defaults to TAG so identifiers stay exact-match")
    void metadataFieldSpecDefaultsToTag() {
        MetadataFieldSpec spec = new MetadataFieldSpec();

        assertThat(spec.getType()).isEqualTo(MetadataFieldType.TAG);
        assertThat(spec.getName()).isNull();
    }
}
