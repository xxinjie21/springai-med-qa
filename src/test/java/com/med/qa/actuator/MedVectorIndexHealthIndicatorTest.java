package com.med.qa.actuator;

import com.med.qa.rag.MedRagIndexProperties;
import com.med.qa.rag.MedVectorIndexProbe;
import com.med.qa.rag.MedVectorIndexReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MedVectorIndexHealthIndicator}.
 *
 * <p>What is asserted is the verdict, not the probing: a reachable Redis is not the same question as
 * "can a consultation retrieve anything?", and the whole reason this indicator exists is that a
 * missing index and a drifted TAG schema both leave the service answering HTTP 200 while returning
 * less evidence. Each of those two must therefore be an explicit {@code DOWN} with its own
 * machine-readable reason, so a dashboard can tell them apart without parsing a message.</p>
 */
class MedVectorIndexHealthIndicatorTest {

    private static final String INDEX_NAME = "med-doc-index";

    private final MedVectorIndexProbe probe = mock(MedVectorIndexProbe.class);

    private final MedRagIndexProperties indexProperties = new MedRagIndexProperties();

    private MedVectorIndexHealthIndicator indicator() {
        return new MedVectorIndexHealthIndicator(probe, indexProperties);
    }

    /** A report of a well-formed index declaring all three isolation tags. */
    private static MedVectorIndexReport readyReport(long documents) {
        return new MedVectorIndexReport(INDEX_NAME, true, documents,
                Set.of("med:doc:"), Set.of("tenant_id", "dept_id", "patient_id"));
    }

    // ---------------------------------------------------------------- up

    @Test
    @DisplayName("a well-formed index reports UP and exposes only index metadata")
    void wellFormedIndexReportsUp() {
        when(probe.probe()).thenReturn(readyReport(128L));

        Health health = indicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        Map<String, Object> details = health.getDetails();
        assertThat(details).containsEntry(MedVectorIndexHealthIndicator.INDEX_DETAIL, INDEX_NAME);
        assertThat(details).containsEntry(MedVectorIndexHealthIndicator.EXISTS_DETAIL, true);
        assertThat(details).containsEntry(MedVectorIndexHealthIndicator.DOCUMENTS_DETAIL, 128L);
        assertThat(details).containsEntry(MedVectorIndexHealthIndicator.PREFIXES_DETAIL, List.of("med:doc:"));
        assertThat(details).doesNotContainKey(MedVectorIndexHealthIndicator.MISSING_TAG_FIELDS_DETAIL);
        assertThat(details).doesNotContainKey(MedVectorIndexHealthIndicator.REASON_DETAIL);
    }

    @Test
    @DisplayName("an empty but correctly shaped index reports UP: an unpopulated corpus is not a defect")
    void emptyWellFormedIndexReportsUp() {
        when(probe.probe()).thenReturn(readyReport(0L));

        Health health = indicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry(MedVectorIndexHealthIndicator.DOCUMENTS_DETAIL, 0L);
    }

    // ---------------------------------------------------------------- the two silent failures

    @Test
    @DisplayName("a missing index reports DOWN with the index-missing reason")
    void missingIndexReportsDownWithItsReason() {
        when(probe.probe()).thenReturn(MedVectorIndexReport.missing(INDEX_NAME));

        Health health = indicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry(
                MedVectorIndexHealthIndicator.REASON_DETAIL,
                MedVectorIndexHealthIndicator.REASON_INDEX_MISSING);
        assertThat(health.getDetails())
                .containsEntry(MedVectorIndexHealthIndicator.EXISTS_DETAIL, false)
                .containsEntry(MedVectorIndexHealthIndicator.DOCUMENTS_DETAIL, 0L);
    }

    @Test
    @DisplayName("a drifted TAG schema reports DOWN with the schema-drift reason and the missing fields")
    void driftedSchemaReportsDownWithItsReason() {
        when(probe.probe()).thenReturn(new MedVectorIndexReport(INDEX_NAME, true, 64L,
                Set.of("med:doc:"), Set.of("tenant_id", "dept_id")));

        Health health = indicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry(
                MedVectorIndexHealthIndicator.REASON_DETAIL,
                MedVectorIndexHealthIndicator.REASON_SCHEMA_DRIFT);
        assertThat(health.getDetails()).containsEntry(
                MedVectorIndexHealthIndicator.MISSING_TAG_FIELDS_DETAIL, List.of("patient_id"));
    }

    @Test
    @DisplayName("boundary: an index declaring no TAG field at all reports every expected field missing")
    void indexWithoutTagsReportsEveryFieldMissing() {
        when(probe.probe()).thenReturn(new MedVectorIndexReport(INDEX_NAME, true, 1L,
                Set.of("med:doc:"), Set.of()));

        Health health = indicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry(
                MedVectorIndexHealthIndicator.MISSING_TAG_FIELDS_DETAIL,
                List.of("tenant_id", "dept_id", "patient_id"));
    }

    @Test
    @DisplayName("a probe that throws reports DOWN as unreachable instead of propagating")
    void throwingProbeReportsDownAsUnreachable() {
        when(probe.indexName()).thenReturn(INDEX_NAME);
        when(probe.probe()).thenThrow(new JedisConnectionException("connection refused"));

        Health health = indicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry(
                MedVectorIndexHealthIndicator.REASON_DETAIL,
                MedVectorIndexHealthIndicator.REASON_UNREACHABLE);
        assertThat(health.getDetails())
                .containsEntry(MedVectorIndexHealthIndicator.INDEX_DETAIL, INDEX_NAME)
                .containsEntry(MedVectorIndexHealthIndicator.EXISTS_DETAIL, false);
    }

    // ---------------------------------------------------------------- policy interaction

    @Test
    @DisplayName("a relaxed expectation accepts an index that lacks one of the default tags")
    void relaxedExpectationAcceptsAPartialIndex() {
        indexProperties.setExpectedTagFields(List.of("tenant_id"));
        when(probe.probe()).thenReturn(new MedVectorIndexReport(INDEX_NAME, true, 8L,
                Set.of("med:doc:"), Set.of("tenant_id")));

        assertThat(indicator().health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("an expectation of nothing still requires the index to exist")
    void emptyExpectationStillRequiresTheIndex() {
        indexProperties.setExpectedTagFields(List.of());
        when(probe.probe()).thenReturn(MedVectorIndexReport.missing(INDEX_NAME));

        assertThat(indicator().health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("boundary: an unexpected extra TAG field is not a drift and does not fail the probe")
    void extraTagFieldsAreNotDrift() {
        when(probe.probe()).thenReturn(new MedVectorIndexReport(INDEX_NAME, true, 5L,
                Set.of("med:doc:"),
                Set.of("tenant_id", "dept_id", "patient_id", "encounter_id")));

        assertThat(indicator().health().getStatus()).isEqualTo(Status.UP);
    }

    // ---------------------------------------------------------------- construction

    @Test
    @DisplayName("boundary: a null probe or null properties is rejected at construction")
    void nullConstructorArgumentsAreRejected() {
        assertThatThrownBy(() -> new MedVectorIndexHealthIndicator(null, indexProperties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("probe");
        assertThatThrownBy(() -> new MedVectorIndexHealthIndicator(probe, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("indexProperties");
    }
}
