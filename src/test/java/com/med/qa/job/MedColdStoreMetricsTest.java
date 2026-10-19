package com.med.qa.job;

import com.med.qa.mapper.SessionArchiveMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests of {@link MedColdStoreMetrics} (D54), the publisher of the cold transcript store's size and
 * backlog gauges.
 *
 * <p>Four behaviours carry the weight of this class and are pinned here:</p>
 * <ol>
 *   <li><b>Nothing is published before anything was measured.</b> The gauges are registered on the first
 *       measurement, so a deployment whose maintenance jobs never ran exports no cold-store series at
 *       all. An eagerly registered gauge would export a zero, and a zero reads as "the cold store is
 *       empty" — a claim nothing verified. This is asserted on the registry rather than on a counter.</li>
 *   <li><b>An empty cold store has age zero, not age {@code now}.</b> The aggregate reports {@code 0} for
 *       "no oldest row"; deriving an age from that would read as ancient data that does not exist. A row
 *       stamped in the future (clock skew between replicas) clamps to zero for the same family of
 *       reasons.</li>
 *   <li><b>A failed measurement is contained.</b> This runs on the scheduler thread after the run's own
 *       accounting is complete, so a storage failure there is logged with the previous values kept —
 *       it must not be able to turn a successful reclaim into a failed one.</li>
 *   <li><b>The backlog gauge is registered by the reclaim alone.</b> An export-only deployment measures
 *       the size but never the backlog, and must not publish a backlog of zero it never measured.</li>
 * </ol>
 */
class MedColdStoreMetricsTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

    private SessionArchiveMapper archiveMapper;

    private SimpleMeterRegistry registry;

    private MedColdStoreMetrics metrics;

    @BeforeEach
    void setUp() {
        archiveMapper = mock(SessionArchiveMapper.class);
        registry = new SimpleMeterRegistry();
        metrics = new MedColdStoreMetrics(archiveMapper, registry,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ------------------------------------------------------------------ constructor

    @Test
    @DisplayName("the constructor refuses a null collaborator")
    void constructorRejectsNulls() {
        Clock clock = Clock.systemUTC();
        assertThatThrownBy(() -> new MedColdStoreMetrics(null, registry, clock))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("archiveMapper");
        assertThatThrownBy(() -> new MedColdStoreMetrics(archiveMapper, null, clock))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("meterRegistry");
        assertThatThrownBy(() -> new MedColdStoreMetrics(archiveMapper, registry, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("clock");
    }

    // ------------------------------------------------------------------ lazy registration

    @Test
    @DisplayName("no cold-store series exists before the first measurement, not even a zero")
    void nothingIsPublishedBeforeTheFirstMeasurement() {
        // The whole point of lazy registration: a zero exported before anything was measured would read
        // as "the cold store is empty", which is a claim nothing has verified.
        assertThat(registry.find(MedColdStoreMetrics.ROWS_GAUGE).gauge()).isNull();
        assertThat(registry.find(MedColdStoreMetrics.OLDEST_ROW_AGE_GAUGE).gauge()).isNull();
        assertThat(registry.find(MedColdStoreMetrics.RECLAIM_BACKLOG_GAUGE).gauge()).isNull();
        assertThat(registry.getMeters())
                .noneMatch(meter -> meter.getId().getName().startsWith("med_qa_cold_store_"));
    }

    @Test
    @DisplayName("a size measurement registers exactly one gauge per name and reuses it afterwards")
    void sizeGaugesAreRegisteredOnce() {
        when(archiveMapper.countArchivedMessages()).thenReturn(10L, 20L);
        when(archiveMapper.oldestArchivedMessageCreatedAt()).thenReturn(NOW.toEpochMilli());

        metrics.recordColdStoreSize();
        metrics.recordColdStoreSize();

        assertThat(registry.getMeters())
                .filteredOn(meter -> meter.getId().getName()
                        .equals(MedColdStoreMetrics.ROWS_GAUGE))
                .hasSize(1);
        assertThat(gauge(MedColdStoreMetrics.ROWS_GAUGE)).isEqualTo(20d);
    }

    // ------------------------------------------------------------------ the size gauges

    @Test
    @DisplayName("the row count and the oldest row's age are published as measured")
    void publishesRowsAndAge() {
        when(archiveMapper.countArchivedMessages()).thenReturn(4_242L);
        when(archiveMapper.oldestArchivedMessageCreatedAt())
                .thenReturn(NOW.minus(Duration.ofHours(3)).toEpochMilli());

        metrics.recordColdStoreSize();

        assertThat(gauge(MedColdStoreMetrics.ROWS_GAUGE)).isEqualTo(4_242d);
        assertThat(gauge(MedColdStoreMetrics.OLDEST_ROW_AGE_GAUGE)).isEqualTo(10_800d);
    }

    @Test
    @DisplayName("an empty cold store has age zero rather than the age of the epoch")
    void emptyStoreHasAgeZero() {
        when(archiveMapper.countArchivedMessages()).thenReturn(0L);
        when(archiveMapper.oldestArchivedMessageCreatedAt()).thenReturn(0L);

        metrics.recordColdStoreSize();

        assertThat(gauge(MedColdStoreMetrics.ROWS_GAUGE)).isZero();
        assertThat(gauge(MedColdStoreMetrics.OLDEST_ROW_AGE_GAUGE)).isZero();
    }

    @Test
    @DisplayName("a row stamped in the future clamps to zero rather than reporting a negative age")
    void futureRowClampsToZero() {
        when(archiveMapper.countArchivedMessages()).thenReturn(1L);
        when(archiveMapper.oldestArchivedMessageCreatedAt())
                .thenReturn(NOW.plus(Duration.ofMinutes(5)).toEpochMilli());

        metrics.recordColdStoreSize();

        assertThat(gauge(MedColdStoreMetrics.OLDEST_ROW_AGE_GAUGE)).isZero();
    }

    @Test
    @DisplayName("a negative row count is normalised to zero rather than published as-is")
    void negativeRowCountIsNormalised() {
        when(archiveMapper.countArchivedMessages()).thenReturn(-5L);
        when(archiveMapper.oldestArchivedMessageCreatedAt()).thenReturn(0L);

        metrics.recordColdStoreSize();

        assertThat(gauge(MedColdStoreMetrics.ROWS_GAUGE)).isZero();
    }

    // ------------------------------------------------------------------ failure containment

    @Test
    @DisplayName("a storage failure keeps the previous values and does not throw")
    void failedMeasurementKeepsThePreviousValues() {
        when(archiveMapper.countArchivedMessages()).thenReturn(100L);
        when(archiveMapper.oldestArchivedMessageCreatedAt())
                .thenReturn(NOW.minus(Duration.ofSeconds(60)).toEpochMilli());
        metrics.recordColdStoreSize();

        when(archiveMapper.countArchivedMessages())
                .thenThrow(new DataAccessResourceFailureException("mysql went away"));

        // The scheduler calls this after the run's own accounting is complete, so it must not be able to
        // turn a successful run into a failed one.
        metrics.recordColdStoreSize();

        assertThat(gauge(MedColdStoreMetrics.ROWS_GAUGE)).isEqualTo(100d);
        assertThat(gauge(MedColdStoreMetrics.OLDEST_ROW_AGE_GAUGE)).isEqualTo(60d);
    }

    @Test
    @DisplayName("a failed first measurement publishes nothing at all")
    void failedFirstMeasurementPublishesNothing() {
        when(archiveMapper.countArchivedMessages())
                .thenThrow(new DataAccessResourceFailureException("mysql went away"));

        metrics.recordColdStoreSize();

        assertThat(registry.find(MedColdStoreMetrics.ROWS_GAUGE).gauge()).isNull();
        assertThat(registry.find(MedColdStoreMetrics.OLDEST_ROW_AGE_GAUGE).gauge()).isNull();
    }

    // ------------------------------------------------------------------ the backlog gauge

    @Test
    @DisplayName("the backlog is published as given and updated in place by the next run")
    void publishesTheBacklog() {
        metrics.recordReclaimBacklog(7L);
        metrics.recordReclaimBacklog(0L);

        assertThat(gauge(MedColdStoreMetrics.RECLAIM_BACKLOG_GAUGE)).isZero();
        assertThat(registry.getMeters())
                .filteredOn(meter -> meter.getId().getName()
                        .equals(MedColdStoreMetrics.RECLAIM_BACKLOG_GAUGE))
                .hasSize(1);
    }

    @Test
    @DisplayName("the size gauges and the backlog gauge are registered independently")
    void sizeAndBacklogGaugesAreIndependent() {
        // An export-only deployment measures the size but never the backlog; a size measurement must not
        // drag a backlog of zero into existence.
        metrics.recordColdStoreSize();

        assertThat(registry.find(MedColdStoreMetrics.RECLAIM_BACKLOG_GAUGE).gauge()).isNull();
    }

    @Test
    @DisplayName("the backlog gauge alone does not register the size gauges either")
    void backlogGaugeAloneLeavesTheSizeGaugesUnregistered() {
        metrics.recordReclaimBacklog(3L);

        assertThat(registry.find(MedColdStoreMetrics.ROWS_GAUGE).gauge()).isNull();
        assertThat(registry.find(MedColdStoreMetrics.OLDEST_ROW_AGE_GAUGE).gauge()).isNull();
    }

    @Test
    @DisplayName("a negative backlog is refused rather than published")
    void negativeBacklogIsRefused() {
        assertThatThrownBy(() -> metrics.recordReclaimBacklog(-1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backlog must not be negative");
        assertThat(registry.find(MedColdStoreMetrics.RECLAIM_BACKLOG_GAUGE).gauge()).isNull();
    }

    // ------------------------------------------------------------------ helpers

    /** Reads one gauge out of the test registry, the way a Prometheus scrape would. */
    private double gauge(String name) {
        var gauge = registry.find(name).gauge();
        assertThat(gauge).as("gauge %s must be registered", name).isNotNull();
        return gauge.value();
    }
}
