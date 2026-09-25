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
 *   <li>{@link #INDEX_PROBE_FAILED} at {@code CRITICAL} — the probe itself threw, which means the
 *       index verdict is unknown and every other RAG signal is untrustworthy.</li>
 * </ul>
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

    /** Alert code raised when the index probe itself throws. */
    public static final String INDEX_PROBE_FAILED = "rag-index-probe-failed";

    /** Component label carried by every alert of this monitor. */
    public static final String INDEX_COMPONENT = "vector-index";

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
            // The probe is documented not to throw, so reaching here means something is deeply wrong.
            notifier.raise(MedAlertSeverity.CRITICAL, INDEX_PROBE_FAILED, INDEX_COMPONENT,
                    "vector index health probe threw " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return degraded.get();
        }

        boolean currentlyDegraded = !Status.UP.equals(health.getStatus());
        if (currentlyDegraded) {
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
