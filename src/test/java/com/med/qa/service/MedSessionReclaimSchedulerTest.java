package com.med.qa.service;

import com.med.qa.alert.MedAlertNotifier;
import com.med.qa.alert.MedAlertSeverity;
import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

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
 */
class MedSessionReclaimSchedulerTest {

    private MedSessionReclaimService reclaimService;

    private MedAlertNotifier notifier;

    private ObjectProvider<MedAlertNotifier> notifierProvider;

    private MedSessionReclaimScheduler scheduler;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        reclaimService = mock(MedSessionReclaimService.class);
        notifier = mock(MedAlertNotifier.class);
        notifierProvider = mock(ObjectProvider.class);
        when(notifierProvider.getIfAvailable()).thenReturn(notifier);
        scheduler = new MedSessionReclaimScheduler(reclaimService, notifierProvider);
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
        assertThatThrownBy(() -> new MedSessionReclaimScheduler(null, notifierProvider))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reclaimService");
        assertThatThrownBy(() -> new MedSessionReclaimScheduler(reclaimService, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("notifier");
    }

    private static SessionReclaimReport report(boolean dryRun, int reclaimed, int skipped, int refused,
                                               int failed, long remaining) {
        return new SessionReclaimReport(SessionReclaimReport.Outcome.COMPLETED, dryRun,
                reclaimed + skipped + refused + failed, reclaimed, reclaimed * 2L, skipped, refused, failed,
                1, remaining, 42,
                refused > 0 || failed > 0 ? List.of("s-1: REFUSED: not in the live transcript") : List.of());
    }
}
