package com.med.qa.alert;

import com.med.qa.actuator.MedStorageHealthIndicator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;

import java.util.Set;

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
 * Unit tests for {@link MedStorageAlertMonitor}.
 *
 * <p>The monitor is the bridge between the pull-only health endpoint and the push-based alert chain,
 * so what is asserted here is the transition logic: a component that stops answering must raise a
 * critical alert, and a component that answers again must raise the recovery -- without which an
 * on-call engineer joining mid-incident cannot tell "still broken" from "fixed twenty minutes
 * ago".</p>
 */
class MedStorageAlertMonitorTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<MedStorageHealthIndicator> provider = mock(ObjectProvider.class);

    private final MedStorageHealthIndicator indicator = mock(MedStorageHealthIndicator.class);

    private final MedAlertNotifier notifier = mock(MedAlertNotifier.class);

    /** Monitor over the mocked provider and notifier. */
    private MedStorageAlertMonitor monitor() {
        return new MedStorageAlertMonitor(provider, notifier);
    }

    /** Health report with the given per-component details. */
    private static Health report(String mysqlDetail, String redisDetail) {
        Health.Builder builder = Health.up();
        builder.withDetail(MedStorageHealthIndicator.MYSQL_COMPONENT, mysqlDetail);
        builder.withDetail(MedStorageHealthIndicator.REDIS_COMPONENT, redisDetail);
        return builder.build();
    }

    // ---------------------------------------------------------------- healthy path

    @Test
    @DisplayName("everything answering raises nothing and reports no down component")
    void healthyStoresRaiseNothing() {
        when(provider.getIfAvailable()).thenReturn(indicator);
        when(indicator.health()).thenReturn(report(MedStorageHealthIndicator.AVAILABLE,
                MedStorageHealthIndicator.AVAILABLE));

        MedStorageAlertMonitor monitor = monitor();

        assertThat(monitor.checkNow()).isEmpty();
        assertThat(monitor.downComponents()).isEmpty();
        assertThat(monitor.evaluationCount()).isEqualTo(1L);
        assertThat(monitor.lastEvaluationEpochMillis()).isPositive();
        verify(notifier, never()).raise(any(MedAlertSeverity.class), any(), any(), any());
    }

    // ---------------------------------------------------------------- outage path

    @Test
    @DisplayName("a component that stops answering raises one critical alert for that component")
    void downComponentRaisesCriticalAlert() {
        when(provider.getIfAvailable()).thenReturn(indicator);
        when(indicator.health()).thenReturn(report("unavailable: connection refused",
                MedStorageHealthIndicator.AVAILABLE));

        MedStorageAlertMonitor monitor = monitor();

        assertThat(monitor.checkNow())
                .containsExactly(MedStorageHealthIndicator.MYSQL_COMPONENT);
        assertThat(monitor.downComponents())
                .containsExactly(MedStorageHealthIndicator.MYSQL_COMPONENT);
        verify(notifier).raise(eq(MedAlertSeverity.CRITICAL),
                eq(MedStorageAlertMonitor.STORAGE_DOWN),
                eq(MedStorageHealthIndicator.MYSQL_COMPONENT),
                contains("connection refused"));
    }

    @Test
    @DisplayName("both components failing produces one alert each, not a single merged alert")
    void bothComponentsFailingRaiseTwoAlerts() {
        when(provider.getIfAvailable()).thenReturn(indicator);
        when(indicator.health()).thenReturn(report("unavailable: timeout", "unexpected ping reply"));

        Set<String> down = monitor().checkNow();

        assertThat(down).containsExactlyInAnyOrder(MedStorageHealthIndicator.MYSQL_COMPONENT,
                MedStorageHealthIndicator.REDIS_COMPONENT);
        verify(notifier).raise(eq(MedAlertSeverity.CRITICAL),
                eq(MedStorageAlertMonitor.STORAGE_DOWN),
                eq(MedStorageHealthIndicator.MYSQL_COMPONENT), any());
        verify(notifier).raise(eq(MedAlertSeverity.CRITICAL),
                eq(MedStorageAlertMonitor.STORAGE_DOWN),
                eq(MedStorageHealthIndicator.REDIS_COMPONENT), any());
    }

    @Test
    @DisplayName("a repeated failure inside the same window raises again; suppression is the notifier's job")
    void repeatedFailureIsReRaised() {
        when(provider.getIfAvailable()).thenReturn(indicator);
        when(indicator.health()).thenReturn(report("unavailable: down", MedStorageHealthIndicator.AVAILABLE));

        MedStorageAlertMonitor monitor = monitor();
        monitor.checkNow();
        monitor.checkNow();

        assertThat(monitor.evaluationCount()).isEqualTo(2L);
        // Twice, so the notifier can apply its own cooldown without the monitor having to remember.
        verify(notifier, org.mockito.Mockito.times(2)).raise(eq(MedAlertSeverity.CRITICAL),
                eq(MedStorageAlertMonitor.STORAGE_DOWN),
                eq(MedStorageHealthIndicator.MYSQL_COMPONENT), any());
    }

    @Test
    @DisplayName("a component that answers again raises the recovery and clears the down set")
    void recoveryIsReportedOnce() {
        when(provider.getIfAvailable()).thenReturn(indicator);
        when(indicator.health()).thenReturn(
                report("unavailable: down", MedStorageHealthIndicator.AVAILABLE),
                report(MedStorageHealthIndicator.AVAILABLE, MedStorageHealthIndicator.AVAILABLE));

        MedStorageAlertMonitor monitor = monitor();
        monitor.checkNow();
        assertThat(monitor.checkNow()).isEmpty();

        verify(notifier).raise(eq(MedAlertSeverity.INFO),
                eq(MedStorageAlertMonitor.STORAGE_RECOVERED),
                eq(MedStorageHealthIndicator.MYSQL_COMPONENT),
                contains("recovered"));
        assertThat(monitor.downComponents()).isEmpty();
    }

    @Test
    @DisplayName("a component that stays down never produces a spurious recovery alert")
    void stillDownDoesNotReportRecovery() {
        when(provider.getIfAvailable()).thenReturn(indicator);
        when(indicator.health()).thenReturn(report(MedStorageHealthIndicator.AVAILABLE,
                "unavailable: down"));

        MedStorageAlertMonitor monitor = monitor();
        monitor.checkNow();
        monitor.checkNow();

        verify(notifier, never()).raise(eq(MedAlertSeverity.INFO), any(), any(), any());
    }

    @Test
    @DisplayName("a missing detail is rendered as an explicit 'no detail reported' message")
    void missingDetailIsDescribed() {
        when(provider.getIfAvailable()).thenReturn(indicator);
        when(indicator.health()).thenReturn(Health.up().build());

        assertThat(monitor().checkNow()).containsExactlyInAnyOrder(
                MedStorageHealthIndicator.MYSQL_COMPONENT, MedStorageHealthIndicator.REDIS_COMPONENT);
        verify(notifier, org.mockito.Mockito.times(2)).raise(eq(MedAlertSeverity.CRITICAL),
                eq(MedStorageAlertMonitor.STORAGE_DOWN), any(), contains("no detail reported"));
    }

    // ---------------------------------------------------------------- degraded paths

    @Test
    @DisplayName("a context without a storage probe degrades to a no-op instead of failing")
    void missingProbeIsANoOp() {
        when(provider.getIfAvailable()).thenReturn(null);

        MedStorageAlertMonitor monitor = monitor();

        assertThat(monitor.checkNow()).isEmpty();
        assertThat(monitor.evaluationCount()).isEqualTo(1L);
        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("a probe that throws is reported as a probe failure, not as a storage outage")
    void throwingProbeIsReportedAsProbeFailure() {
        when(provider.getIfAvailable()).thenReturn(indicator);
        when(indicator.health()).thenThrow(new IllegalStateException("pool exhausted"));

        assertThat(monitor().checkNow()).isEmpty();

        verify(notifier).raise(eq(MedAlertSeverity.CRITICAL),
                eq(MedStorageAlertMonitor.STORAGE_PROBE_FAILED), any(), contains("pool exhausted"));
    }

    @Test
    @DisplayName("the scheduled entry point evaluates the probe exactly like the manual one")
    void scheduledCheckDelegates() {
        when(provider.getIfAvailable()).thenReturn(indicator);
        when(indicator.health()).thenReturn(report(MedStorageHealthIndicator.AVAILABLE,
                MedStorageHealthIndicator.AVAILABLE));

        MedStorageAlertMonitor monitor = monitor();
        monitor.scheduledCheck();

        assertThat(monitor.evaluationCount()).isEqualTo(1L);
        assertThat(monitor.lastEvaluationEpochMillis()).isPositive();
    }

    @Test
    @DisplayName("the down-component snapshot is immutable")
    void downComponentsSnapshotIsImmutable() {
        when(provider.getIfAvailable()).thenReturn(indicator);
        when(indicator.health()).thenReturn(report("unavailable: down", MedStorageHealthIndicator.AVAILABLE));

        MedStorageAlertMonitor monitor = monitor();
        monitor.checkNow();

        assertThatThrownBy(() -> monitor.downComponents().add("injected"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("boundary: null collaborators are rejected at construction time")
    void rejectsNullCollaborators() {
        assertThatThrownBy(() -> new MedStorageAlertMonitor(null, notifier))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("healthIndicators must not be null");
        assertThatThrownBy(() -> new MedStorageAlertMonitor(provider, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("notifier must not be null");
    }
}
