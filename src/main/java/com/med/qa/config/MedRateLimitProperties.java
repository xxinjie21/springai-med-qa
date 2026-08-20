package com.med.qa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * Tuning of the {@code @RateLimit} annotation enforcement.
 *
 * <p>Only the knobs the code cannot infer are configurable: the master switch, the advice order of the
 * aspect, the Redis key namespace, the wait window for a permit, and the fallback rate/window used when
 * an annotation leaves them at their {@code 0} default. Every numeric setter is validated at startup so
 * a misconfiguration fails fast rather than producing an unusable limiter or a negative wait.</p>
 *
 * <p>{@code enabled} exists for local development and the offline test suite; switching the limiter off
 * in a production deployment removes protection from a downstream that may not rate-limit on its own, so
 * it defaults to {@code true}.</p>
 */
@ConfigurationProperties(prefix = "med.rate-limit")
public class MedRateLimitProperties {

    /** Default Redis key prefix, kept clear of the {@code med:chat:} cache and {@code med:lock:} keys. */
    public static final String KEY_PREFIX_DEFAULT = "med:ratelimit:";

    /** Default permits per window when an annotation omits {@code rate()}. */
    public static final int DEFAULT_RATE = 10;

    /** Default window length (seconds) when an annotation omits {@code durationSeconds()}. */
    public static final int DEFAULT_DURATION_SECONDS = 1;

    /** Default wait for a permit before giving up; {@code 0} fails immediately when none is free. */
    public static final long DEFAULT_ACQUIRE_TIMEOUT_MILLIS = 0L;

    /** Default advice order; lower than the audit aspect so a rejection is cheap. */
    public static final int DEFAULT_ORDER = 50;

    private boolean enabled = true;

    private int order = DEFAULT_ORDER;

    private String keyPrefix = KEY_PREFIX_DEFAULT;

    private long acquireTimeoutMillis = DEFAULT_ACQUIRE_TIMEOUT_MILLIS;

    private int defaultRate = DEFAULT_RATE;

    private int defaultDurationSeconds = DEFAULT_DURATION_SECONDS;

    /**
     * Creates the properties with their safe defaults.
     */
    public MedRateLimitProperties() {
    }

    /**
     * Whether annotated operations are throttled at all.
     *
     * @return {@code true} when the limiter is active
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables or disables rate limiting.
     *
     * @param enabled {@code true} to enforce {@code @RateLimit}
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns the advice order of the rate-limit aspect.
     *
     * @return order value, lower meaning further outside in the advice chain
     */
    public int getOrder() {
        return order;
    }

    /**
     * Sets the advice order of the rate-limit aspect.
     *
     * @param order order value, must not be negative
     * @throws IllegalArgumentException when the value is negative
     */
    public void setOrder(int order) {
        Assert.isTrue(order >= 0, "order must not be negative");
        this.order = order;
    }

    /**
     * Returns the Redis key prefix for every rate-limiter bucket.
     *
     * @return the key prefix, never {@code null} once constructed
     */
    public String getKeyPrefix() {
        return keyPrefix;
    }

    /**
     * Sets the Redis key prefix.
     *
     * @param keyPrefix bucket namespace prefix, must not be blank
     * @throws IllegalArgumentException when blank
     */
    public void setKeyPrefix(String keyPrefix) {
        Assert.hasText(keyPrefix, "keyPrefix must not be blank");
        this.keyPrefix = keyPrefix;
    }

    /**
     * Returns the maximum time to wait for a permit before rejecting.
     *
     * @return wait in milliseconds, {@code 0} for immediate failure
     */
    public long getAcquireTimeoutMillis() {
        return acquireTimeoutMillis;
    }

    /**
     * Sets the maximum wait for a permit.
     *
     * @param acquireTimeoutMillis wait in milliseconds, must not be negative
     * @throws IllegalArgumentException when negative
     */
    public void setAcquireTimeoutMillis(long acquireTimeoutMillis) {
        Assert.isTrue(acquireTimeoutMillis >= 0, "acquireTimeoutMillis must not be negative");
        this.acquireTimeoutMillis = acquireTimeoutMillis;
    }

    /**
     * Returns the fallback permit count used when an annotation omits {@code rate()}.
     *
     * @return default permits per window, always positive
     */
    public int getDefaultRate() {
        return defaultRate;
    }

    /**
     * Sets the fallback permit count.
     *
     * @param defaultRate default permits per window, must be positive
     * @throws IllegalArgumentException when not positive
     */
    public void setDefaultRate(int defaultRate) {
        Assert.isTrue(defaultRate > 0, "defaultRate must be positive");
        this.defaultRate = defaultRate;
    }

    /**
     * Returns the fallback window length used when an annotation omits {@code durationSeconds()}.
     *
     * @return default window length in seconds, always positive
     */
    public int getDefaultDurationSeconds() {
        return defaultDurationSeconds;
    }

    /**
     * Sets the fallback window length.
     *
     * @param defaultDurationSeconds default window in seconds, must be positive
     * @throws IllegalArgumentException when not positive
     */
    public void setDefaultDurationSeconds(int defaultDurationSeconds) {
        Assert.isTrue(defaultDurationSeconds > 0, "defaultDurationSeconds must be positive");
        this.defaultDurationSeconds = defaultDurationSeconds;
    }
}
