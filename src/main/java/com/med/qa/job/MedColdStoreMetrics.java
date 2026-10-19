package com.med.qa.job;

import com.med.qa.mapper.SessionArchiveMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes the size and the age of the cold transcript store ({@code med_message_archive}) as Micrometer
 * gauges (D54).
 *
 * <h2>Why this exists</h2>
 * <p>D52 made the orphaned cold copies stop growing without bound, and nothing since has said how big
 * {@code med_message_archive} actually is or how long its oldest content has been sitting there. The two
 * states that motivated the whole cold/hot lifecycle therefore still look identical from the outside:
 * "the reclaim is keeping up with the orphans the restores produce" and "the reclaim is falling behind"
 * both produce a quiet log, no alert and no metric. The reclaim report answers for one run; only a gauge
 * refreshed after every run can be read by a rule that compares two moments in time.</p>
 *
 * <h2>What is published</h2>
 * <ul>
 *   <li>{@value #ROWS_GAUGE} — {@code COUNT(*)} over the cold table, i.e. how many archived message rows
 *       the store holds. This is the number an operator reasons about when sizing the archive, and it is
 *       the only one that grows with the export as well as with a reclaim that stops.</li>
 *   <li>{@value #OLDEST_ROW_AGE_GAUGE} — the age in seconds of the store's oldest row by
 *       {@code created_at}. The export carries the original message's creation instant over, so this is
 *       "how old is the coldest data in the store"; on a table dominated by orphaned copies it is the
 *       metadata-only answer to "how long has the oldest orphan been there".</li>
 *   <li>{@value #RECLAIM_BACKLOG_GAUGE} — the orphaned copies the last <em>deleting</em> reclaim run left
 *       behind, taken from the run report's own backlog figure
 *       ({@code countReclaimableOrphans}) rather than from a second scan. This is the series
 *       {@code MedQaColdStoreBacklogGrowing} consumes.</li>
 * </ul>
 *
 * <h2>Metadata only</h2>
 * <p>Every read is an aggregate — {@code COUNT(*)} and {@code MIN(created_at)} — so no payload is decoded,
 * no message body is inspected, and nothing here can delete or alter a row. The reads sit on the
 * maintenance path (the D48 export and the D52 reclaim refresh them after each run they actually perform),
 * never on a request path.</p>
 *
 * <h2>Lazy registration, and why the backlog is gated on a deleting run</h2>
 * <p>The gauges are registered on the first measurement rather than at construction. A deployment whose
 * maintenance jobs are switched off must publish <em>no</em> cold-store series at all: a zero exported
 * before anything was measured would read as "the cold store is empty", which is a claim nothing has
 * verified. This is the same boundary {@code MedJobRunLedger} draws — a job that is deliberately disabled
 * is an operator decision, not a page.</p>
 *
 * <p>The backlog is recorded only from a run that was allowed to delete ({@code dry-run=false}). A dry
 * run is a measurement of a deployment that has decided not to reclaim; its backlog cannot drain by
 * construction, so publishing it would put a permanently non-zero value under a rule whose whole point
 * is "the job runs and still never drains" — paging exactly the deployments that only measure. The
 * consequence, documented rather than hidden: a deployment that flips back to dry-run keeps the last
 * deleting run's figure on the gauge (Micrometer exports a gauge's last value until it is replaced), so
 * the page a leftover backlog raises stays visible until a deleting run refreshes it.</p>
 *
 * <h2>A failed measurement is contained</h2>
 * <p>{@link #recordColdStoreSize()} runs on the scheduler thread <em>after</em> the run's own accounting
 * is complete. A storage failure there is logged as a warning and the previous gauge values are kept: a
 * cold-store refresh that cannot complete must not turn a successful reclaim into a failed one, and the
 * staleness of the gauges is visible on a dashboard while a lost ledger record would not be.</p>
 *
 * <p>Not annotated with {@code @Service} and carrying no switch of its own: it is contributed by
 * {@code config/MedColdStoreMetricsConfig} because it is observability infrastructure, and a switch that
 * removed it would remove the only size signal the cold store has.</p>
 */
public class MedColdStoreMetrics {

    private static final Logger log = LoggerFactory.getLogger(MedColdStoreMetrics.class);

    /** Gauge holding the number of rows in {@code med_message_archive}. */
    public static final String ROWS_GAUGE = "med_qa_cold_store_rows";

    /** Gauge holding the age of the cold store's oldest row, in seconds; {@code 0} when it is empty. */
    public static final String OLDEST_ROW_AGE_GAUGE = "med_qa_cold_store_oldest_row_age_seconds";

    /** Gauge holding the orphaned cold copies the last deleting reclaim run left behind. */
    public static final String RECLAIM_BACKLOG_GAUGE = "med_qa_cold_store_reclaim_backlog";

    private final SessionArchiveMapper archiveMapper;

    private final MeterRegistry meterRegistry;

    private final Clock clock;

    private final AtomicLong rows = new AtomicLong();

    private final AtomicLong oldestRowAgeSeconds = new AtomicLong();

    private final AtomicLong reclaimBacklog = new AtomicLong();

    private final AtomicBoolean sizeGaugesRegistered = new AtomicBoolean();

    private final AtomicBoolean backlogGaugeRegistered = new AtomicBoolean();

    /**
     * Creates the publisher over the application meter registry and the system clock.
     *
     * @param archiveMapper  MyBatis mapper over the cold archive tables, source of the two aggregates,
     *                       must not be {@code null}
     * @param meterRegistry  the application registry the gauges are published into, must not be
     *                       {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public MedColdStoreMetrics(SessionArchiveMapper archiveMapper, MeterRegistry meterRegistry) {
        this(archiveMapper, meterRegistry, Clock.systemUTC());
    }

    /**
     * Creates the publisher with an explicit clock, so the derived age is deterministic in tests.
     *
     * @param archiveMapper  MyBatis mapper over the cold archive tables, must not be {@code null}
     * @param meterRegistry  the application registry, must not be {@code null}
     * @param clock          the clock the oldest row's age is measured against, must not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public MedColdStoreMetrics(SessionArchiveMapper archiveMapper, MeterRegistry meterRegistry, Clock clock) {
        if (archiveMapper == null) {
            throw new IllegalArgumentException("archiveMapper must not be null");
        }
        if (meterRegistry == null) {
            throw new IllegalArgumentException("meterRegistry must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.archiveMapper = archiveMapper;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * Measures the cold store and refreshes {@value #ROWS_GAUGE} and {@value #OLDEST_ROW_AGE_GAUGE}.
     *
     * <p>Called by the D48 export and the D52 reclaim after every run that held the cluster mutex,
     * dry-run runs included: the size of the cold store is a fact about the data, not about an action, so
     * a deployment that only measures still reports it.</p>
     *
     * <p>A {@link DataAccessException} from either aggregate is logged and swallowed with the previous
     * values kept, because this runs after the run's own accounting is complete and must not be able to
     * turn a successful run into a failed one. Any other {@link RuntimeException} propagates: it is a
     * defect, not a storage outage.</p>
     */
    public void recordColdStoreSize() {
        long rowCount;
        long oldestCreatedAt;
        try {
            rowCount = Math.max(0L, archiveMapper.countArchivedMessages());
            oldestCreatedAt = archiveMapper.oldestArchivedMessageCreatedAt();
        } catch (DataAccessException ex) {
            log.warn("failed to measure the cold transcript store, keeping the previous "
                    + "med_qa_cold_store_* gauges", ex);
            return;
        }
        // An empty store has no oldest row (the aggregate reports 0); deriving an age from that would
        // read as "ancient data". A row stamped in the future (clock skew between replicas) clamps to 0.
        long ageSeconds = oldestCreatedAt <= 0L
                ? 0L
                : Math.max(0L, (clock.millis() - oldestCreatedAt) / 1000L);
        registerSizeGauges();
        rows.set(rowCount);
        oldestRowAgeSeconds.set(ageSeconds);
    }

    /**
     * Publishes the orphaned-cold-copy backlog a <em>deleting</em> reclaim run left behind.
     *
     * <p>The caller passes the run report's own backlog figure, which the reclaim service derives from
     * {@code countReclaimableOrphans} — the same number the run reports as its {@code remaining} — so no
     * second scan of the candidate predicate is introduced. A dry run must not call this: see the class
     * javadoc for why a non-draining backlog is only meaningful from a run that was allowed to drain
     * it.</p>
     *
     * @param backlog the number of orphaned cold copies still present, must not be negative
     * @throws IllegalArgumentException if {@code backlog} is negative
     */
    public void recordReclaimBacklog(long backlog) {
        if (backlog < 0) {
            throw new IllegalArgumentException("backlog must not be negative but was " + backlog);
        }
        registerBacklogGauge();
        reclaimBacklog.set(backlog);
    }

    /**
     * Registers the two size gauges exactly once.
     *
     * <p>Lazy on purpose: a deployment that never ran a maintenance job must not export a cold-store
     * series at all, and {@link Gauge#register} returning an existing meter of the same name means a
     * second call would silently keep the first state object — which is fine here, because there is
     * exactly one instance and exactly one set of atomics.</p>
     */
    private void registerSizeGauges() {
        if (sizeGaugesRegistered.compareAndSet(false, true)) {
            Gauge.builder(ROWS_GAUGE, rows, AtomicLong::doubleValue)
                    .description("Rows currently held by the cold transcript store "
                            + "(med_message_archive)")
                    .register(meterRegistry);
            Gauge.builder(OLDEST_ROW_AGE_GAUGE, oldestRowAgeSeconds, AtomicLong::doubleValue)
                    .description("Age of the oldest row in the cold transcript store, in seconds; "
                            + "0 when the store is empty")
                    .register(meterRegistry);
        }
    }

    /**
     * Registers the backlog gauge exactly once, on the first deleting run that reports one.
     *
     * <p>Separate from {@link #registerSizeGauges()} so an export-only deployment (the reclaim switched
     * off) never publishes a backlog series it has never measured.</p>
     */
    private void registerBacklogGauge() {
        if (backlogGaugeRegistered.compareAndSet(false, true)) {
            Gauge.builder(RECLAIM_BACKLOG_GAUGE, reclaimBacklog, AtomicLong::doubleValue)
                    .description("Orphaned cold copies still present after the most recent reclaim run "
                            + "that was allowed to delete")
                    .register(meterRegistry);
        }
    }
}
