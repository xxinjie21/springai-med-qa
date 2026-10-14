package com.med.qa.service;

import com.med.qa.alert.MedAlertNotifier;
import com.med.qa.alert.MedAlertSeverity;
import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.job.MedJobRun;
import com.med.qa.job.MedJobRunLedger;
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
import static org.mockito.Mockito.when;

/**
 * Tests of {@link MedSessionRetentionScheduler}.
 *
 * <p>The scheduler is the only part of the retention story that runs unattended, so what is pinned here
 * is the alerting contract: a run that archived something announces itself, a quiet run and a run that
 * stepped aside for another replica do not, and a run that failed says so instead of looking like a
 * hospital with no abandoned sessions. The last case is the point of the class — a retention job that
 * stopped running and a hospital that needs no retention look identical from the outside.</p>
 *
 * <p>D53 adds the half of that contract no alert can carry: the same three outcomes are also pinned in the
 * run ledger, whose staleness is what a dead job cannot suppress. The ledger is exercised for real over a
 * {@code SimpleMeterRegistry} rather than mocked, because the assertion that matters is the meter a
 * Prometheus scrape would read.</p>
 */
class MedSessionRetentionSchedulerTest {

    private static final Instant BOOT = Instant.parse("2026-10-14T09:00:00Z");

    private static final long BOOT_EPOCH_SECONDS = BOOT.getEpochSecond();

    private MedSessionRetentionService retentionService;

    private MedAlertNotifier notifier;

    private ObjectProvider<MedAlertNotifier> notifierProvider;

    private SimpleMeterRegistry registry;

    private MedJobRunLedger ledger;

