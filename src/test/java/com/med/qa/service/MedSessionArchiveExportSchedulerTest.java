package com.med.qa.service;

import com.med.qa.alert.MedAlertNotifier;
import com.med.qa.alert.MedAlertSeverity;
import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.job.MedColdStoreMetrics;
import com.med.qa.job.MedJobRun;
import com.med.qa.job.MedJobRunLedger;
import com.med.qa.mapper.SessionArchiveMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests of {@link MedSessionArchiveExportScheduler}.
 *
 * <p>This is the only part of the archive story that runs unattended, so what is pinned here is the
 * alerting contract: a run that certified something announces itself, a quiet run and a run that stepped
 * aside for another replica do not, a run that failed says so, and — the case that matters most — a run
 * that certified nine sessions and refused one raises the mismatch warning <em>as well as</em> the
 * completion notice, because the refusal is the part that needs a human.</p>
 *
 * <p>D53 adds the half of that contract no alert can carry: the same three outcomes are also pinned in the
 * run ledger, whose staleness is what a dead job cannot suppress. The ledger is exercised for real over a
 * {@code SimpleMeterRegistry} rather than mocked, because the assertion that matters is the meter a
 * Prometheus scrape would read.</p>
 *
 * <p>D54 adds the export's share of the cold store's observability: the export runs more often than the
 * reclaim, so after every run that held the cluster mutex — dry-run runs included, because the size of
 * the cold store is a fact about the data rather than about an action — it refreshes the
 * {@code med_qa_cold_store_*} size gauges. A run that stepped aside for another replica records nothing:
 * the replica that held the mutex is doing the measuring.</p>
 */
class MedSessionArchiveExportSchedulerTest {

    private static final Instant BOOT = Instant.parse("2026-10-14T09:00:00Z");

    private static final long BOOT_EPOCH_SECONDS = BOOT.getEpochSecond();

    private MedSessionArchiveExportService exportService;

    private MedAlertNotifier notifier;

    private ObjectProvider<MedAlertNotifier> notifierProvider;

    private SimpleMeterRegistry registry;

    private MedJobRunLedger ledger;

    private SessionArchiveMapper archiveMapper;

    private MedColdStoreMetrics coldStoreMetrics;

