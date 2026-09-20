package com.med.qa.alert;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Exposes dispatched alerts as Micrometer meters.
 *
 * <p>This sink is what turns an in-process event into something the Prometheus rules in
 * {@code deploy/prometheus/med-qa-alerts.yml} can page on. Two meters are published:</p>
 *
 * <ul>
 *   <li>{@value #ALERT_COUNTER} -- a monotonically increasing counter tagged with
 *       {@code severity}, {@code code} and {@code component}, so a rule can page on
 *       {@code increase(med_qa_alert_total{code="storage-down"}[5m])} while a dashboard can still
 *       break the same series down by component;</li>
 *   <li>{@value #LAST_ALERT_GAUGE} -- the epoch second of the most recent alert, which makes
 *       "how long has this instance been quiet?" a single query and lets a dead-man's-switch rule
 *       detect a pod that stopped evaluating entirely.</li>
 * </ul>
 *
 * <p>The counter's cardinality is bounded by the number of distinct rules times the number of
 * components -- both are small, hand-written sets, not user input, so no label explosion is
 * possible.</p>
 */
public class MetricsMedAlertSink implements MedAlertSink {

    /** Counter of dispatched alerts, tagged with severity, code and component. */
    public static final String ALERT_COUNTER = "med_qa_alert_total";

    /** Gauge holding the epoch second of the most recently dispatched alert. */
    public static final String LAST_ALERT_GAUGE = "med_qa_alert_last_epoch_seconds";

    /** Tag key carrying the alert severity name. */
    public static final String SEVERITY_TAG = "severity";

    /** Tag key carrying the stable alert code. */
    public static final String CODE_TAG = "code";

    /** Tag key carrying the affected component. */
    public static final String COMPONENT_TAG = "component";

    private final MeterRegistry meterRegistry;

    private final AtomicLong lastAlertEpochSeconds = new AtomicLong();

    /**
     * Registers the alert meters on the application registry.
     *
     * @param meterRegistry the application meter registry, must not be {@code null}
     * @throws IllegalArgumentException if {@code meterRegistry} is {@code null}
     */
    public MetricsMedAlertSink(MeterRegistry meterRegistry) {
        if (meterRegistry == null) {
            throw new IllegalArgumentException("meterRegistry must not be null");
        }
        this.meterRegistry = meterRegistry;
        Gauge.builder(LAST_ALERT_GAUGE, lastAlertEpochSeconds, AtomicLong::doubleValue)
                .description("Epoch second of the most recent medical alert dispatched by this instance")
                .register(meterRegistry);
    }

    /**
     * Increments the alert counter and refreshes the last-alert gauge.
     *
     * @param alert the alert to record, never {@code null}
     */
    @Override
    public void publish(MedAlert alert) {
        Counter.builder(ALERT_COUNTER)
                .description("Medical alerts dispatched by this instance")
                .tag(SEVERITY_TAG, alert.severity().name())
                .tag(CODE_TAG, alert.code())
                .tag(COMPONENT_TAG, alert.component())
                .register(meterRegistry)
                .increment();
        lastAlertEpochSeconds.set(alert.occurredAt().getEpochSecond());
    }

    /**
     * Returns the epoch second of the most recently recorded alert.
     *
     * @return the last alert instant in epoch seconds, or {@code 0} when nothing was ever recorded
     */
    public long lastAlertEpochSeconds() {
        return lastAlertEpochSeconds.get();
    }
}