    private MedSessionRetentionScheduler scheduler;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        retentionService = mock(MedSessionRetentionService.class);
        notifier = mock(MedAlertNotifier.class);
        notifierProvider = mock(ObjectProvider.class);
        when(notifierProvider.getIfAvailable()).thenReturn(notifier);
        registry = new SimpleMeterRegistry();
        ledger = new MedJobRunLedger(registry, Clock.fixed(BOOT, ZoneOffset.UTC));
        scheduler = new MedSessionRetentionScheduler(retentionService, notifierProvider, ledger,
                Duration.ofHours(1));
    }

    @Test
    @DisplayName("a run that archived sessions raises an informational alert")
    void archivedRunRaisesAnInfoAlert() {
        when(retentionService.sweepOnce()).thenReturn(report(3, 0, 0, 1, 0L));

        Optional<SessionRetentionReport> result = scheduler.sweepNow();

        assertThat(result).isPresent();
        assertThat(result.get().archived()).isEqualTo(3);
        assertThat(scheduler.lastReport()).contains(result.get());
        verify(notifier).raise(eq(MedAlertSeverity.INFO),
                eq(MedSessionRetentionScheduler.SESSION_RETENTION_COMPLETED),
                eq(MedSessionRetentionScheduler.COMPONENT), contains("archived=3"));
    }

    @Test
    @DisplayName("a quiet run raises nothing")
    void quietRunRaisesNothing() {
        when(retentionService.sweepOnce()).thenReturn(report(0, 0, 0, 1, 0L));

        assertThat(scheduler.sweepNow()).isPresent();
        verify(notifier, never()).raise(any(MedAlertSeverity.class), anyString(), anyString(),
                anyString());
    }

    @Test
    @DisplayName("a dry run that found candidates raises nothing, or a measuring deployment pages")
    void dryRunRaisesNothing() {
        when(retentionService.sweepOnce()).thenReturn(new SessionRetentionReport(
                SessionRetentionReport.Outcome.COMPLETED, true, 1_000L, 5, 0, 0, 0, 1, 5L, 2L,
                List.of()));

        assertThat(scheduler.sweepNow()).isPresent();
        verify(notifier, never()).raise(any(MedAlertSeverity.class), anyString(), anyString(),
                anyString());
    }

    @Test
    @DisplayName("a run that stepped aside for another replica is not an incident")
    void skippedRunRaisesNothing() {
        when(retentionService.sweepOnce()).thenReturn(SessionRetentionReport.skipped(1_000L, 3L));

        Optional<SessionRetentionReport> result = scheduler.sweepNow();

        assertThat(result).isPresent();
        assertThat(result.get().outcome())
                .isEqualTo(SessionRetentionReport.Outcome.SKIPPED_LOCK_HELD);
        verify(notifier, never()).raise(any(MedAlertSeverity.class), anyString(), anyString(),
                anyString());
    }

    @Test
    @DisplayName("a failing sweep is alerted and never propagates to the scheduler thread")
    void failingSweepIsAlerted() {
        when(retentionService.sweepOnce())
                .thenThrow(new BizException(ErrorCode.STORAGE_ERROR, "mysql down"));

        assertThat(scheduler.sweepNow()).isEmpty();
        assertThat(scheduler.lastReport()).isEmpty();
        verify(notifier).raise(eq(MedAlertSeverity.WARNING),
                eq(MedSessionRetentionScheduler.SESSION_RETENTION_FAILED),
                eq(MedSessionRetentionScheduler.COMPONENT), contains("mysql down"));
    }

    @Test
    @DisplayName("the scheduled entry point swallows a failure as well")
    void scheduledEntryPointSwallowsFailures() {
        when(retentionService.sweepOnce()).thenThrow(new IllegalStateException("boom"));

        scheduler.scheduledSweep();

        verify(retentionService).sweepOnce();
        verify(notifier).raise(eq(MedAlertSeverity.WARNING),
                eq(MedSessionRetentionScheduler.SESSION_RETENTION_FAILED),
                eq(MedSessionRetentionScheduler.COMPONENT), contains("IllegalStateException"));
    }

    @Test
    @DisplayName("without an alert notifier the sweep still runs and reports")
    void worksWithoutAnAlertChain() {
        when(notifierProvider.getIfAvailable()).thenReturn(null);
        when(retentionService.sweepOnce()).thenReturn(report(1, 0, 0, 1, 0L));

        assertThat(scheduler.sweepNow()).isPresent();
        assertThat(scheduler.lastReport()).isPresent();
    }

    @Test
    @DisplayName("the constructor refuses a null collaborator")
    void constructorRejectsNulls() {
        assertThatThrownBy(() -> new MedSessionRetentionScheduler(null, notifierProvider, ledger,
                Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retentionService");
        assertThatThrownBy(() -> new MedSessionRetentionScheduler(retentionService, null, ledger,
                Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("notifier");
        assertThatThrownBy(() -> new MedSessionRetentionScheduler(retentionService, notifierProvider,
                null, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ledger");
    }

    // ------------------------------------------------------------------ the run ledger (D53)

    @Test
    @DisplayName("building the scheduler registers the job, so a job that never runs still has a series")
    void buildingTheSchedulerRegistersTheJob() {
        assertThat(ledger.registeredJobs()).containsExactly(MedSessionRetentionScheduler.COMPONENT);
        assertThat(ledger.expectedIntervalSeconds(MedSessionRetentionScheduler.COMPONENT))
                .isEqualTo(3600d);
        assertThat(ledger.lastRunEpochSeconds(MedSessionRetentionScheduler.COMPONENT))
                .isEqualTo(BOOT_EPOCH_SECONDS);
    }

    @Test
    @DisplayName("a completed run advances both ledger timestamps")
    void completedRunAdvancesBothTimestamps() {
        when(retentionService.sweepOnce()).thenReturn(report(3, 0, 0, 1, 0L));

        scheduler.sweepNow();

        assertThat(ledger.lastRunEpochSeconds(MedSessionRetentionScheduler.COMPONENT))
                .isGreaterThan(BOOT_EPOCH_SECONDS);
        assertThat(ledger.lastSuccessEpochSeconds(MedSessionRetentionScheduler.COMPONENT))
                .isEqualTo(ledger.lastRunEpochSeconds(MedSessionRetentionScheduler.COMPONENT));
    }

    @Test
    @DisplayName("a run that stepped aside counts as an attempt but never as a success")
    void skippedRunAdvancesOnlyTheAttempt() {
        // Losing the cluster mutex is the mutex working, so the attempt stamp has to move; counting it as a
        // success would hide a leaked lock key that turned every replica into a permanent no-op.
        when(retentionService.sweepOnce()).thenReturn(SessionRetentionReport.skipped(1_000L, 3L));

        scheduler.sweepNow();

        assertThat(ledger.lastRunEpochSeconds(MedSessionRetentionScheduler.COMPONENT))
                .isGreaterThan(BOOT_EPOCH_SECONDS);
        assertThat(ledger.lastSuccessEpochSeconds(MedSessionRetentionScheduler.COMPONENT))
                .isEqualTo(BOOT_EPOCH_SECONDS);
    }

    @Test
    @DisplayName("a failing run is recorded as failed before it is alerted")
    void failingRunIsRecordedBeforeItIsAlerted() {
        // The alert chain can be broken by the very incident that breaks the job (D43: every sink failing at
        // once), and the attempt timestamp is then the only evidence left that the sweep is still being
        // attempted. Hence the order, asserted rather than commented.
        MedJobRunLedger spiedLedger =
                spy(new MedJobRunLedger(registry, Clock.fixed(BOOT, ZoneOffset.UTC)));
        MedSessionRetentionScheduler schedulerWithSpy =
                new MedSessionRetentionScheduler(retentionService, notifierProvider, spiedLedger,
                        Duration.ofHours(1));
        when(retentionService.sweepOnce()).thenThrow(new IllegalStateException("boom"));

        assertThat(schedulerWithSpy.sweepNow()).isEmpty();

        InOrder order = inOrder(spiedLedger, notifier);
        order.verify(spiedLedger).record(any(MedJobRun.class));
        order.verify(notifier).raise(any(MedAlertSeverity.class), anyString(), anyString(), anyString());
        assertThat(spiedLedger.lastSuccessEpochSeconds(MedSessionRetentionScheduler.COMPONENT))
                .isEqualTo(BOOT_EPOCH_SECONDS);
    }

    private static SessionRetentionReport report(int archived, int skipped, int failed, int batches,
                                                 long remaining) {
        return new SessionRetentionReport(SessionRetentionReport.Outcome.COMPLETED, false, 1_000L,
                archived + skipped + failed, archived, skipped, failed, batches, remaining, 7L,
                List.of());
    }
}
