package com.med.qa.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Externalized policy of the RAG vector-index health monitoring, bound from {@code med.rag.index.*}.
 *
 * <pre>
 * med:
 *   rag:
 *     index:
 *       enabled: true             # master switch; false removes the probe and its alert monitor
 *       check-interval: 5m        # how often the index probe is evaluated for alerting
 *       initial-delay: 1m         # grace period after boot before the first evaluation
 *       expected-tag-fields:      # TAG fields the index must declare for scoped retrieval to work
 *         - tenant_id
 *         - dept_id
 *         - patient_id
 * </pre>
 *
 * <h2>Why this exists</h2>
 * <p>Index topology is declared in {@code med.rag.vector-store.*} and applied by
 * {@code RedisVectorStore} when it runs {@code FT.CREATE}. Nothing re-checks the index afterwards, so
 * the two ways retrieval can break are both invisible to the running service:</p>
 * <ul>
 *   <li>the index is <em>absent</em> — it was dropped, or {@code FT.CREATE} never succeeded because
 *       the Redis instance is not a Redis Stack build;</li>
 *   <li>the index <em>exists but its schema drifted</em> — a TAG field was renamed or added to the
 *       configuration after the index was created, so the filter expression the retrieval layer
 *       builds no longer matches anything.</li>
 * </ul>
 * <p>Neither throws. A consultation keeps answering with HTTP 200 and simply retrieves less — or
 * nothing — so a doctor receives an answer assembled from an incomplete corpus. Both are exactly the
 * failure shape D36 uncovered. {@link #getExpectedTagFields()} is the list the probe compares the
 * live index against; it defaults to the three isolation dimensions of the unified storage
 * specification and must be kept in step with
 * {@link MedVectorStoreProperties#getMetadataFields()}.</p>
 *
 * <h2>Deployment note</h2>
 * <p>The probe requires Redis Stack (RediSearch). A deployment that runs the conversation cache on a
 * plain Redis build must set {@code enabled} to {@code false}, otherwise the index component reports
 * {@code DOWN} — deliberately, because on such a deployment the RAG layer genuinely cannot work.</p>
 *
 * <p>Every setter validates eagerly so a typo fails the application context at startup instead of
 * silently disabling the only signal that would have caught a broken index.</p>
 */
@ConfigurationProperties(prefix = MedRagIndexProperties.PREFIX)
public class MedRagIndexProperties {

    /** Configuration namespace owned by this class. */
    public static final String PREFIX = "med.rag.index";

    /** Default interval between two index-probe evaluations. */
    public static final Duration DEFAULT_CHECK_INTERVAL = Duration.ofMinutes(5);

    /** Default grace period between context startup and the first evaluation. */
    public static final Duration DEFAULT_INITIAL_DELAY = Duration.ofMinutes(1);

    /**
     * The isolation tags of the unified storage specification. Scoped retrieval cannot work without
     * all three, so they are the default expectation of the probe.
     */
    public static final List<String> DEFAULT_EXPECTED_TAG_FIELDS =
            List.of("tenant_id", "dept_id", "patient_id");

    private boolean enabled = true;

    private Duration checkInterval = DEFAULT_CHECK_INTERVAL;

    private Duration initialDelay = DEFAULT_INITIAL_DELAY;

    private List<String> expectedTagFields = new ArrayList<>(DEFAULT_EXPECTED_TAG_FIELDS);

    /**
     * Tells whether the index probe and its alert monitor are wired at all.
     *
     * @return {@code true} when the health indicator and the monitor beans exist
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables or disables the whole vector-index monitoring.
     *
     * @param enabled {@code false} removes the health component and the alert monitor; a deployment
     *                without Redis Stack must use this, because otherwise the index component would
     *                report the pod as unhealthy
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns how often the index is probed for alerting.
     *
     * @return a strictly positive duration, never {@code null}
     */
    public Duration getCheckInterval() {
        return checkInterval;
    }

    /**
     * Sets the index-probe evaluation interval.
     *
     * @param checkInterval a strictly positive duration
     * @throws IllegalArgumentException if {@code checkInterval} is {@code null}, zero or negative;
     *                                  a zero period would spin the scheduler and hammer Redis
     */
    public void setCheckInterval(Duration checkInterval) {
        if (checkInterval == null || checkInterval.isZero() || checkInterval.isNegative()) {
            throw new IllegalArgumentException(
                    PREFIX + ".check-interval must be a positive duration but was " + checkInterval);
        }
        this.checkInterval = checkInterval;
    }

    /**
     * Returns the grace period between context startup and the first evaluation.
     *
     * @return a non-negative duration, never {@code null}
     */
    public Duration getInitialDelay() {
        return initialDelay;
    }

    /**
     * Sets the grace period before the first evaluation.
     *
     * @param initialDelay a non-negative duration; zero probes immediately, which on a cold start can
     *                     raise a degradation alert for an index that has simply not been created yet
     * @throws IllegalArgumentException if {@code initialDelay} is {@code null} or negative
     */
    public void setInitialDelay(Duration initialDelay) {
        if (initialDelay == null || initialDelay.isNegative()) {
            throw new IllegalArgumentException(
                    PREFIX + ".initial-delay must not be negative but was " + initialDelay);
        }
        this.initialDelay = initialDelay;
    }

    /**
     * Returns the TAG fields the live index must declare.
     *
     * @return a mutable copy in configuration order, never {@code null}
     */
    public List<String> getExpectedTagFields() {
        return expectedTagFields;
    }

    /**
     * Sets the TAG fields the live index must declare.
     *
     * <p>Must stay in step with the {@code TAG} entries of
     * {@code med.rag.vector-store.metadata-fields}: the probe can only detect the drift it is told to
     * look for.</p>
     *
     * @param expectedTagFields field names, must not be {@code null}, must not contain a {@code null}
     *                          or blank entry and must not repeat a name
     * @throws IllegalArgumentException if the list violates any of those rules
     */
    public void setExpectedTagFields(List<String> expectedTagFields) {
        if (expectedTagFields == null) {
            throw new IllegalArgumentException(PREFIX + ".expected-tag-fields must not be null");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String field : expectedTagFields) {
            if (!StringUtils.hasText(field)) {
                throw new IllegalArgumentException(
                        PREFIX + ".expected-tag-fields must not contain blank entries");
            }
            if (!seen.add(field)) {
                throw new IllegalArgumentException(
                        PREFIX + ".expected-tag-fields contains duplicate name: " + field);
            }
        }
        this.expectedTagFields = new ArrayList<>(expectedTagFields);
    }

    /**
     * Tells whether a field is expected to be indexed as a TAG.
     *
     * @param fieldName the field to test, may be {@code null}
     * @return {@code true} when the field is in {@link #getExpectedTagFields()}
     */
    public boolean isExpectedTagField(String fieldName) {
        return fieldName != null && expectedTagFields.contains(fieldName);
    }

    @Override
    public String toString() {
        return "MedRagIndexProperties{enabled=" + enabled
                + ", checkInterval=" + checkInterval
                + ", initialDelay=" + initialDelay
                + ", expectedTagFields=" + expectedTagFields + '}';
    }
}
