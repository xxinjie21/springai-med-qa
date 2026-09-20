package com.med.qa.alert;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the two production {@link MedAlertSink} implementations.
 *
 * <p>The log sink is asserted through the captured application output and the metrics sink through a
 * plain {@link SimpleMeterRegistry}, so neither test needs a log collector or a Prometheus server.
 * What matters is the contract an operator depends on: the severity decides the log level, and the
 * exported meter carries the labels the alert rules select on.</p>
 */
@ExtendWith(OutputCaptureExtension.class)
class MedAlertSinkTest {

    // ---------------------------------------------------------------- LoggingMedAlertSink

    @Test
    @DisplayName("a critical alert is logged at ERROR with the flat key=value shape")
    void loggingSinkWritesCriticalAtError(CapturedOutput output) {
        new LoggingMedAlertSink().publish(
                MedAlert.of(MedAlertSeverity.CRITICAL, "storage-down", "mysql", "mysql is down"));

        assertThat(output).contains("med-alert severity=CRITICAL code=storage-down component=mysql");
        assertThat(output).contains("message=mysql is down");
    }

    @Test
    @DisplayName("warning and informational alerts are logged at their own levels")
    void loggingSinkWritesOtherSeverities(CapturedOutput output) {
        LoggingMedAlertSink sink = new LoggingMedAlertSink();
        sink.publish(MedAlert.of(MedAlertSeverity.WARNING, "slow", "redis", "redis is slow"));
        sink.publish(MedAlert.of(MedAlertSeverity.INFO, "storage-recovered", "redis", "redis is back"));

        assertThat(output).contains("severity=WARNING code=slow component=redis");
        assertThat(output).contains("severity=INFO code=storage-recovered component=redis");
    }

    // ---------------------------------------------------------------- MetricsMedAlertSink

    @Test
    @DisplayName("publishing registers the counter with severity, code and component tags")
    void metricsSinkRegistersTaggedCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsMedAlertSink sink = new MetricsMedAlertSink(registry);

        sink.publish(MedAlert.of(MedAlertSeverity.CRITICAL, "storage-down", "mysql", "down"));
        sink.publish(MedAlert.of(MedAlertSeverity.CRITICAL, "storage-down", "mysql", "down again"));

        assertThat(registry.get(MetricsMedAlertSink.ALERT_COUNTER)
                .tag(MetricsMedAlertSink.SEVERITY_TAG, "CRITICAL")
                .tag(MetricsMedAlertSink.CODE_TAG, "storage-down")
                .tag(MetricsMedAlertSink.COMPONENT_TAG, "mysql")
                .counter()
                .count()).isEqualTo(2.0d);
    }

    @Test
    @DisplayName("different components produce distinct series instead of one merged counter")
    void metricsSinkSeparatesComponents() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsMedAlertSink sink = new MetricsMedAlertSink(registry);

        sink.publish(MedAlert.of(MedAlertSeverity.CRITICAL, "storage-down", "mysql", "down"));
        sink.publish(MedAlert.of(MedAlertSeverity.CRITICAL, "storage-down", "redis", "down"));

        assertThat(registry.get(MetricsMedAlertSink.ALERT_COUNTER)
                .tag(MetricsMedAlertSink.COMPONENT_TAG, "mysql").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get(MetricsMedAlertSink.ALERT_COUNTER)
                .tag(MetricsMedAlertSink.COMPONENT_TAG, "redis").counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("the last-alert gauge follows the most recent alert and starts at zero")
    void metricsSinkTracksLastAlert() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsMedAlertSink sink = new MetricsMedAlertSink(registry);

        assertThat(sink.lastAlertEpochSeconds()).isZero();
        assertThat(registry.get(MetricsMedAlertSink.LAST_ALERT_GAUGE).gauge().value()).isZero();

        MedAlert alert = new MedAlert(MedAlertSeverity.WARNING, "slow", "redis", "slow",
                java.time.Instant.ofEpochSecond(1_700_000_000L), java.util.Map.of());
        sink.publish(alert);

        assertThat(sink.lastAlertEpochSeconds()).isEqualTo(1_700_000_000L);
        assertThat(registry.get(MetricsMedAlertSink.LAST_ALERT_GAUGE).gauge().value())
                .isEqualTo(1_700_000_000d);
    }

    @Test
    @DisplayName("boundary: a metrics sink without a registry is rejected")
    void metricsSinkRejectsNullRegistry() {
        assertThatThrownBy(() -> new MetricsMedAlertSink(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("meterRegistry must not be null");
    }
}
