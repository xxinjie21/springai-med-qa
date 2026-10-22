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
 * Tests of {@link MedAuditLogRetentionScheduler} (D55).
 *
 * <p>This is the part of the trim story that runs unattended, and it drives the only job in the project
 * whose deletions have no second copy, so what is pinned here is the alerting contract: a run that
 * deleted rows announces itself (but only when it really deleted — never for a dry run), a quiet run and
 * a run that stepped aside for another replica do not, and a run that threw says so with its error code.</p>
 *
 * <p>D53 adds the half of that contract no alert can carry: the same three outcomes are also pinned in the
 * run ledger, whose staleness is what a dead job cannot suppress. The ledger is exercised for real over a
 * {@code SimpleMeterRegistry} rather than mocked, because the assertion that matters is the meter a
 * Prometheus scrape would read.</p>
 */
class MedAuditLogRetentionSchedulerTest {

    private static final Instant BOOT = Instant.parse("2026-10-21T09:00:00Z");

    private static final long BOOT_EPOCH_SECONDS = BOOT.getEpochSecond();

    private MedAuditLogRetentionService retentionService;

    private MedAlertNotifier notifier;

    private ObjectProvider<MedAlertNotifier> notifierProvider;

    private SimpleMeterRegistry registry;

    private MedJobRunLedger ledger;

