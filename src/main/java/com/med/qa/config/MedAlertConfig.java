package com.med.qa.config;

import com.med.qa.actuator.MedStorageHealthIndicator;
import com.med.qa.alert.LoggingMedAlertSink;
import com.med.qa.alert.MedAlertNotifier;
import com.med.qa.alert.MedAlertProperties;
import com.med.qa.alert.MedAlertSink;
import com.med.qa.alert.MedStorageAlertMonitor;
import com.med.qa.alert.MetricsMedAlertSink;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;

/**
 * Wires the alert chain: log sink + metrics sink -> notifier -> storage monitor.
 *
 * <p>The whole configuration is gated by {@code med.alert.enabled} (default {@code true}). Setting
 * it to {@code false} removes every alert bean, which is what the offline test slices and a
 * developer machine want: nothing schedules, nothing touches Redis, and no alert is emitted for the
 * absence of middleware.</p>
 *
 * <p>{@link EnableScheduling} is declared here rather than on the application class so the scheduler
 * infrastructure is only created when alerting is actually on.</p>
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(MedAlertProperties.class)
@ConditionalOnProperty(prefix = MedAlertProperties.PREFIX, name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class MedAlertConfig {

    /** Bean name of the log sink. */
    public static final String LOGGING_SINK = "loggingMedAlertSink";

    /** Bean name of the Micrometer sink. */
    public static final String METRICS_SINK = "metricsMedAlertSink";

    /** Bean name of the alert dispatcher. */
    public static final String NOTIFIER = "medAlertNotifier";

    /** Bean name of the scheduled storage monitor. */
    public static final String STORAGE_MONITOR = "medStorageAlertMonitor";

    /**
     * Contributes the sink that always works, even when every remote collector is down.
     *
     * @return the log sink
     */
    @Bean(LOGGING_SINK)
    public MedAlertSink loggingMedAlertSink() {
        return new LoggingMedAlertSink();
    }

    /**
     * Contributes the sink that publishes {@code med_qa_alert_total} for the Prometheus rules.
     *
     * @param meterRegistry the application meter registry
     * @return the metrics sink
     */
    @Bean(METRICS_SINK)
    public MedAlertSink metricsMedAlertSink(MeterRegistry meterRegistry) {
        return new MetricsMedAlertSink(meterRegistry);
    }

    /**
     * Contributes the alert dispatcher.
     *
     * <p>{@link Lazy} on the Redisson parameter matters: {@code config.RedissonConfig} declares the
     * client lazily because creating it opens a connection, and a plain injection here would force
     * that connection during context refresh -- breaking the invariant that the context boots
     * without middleware. The lazy proxy only resolves when an alert actually has to be
     * deduplicated, and the notifier fails open if that resolution throws.</p>
     *
     * @param properties     the {@code med.alert.*} policy
     * @param sinks          every {@link MedAlertSink} bean on the context, in bean order
     * @param redissonClient lazily resolved deduplication store
     * @return the notifier
     */
    @Bean(NOTIFIER)
    public MedAlertNotifier medAlertNotifier(MedAlertProperties properties,
                                             List<MedAlertSink> sinks,
                                             @Lazy RedissonClient redissonClient) {
        return new MedAlertNotifier(properties, sinks, redissonClient);
    }

    /**
     * Contributes the scheduled storage monitor.
     *
     * <p>The probe arrives as an {@link ObjectProvider} so a context without a Redis connection
     * factory or a JDBC data source still starts; the monitor then degrades to a no-op instead of
     * failing the refresh.</p>
     *
     * @param healthIndicators provider of the {@link MedStorageHealthIndicator}
     * @param notifier         the alert dispatcher
     * @return the storage monitor
     */
    @Bean(STORAGE_MONITOR)
    public MedStorageAlertMonitor medStorageAlertMonitor(
            ObjectProvider<MedStorageHealthIndicator> healthIndicators,
            MedAlertNotifier notifier) {
        return new MedStorageAlertMonitor(healthIndicators, notifier);
    }
}
