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
 * Tests of {@link MedSessionPurgeScheduler}.
 *
 * <p>This is the only part of the purge story that runs unattended, and it drives the one job that
 * deletes clinical data, so what is pinned here is the alerting contract: a run that retired something
 * announces itself (but only when it really deleted — never for a dry run), a quiet run and a run that
 * stepped aside for another replica do not, a run that threw says so, and — the case that matters most —
 * a run that retired nine sessions and refused one raises the refusal warning <em>as well as</em> the
 * completion notice, because the refusal is the part that needs a human.</p>
 *
 * <p>D53 adds the half of that contract no alert can carry: the same three outcomes are also pinned in the
 * run ledger, whose staleness is what a dead job cannot suppress. It matters most for this job — a purge
 * that stopped running leaves no trace in any of its own alerts. The ledger is exercised for real over a
 * {@code SimpleMeterRegistry} rather than mocked, because the assertion that matters is the meter a
 * Prometheus scrape would read.</p>
 */
class MedSessionPurgeSchedulerTest {

    private static final Instant BOOT = Instant.parse("2026-10-14T09:00:00Z");

    private static final long BOOT_EPOCH_SECONDS = BOOT.getEpochSecond();

    private MedSessionPurgeService purgeService;

    private MedAlertNotifier notifier;

    private ObjectProvider<MedAlertNotifier> notifierProvider;

    private SimpleMeterRegistry registry;

    private MedJobRunLedger ledger;

