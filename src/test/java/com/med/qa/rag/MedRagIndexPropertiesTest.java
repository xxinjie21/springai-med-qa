package com.med.qa.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MedRagIndexProperties}.
 *
 * <p>The defaults matter as much as the validation here: the expected TAG field list is the only
 * thing that lets the probe notice a schema drift, so a silently empty list would turn the whole D37
 * probe into a no-op that always reports {@code UP}.</p>
 */
class MedRagIndexPropertiesTest {

    private final MedRagIndexProperties properties = new MedRagIndexProperties();

    @Test
    @DisplayName("the defaults monitor the index and expect the three isolation tags")
    void defaultsAreTheDocumentedOnes() {
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getCheckInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.getInitialDelay()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.getExpectedTagFields())
                .containsExactly("tenant_id", "dept_id", "patient_id");
    }

    @Test
    @DisplayName("the default expectation covers exactly the isolation dimensions of the storage spec")
    void defaultExpectationMatchesTheStorageSpecification() {
        assertThat(MedRagIndexProperties.DEFAULT_EXPECTED_TAG_FIELDS)
                .isEqualTo(MedVectorStoreProperties.defaultMetadataFields().stream()
                        .map(MedVectorStoreProperties.MetadataFieldSpec::getName)
                        .toList());
    }

    @Test
    @DisplayName("enabled is the switch a deployment without Redis Stack flips")
    void enabledCanBeTurnedOff() {
        properties.setEnabled(false);

        assertThat(properties.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("a positive check interval is accepted")
    void positiveCheckIntervalIsAccepted() {
        properties.setCheckInterval(Duration.ofSeconds(30));

        assertThat(properties.getCheckInterval()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("boundary: a zero check interval is rejected, it would spin the scheduler")
    void zeroCheckIntervalIsRejected() {
        assertThatThrownBy(() -> properties.setCheckInterval(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("med.rag.index.check-interval");
    }

    @Test
    @DisplayName("boundary: a negative check interval and a null one are rejected")
    void negativeAndNullCheckIntervalAreRejected() {
        assertThatThrownBy(() -> properties.setCheckInterval(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setCheckInterval(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("boundary: a zero initial delay is allowed, it just probes immediately")
    void zeroInitialDelayIsAllowed() {
        properties.setInitialDelay(Duration.ZERO);

        assertThat(properties.getInitialDelay()).isZero();
    }

    @Test
    @DisplayName("boundary: a negative initial delay is rejected")
    void negativeInitialDelayIsRejected() {
        assertThatThrownBy(() -> properties.setInitialDelay(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initial-delay");
    }

    @Test
    @DisplayName("a custom expected tag field list replaces the defaults")
    void expectedTagFieldsCanBeReplaced() {
        properties.setExpectedTagFields(List.of("tenant_id", "dept_id"));

        assertThat(properties.getExpectedTagFields()).containsExactly("tenant_id", "dept_id");
        assertThat(properties.isExpectedTagField("patient_id")).isFalse();
        assertThat(properties.isExpectedTagField("dept_id")).isTrue();
    }

    @Test
    @DisplayName("boundary: an empty expected list is accepted and expects nothing")
    void emptyExpectedTagFieldsExpectNothing() {
        properties.setExpectedTagFields(List.of());

        assertThat(properties.getExpectedTagFields()).isEmpty();
        assertThat(properties.isExpectedTagField("tenant_id")).isFalse();
    }

    @Test
    @DisplayName("boundary: a blank or duplicate expected tag field is rejected")
    void blankAndDuplicateExpectedTagFieldsAreRejected() {
        assertThatThrownBy(() -> properties.setExpectedTagFields(List.of("tenant_id", " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
        assertThatThrownBy(() -> properties.setExpectedTagFields(List.of("tenant_id", "tenant_id")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> properties.setExpectedTagFields(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the expected list is copied, so mutating the caller's list cannot change the policy")
    void expectedTagFieldsAreDefensivelyCopied() {
        List<String> source = new ArrayList<>(List.of("tenant_id"));
        properties.setExpectedTagFields(source);

        source.add("dept_id");

        assertThat(properties.getExpectedTagFields()).containsExactly("tenant_id");
    }

    @Test
    @DisplayName("boundary: a null field name is never expected")
    void nullFieldNameIsNeverExpected() {
        assertThat(properties.isExpectedTagField(null)).isFalse();
    }

    @Test
    @DisplayName("the string form names the switch and the expectation, for startup logs")
    void toStringIsInformative() {
        assertThat(properties.toString())
                .contains("enabled=true")
                .contains("tenant_id")
                .contains("patient_id");
    }
}
