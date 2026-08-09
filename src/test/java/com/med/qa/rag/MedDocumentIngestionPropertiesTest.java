package com.med.qa.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests of the ingestion guard rails.
 */
class MedDocumentIngestionPropertiesTest {

    @Test
    @DisplayName("defaults keep a bulk import bounded without extra configuration")
    void defaultsAreBounded() {
        MedDocumentIngestionProperties properties = new MedDocumentIngestionProperties();

        assertThat(properties.getBatchSize()).isEqualTo(25);
        assertThat(properties.getMaxContentLength()).isEqualTo(20_000);
        assertThat(properties.getMaxDocumentsPerRequest()).isEqualTo(500);
    }

    @Test
    @DisplayName("accepts tuned values")
    void acceptsTunedValues() {
        MedDocumentIngestionProperties properties = new MedDocumentIngestionProperties();

        properties.setBatchSize(1);
        properties.setMaxContentLength(1);
        properties.setMaxDocumentsPerRequest(1);

        assertThat(properties.getBatchSize()).isOne();
        assertThat(properties.getMaxContentLength()).isOne();
        assertThat(properties.getMaxDocumentsPerRequest()).isOne();

        properties.setBatchSize(MedDocumentIngestionProperties.MAX_BATCH_SIZE);
        assertThat(properties.getBatchSize()).isEqualTo(MedDocumentIngestionProperties.MAX_BATCH_SIZE);
    }

    @ParameterizedTest(name = "rejects batch size {0}")
    @ValueSource(ints = {0, -1, MedDocumentIngestionProperties.MAX_BATCH_SIZE + 1})
    @DisplayName("rejects a batch size that is not usable")
    void rejectsInvalidBatchSize(int batchSize) {
        MedDocumentIngestionProperties properties = new MedDocumentIngestionProperties();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setBatchSize(batchSize))
                .withMessageContaining("batch-size");
    }

    @Test
    @DisplayName("rejects non positive limits")
    void rejectsNonPositiveLimits() {
        MedDocumentIngestionProperties properties = new MedDocumentIngestionProperties();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setMaxContentLength(0))
                .withMessageContaining("max-content-length");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setMaxDocumentsPerRequest(-5))
                .withMessageContaining("max-documents-per-request");
    }

    @Test
    @DisplayName("describes itself for startup logging")
    void toStringListsLimits() {
        assertThat(new MedDocumentIngestionProperties().toString())
                .contains("batchSize=25", "maxContentLength=20000", "maxDocumentsPerRequest=500");
    }
}