    private MedSessionArchiveExportScheduler scheduler;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        exportService = mock(MedSessionArchiveExportService.class);
        notifier = mock(MedAlertNotifier.class);
        notifierProvider = mock(ObjectProvider.class);
        when(notifierProvider.getIfAvailable()).thenReturn(notifier);
        registry = new SimpleMeterRegistry();
        ledger = new MedJobRunLedger(registry, Clock.fixed(BOOT, ZoneOffset.UTC));
        // The cold-store publisher is exercised for real over the same registry, for the same reason as
        // the ledger: what is asserted is the meter a Prometheus scrape would read.
        archiveMapper = mock(SessionArchiveMapper.class);
        when(archiveMapper.countArchivedMessages()).thenReturn(9_001L);
        when(archiveMapper.oldestArchivedMessageCreatedAt())
                .thenReturn(BOOT.toEpochMilli() - 7_200_000L);
        coldStoreMetrics =
                new MedColdStoreMetrics(archiveMapper, registry, Clock.fixed(BOOT, ZoneOffset.UTC));
        scheduler = new MedSessionArchiveExportScheduler(exportService, notifierProvider, ledger,
                coldStoreMetrics, Duration.ofHours(6));
    }

    @Test
    @DisplayName("a run that certified transcripts raises an informational alert")
    void exportedRunRaisesAnInfoAlert() {
        when(exportService.exportOnce()).thenReturn(report(3, 0, 0, 1, 0L));

        Optional<SessionArchiveExportReport> result = scheduler.exportNow();

        assertThat(result).isPresent();
        assertThat(result.get().exported()).isEqualTo(3);
        assertThat(scheduler.lastReport()).contains(result.get());
        verify(notifier).raise(eq(MedAlertSeverity.INFO),
                eq(MedSessionArchiveExportScheduler.SESSION_ARCHIVE_COMPLETED),
                eq(MedSessionArchiveExportScheduler.COMPONENT), contains("exported=3"));
    }

    @Test
    @DisplayName("a quiet run raises nothing")
    void quietRunRaisesNothing() {
        when(exportService.exportOnce()).thenReturn(report(0, 0, 0, 1, 0L));

        assertThat(scheduler.exportNow()).isPresent();
        verify(notifier, never()).raise(any(MedAlertSeverity.class), anyString(), anyString(),
                anyString());
    }

    @Test
    @DisplayName("a dry run that found candidates raises nothing, or a measuring deployment pages")
    void dryRunRaisesNothing() {
        when(exportService.exportOnce()).thenReturn(new SessionArchiveExportReport(
                SessionArchiveExportReport.Outcome.COMPLETED, true, 5, 0, 0, 0, 0, 1, 5L, 2L,
                List.of()));

        assertThat(scheduler.exportNow()).isPresent();
        verify(notifier, never()).raise(any(MedAlertSeverity.class), anyString(), anyString(),
                anyString());
    }

    @Test
    @DisplayName("a run that stepped aside for another replica is not an incident")
    void skippedRunRaisesNothing() {
        when(exportService.exportOnce()).thenReturn(SessionArchiveExportReport.skipped(3L));

        Optional<SessionArchiveExportReport> result = scheduler.exportNow();

        assertThat(result).isPresent();
        assertThat(result.get().outcome())
                .isEqualTo(SessionArchiveExportReport.Outcome.SKIPPED_LOCK_HELD);
        verify(notifier, never()).raise(any(MedAlertSeverity.class), anyString(), anyString(),
                anyString());
    }

    @Test
    @DisplayName("a refused certification is alerted in addition to the completion notice")
    void mismatchIsAlertedAlongsideTheCompletionNotice() {
        when(exportService.exportOnce()).thenReturn(report(2, 1, 0, 1, 0L));

        assertThat(scheduler.exportNow()).isPresent();

        // A run can export a hundred sessions and still have refused one; the refusal must not be
        // averaged away by the sessions that exported cleanly. Order matters too: the warning first.
        var ordered = inOrder(notifier);
        ordered.verify(notifier).raise(eq(MedAlertSeverity.WARNING),
                eq(MedSessionArchiveExportScheduler.SESSION_ARCHIVE_MISMATCH),
                eq(MedSessionArchiveExportScheduler.COMPONENT), contains("refused certification"));
        ordered.verify(notifier).raise(eq(MedAlertSeverity.INFO),
                eq(MedSessionArchiveExportScheduler.SESSION_ARCHIVE_COMPLETED),
                eq(MedSessionArchiveExportScheduler.COMPONENT), anyString());
    }

    @Test
    @DisplayName("a refused certification on its own raises the warning and no completion notice")
    void mismatchWithoutAnExportRaisesOnlyTheWarning() {
        when(exportService.exportOnce()).thenReturn(report(0, 1, 0, 1, 0L));

        assertThat(scheduler.exportNow()).isPresent();

        verify(notifier).raise(eq(MedAlertSeverity.WARNING),
                eq(MedSessionArchiveExportScheduler.SESSION_ARCHIVE_MISMATCH),
                eq(MedSessionArchiveExportScheduler.COMPONENT), contains("mismatched=1"));
        verify(notifier, never()).raise(eq(MedAlertSeverity.INFO), anyString(), anyString(),
                anyString());
    }

    @Test
    @DisplayName("a failing run is alerted and never propagates to the scheduler thread")
    void failingRunIsAlerted() {
        when(exportService.exportOnce())
                .thenThrow(new BizException(ErrorCode.STORAGE_ERROR, "mysql down"));

        assertThat(scheduler.exportNow()).isEmpty();
        verify(notifier).raise(eq(MedAlertSeverity.WARNING),
                eq(MedSessionArchiveExportScheduler.SESSION_ARCHIVE_FAILED),
                eq(MedSessionArchiveExportScheduler.COMPONENT), contains("STORAGE_ERROR"));
    }

    @Test
    @DisplayName("a run that failed leaves the previous report in place instead of reporting a fake success")
    void failedRunKeepsThePreviousReport() {
        when(exportService.exportOnce()).thenReturn(report(1, 0, 0, 1, 0L));
        assertThat(scheduler.exportNow()).isPresent();

        when(exportService.exportOnce()).thenThrow(new IllegalStateException("boom"));
        assertThat(scheduler.exportNow()).isEmpty();

        assertThat(scheduler.lastReport()).isPresent();
        assertThat(scheduler.lastReport().orElseThrow().exported()).isEqualTo(1);
        // A failure that is not a BizException has no error code to render, so the class name is the
        // best the alert can do - asserted so the two rendering paths stay distinguishable.
        verify(notifier).raise(eq(MedAlertSeverity.WARNING),
                eq(MedSessionArchiveExportScheduler.SESSION_ARCHIVE_FAILED),
                eq(MedSessionArchiveExportScheduler.COMPONENT),
                contains("IllegalStateException: boom"));
    }

    @Test
    @DisplayName("no report has been recorded before the first run")
    void lastReportIsEmptyBeforeTheFirstRun() {
        assertThat(scheduler.lastReport()).isEmpty();
    }

    @Test
    @DisplayName("with alerting switched off the export still runs and simply has nowhere to push")
    void runsWithoutANotifier() {
        when(notifierProvider.getIfAvailable()).thenReturn(null);
        when(exportService.exportOnce()).thenReturn(report(1, 0, 0, 1, 0L));

        assertThat(scheduler.exportNow()).isPresent();
        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("no collaborator may be null")
    void collaboratorsMustNotBeNull() {
        assertThatThrownBy(() -> new MedSessionArchiveExportScheduler(null, notifierProvider, ledger,
                coldStoreMetrics, Duration.ofHours(6)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exportService must not be null");
        assertThatThrownBy(() -> new MedSessionArchiveExportScheduler(exportService, null, ledger,
                coldStoreMetrics, Duration.ofHours(6)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("notifier must not be null");
        assertThatThrownBy(() -> new MedSessionArchiveExportScheduler(exportService, notifierProvider,
                null, coldStoreMetrics, Duration.ofHours(6)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ledger must not be null");
        assertThatThrownBy(() -> new MedSessionArchiveExportScheduler(exportService, notifierProvider,
                ledger, null, Duration.ofHours(6)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("coldStoreMetrics must not be null");
    }

    // ------------------------------------------------------------------ the run ledger (D53)

    @Test
    @DisplayName("building the scheduler registers the job, so a job that never runs still has a series")
    void buildingTheSchedulerRegistersTheJob() {
        assertThat(ledger.registeredJobs())
                .containsExactly(MedSessionArchiveExportScheduler.COMPONENT);
        assertThat(ledger.expectedIntervalSeconds(MedSessionArchiveExportScheduler.COMPONENT))
                .isEqualTo(21_600d);
        assertThat(ledger.lastRunEpochSeconds(MedSessionArchiveExportScheduler.COMPONENT))
                .isEqualTo(BOOT_EPOCH_SECONDS);
    }

    @Test
    @DisplayName("a completed run advances both ledger timestamps")
    void completedRunAdvancesBothTimestamps() {
        when(exportService.exportOnce()).thenReturn(report(3, 0, 0, 1, 0L));

        scheduler.exportNow();

        assertThat(ledger.lastRunEpochSeconds(MedSessionArchiveExportScheduler.COMPONENT))
                .isGreaterThan(BOOT_EPOCH_SECONDS);
        assertThat(ledger.lastSuccessEpochSeconds(MedSessionArchiveExportScheduler.COMPONENT))
                .isEqualTo(ledger.lastRunEpochSeconds(MedSessionArchiveExportScheduler.COMPONENT));
    }

    @Test
    @DisplayName("a run that stepped aside counts as an attempt but never as a success")
    void skippedRunAdvancesOnlyTheAttempt() {
        when(exportService.exportOnce()).thenReturn(SessionArchiveExportReport.skipped(3L));

        scheduler.exportNow();

        assertThat(ledger.lastRunEpochSeconds(MedSessionArchiveExportScheduler.COMPONENT))
                .isGreaterThan(BOOT_EPOCH_SECONDS);
        assertThat(ledger.lastSuccessEpochSeconds(MedSessionArchiveExportScheduler.COMPONENT))
                .isEqualTo(BOOT_EPOCH_SECONDS);
    }

    @Test
    @DisplayName("a failing run is recorded as failed before it is alerted")
    void failingRunIsRecordedBeforeItIsAlerted() {
        // The alert chain can be broken by the very incident that breaks the job (D43: every sink failing at
        // once), and the attempt timestamp is then the only evidence left that the export is still being
        // attempted. Hence the order, asserted rather than commented.
        MedJobRunLedger spiedLedger =
                spy(new MedJobRunLedger(registry, Clock.fixed(BOOT, ZoneOffset.UTC)));
        MedSessionArchiveExportScheduler schedulerWithSpy =
                new MedSessionArchiveExportScheduler(exportService, notifierProvider, spiedLedger,
                        coldStoreMetrics, Duration.ofHours(6));
        when(exportService.exportOnce()).thenThrow(new IllegalStateException("boom"));

        assertThat(schedulerWithSpy.exportNow()).isEmpty();

        InOrder order = inOrder(spiedLedger, notifier);
        order.verify(spiedLedger).record(any(MedJobRun.class));
        order.verify(notifier).raise(any(MedAlertSeverity.class), anyString(), anyString(), anyString());
        assertThat(spiedLedger.lastSuccessEpochSeconds(MedSessionArchiveExportScheduler.COMPONENT))
                .isEqualTo(BOOT_EPOCH_SECONDS);
    }

    // ------------------------------------------------------------------ the cold store (D54)

    @Test
    @DisplayName("a completed run refreshes the cold store's size gauges, dry run included")
    void completedRunRefreshesTheSizeGauges() {
        when(exportService.exportOnce()).thenReturn(report(3, 0, 0, 1, 0L));

        scheduler.exportNow();

        assertThat(gauge(MedColdStoreMetrics.ROWS_GAUGE)).isEqualTo(9_001d);
        assertThat(gauge(MedColdStoreMetrics.OLDEST_ROW_AGE_GAUGE)).isEqualTo(7_200d);
        // The export never owns the reclaim backlog: publishing it here would attribute a number to a
        // job that did not measure it.
        assertThat(registry.find(MedColdStoreMetrics.RECLAIM_BACKLOG_GAUGE).gauge()).isNull();
    }

    @Test
    @DisplayName("a dry run refreshes the size gauges too, because size is a fact about the data")
    void dryRunRefreshesTheSizeGauges() {
        when(exportService.exportOnce())
                .thenReturn(new SessionArchiveExportReport(
                        SessionArchiveExportReport.Outcome.COMPLETED, true, 5, 0, 0, 0, 0, 1, 5L, 2L,
                        List.of()));

        scheduler.exportNow();

        assertThat(gauge(MedColdStoreMetrics.ROWS_GAUGE)).isEqualTo(9_001d);
        assertThat(gauge(MedColdStoreMetrics.OLDEST_ROW_AGE_GAUGE)).isEqualTo(7_200d);
    }

    @Test
    @DisplayName("a run that stepped aside publishes nothing: the replica holding the mutex measures")
    void skippedRunPublishesNothing() {
        when(exportService.exportOnce()).thenReturn(SessionArchiveExportReport.skipped(3L));

        scheduler.exportNow();

        assertThat(registry.find(MedColdStoreMetrics.ROWS_GAUGE).gauge()).isNull();
        assertThat(registry.find(MedColdStoreMetrics.OLDEST_ROW_AGE_GAUGE).gauge()).isNull();
    }

    @Test
    @DisplayName("a failed run publishes nothing, so a stale gauge cannot look like a fresh measurement")
    void failedRunPublishesNothing() {
        when(exportService.exportOnce())
                .thenThrow(new BizException(ErrorCode.STORAGE_ERROR, "mysql down"));

        assertThat(scheduler.exportNow()).isEmpty();

        assertThat(registry.find(MedColdStoreMetrics.ROWS_GAUGE).gauge()).isNull();
        assertThat(registry.find(MedColdStoreMetrics.OLDEST_ROW_AGE_GAUGE).gauge()).isNull();
    }

    /** Reads one gauge out of the test registry, the way a Prometheus scrape would. */
    private double gauge(String name) {
        var gauge = registry.find(name).gauge();
        assertThat(gauge).as("gauge %s must be registered", name).isNotNull();
        return gauge.value();
    }

    private static SessionArchiveExportReport report(int exported, int mismatched, int failed,
                                                     int batches, long remaining) {
        return new SessionArchiveExportReport(SessionArchiveExportReport.Outcome.COMPLETED, false,
                exported + mismatched + failed, exported, 0, mismatched, failed, batches, remaining,
                7L, List.of());
    }
}
