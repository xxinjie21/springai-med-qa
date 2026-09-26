package com.med.qa.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests for {@link MedIndexRebuildProperties}.
 *
 * <p>The defaults matter more than usual here: {@code enabled} and {@code allow-document-deletion}
 * both default to {@code false}, and those two booleans are what keep the only destructive code path
 * in the service out of a deployment that never asked for it. A test that only checked the happy
 * path would not notice them being flipped.</p>
 */
class MedIndexRebuildPropertiesTest {

    @Test
    @DisplayName("a fresh instance is disabled: no rebuild code path exists by default")
    void disabledByDefault() {
        MedIndexRebuildProperties properties = new MedIndexRebuildProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isAllowDocumentDeletion()).isFalse();
        assertThat(MedIndexRebuildProperties.PREFIX).isEqualTo("med.rag.index.rebuild");
    }

    @Test
    @DisplayName("the timings and limits have usable defaults")
    void defaultsAreUsable() {
        MedIndexRebuildProperties properties = new MedIndexRebuildProperties();

        assertThat(properties.getLockWaitTime()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.getLockLeaseTime()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.getBatchSize()).isEqualTo(50);
        assertThat(properties.getMaxDocuments()).isEqualTo(5000);
    }

    @Test
    @DisplayName("enabling the rebuild does not authorise deleting documents")
    void enablingDoesNotAuthoriseDeletion() {
        MedIndexRebuildProperties properties = new MedIndexRebuildProperties();

        properties.setEnabled(true);

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.isAllowDocumentDeletion())
                .as("the two switches are independent: the routine repair must not imply the "
                        + "destructive one")
                .isFalse();
    }

    @Test
    @DisplayName("boundary: a zero lock wait is accepted, because it means 'do not queue'")
    void zeroLockWaitIsAccepted() {
        MedIndexRebuildProperties properties = new MedIndexRebuildProperties();

        properties.setLockWaitTime(Duration.ZERO);

        assertThat(properties.getLockWaitTime()).isZero();
    }

    @Test
    @DisplayName("boundary: a negative lock wait is rejected")
    void negativeLockWaitIsRejected() {
        MedIndexRebuildProperties properties = new MedIndexRebuildProperties();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setLockWaitTime(Duration.ofSeconds(-1)))
                .withMessageContaining("lock-wait-time");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setLockWaitTime(null))
                .withMessageContaining("lock-wait-time");
    }

    @Test
    @DisplayName("boundary: a zero lease is accepted and keeps the Redisson watchdog in charge")
    void zeroLeaseIsAccepted() {
        MedIndexRebuildProperties properties = new MedIndexRebuildProperties();

        properties.setLockLeaseTime(Duration.ZERO);

        assertThat(properties.getLockLeaseTime()).isZero();
    }

    @Test
    @DisplayName("boundary: a negative lease is rejected")
    void negativeLeaseIsRejected() {
        MedIndexRebuildProperties properties = new MedIndexRebuildProperties();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setLockLeaseTime(Duration.ofSeconds(-1)))
                .withMessageContaining("lock-lease-time");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setLockLeaseTime(null))
                .withMessageContaining("lock-lease-time");
    }

    @Test
    @DisplayName("boundary: a non-positive batch size or document limit is rejected")
    void nonPositiveLimitsAreRejected() {
        MedIndexRebuildProperties properties = new MedIndexRebuildProperties();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setBatchSize(0))
                .withMessageContaining("batch-size");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setMaxDocuments(-1))
                .withMessageContaining("max-documents");
    }

    @Test
    @DisplayName("the policy renders every value an operator needs when a rebuild is refused")
    void describeNamesEveryValue() {
        MedIndexRebuildProperties properties = new MedIndexRebuildProperties();
        properties.setEnabled(true);
        properties.setBatchSize(10);

        assertThat(properties.describe())
                .contains("enabled=true")
                .contains("allowDocumentDeletion=false")
                .contains("batchSize=10")
                .contains("maxDocuments=5000");
    }

    @Test
    @DisplayName("every configuration key this class owns is listed, so a doc guard can assert it")
    void propertyNamesCoverTheNamespace() {
        assertThat(MedIndexRebuildProperties.propertyNames())
                .containsExactly(
                        "med.rag.index.rebuild.enabled",
                        "med.rag.index.rebuild.allow-document-deletion",
                        "med.rag.index.rebuild.lock-wait-time",
                        "med.rag.index.rebuild.lock-lease-time",
                        "med.rag.index.rebuild.batch-size",
                        "med.rag.index.rebuild.max-documents");
    }
}
