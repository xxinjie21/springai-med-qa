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
 * Tests of {@link MedSessionPurgeScheduler}.
 *
 * <p>This is the only part of the purge story that runs unattended, and it drives the one job that
 * deletes clinical data, so what is pinned here is the alerting contract: a run that retired something
 * announces itself (but only when it really deleted — never for a dry run), a quiet run and a run that
 * stepped aside for another replica do not, a run that threw says so, and — the case that matters most —
 * a run that retired nine sessions and refused one raises the refusal warning <em>as well as</em> the
 * completion notice, because the refusal is the part that needs a human.</p>
 */
class MedSessionPurgeSchedulerTest {

    private MedSessionPurgeService purgeService;

    private MedAlertNotifier notifier;

    private ObjectProvider<MedAlertNotifier> notifierProvider;

    private MedSessionPurgeScheduler scheduler;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        purgeService = mock(MedSessionPurgeService.class);
        notifier = mock(MedAlertNotifier.class);
        notifierProvider = mock(ObjectProvider.class);
        when(notifierProvider.getIfAvailable()).thenReturn(notifier);
        scheduler = new MedSessionPurgeScheduler(purgeService, notifierProvider);
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
        assertThatThrownBy(() -> new MedSessionPurgeScheduler(null, notifierProvider))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("purgeService");
        assertThatThrownBy(() -> new MedSessionPurgeScheduler(purgeService, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("notifier");
    }

    private static SessionPurgeReport report(boolean dryRun, int purged, int skipped, int refused,
                                             int failed, long remaining) {
        return new SessionPurgeReport(SessionPurgeReport.Outcome.COMPLETED, dryRun, 1_735_000_000_000L,
                purged + skipped + refused + failed, purged, skipped, refused, failed, 1, remaining, 42,
                refused > 0 || failed > 0 ? List.of("s-1: REFUSED: cold copy differs") : List.of());
    }
}
