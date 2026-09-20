package com.med.qa.alert;

import com.med.qa.actuator.MedStorageHealthIndicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Turns the storage health report into alerts.
 *
 * <p>{@code /actuator/health} is a pull endpoint: it only tells someone who asks, which means an
 * outage is noticed by whoever happens to look. This monitor polls the very same
 * {@link MedStorageHealthIndicator} on a fixed schedule and pushes an alert when a component is not
 * answering, so the failure reaches the log and metrics pipelines on its own.</p>
 *
 * <p>Both transitions are reported, not just the failure: a component that comes back raises an
 * {@code INFO} recovery alert. Without it an on-call engineer who joins mid-incident cannot tell
 * "still broken" from "fixed twenty minutes ago".</p>
 *
 * <p>The probe is injected as an {@link ObjectProvider} on purpose. The indicator only exists when
 * both a Redis connection factory and a JDBC data source are present, and a hard dependency here
 * would break the offline test slices that boot without middleware; when it is absent the monitor
 * degrades to a no-op instead of failing the context.</p>
 */
public class MedStorageAlertMonitor {

    private static final Logger log = LoggerFactory.getLogger(MedStorageAlertMonitor.class);

    /** Alert code raised for a storage component that does not answer. */
    public static final String STORAGE_DOWN = "storage-down";

    /** Alert code raised when a previously failing storage component answers again. */
    public static final String STORAGE_RECOVERED = "storage-recovered";

    /** Alert code raised when the health probe itself throws instead of reporting a status. */
    public static final String STORAGE_PROBE_FAILED = "storage-probe-failed";

    /** Components evaluated on every pass, in report order. */
    private static final List<String> COMPONENTS =
            List.of(MedStorageHealthIndicator.MYSQL_COMPONENT, MedStorageHealthIndicator.REDIS_COMPONENT);

    private final ObjectProvider<MedStorageHealthIndicator> healthIndicators;

    private final MedAlertNotifier notifier;

    /** Components that were down on the previous pass; guarded for concurrent reads. */
    private final Set<String> downComponents = ConcurrentHashMap.newKeySet();

    private final AtomicLong evaluationCount = new AtomicLong();

    private final AtomicLong lastEvaluationEpochMillis = new AtomicLong();

    /**
     * Creates the monitor.
     *
     * @param healthIndicators provider of the storage probe, must not be {@code null}
     * @param notifier         the alert dispatcher, must not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public MedStorageAlertMonitor(ObjectProvider<MedStorageHealthIndicator> healthIndicators,
                                  MedAlertNotifier notifier) {
        if (healthIndicators == null) {
            throw new IllegalArgumentException("healthIndicators must not be null");
        }
        if (notifier == null) {
            throw new IllegalArgumentException("notifier must not be null");
        }
        this.healthIndicators = healthIndicators;
        this.notifier = notifier;
    }

    /**
     * Scheduled entry point; see {@link #checkNow()}.
     *
     * <p>The delay and the initial grace period are read from {@code med.alert.check-interval} and
     * {@code med.alert.initial-delay}, so an operator can slow the probing down without a rebuild.</p>
     */
    @Scheduled(fixedDelayString = "${med.alert.check-interval:60s}",
            initialDelayString = "${med.alert.initial-delay:30s}")
    public void scheduledCheck() {
        checkNow();
    }

    /**
     * Evaluates the storage probe once and raises the alerts implied by the transition.
     *
     * @return the components that are down right now; empty when everything answers or when no probe
     *         is available in this context
     */
    public Set<String> checkNow() {
        evaluationCount.incrementAndGet();
        lastEvaluationEpochMillis.set(System.currentTimeMillis());

        MedStorageHealthIndicator indicator = healthIndicators.getIfAvailable();
        if (indicator == null) {
            log.debug("no storage health indicator in this context; skipping alert evaluation");
            return Set.of();
        }

        Health health;
        try {
            health = indicator.health();
        } catch (RuntimeException ex) {
            // The probe is documented not to throw, so reaching here means something is deeply wrong.
            notifier.raise(MedAlertSeverity.CRITICAL, STORAGE_PROBE_FAILED, MedStorageHealthIndicator.MYSQL_COMPONENT,
                    "storage health probe threw " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return Set.of();
        }

        Map<String, Object> details = health.getDetails();
        Set<String> currentlyDown = new LinkedHashSet<>();
        for (String component : COMPONENTS) {
            if (!MedStorageHealthIndicator.AVAILABLE.equals(details.get(component))) {
                currentlyDown.add(component);
                notifier.raise(MedAlertSeverity.CRITICAL, STORAGE_DOWN, component,
                        "storage component " + component + " is unavailable: " + describe(details.get(component)));
            }
        }
        raiseRecoveries(currentlyDown);

        downComponents.clear();
        downComponents.addAll(currentlyDown);
        return Set.copyOf(currentlyDown);
    }

    /**
     * Raises an informational alert for every component that failed before and answers now.
     *
     * @param currentlyDown the components that are down on this pass
     */
    private void raiseRecoveries(Set<String> currentlyDown) {
        Set<String> recovered = new LinkedHashSet<>(downComponents);
        recovered.removeAll(currentlyDown);
        for (String component : recovered) {
            notifier.raise(MedAlertSeverity.INFO, STORAGE_RECOVERED, component,
                    "storage component " + component + " recovered");
        }
    }

    /**
     * Renders a health detail value for a human-readable message.
     *
     * @param detail the detail recorded by the probe, may be {@code null}
     * @return the detail as text, or {@code "no detail reported"} when absent
     */
    private static String describe(Object detail) {
        return detail == null ? "no detail reported" : String.valueOf(detail);
    }

    /**
     * Returns the components that were down on the most recent pass.
     *
     * @return an immutable snapshot, never {@code null}
     */
    public Set<String> downComponents() {
        return Set.copyOf(downComponents);
    }

    /**
     * Returns how many times {@link #checkNow()} ran on this instance.
     *
     * @return the evaluation count, never negative
     */
    public long evaluationCount() {
        return evaluationCount.get();
    }

    /**
     * Returns the epoch millisecond of the most recent evaluation.
     *
     * @return the last evaluation instant, or {@code 0} when nothing was ever evaluated
     */
    public long lastEvaluationEpochMillis() {
        return lastEvaluationEpochMillis.get();
    }
}
