package com.med.qa.common.ratelimit;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.config.MedRateLimitProperties;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Thin facade over Redisson's distributed {@link RRateLimiter} &mdash; the only rate-limiting primitive
 * used by this project. No hand-rolled token bucket, sliding window or Lua script is written here.
 *
 * <h2>What this class owns</h2>
 * <ul>
 *   <li>translating a logical bucket key plus (rate, window) into the Redisson limiter;</li>
 *   <li>idempotently seeding the limiter's rate (the configuration lives in Redis so it survives
 *       restarts and is shared across every application instance);</li>
 *   <li>attempting a single permit with the configured wait timeout; and</li>
 *   <li>translating a Redis outage into the project's {@link ErrorCode#STORAGE_ERROR} vocabulary,
 *       because a guard that fails open would let unbounded traffic reach the protected backend.</li>
 * </ul>
 *
 * <h2>Why fail closed on Redis failure</h2>
 * <p>The session lock takes the same stance: a throttle that silently vanishes during an outage would
 * expose the embedding endpoint or the MySQL session tables to a flood. Rejecting is the safer default
 * for a hospital backend; operators restore Redis and the limiter resumes transparently.</p>
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    /** Bucket key prefix; kept distinct from the {@code med:chat:} cache and {@code med:lock:} namespaces. */
    public static final String KEY_PREFIX_DEFAULT = "med:ratelimit:";

    private final RedissonClient redissonClient;

    private final long acquireTimeoutMillis;

    /**
     * Creates the rate-limit service.
     *
     * <p>The client is injected lazily: Redisson connects on first use, so the application context boots
     * (and the whole unit test suite runs) without Redis available. The wait window comes from
     * {@link MedRateLimitProperties} rather than a bare primitive, so it is resolved from
     * {@code med.rate-limit.acquire-timeout-millis} at startup.</p>
     *
     * @param redissonClient shared Redisson client, must not be {@code null}
     * @param properties     rate-limit configuration, must not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public RateLimitService(@Lazy RedissonClient redissonClient, MedRateLimitProperties properties) {
        if (redissonClient == null) {
            throw new IllegalArgumentException("redissonClient must not be null");
        }
        if (properties == null) {
            throw new IllegalArgumentException("properties must not be null");
        }
        this.redissonClient = redissonClient;
        this.acquireTimeoutMillis = properties.getAcquireTimeoutMillis();
    }

    /**
     * Tries to consume one permit from the bucket identified by {@code key}.
     *
     * @param key               Redis rate-limiter key, must not be {@code null} or blank
     * @param rate              permits granted per {@code durationSeconds}
     * @param durationSeconds   length of the window in seconds, must be positive
     * @return {@code true} when a permit was acquired, {@code false} when the bucket is exhausted
     * @throws IllegalArgumentException if {@code key} is {@code null} or blank, or the window is not
     *                                  positive
     * @throws BizException             {@link ErrorCode#STORAGE_ERROR} when Redis is unreachable
     */
    public boolean tryAcquire(String key, int rate, int durationSeconds) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("rate limit key must not be blank");
        }
        if (rate <= 0) {
            throw new IllegalArgumentException("rate must be positive");
        }
        if (durationSeconds <= 0) {
            throw new IllegalArgumentException("durationSeconds must be positive");
        }
        RRateLimiter limiter = redissonClient.getRateLimiter(key);
        try {
            // Idempotent: only seeds the rate if the bucket has no configuration yet. The rate lives
            // in Redis, so every instance of the deployment converges on the same window.
            limiter.trySetRate(RateType.OVERALL, rate, durationSeconds, RateIntervalUnit.SECONDS);
            return limiter.tryAcquire(1, acquireTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (RedisException e) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "rate limiter unavailable for key " + key, e);
        }
    }
}