    private MedAuditLogRetentionScheduler scheduler;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        retentionService = mock(MedAuditLogRetentionService.class);
        notifier = mock(MedAlertNotifier.class);
        notifierProvider = mock(ObjectProvider.class);
        when(notifierProvider.getIfAvailable()).thenReturn(notifier);
        registry = new SimpleMeterRegistry();
        ledger = new MedJobRunLedger(registry, Clock.fixed(BOOT, ZoneOffset.UTC));
        scheduler = new MedAuditLogRetentionScheduler(retentionService, notifierProvider, ledger,
                Duration.ofHours(24));
    }

    @Test
    @DisplayName("a run that trimmed rows raises an informational alert")
    void trimmingRunRaisesAnInfoAlert() {
        when(retentionService.trimOnce()).thenReturn(report(false, 12, 0));

        Optional<AuditLogRetentionReport> result = scheduler.trimNow();

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().deleted()).isEqualTo(12);
        verify(notifier).raise(eq(MedAlertSeverity.INFO),
                eq(MedAuditLogRetentionScheduler.AUDIT_LOG_RETENTION_COMPLETED),
                eq(MedAuditLogRetentionScheduler.COMPONENT), anyString());
    }

    @Test
    @DisplayName("a quiet run stays silent, because 'nothing expired' is the normal case")
    void quietRunIsSilent() {
        when(retentionService.trimOnce()).thenReturn(report(false, 0, 0));

        assertThat(scheduler.trimNow()).isPresent();

        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("a dry run never announces a completion, however many rows it would delete")
    void dryRunIsSilentAboutCompletions() {
        when(retentionService.trimOnce()).thenReturn(report(true, 500, 0));

        assertThat(scheduler.trimNow()).isPresent();

        verify(notifier, never()).raise(eq(MedAlertSeverity.INFO), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a run that stepped aside for another replica is logged only, never alerted")
    void lockHeldRunIsNotAlerted() {
        when(retentionService.trimOnce()).thenReturn(AuditLogRetentionReport.skipped(365, 7));

        assertThat(scheduler.trimNow()).isPresent();

        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("a throwing run is alerted with its error code and reported as empty")
    void throwingRunIsAlerted() {
        when(retentionService.trimOnce())
                .thenThrow(new BizException(ErrorCode.STORAGE_ERROR, "mysql went away"));

        Optional<AuditLogRetentionReport> result = scheduler.trimNow();

        assertThat(result).isEmpty();
        assertThat(scheduler.lastReport()).isEmpty();
        verify(notifier).raise(eq(MedAlertSeverity.WARNING),
                eq(MedAuditLogRetentionScheduler.AUDIT_LOG_RETENTION_FAILED),
                eq(MedAuditLogRetentionScheduler.COMPONENT), contains("STORAGE_ERROR"));
    }

    @Test
    @DisplayName("the last report is remembered, so an operator can ask what the job just did")
    void lastReportIsRemembered() {
        assertThat(scheduler.lastReport()).isEmpty();
        when(retentionService.trimOnce()).thenReturn(report(false, 1, 0));

        scheduler.trimNow();

        assertThat(scheduler.lastReport()).isPresent();
        assertThat(scheduler.lastReport().orElseThrow().deleted()).isEqualTo(1);
    }

    @Test
    @DisplayName("the scheduled entry point delegates to the same run")
    void scheduledEntryPointDelegates() {
        when(retentionService.trimOnce()).thenReturn(report(false, 0, 0));

        scheduler.scheduledTrim();

        verify(retentionService).trimOnce();
    }

    @Test
    @DisplayName("a context without the alert chain still trims and simply has nowhere to push")
    void missingNotifierIsTolerated() {
        when(notifierProvider.getIfAvailable()).thenReturn(null);
        when(retentionService.trimOnce()).thenReturn(report(false, 2, 0));

        assertThat(scheduler.trimNow()).isPresent();

        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("the constructor refuses a null collaborator or a non-positive interval")
    void constructorRejectsNulls() {
        assertThatThrownBy(() -> new MedAuditLogRetentionScheduler(null, notifierProvider, ledger,
                Duration.ofHours(24)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retentionService");
        assertThatThrownBy(() -> new MedAuditLogRetentionScheduler(retentionService, null, ledger,
                Duration.ofHours(24)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("notifier");
        assertThatThrownBy(() -> new MedAuditLogRetentionScheduler(retentionService, notifierProvider, null,
                Duration.ofHours(24)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ledger");
        assertThatThrownBy(() -> new MedAuditLogRetentionScheduler(retentionService, notifierProvider,
                ledger, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expectedInterval");
    }

    // ------------------------------------------------------------------ the run ledger (D53)

    @Test
    @DisplayName("building the scheduler registers the job, so a job that never runs still has a series")
    void buildingTheSchedulerRegistersTheJob() {
        assertThat(ledger.registeredJobs()).containsExactly(MedAuditLogRetentionScheduler.COMPONENT);
        assertThat(ledger.expectedIntervalSeconds(MedAuditLogRetentionScheduler.COMPONENT))
                .isEqualTo(86_400d);
        assertThat(ledger.lastRunEpochSeconds(MedAuditLogRetentionScheduler.COMPONENT))
                .isEqualTo(BOOT_EPOCH_SECONDS);
    }

    @Test
    @DisplayName("a completed run advances both ledger timestamps")
    void completedRunAdvancesBothTimestamps() {
        when(retentionService.trimOnce()).thenReturn(report(false, 3, 0));

        scheduler.trimNow();

        assertThat(ledger.lastRunEpochSeconds(MedAuditLogRetentionScheduler.COMPONENT))
                .isGreaterThan(BOOT_EPOCH_SECONDS);
        assertThat(ledger.lastSuccessEpochSeconds(MedAuditLogRetentionScheduler.COMPONENT))
                .isEqualTo(ledger.lastRunEpochSeconds(MedAuditLogRetentionScheduler.COMPONENT));
    }

    @Test
    @DisplayName("a run that stepped aside counts as an attempt but never as a success")
    void skippedRunAdvancesOnlyTheAttempt() {
        when(retentionService.trimOnce()).thenReturn(AuditLogRetentionReport.skipped(365, 7));

        scheduler.trimNow();

        assertThat(ledger.lastRunEpochSeconds(MedAuditLogRetentionScheduler.COMPONENT))
                .isGreaterThan(BOOT_EPOCH_SECONDS);
        assertThat(ledger.lastSuccessEpochSeconds(MedAuditLogRetentionScheduler.COMPONENT))
                .isEqualTo(BOOT_EPOCH_SECONDS);
    }

    @Test
    @DisplayName("a failing run is recorded as failed before it is alerted")
    void failingRunIsRecordedBeforeItIsAlerted() {
        // The alert chain can be broken by the very incident that breaks the job (D43: every sink failing at
        // once), and the attempt timestamp is then the only evidence left that the trim is still being
        // attempted. Hence the order, asserted rather than commented.
        MedJobRunLedger spiedLedger =
                spy(new MedJobRunLedger(registry, Clock.fixed(BOOT, ZoneOffset.UTC)));
        MedAuditLogRetentionScheduler schedulerWithSpy =
                new MedAuditLogRetentionScheduler(retentionService, notifierProvider, spiedLedger,
                        Duration.ofHours(24));
        when(retentionService.trimOnce()).thenThrow(new IllegalStateException("boom"));

        assertThat(schedulerWithSpy.trimNow()).isEmpty();

        InOrder order = inOrder(spiedLedger, notifier);
        order.verify(spiedLedger).record(any(MedJobRun.class));
        order.verify(notifier).raise(any(MedAlertSeverity.class), anyString(), anyString(), anyString());
        assertThat(spiedLedger.lastSuccessEpochSeconds(MedAuditLogRetentionScheduler.COMPONENT))
                .isEqualTo(BOOT_EPOCH_SECONDS);
    }

    // ------------------------------------------------------------------ helpers

    private static AuditLogRetentionReport report(boolean dryRun, long deleted, long remaining) {
        return new AuditLogRetentionReport(AuditLogRetentionReport.Outcome.COMPLETED, dryRun, 365,
                deleted, deleted, remaining, 1, 42L);
    }
}
