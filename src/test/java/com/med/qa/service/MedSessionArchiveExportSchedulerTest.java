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
 * Tests of {@link MedSessionArchiveExportScheduler}.
 *
 * <p>This is the only part of the archive story that runs unattended, so what is pinned here is the
 * alerting contract: a run that certified something announces itself, a quiet run and a run that stepped
 * aside for another replica do not, a run that failed says so, and — the case that matters most — a run
 * that certified nine sessions and refused one raises the mismatch warning <em>as well as</em> the
 * completion notice, because the refusal is the part that needs a human.</p>
 */
class MedSessionArchiveExportSchedulerTest {

    private MedSessionArchiveExportService exportService;

    private MedAlertNotifier notifier;

    private ObjectProvider<MedAlertNotifier> notifierProvider;

    private MedSessionArchiveExportScheduler scheduler;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        exportService = mock(MedSessionArchiveExportService.class);
        notifier = mock(MedAlertNotifier.class);
        notifierProvider = mock(ObjectProvider.class);
        when(notifierProvider.getIfAvailable()).thenReturn(notifier);
        scheduler = new MedSessionArchiveExportScheduler(exportService, notifierProvider);
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
        assertThatThrownBy(() -> new MedSessionArchiveExportScheduler(null, notifierProvider))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exportService must not be null");
        assertThatThrownBy(() -> new MedSessionArchiveExportScheduler(exportService, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("notifier must not be null");
    }

    private static SessionArchiveExportReport report(int exported, int mismatched, int failed,
                                                     int batches, long remaining) {
        return new SessionArchiveExportReport(SessionArchiveExportReport.Outcome.COMPLETED, false,
                exported + mismatched + failed, exported, 0, mismatched, failed, batches, remaining,
                7L, List.of());
    }
}
