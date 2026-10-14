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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests of {@link MedSessionReclaimScheduler} (D52).
 *
 * <p>This is the part of the reclaim story that runs unattended, and it drives the second job in the
 * project that deletes clinical data, so what is pinned here is the alerting contract: a run that
 * reclaimed something announces itself (but only when it really deleted — never for a dry run), a quiet
 * run and a run that stepped aside for another replica do not, a run that threw says so, and — the case
 * that matters most — a run that reclaimed nine copies and refused one raises the refusal warning
 * <em>as well as</em> the completion notice, because the refusal is the part that needs a human.</p>
 *
 * <p>D53 adds the half of that contract no alert can carry: the same three outcomes are also pinned in the
 * run ledger, whose staleness is what a dead job cannot suppress. The ledger is exercised for real over a
 * {@code SimpleMeterRegistry} rather than mocked, because the assertion that matters is the meter a
 * Prometheus scrape would read.</p>
 */
class MedSessionReclaimSchedulerTest {

    private static final Instant BOOT = Instant.parse("2026-10-14T09:00:00Z");

    private static final long BOOT_EPOCH_SECONDS = BOOT.getEpochSecond();

    private MedSessionReclaimService reclaimService;

    private MedAlertNotifier notifier;

    private ObjectProvider<MedAlertNotifier> notifierProvider;

    private SimpleMeterRegistry registry;

    private MedJobRunLedger ledger;

