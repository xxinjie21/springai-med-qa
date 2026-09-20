package com.med.qa.alert;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Externalized alerting policy of the medical backend, bound from the {@code med.alert.*}
 * configuration namespace.
 *
 * <pre>
 * med:
 *   alert:
 *     enabled: true                 # master switch; false removes the whole alert chain
 *     check-interval: 60s           # how often the storage probe is evaluated
 *     initial-delay: 30s            # grace period after boot before the first evaluation
 *     cooldown: 10m                 # suppression window per (code, component) fingerprint
 *     minimum-severity: INFO        # raise the floor to WARNING to silence recovery notices
 *     key-prefix: "med:alert:"      # Redis namespace of the deduplication map
 *     muted-codes: []               # codes that are observed but never dispatched
 * </pre>
 *
 * <p>Every knob exists because alerting has two failure modes and both are operational bugs: too
 * quiet and an outage goes unnoticed, too loud and the on-call engineer stops reading. The
 * {@link #getCooldown() cooldown} bounds the second, {@link #getMinimumSeverity() minimum-severity}
 * lets a team trade detail for signal, and {@link #getMutedCodes() muted-codes} is the escape hatch
 * for a known-noisy rule during a maintenance window.</p>
 *
 * <p>Setters validate eagerly so an operator typo fails the application context at startup instead
 * of silently turning alerting off at runtime.</p>
 */
@ConfigurationProperties(prefix = MedAlertProperties.PREFIX)
public class MedAlertProperties {

    /** Configuration namespace owned by this class. */
    public static final String PREFIX = "med.alert";

    /** Default interval between two storage-probe evaluations. */
    public static final Duration DEFAULT_CHECK_INTERVAL = Duration.ofSeconds(60);

    /** Default grace period between context startup and the first evaluation. */
    public static final Duration DEFAULT_INITIAL_DELAY = Duration.ofSeconds(30);

    /** Default suppression window per alert fingerprint. */
    public static final Duration DEFAULT_COOLDOWN = Duration.ofMinutes(10);

    /** Default Redis namespace for the deduplication map. */
    public static final String DEFAULT_KEY_PREFIX = "med:alert:";

    /** Default severity floor: nothing is filtered out. */
    public static final MedAlertSeverity DEFAULT_MINIMUM_SEVERITY = MedAlertSeverity.INFO;

    private boolean enabled = true;

    private Duration checkInterval = DEFAULT_CHECK_INTERVAL;

    private Duration initialDelay = DEFAULT_INITIAL_DELAY;

    private Duration cooldown = DEFAULT_COOLDOWN;

    private MedAlertSeverity minimumSeverity = DEFAULT_MINIMUM_SEVERITY;

    private String keyPrefix = DEFAULT_KEY_PREFIX;

    private List<String> mutedCodes = new ArrayList<>();

    /**
     * Tells whether the alert chain is wired at all.
     *
     * @return {@code true} when the notifier, the sinks and the monitor beans exist
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables or disables the whole alert chain.
     *
     * @param enabled {@code false} removes every alert bean from the context
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns how often the storage probe is evaluated.
     *
     * @return a strictly positive duration, never {@code null}
     */
    public Duration getCheckInterval() {
        return checkInterval;
    }

    /**
     * Sets the storage-probe evaluation interval.
     *
     * @param checkInterval a strictly positive duration
     * @throws IllegalArgumentException if {@code checkInterval} is {@code null}, zero or negative;
     *                                  a zero period would spin the scheduler and hammer the
     *                                  probed stores
     */
    public void setCheckInterval(Duration checkInterval) {
        if (checkInterval == null || checkInterval.isZero() || checkInterval.isNegative()) {
            throw new IllegalArgumentException(
                    "med.alert.check-interval must be a positive duration but was " + checkInterval);
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
     * @param initialDelay a non-negative duration; zero starts probing immediately, which on a
     *                     cold start can page for a store that simply has not connected yet
     * @throws IllegalArgumentException if {@code initialDelay} is {@code null} or negative
     */
    public void setInitialDelay(Duration initialDelay) {
        if (initialDelay == null || initialDelay.isNegative()) {
            throw new IllegalArgumentException(
                    "med.alert.initial-delay must not be negative but was " + initialDelay);
        }
        this.initialDelay = initialDelay;
    }

    /**
     * Returns the suppression window applied per alert fingerprint.
     *
     * @return a non-negative duration, never {@code null}; {@link Duration#ZERO} disables
     *         deduplication entirely
     */
    public Duration getCooldown() {
        return cooldown;
    }

    /**
     * Sets the suppression window applied per alert fingerprint.
     *
     * @param cooldown a non-negative duration; zero disables deduplication and dispatches every
     *                 observation, which is useful while tuning a new rule
     * @throws IllegalArgumentException if {@code cooldown} is {@code null} or negative
     */
    public void setCooldown(Duration cooldown) {
        if (cooldown == null || cooldown.isNegative()) {
            throw new IllegalArgumentException(
                    "med.alert.cooldown must not be negative but was " + cooldown);
        }
        this.cooldown = cooldown;
    }

    /**
     * Returns the least severe alert that is still dispatched.
     *
     * @return a severity, never {@code null}
     */
    public MedAlertSeverity getMinimumSeverity() {
        return minimumSeverity;
    }

    /**
     * Sets the severity floor.
     *
     * @param minimumSeverity the least severe alert to dispatch
     * @throws IllegalArgumentException if {@code minimumSeverity} is {@code null}
     */
    public void setMinimumSeverity(MedAlertSeverity minimumSeverity) {
        if (minimumSeverity == null) {
            throw new IllegalArgumentException("med.alert.minimum-severity must not be null");
        }
        this.minimumSeverity = minimumSeverity;
    }

    /**
     * Returns the Redis namespace of the deduplication map.
     *
     * @return a non-blank prefix, never {@code null}
     */
    public String getKeyPrefix() {
        return keyPrefix;
    }

    /**
     * Sets the Redis namespace of the deduplication map.
     *
     * @param keyPrefix a non-blank prefix; it must stay outside the {@code med:chat:} cache
     *                  namespace so the two key families never collide
     * @throws IllegalArgumentException if {@code keyPrefix} is {@code null} or blank
     */
    public void setKeyPrefix(String keyPrefix) {
        if (!StringUtils.hasText(keyPrefix)) {
            throw new IllegalArgumentException("med.alert.key-prefix must not be blank");
        }
        this.keyPrefix = keyPrefix;
    }

    /**
     * Returns the alert codes that are never dispatched.
     *
     * @return a mutable, never {@code null} list in configuration order
     */
    public List<String> getMutedCodes() {
        return mutedCodes;
    }

    /**
     * Sets the alert codes that are never dispatched.
     *
     * @param mutedCodes the codes to mute; {@code null} clears the list
     */
    public void setMutedCodes(List<String> mutedCodes) {
        this.mutedCodes = mutedCodes == null ? new ArrayList<>() : new ArrayList<>(mutedCodes);
    }

    /**
     * Tells whether an alert code is muted.
     *
     * <p>Comparison is case-insensitive and ignores surrounding whitespace, so
     * {@code STORAGE-DOWN} in a YAML file mutes the {@code storage-down} rule.</p>
     *
     * @param code the alert code to test, may be {@code null}
     * @return {@code true} when the code is configured as muted
     */
    public boolean isMuted(String code) {
        if (code == null || mutedCodes.isEmpty()) {
            return false;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String muted : mutedCodes) {
            if (muted != null) {
                normalized.add(muted.trim().toLowerCase(Locale.ROOT));
            }
        }
        return normalized.contains(code.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Tells whether an alert of the given severity clears the configured floor.
     *
     * @param severity the severity to test, must not be {@code null}
     * @return {@code true} when {@code severity} is at least {@link #getMinimumSeverity()}
     * @throws IllegalArgumentException if {@code severity} is {@code null}
     */
    public boolean isReportable(MedAlertSeverity severity) {
        if (severity == null) {
            throw new IllegalArgumentException("severity must not be null");
        }
        return severity.isAtLeast(minimumSeverity);
    }

    /**
     * Tells whether the deduplication store should be consulted.
     *
     * @return {@code true} when a positive cooldown is configured
     */
    public boolean isDeduplicationEnabled() {
        return !cooldown.isZero() && !cooldown.isNegative();
    }

    @Override
    public String toString() {
        return "MedAlertProperties{enabled=" + enabled
                + ", checkInterval=" + checkInterval
                + ", initialDelay=" + initialDelay
                + ", cooldown=" + cooldown
                + ", minimumSeverity=" + minimumSeverity
                + ", keyPrefix='" + keyPrefix + '\''
                + ", mutedCodes=" + mutedCodes + '}';
    }
}
