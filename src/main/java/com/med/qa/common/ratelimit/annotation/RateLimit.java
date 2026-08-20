package com.med.qa.common.ratelimit.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declarative, per-caller and per-endpoint rate limiting backed by Redisson's distributed
 * {@code RRateLimiter}.
 *
 * <h2>How a call is bucketed</h2>
 * <p>Every annotated invocation is mapped to a Redis rate-limiter key
 * {@code med:ratelimit:{dimension}:{interface}}, where</p>
 * <ul>
 *   <li>{@code dimension} isolates callers &mdash; by default the authenticated principal
 *       ({@code patient:&lt;id&gt;} for patients, {@code tenant:&lt;id&gt;} for staff), or, when
 *       {@link #key()} is set, the SpEL expression evaluated against the method arguments; and</li>
 *   <li>{@code interface} is {@code ClassName#methodName}, so each endpoint keeps its own bucket.</li>
 * </ul>
 * <p>The combination yields exactly the "patient-per-interface" and "tenant-per-interface" buckets the
 * medical API needs: one noisy patient hammering {@code /api/chat/stream} is throttled without
 * affecting anyone else, and a heavy staff batch job is bounded to its own endpoint.</p>
 *
 * <h2>Behaviour</h2>
 * <p>When the bucket is exhausted the call is rejected with
 * {@link com.med.qa.common.exception.ErrorCode#RATE_LIMITED} (HTTP 429-style). A Redis outage fails
 * closed with {@code STORAGE_ERROR} &mdash; like the distributed lock, a guard must never silently
 * disappear. The whole mechanism is switched off by {@code med.rate-limit.enabled=false} (local dev
 * and the offline test suite), at which point the annotation is a pure no-op.</p>
 *
 * <p>Example &mdash; throttle a patient's streaming endpoint to five starts per second:</p>
 * <pre>{@code
 * @RateLimit(rate = 5, durationSeconds = 1)
 * public SseEmitter streamConsultation(ChatStreamRequest request) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /**
     * Optional SpEL expression selecting the rate-limit dimension, e.g. {@code #patientId}.
     *
     * <p>Arguments are addressable by name ({@code #patientId}) or position ({@code #a0}); the return
     * value is available as {@code #result}. When blank, the dimension falls back to the authenticated
     * principal (patient id, else tenant id), or to {@code anonymous} for an unauthenticated call.</p>
     *
     * @return the dimension expression, or an empty string to use the principal
     */
    String key() default "";

    /**
     * Maximum number of permits granted per {@link #durationSeconds()}.
     *
     * @return the permit count; {@code 0} (the default) means "use {@code med.rate-limit.default-rate}"
     */
    int rate() default 0;

    /**
     * Length of the sliding window, in seconds.
     *
     * @return the window length in seconds; {@code 0} (the default) means "use
     *         {@code med.rate-limit.default-duration-seconds}"
     */
    int durationSeconds() default 0;
}
