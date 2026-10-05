package com.med.qa.alert;

import com.med.qa.actuator.MedVectorIndexHealthIndicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Turns the RAG vector-index health report into alerts.
 *
 * <p>The counterpart of {@link MedStorageAlertMonitor} for the retrieval layer, and it exists for the
 * same reason: {@code /actuator/health} only tells whoever asks, and nobody asks at 03:00. A degraded
 * index does not fail a request, it degrades the answer — the consultation endpoint still returns
 * HTTP 200 while the model is fed an incomplete corpus — so without a push signal the first person to
 * notice is a clinician reading a wrong answer.</p>
 *
 * <p>Three codes are raised:</p>
 * <ul>
 *   <li>{@link #INDEX_DEGRADED} at {@code WARNING} — the index is missing or its TAG schema drifted.
 *       Deliberately not {@code CRITICAL}: consultations keep working, answers get worse.</li>
 *   <li>{@link #INDEX_RECOVERED} at {@code INFO} — a previously degraded index is healthy again.
 *       Without it an on-call engineer joining mid-incident cannot tell "still broken" from "fixed
 *       twenty minutes ago".</li>
 *   <li>{@link #INDEX_PROBE_FAILED} at {@code CRITICAL} — the probe itself failed, which means the
 *       index verdict is unknown and every other RAG signal is untrustworthy. Detected from the
 *       health details, <strong>not</strong> from an exception: see {@link #isProbeFailure(Health)}.</li>
 * </ul>
 *
 * <h2>Why the probe failure is read out of the details</h2>
 * <p>{@code AbstractHealthIndicator#health()} is {@code final} and catches every exception, turning it
 * into {@code DOWN} plus an {@code error} detail. A {@code try}/{@code catch} around
 * {@code indicator.health()} therefore never fires in production, and an earlier version of this
 * monitor ended up reporting a total Redis outage as a {@code WARNING} "index degraded" — the
 * {@code CRITICAL} branch was unreachable. The indicator is where the distinction survives: it wraps
 * its own probe call and records {@code reason=unreachable} when the probe could not reach Redis, as
 * opposed to {@code index-missing} / {@code schema-drift} where the probe ran and reached a verdict.
 * {@link #isProbeFailure(Health)} reads exactly that.</p>
 *
 * <p>Like the storage monitor, the probe arrives as an {@link ObjectProvider}: the indicator only
 * exists when the RAG index monitoring is enabled and a Jedis client is present, and a hard dependency
 * would break the offline test slices that boot without middleware. When it is absent the monitor
 * degrades to a no-op instead of failing the context.</p>
 */
public class MedVectorIndexAlertMonitor {

    private static final Logger log = LoggerFactory.getLogger(MedVectorIndexAlertMonitor.class);

    /** Alert code raised when the vector index is missing or its TAG schema drifted. */
    public static final String INDEX_DEGRADED = "rag-index-degraded";

    /** Alert code raised when a previously degraded vector index is healthy again. */
    public static final String INDEX_RECOVERED = "rag-index-recovered";

    /** Alert code raised when the index probe itself failed, so no verdict could be reached. */
    public static final String INDEX_PROBE_FAILED = "rag-index-probe-failed";

    /** Component label carried by every alert of this monitor. */
    public static final String INDEX_COMPONENT = "vector-index";

    /**
     * Detail key Actuator's {@code AbstractHealthIndicator} attaches when the check itself throws.
     *
     * <p>Verified against {@code spring-boot-actuator} 3.4.5: {@code health()} is {@code final},
     * catches {@code Exception} and calls {@code Health.Builder#down(Throwable)}, which records the
     * exception class and message under this key. It is the only trace left when
     * {@code doHealthCheck} blows up outside the indicator's own guard.</p>
     */
    public static final String ACTUATOR_ERROR_DETAIL = "error";

    private final ObjectProvider<MedVectorIndexHealthIndicator> healthIndicators;

    private final MedAlertNotifier notifier;

    /** Whether the index was degraded on the previous pass; guarded for concurrent reads. */
    private final AtomicBoolean degraded = new AtomicBoolean();

    private final AtomicLong evaluationCount = new AtomicLong();

    private final AtomicLong lastEvaluationEpochMillis = new AtomicLong();

    /**
     * Creates the monitor.
     *
     * @param healthIndicators provider of the vector-index probe, must not be {@code null}
     * @param notifier         the alert dispatcher, must not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public MedVectorIndexAlertMonitor(ObjectProvider<MedVectorIndexHealthIndicator> healthIndicators,
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
     * <p>The delay and the grace period are read from {@code med.rag.index.check-interval} and
     * {@code med.rag.index.initial-delay}, so an operator can slow the probing down without a rebuild.
     * The defaults are far slower than the storage probe's: an index does not flap the way a
     * connection does.</p>
     */
    @Scheduled(fixedDelayString = "${med.rag.index.check-interval:5m}",
            initialDelayString = "${med.rag.index.initial-delay:1m}")
    public void scheduledCheck() {
        checkNow();
    }

    /**
     * Evaluates the index probe once and raises the alerts implied by the transition.
     *
     * <p>A verdict that is not {@code UP} is reported at one of two severities, chosen by
     * {@link #isProbeFailure(Health)}: a probe that ran and found the index broken is a
     * {@code WARNING} (consultations keep working, the evidence shrinks), while a probe that could
     * not reach Redis at all is {@code CRITICAL} — the index state is unknown and a total outage of
     * the retrieval layer must not be filed as a degradation.</p>
     *
     * @return {@code true} when the index is degraded right now; {@code false} when it is healthy or
     *         when no probe is available in this context
     */
    public boolean checkNow() {
        evaluationCount.incrementAndGet();
        lastEvaluationEpochMillis.set(System.currentTimeMillis());

        MedVectorIndexHealthIndicator indicator = healthIndicators.getIfAvailable();
        if (indicator == null) {
            log.debug("no vector index health indicator in this context; skipping alert evaluation");
            return false;
        }

        Health health;
        try {
            health = indicator.health();
        } catch (RuntimeException ex) {
            // Defensive net only: health() is final and converts a throwing check into DOWN, so in
            // production this path is unreachable and the reason detail carries the failure instead.
            notifier.raise(MedAlertSeverity.CRITICAL, INDEX_PROBE_FAILED, INDEX_COMPONENT,
                    "vector index health probe threw " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return degraded.get();
        }

        boolean currentlyDegraded = !Status.UP.equals(health.getStatus());
        if (currentlyDegraded && isProbeFailure(health)) {
            notifier.raise(MedAlertSeverity.CRITICAL, INDEX_PROBE_FAILED, INDEX_COMPONENT,
                    "medical RAG vector index probe failed: " + describe(health.getDetails()));
        } else if (currentlyDegraded) {
            notifier.raise(MedAlertSeverity.WARNING, INDEX_DEGRADED, INDEX_COMPONENT,
                    "medical RAG vector index is degraded: " + describe(health.getDetails()));
        } else if (degraded.get()) {
            notifier.raise(MedAlertSeverity.INFO, INDEX_RECOVERED, INDEX_COMPONENT,
                    "medical RAG vector index is healthy again: " + describe(health.getDetails()));
        }

        degraded.set(currentlyDegraded);
        return currentlyDegraded;
    }

    /**
     * Tells whether a non-{@code UP} verdict means the probe failed rather than "the index is broken".
     *
     * <p>Two shapes are recognised, in order of reliability:</p>
     * <ol>
     *   <li>{@code reason=unreachable} — set by {@link MedVectorIndexHealthIndicator} when
     *       {@code MedVectorIndexProbe#probe()} threw. This is the production signal for "Redis is
     *       down", and it is explicit rather than inferred.</li>
     *   <li>an {@code error} detail — attached by Actuator when {@code doHealthCheck} itself throws,
     *       which is the only way a failure can escape the indicator's own guard.</li>
     * </ol>
     *
     * <p>A {@code DOWN} carrying {@code index-missing} or {@code schema-drift} is deliberately
     * <em>not</em> a probe failure: the probe answered, and the answer is "the index needs repair",
     * which is the {@code WARNING} case.</p>
     *
     * @param health the health verdict to classify, may be {@code null}
     * @return {@code true} when no index verdict could be reached
     */
    public static boolean isProbeFailure(Health health) {
        if (health == null || Status.UP.equals(health.getStatus())) {
            return false;
        }
        Map<String, Object> details = health.getDetails();
        if (MedVectorIndexHealthIndicator.REASON_UNREACHABLE
                .equals(details.get(MedVectorIndexHealthIndicator.REASON_DETAIL))) {
            return true;
        }
        return details.get(ACTUATOR_ERROR_DETAIL) instanceof String;
    }

    /**
     * Renders the health details of the index probe into a one-line operator-facing description.
     *
     * <p>Only index metadata is rendered — name, existence, document count, reason and the missing TAG
     * field names. The details come from {@link MedVectorIndexHealthIndicator}, which never records
     * document content, so nothing clinical can reach a log aggregator through this path.</p>
     *
     * @param details the health details, must not be {@code null}
     * @return a single-line description, never blank
     */
    private static String describe(Map<String, Object> details) {
        StringBuilder description = new StringBuilder();
        append(description, "index", details.get(MedVectorIndexHealthIndicator.INDEX_DETAIL));
        append(description, "reason", details.get(MedVectorIndexHealthIndicator.REASON_DETAIL));
        append(description, "exists", details.get(MedVectorIndexHealthIndicator.EXISTS_DETAIL));
        append(description, "documents", details.get(MedVectorIndexHealthIndicator.DOCUMENTS_DETAIL));
        Object missing = details.get(MedVectorIndexHealthIndicator.MISSING_TAG_FIELDS_DETAIL);
        if (missing instanceof List<?> fields && !fields.isEmpty()) {
            append(description, "missing-tag-fields", fields);
        }
        return description.length() == 0 ? "no detail reported" : description.toString();
    }

    private static void append(StringBuilder target, String label, Object value) {
        if (value == null) {
            return;
        }
        if (target.length() > 0) {
            target.append(", ");
        }
        target.append(label).append('=').append(value);
    }

    /**
     * Tells whether the index was degraded on the most recent pass.
     *
     * @return {@code true} when the last evaluation reported a degraded index
     */
    public boolean isDegraded() {
        return degraded.get();
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
