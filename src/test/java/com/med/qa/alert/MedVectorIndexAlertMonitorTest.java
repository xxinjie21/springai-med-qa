package com.med.qa.alert;

import com.med.qa.actuator.MedVectorIndexHealthIndicator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MedVectorIndexAlertMonitor}.
 *
 * <p>The monitor is the bridge between the pull-only health endpoint and the push-based alert chain
 * for the retrieval layer. What is asserted is the transition logic and the severity choice: a
 * degraded index must raise a {@code WARNING} (consultations keep working, answers get worse) and a
 * recovery must raise an {@code INFO} — without which an on-call engineer joining mid-incident cannot
 * tell "still broken" from "fixed twenty minutes ago".</p>
 */
class MedVectorIndexAlertMonitorTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<MedVectorIndexHealthIndicator> provider = mock(ObjectProvider.class);

    private final MedVectorIndexHealthIndicator indicator = mock(MedVectorIndexHealthIndicator.class);

    private final MedAlertNotifier notifier = mock(MedAlertNotifier.class);

    private MedVectorIndexAlertMonitor monitor() {
        return new MedVectorIndexAlertMonitor(provider, notifier);
    }

    /** A healthy index report, with the details the production probe records. */
    private static Health healthy() {
        return Health.up()
                .withDetail(MedVectorIndexHealthIndicator.INDEX_DETAIL, "med-doc-index")
                .withDetail(MedVectorIndexHealthIndicator.EXISTS_DETAIL, true)
                .withDetail(MedVectorIndexHealthIndicator.DOCUMENTS_DETAIL, 128L)
                .build();
    }

    /** A report of an index whose TAG schema drifted. */
    private static Health drifted() {
        return Health.down()
                .withDetail(MedVectorIndexHealthIndicator.INDEX_DETAIL, "med-doc-index")
                .withDetail(MedVectorIndexHealthIndicator.EXISTS_DETAIL, true)
                .withDetail(MedVectorIndexHealthIndicator.DOCUMENTS_DETAIL, 64L)
                .withDetail(MedVectorIndexHealthIndicator.REASON_DETAIL,
                        MedVectorIndexHealthIndicator.REASON_SCHEMA_DRIFT)
                .withDetail(MedVectorIndexHealthIndicator.MISSING_TAG_FIELDS_DETAIL, List.of("patient_id"))
                .build();
    }

    /** A report of an index that does not exist. */
    private static Health missing() {
        return Health.down()
                .withDetail(MedVectorIndexHealthIndicator.INDEX_DETAIL, "med-doc-index")
                .withDetail(MedVectorIndexHealthIndicator.EXISTS_DETAIL, false)
                .withDetail(MedVectorIndexHealthIndicator.REASON_DETAIL,
                        MedVectorIndexHealthIndicator.REASON_INDEX_MISSING)
                .build();
    }

    // ---------------------------------------------------------------- healthy path

    @Test
    @DisplayName("a healthy index raises nothing and reports no degradation")
    void healthyIndexRaisesNothing() {
        when(provider.getIfAvailable()).thenReturn(indicator);
        when(indicator.health()).thenReturn(healthy());

        MedVectorIndexAlertMonitor monitor = monitor();

        assertThat(monitor.checkNow()).isFalse();
        assertThat(monitor.isDegraded()).isFalse();
        assertThat(monitor.evaluationCount()).isEqualTo(1L);
        assertThat(monitor.lastEvaluationEpochMillis()).isPositive();
        verify(notifier, never()).raise(any(MedAlertSeverity.class), any(), any(), any());
    }

    // ---------------------------------------------------------------- degradation path

    @Test
    @DisplayName("a drifted index raises one warning naming the reason and the missing field")
    void driftedIndexRaisesWarning() {
        when(provider.getIfAvailable()).thenReturn(indicator);
        when(indicator.health()).thenReturn(drifted());

        MedVectorIndexAlertMonitor monitor = monitor();

        assertThat(monitor.checkNow()).isTrue();
        assertThat(monitor.isDegraded()).isTrue();
        verify(notifier).raise(eq(MedAlertSeverity.WARNING),
                eq(MedVectorIndexAlertMonitor.INDEX_DEGRADED),
                eq(MedVectorIndexAlertMonitor.INDEX_COMPONENT),
                contains("reason=schema-drift"));
        verify(notifier).raise(eq(MedAlertSeverity.WARNING),
                eq(MedVectorIndexAlertMonitor.INDEX_DEGRADED),
                eq(MedVectorIndexAlertMonitor.INDEX_COMPONENT),
                contains("patient_id"));
    }

    @Test
    @DisplayName("a missing index raises a warning carrying the index-missing reason")
    void missingIndexRaisesWarning() {
        when(provider.getIfAvailable()).thenReturn(indicator);
        when(indicator.health()).thenReturn(missing());

        MedVectorIndexAlertMonitor monitor = monitor();

        assertThat(monitor.checkNow()).isTrue();
        verify(notifier).raise(eq(MedAlertSeverity.WARNING),
                eq(MedVectorIndexAlertMonitor.INDEX_DEGRADED),
                eq(MedVectorIndexAlertMonitor.INDEX_COMPONENT),
                contains("reason=index-missing"));
    }

    @Test
    @DisplayName("the degradation alert is not critical: consultations still work, only the evidence shrinks")
    void degradationIsNotRaisedAsCritical() {
        when(provider.getIfAvailable()).thenReturn(indicator);
        when(indicator.health()).thenReturn(missing());

        monitor().checkNow();

        verify(notifier, never()).raise(eq(MedAlertSeverity.CRITICAL), any(), any(), any());
    }

    // ---------------------------------------------------------------- recovery

    @Test
    @DisplayName("an index that recovers raises the informational recovery alert exactly once")
    void recoveryRaisesInformationalAlertOnce() {
        when(provider.getIfAvailable()).thenReturn(indicator);
        MedVectorIndexAlertMonitor monitor = monitor();

        when(indicator.health()).thenReturn(missing());
        assertThat(monitor.checkNow()).isTrue();

        when(indicator.health()).thenReturn(healthy());
        assertThat(monitor.checkNow()).isFalse();

        verify(notifier).raise(eq(MedAlertSeverity.INFO),
                eq(MedVectorIndexAlertMonitor.INDEX_RECOVERED),
                eq(MedVectorIndexAlertMonitor.INDEX_COMPONENT),
                contains("healthy again"));

        // A second healthy pass must not repeat the recovery notice.
        monitor.checkNow();
        verify(notifier, org.mockito.Mockito.times(1))
                .raise(eq(MedAlertSeverity.INFO), any(), any(), any());
    }

    @Test
    @DisplayName("boundary: an index that stays degraded does not emit a recovery notice")
    void stillDegradedDoesNotRecover() {
        when(provider.getIfAvailable()).thenReturn(indicator);
        when(indicator.health()).thenReturn(missing());

        MedVectorIndexAlertMonitor monitor = monitor();
        monitor.checkNow();
        monitor.checkNow();

        verify(notifier, never()).raise(eq(MedAlertSeverity.INFO), any(), any(), any());
        assertThat(monitor.isDegraded()).isTrue();
    }

    // ---------------------------------------------------------------- probe failure

    @Test
    @DisplayName("a probe that throws raises a critical alert and leaves the previous verdict untouched")
    void throwingProbeRaisesCriticalAndKeepsTheVerdict() {
        when(provider.getIfAvailable()).thenReturn(indicator);
        when(indicator.health()).thenThrow(new IllegalStateException("probe exploded"));

        MedVectorIndexAlertMonitor monitor = monitor();

        assertThat(monitor.checkNow()).isFalse();
        assertThat(monitor.isDegraded()).isFalse();
        verify(notifier).raise(eq(MedAlertSeverity.CRITICAL),
                eq(MedVectorIndexAlertMonitor.INDEX_PROBE_FAILED),
                eq(MedVectorIndexAlertMonitor.INDEX_COMPONENT),
                contains("IllegalStateException"));
    }

    @Test
    @DisplayName("boundary: a probe failure after a degradation does not pretend the index recovered")
    void probeFailureAfterDegradationKeepsTheVerdict() {
        when(provider.getIfAvailable()).thenReturn(indicator);
        MedVectorIndexAlertMonitor monitor = monitor();

        when(indicator.health()).thenReturn(missing());
        assertThat(monitor.checkNow()).isTrue();

        when(indicator.health()).thenThrow(new IllegalStateException("probe exploded"));
        assertThat(monitor.checkNow()).isTrue();
        assertThat(monitor.isDegraded()).isTrue();
        verify(notifier, never()).raise(eq(MedAlertSeverity.INFO), any(), any(), any());
    }

    // ---------------------------------------------------------------- no probe in this context

    @Test
    @DisplayName("a context without the index probe degrades to a no-op instead of failing")
    void contextWithoutProbeIsANoop() {
        when(provider.getIfAvailable()).thenReturn(null);

        MedVectorIndexAlertMonitor monitor = monitor();

        assertThat(monitor.checkNow()).isFalse();
        assertThat(monitor.isDegraded()).isFalse();
        assertThat(monitor.evaluationCount()).isEqualTo(1L);
        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("the scheduled entry point delegates to the same evaluation")
    void scheduledEntryPointDelegates() {
        when(provider.getIfAvailable()).thenReturn(indicator);
        when(indicator.health()).thenReturn(healthy());

        MedVectorIndexAlertMonitor monitor = monitor();
        monitor.scheduledCheck();

        assertThat(monitor.evaluationCount()).isEqualTo(1L);
    }

    // ---------------------------------------------------------------- construction

    @Test
    @DisplayName("boundary: a null provider or null notifier is rejected at construction")
    void nullConstructorArgumentsAreRejected() {
        assertThatThrownBy(() -> new MedVectorIndexAlertMonitor(null, notifier))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("healthIndicators");
        assertThatThrownBy(() -> new MedVectorIndexAlertMonitor(provider, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("notifier");
    }
}
