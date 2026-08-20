/**
 * Annotation-driven API rate limiting (D25).
 *
 * <p>Hosts the {@link com.med.qa.common.ratelimit.RateLimitAspect} (AOP enforcement) and the thin
 * {@link com.med.qa.common.ratelimit.RateLimitService} facade over Redisson's {@code RRateLimiter}.
 * No token-bucket or counter is hand-written: the rate limiter is the maintained, distributed
 * Redisson primitive.</p>
 */
package com.med.qa.common.ratelimit;
