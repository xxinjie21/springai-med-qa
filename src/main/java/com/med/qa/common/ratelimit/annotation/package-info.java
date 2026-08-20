/**
 * Annotation-driven API rate limiting (D25).
 *
 * <p>{@link RateLimit} marks a method whose invocations are throttled by a distributed
 * {@code RRateLimiter}. The bucket key combines a caller dimension (principal id or a SpEL
 * expression) with the endpoint identity, so each caller is limited independently per interface.</p>
 */
package com.med.qa.common.ratelimit.annotation;