    private MedSessionPurgeScheduler scheduler;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        purgeService = mock(MedSessionPurgeService.class);
        notifier = mock(MedAlertNotifier.class);
        notifierProvider = mock(ObjectProvider.class);
        when(notifierProvider.getIfAvailable()).thenReturn(notifier);
        registry = new SimpleMeterRegistry();
        ledger = new MedJobRunLedger(registry, Clock.fixed(BOOT, ZoneOffset.UTC));
        scheduler = new MedSessionPurgeScheduler(purgeService, notifierProvider, ledger,
                Duration.ofHours(12));
    }

    @Test
    @DisplayName("a run that retired transcripts raises an informational alert")
    void purgedRunRaisesAnInfoAlert() {
        when(purgeService.purgeOnce()).thenReturn(report(false, 3, 0, 0, 0, 0L));

        Optional<SessionPurgeReport> result = scheduler.purgeNow();

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().purged()).isEqualTo(3);
        verify(notifier).raise(eq(MedAlertSeverity.INFO), eq(MedSessionPurgeScheduler.SESSION_PURGE_COMPLETED),
                eq(MedSessionPurgeScheduler.COMPONENT), anyString());
    }

    @Test
    @DisplayName("a quiet run stays silent, because 'nothing to purge' is the normal case")
    void quietRunIsSilent() {
        when(purgeService.purgeOnce()).thenReturn(report(false, 0, 0, 0, 0, 0L));

        assertThat(scheduler.purgeNow()).isPresent();

        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("a dry run never announces a completion, however many sessions it would retire")
    void dryRunIsSilentAboutCompletions() {
        when(purgeService.purgeOnce()).thenReturn(report(true, 12, 0, 0, 0, 0L));

        assertThat(scheduler.purgeNow()).isPresent();

        verify(notifier, never()).raise(eq(MedAlertSeverity.INFO), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a refusal is a warning and is raised even when the same run retired sessions")
    void refusalIsAWarning() {
        when(purgeService.purgeOnce()).thenReturn(report(false, 9, 1, 1, 0, 0L));

        assertThat(scheduler.purgeNow()).isPresent();

        // The refusal first, and independently of the completion notice: a run can retire a hundred
        // sessions and still have refused one, and the refusal is the part that needs attention.
        inOrder(notifier).verify(notifier).raise(eq(MedAlertSeverity.WARNING),
                eq(MedSessionPurgeScheduler.SESSION_PURGE_REFUSED), eq(MedSessionPurgeScheduler.COMPONENT),
                contains("1 certified transcript"));
        verify(notifier).raise(eq(MedAlertSeverity.INFO), eq(MedSessionPurgeScheduler.SESSION_PURGE_COMPLETED),
                eq(MedSessionPurgeScheduler.COMPONENT), anyString());
    }

    @Test
    @DisplayName("a refusal alone is a warning and nothing else")
    void refusalAloneDoesNotAnnounceACompletion() {
        when(purgeService.purgeOnce()).thenReturn(report(false, 0, 0, 2, 0, 0L));

        assertThat(scheduler.purgeNow()).isPresent();

        verify(notifier).raise(eq(MedAlertSeverity.WARNING), eq(MedSessionPurgeScheduler.SESSION_PURGE_REFUSED),
                eq(MedSessionPurgeScheduler.COMPONENT), anyString());
        verify(notifier, never()).raise(eq(MedAlertSeverity.INFO), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a run that stepped aside for another replica is logged only, never alerted")
    void lockHeldRunIsNotAlerted() {
        when(purgeService.purgeOnce())
                .thenReturn(SessionPurgeReport.skipped(1_735_000_000_000L, 7));

        assertThat(scheduler.purgeNow()).isPresent();

        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("a throwing run is alerted with its error code and reported as empty")
    void throwingRunIsAlerted() {
        when(purgeService.purgeOnce())
                .thenThrow(new BizException(ErrorCode.STORAGE_ERROR, "mysql went away"));

        Optional<SessionPurgeReport> result = scheduler.purgeNow();

        assertThat(result).isEmpty();
        assertThat(scheduler.lastReport()).isEmpty();
        verify(notifier).raise(eq(MedAlertSeverity.WARNING), eq(MedSessionPurgeScheduler.SESSION_PURGE_FAILED),
                eq(MedSessionPurgeScheduler.COMPONENT), contains("STORAGE_ERROR"));
    }

    @Test
    @DisplayName("the last report is remembered, so an operator can ask what the job just did")
    void lastReportIsRemembered() {
        assertThat(scheduler.lastReport()).isEmpty();
        when(purgeService.purgeOnce()).thenReturn(report(false, 1, 0, 0, 0, 0L));

        scheduler.purgeNow();

        assertThat(scheduler.lastReport()).isPresent();
        assertThat(scheduler.lastReport().orElseThrow().purged()).isEqualTo(1);
    }

    @Test
    @DisplayName("the scheduled entry point delegates to the same run")
    void scheduledEntryPointDelegates() {
        when(purgeService.purgeOnce()).thenReturn(report(false, 0, 0, 0, 0, 0L));

        scheduler.scheduledPurge();

        verify(purgeService).purgeOnce();
    }

    @Test
    @DisplayName("a context without the alert chain still purges and simply has nowhere to push")
    void missingNotifierIsTolerated() {
        when(notifierProvider.getIfAvailable()).thenReturn(null);
        when(purgeService.purgeOnce()).thenReturn(report(false, 2, 0, 1, 0, 0L));

        assertThat(scheduler.purgeNow()).isPresent();

        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("the constructor refuses a null collaborator")
    void constructorRejectsNulls() {
        assertThatThrownBy(() -> new MedSessionPurgeScheduler(null, notifierProvider, ledger,
                Duration.ofHours(12)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("purgeService");
        assertThatThrownBy(() -> new MedSessionPurgeScheduler(purgeService, null, ledger,
                Duration.ofHours(12)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("notifier");
        assertThatThrownBy(() -> new MedSessionPurgeScheduler(purgeService, notifierProvider, null,
                Duration.ofHours(12)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ledger");
    }

    // ------------------------------------------------------------------ the run ledger (D53)

    @Test
    @DisplayName("building the scheduler registers the job, so a job that never runs still has a series")
    void buildingTheSchedulerRegistersTheJob() {
        assertThat(ledger.registeredJobs()).containsExactly(MedSessionPurgeScheduler.COMPONENT);
        assertThat(ledger.expectedIntervalSeconds(MedSessionPurgeScheduler.COMPONENT))
                .isEqualTo(43_200d);
        assertThat(ledger.lastRunEpochSeconds(MedSessionPurgeScheduler.COMPONENT))
                .isEqualTo(BOOT_EPOCH_SECONDS);
    }

    @Test
    @DisplayName("a completed run advances both ledger timestamps")
    void completedRunAdvancesBothTimestamps() {
        when(purgeService.purgeOnce()).thenReturn(report(false, 3, 0, 0, 0, 0L));

        scheduler.purgeNow();

        assertThat(ledger.lastRunEpochSeconds(MedSessionPurgeScheduler.COMPONENT))
                .isGreaterThan(BOOT_EPOCH_SECONDS);
        assertThat(ledger.lastSuccessEpochSeconds(MedSessionPurgeScheduler.COMPONENT))
                .isEqualTo(ledger.lastRunEpochSeconds(MedSessionPurgeScheduler.COMPONENT));
    }

    @Test
    @DisplayName("a run that stepped aside counts as an attempt but never as a success")
    void skippedRunAdvancesOnlyTheAttempt() {
        when(purgeService.purgeOnce()).thenReturn(SessionPurgeReport.skipped(1_735_000_000_000L, 3L));

        scheduler.purgeNow();

        assertThat(ledger.lastRunEpochSeconds(MedSessionPurgeScheduler.COMPONENT))
                .isGreaterThan(BOOT_EPOCH_SECONDS);
        assertThat(ledger.lastSuccessEpochSeconds(MedSessionPurgeScheduler.COMPONENT))
                .isEqualTo(BOOT_EPOCH_SECONDS);
    }

    @Test
    @DisplayName("a failing run is recorded as failed before it is alerted")
    void failingRunIsRecordedBeforeItIsAlerted() {
        // The alert chain can be broken by the very incident that breaks the job (D43: every sink failing at
        // once), and the attempt timestamp is then the only evidence left that the purge is still being
        // attempted. Hence the order, asserted rather than commented.
        MedJobRunLedger spiedLedger =
                spy(new MedJobRunLedger(registry, Clock.fixed(BOOT, ZoneOffset.UTC)));
        MedSessionPurgeScheduler schedulerWithSpy =
                new MedSessionPurgeScheduler(purgeService, notifierProvider, spiedLedger,
                        Duration.ofHours(12));
        when(purgeService.purgeOnce()).thenThrow(new IllegalStateException("boom"));

        assertThat(schedulerWithSpy.purgeNow()).isEmpty();

        InOrder order = inOrder(spiedLedger, notifier);
        order.verify(spiedLedger).record(any(MedJobRun.class));
        order.verify(notifier).raise(any(MedAlertSeverity.class), anyString(), anyString(), anyString());
        assertThat(spiedLedger.lastSuccessEpochSeconds(MedSessionPurgeScheduler.COMPONENT))
                .isEqualTo(BOOT_EPOCH_SECONDS);
    }

    private static SessionPurgeReport report(boolean dryRun, int purged, int skipped, int refused,
                                             int failed, long remaining) {
        return new SessionPurgeReport(SessionPurgeReport.Outcome.COMPLETED, dryRun, 1_735_000_000_000L,
                purged + skipped + refused + failed, purged, skipped, refused, failed, 1, remaining, 42,
                refused > 0 || failed > 0 ? List.of("s-1: REFUSED: cold copy differs") : List.of());
    }
}
