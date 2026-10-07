package com.med.qa.memory.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Externalized settings of the conversation memory Redis cache, bound from the {@code med.cache.*}
 * configuration namespace.
 *
 * <pre>
 * med:
 *   cache:
 *     ttl: 30m
 *     max-messages: 200
 * </pre>
 *
 * <p>Setters validate eagerly so an operator typo (a negative TTL, a negative window) fails the
 * application context at startup instead of silently degrading the cache at runtime.</p>
 *
 * <p><b>What {@code max-messages} does and does not bound (D44).</b> It is the size of the Redis
 * window only. MySQL keeps the full consultation transcript and is append-only, so lowering this
 * value narrows a cache and shortens a cold-cache replay — it never deletes a turn. The prompt is
 * bounded separately, and more tightly, by {@code med.chat.max-messages}. Setting this to
 * {@code 0} is the one way to make the read path unbounded; see
 * {@link #setMaxMessages(int)}.</p>
 */
@ConfigurationProperties(prefix = "med.cache")
public class MedCacheProperties {

    /** Default time-to-live applied to every cached session key. */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    /** Default number of most recent messages kept per session key (a cache bound, not a transcript bound). */
    public static final int DEFAULT_MAX_MESSAGES = 200;

    private Duration ttl = DEFAULT_TTL;

    private int maxMessages = DEFAULT_MAX_MESSAGES;

    /**
     * Returns the time-to-live refreshed on every cache write.
     *
     * @return a strictly positive duration, never {@code null}
     */
    public Duration getTtl() {
        return ttl;
    }

    /**
     * Sets the cache time-to-live.
     *
     * @param ttl a strictly positive duration
     * @throws IllegalArgumentException if {@code ttl} is {@code null}, zero or negative; an
     *                                  unbounded cache would keep medical conversations in Redis
     *                                  indefinitely, which the retention policy forbids
     */
    public void setTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("med.cache.ttl must be a positive duration but was " + ttl);
        }
        this.ttl = ttl;
    }

    /**
     * Returns the size of the cached message window.
     *
     * @return the maximum number of messages kept per session key — the cached tail only, never the
     *         durable transcript — {@code 0} meaning unbounded
     */
    public int getMaxMessages() {
        return maxMessages;
    }

    /**
     * Sets the size of the cached message window.
     *
     * <p>{@code 0} disables trimming, and because the read path uses the same value as its
     * back-fill bound ({@code RedisMessageCache#windowSize()}) it also stops a cold-cache replay
     * from being truncated — a long consultation would then hand its entire history to the model
     * prompt. The durable transcript is unaffected either way; this is a prompt-size risk, so leave
     * it at the default unless the deployment really wants an unbounded window.</p>
     *
     * @param maxMessages maximum messages kept per session key; {@code 0} disables trimming
     * @throws IllegalArgumentException if {@code maxMessages} is negative
     */
    public void setMaxMessages(int maxMessages) {
        if (maxMessages < 0) {
            throw new IllegalArgumentException(
                    "med.cache.max-messages must not be negative but was " + maxMessages);
        }
        this.maxMessages = maxMessages;
    }

    /**
     * Tells whether the cached list has to be trimmed after a write.
     *
     * @return {@code true} when a finite message window is configured
     */
    public boolean isWindowBounded() {
        return maxMessages > 0;
    }

    @Override
    public String toString() {
        return "MedCacheProperties{ttl=" + ttl + ", maxMessages=" + maxMessages + '}';
    }
}