    private MedSessionReclaimScheduler scheduler;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        reclaimService = mock(MedSessionReclaimService.class);
        notifier = mock(MedAlertNotifier.class);
        notifierProvider = mock(ObjectProvider.class);
        when(notifierProvider.getIfAvailable()).thenReturn(notifier);
        registry = new SimpleMeterRegistry();
        ledger = new MedJobRunLedger(registry, Clock.fixed(BOOT, ZoneOffset.UTC));
        scheduler = new MedSessionReclaimScheduler(reclaimService, notifierProvider, ledger,
                Duration.ofHours(12));
    }

    @Test
    @DisplayName("a run that reclaimed orphaned copies raises an informational alert")
    void reclaimedRunRaisesAnInfoAlert() {
        when(reclaimService.reclaimOnce()).thenReturn(report(false, 3, 0, 0, 0, 0L));

        Optional<SessionReclaimReport> result = scheduler.reclaimNow();

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().reclaimed()).isEqualTo(3);
        verify(notifier).raise(eq(MedAlertSeverity.INFO),
                eq(MedSessionReclaimScheduler.SESSION_RECLAIM_COMPLETED),
                eq(MedSessionReclaimScheduler.COMPONENT), anyString());
    }

    @Test
    @DisplayName("a quiet run stays silent, because 'nothing to reclaim' is the normal case")
    void quietRunIsSilent() {
        when(reclaimService.reclaimOnce()).thenReturn(report(false, 0, 0, 0, 0, 0L));

        assertThat(scheduler.reclaimNow()).isPresent();

        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("a dry run never announces a completion, however many copies it would retire")
    void dryRunIsSilentAboutCompletions() {
        when(reclaimService.reclaimOnce()).thenReturn(report(true, 12, 0, 0, 0, 0L));

        assertThat(scheduler.reclaimNow()).isPresent();

        verify(notifier, never()).raise(eq(MedAlertSeverity.INFO), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a refusal is a warning and is raised even when the same run reclaimed copies")
    void refusalIsAWarning() {
        when(reclaimService.reclaimOnce()).thenReturn(report(false, 9, 0, 1, 0, 0L));

        assertThat(scheduler.reclaimNow()).isPresent();

        // The refusal first, and independently of the completion notice: a run can reclaim a hundred
        // copies and still have refused one, and the refusal is the part that needs attention.
        inOrder(notifier).verify(notifier).raise(eq(MedAlertSeverity.WARNING),
                eq(MedSessionReclaimScheduler.SESSION_RECLAIM_REFUSED),
                eq(MedSessionReclaimScheduler.COMPONENT), contains("1 orphaned cold copy"));
        verify(notifier).raise(eq(MedAlertSeverity.INFO),
                eq(MedSessionReclaimScheduler.SESSION_RECLAIM_COMPLETED),
                eq(MedSessionReclaimScheduler.COMPONENT), anyString());
    }

    @Test
    @DisplayName("a refusal alone is a warning and nothing else")
    void refusalAloneDoesNotAnnounceACompletion() {
        when(reclaimService.reclaimOnce()).thenReturn(report(false, 0, 0, 2, 0, 0L));

        assertThat(scheduler.reclaimNow()).isPresent();

        verify(notifier).raise(eq(MedAlertSeverity.WARNING),
                eq(MedSessionReclaimScheduler.SESSION_RECLAIM_REFUSED),
                eq(MedSessionReclaimScheduler.COMPONENT), anyString());
        verify(notifier, never()).raise(eq(MedAlertSeverity.INFO), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a run that stepped aside for another replica is logged only, never alerted")
    void lockHeldRunIsNotAlerted() {
        when(reclaimService.reclaimOnce()).thenReturn(SessionReclaimReport.skipped(7));

        assertThat(scheduler.reclaimNow()).isPresent();

        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("a throwing run is alerted with its error code and reported as empty")
    void throwingRunIsAlerted() {
        when(reclaimService.reclaimOnce())
                .thenThrow(new BizException(ErrorCode.STORAGE_ERROR, "mysql went away"));

        Optional<SessionReclaimReport> result = scheduler.reclaimNow();

        assertThat(result).isEmpty();
        assertThat(scheduler.lastReport()).isEmpty();
        verify(notifier).raise(eq(MedAlertSeverity.WARNING),
                eq(MedSessionReclaimScheduler.SESSION_RECLAIM_FAILED),
                eq(MedSessionReclaimScheduler.COMPONENT), contains("STORAGE_ERROR"));
    }

    @Test
    @DisplayName("the last report is remembered, so an operator can ask what the job just did")
    void lastReportIsRemembered() {
        assertThat(scheduler.lastReport()).isEmpty();
        when(reclaimService.reclaimOnce()).thenReturn(report(false, 1, 0, 0, 0, 0L));

        scheduler.reclaimNow();

        assertThat(scheduler.lastReport()).isPresent();
        assertThat(scheduler.lastReport().orElseThrow().reclaimed()).isEqualTo(1);
    }

    @Test
    @DisplayName("the scheduled entry point delegates to the same run")
    void scheduledEntryPointDelegates() {
        when(reclaimService.reclaimOnce()).thenReturn(report(false, 0, 0, 0, 0, 0L));

        scheduler.scheduledReclaim();

        verify(reclaimService).reclaimOnce();
    }

    @Test
    @DisplayName("a context without the alert chain still reclaims and simply has nowhere to push")
    void missingNotifierIsTolerated() {
        when(notifierProvider.getIfAvailable()).thenReturn(null);
        when(reclaimService.reclaimOnce()).thenReturn(report(false, 2, 0, 1, 0, 0L));

        assertThat(scheduler.reclaimNow()).isPresent();

        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("the constructor refuses a null collaborator")
    void constructorRejectsNulls() {
        assertThatThrownBy(() -> new MedSessionReclaimScheduler(null, notifierProvider, ledger,
                Duration.ofHours(12)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reclaimService");
        assertThatThrownBy(() -> new MedSessionReclaimScheduler(reclaimService, null, ledger,
                Duration.ofHours(12)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("notifier");
        assertThatThrownBy(() -> new MedSessionReclaimScheduler(reclaimService, notifierProvider, null,
                Duration.ofHours(12)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ledger");
    }

    // ------------------------------------------------------------------ the run ledger (D53)

    @Test
    @DisplayName("building the scheduler registers the job, so a job that never runs still has a series")
    void buildingTheSchedulerRegistersTheJob() {
        assertThat(ledger.registeredJobs()).containsExactly(MedSessionReclaimScheduler.COMPONENT);
        assertThat(ledger.expectedIntervalSeconds(MedSessionReclaimScheduler.COMPONENT))
                .isEqualTo(43_200d);
        assertThat(ledger.lastRunEpochSeconds(MedSessionReclaimScheduler.COMPONENT))
                .isEqualTo(BOOT_EPOCH_SECONDS);
    }

    @Test
    @DisplayName("a completed run advances both ledger timestamps")
    void completedRunAdvancesBothTimestamps() {
        when(reclaimService.reclaimOnce()).thenReturn(report(false, 3, 0, 0, 0, 0L));

        scheduler.reclaimNow();

        assertThat(ledger.lastRunEpochSeconds(MedSessionReclaimScheduler.COMPONENT))
                .isGreaterThan(BOOT_EPOCH_SECONDS);
        assertThat(ledger.lastSuccessEpochSeconds(MedSessionReclaimScheduler.COMPONENT))
                .isEqualTo(ledger.lastRunEpochSeconds(MedSessionReclaimScheduler.COMPONENT));
    }

    @Test
    @DisplayName("a run that stepped aside counts as an attempt but never as a success")
    void skippedRunAdvancesOnlyTheAttempt() {
        when(reclaimService.reclaimOnce()).thenReturn(SessionReclaimReport.skipped(3L));

        scheduler.reclaimNow();

        assertThat(ledger.lastRunEpochSeconds(MedSessionReclaimScheduler.COMPONENT))
                .isGreaterThan(BOOT_EPOCH_SECONDS);
        assertThat(ledger.lastSuccessEpochSeconds(MedSessionReclaimScheduler.COMPONENT))
                .isEqualTo(BOOT_EPOCH_SECONDS);
    }

    @Test
    @DisplayName("a failing run is recorded as failed before it is alerted")
    void failingRunIsRecordedBeforeItIsAlerted() {
        // The alert chain can be broken by the very incident that breaks the job (D43: every sink failing at
        // once), and the attempt timestamp is then the only evidence left that the reclaim is still being
        // attempted. Hence the order, asserted rather than commented.
        MedJobRunLedger spiedLedger =
                spy(new MedJobRunLedger(registry, Clock.fixed(BOOT, ZoneOffset.UTC)));
        MedSessionReclaimScheduler schedulerWithSpy =
                new MedSessionReclaimScheduler(reclaimService, notifierProvider, spiedLedger,
                        Duration.ofHours(12));
        when(reclaimService.reclaimOnce()).thenThrow(new IllegalStateException("boom"));

        assertThat(schedulerWithSpy.reclaimNow()).isEmpty();

        InOrder order = inOrder(spiedLedger, notifier);
        order.verify(spiedLedger).record(any(MedJobRun.class));
        order.verify(notifier).raise(any(MedAlertSeverity.class), anyString(), anyString(), anyString());
        assertThat(spiedLedger.lastSuccessEpochSeconds(MedSessionReclaimScheduler.COMPONENT))
                .isEqualTo(BOOT_EPOCH_SECONDS);
    }

    private static SessionReclaimReport report(boolean dryRun, int reclaimed, int skipped, int refused,
                                               int failed, long remaining) {
        return new SessionReclaimReport(SessionReclaimReport.Outcome.COMPLETED, dryRun,
                reclaimed + skipped + refused + failed, reclaimed, reclaimed * 2L, skipped, refused, failed,
                1, remaining, 42,
                refused > 0 || failed > 0 ? List.of("s-1: REFUSED: not in the live transcript") : List.of());
    }
}
