package com.med.qa.memory.lock;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Externalized timings of the distributed session lock, bound from the {@code med.lock.*}
 * configuration namespace.
 *
 * <pre>
 * med:
 *   lock:
 *     wait-time: 3s          # how long a request queues for a busy session
 *     lease-time: 0s         # 0 hands lease management to the Redisson watchdog
 *     watchdog-timeout: 30s  # initial lease granted (and renewed) by the watchdog
 * </pre>
 *
 * <p>A {@code lease-time} of zero is the recommended production setting: Redisson then renews the
 * lease in the background for as long as the owning thread lives, so a slow LLM round trip cannot
 * lose the lock mid-flight, while a crashed node still releases it after
 * {@link #getWatchdogTimeout()}. A non-zero {@code lease-time} disables the watchdog and caps the
 * hold time hard.</p>
 *
 * <p>Setters validate eagerly so an operator typo fails the application context at startup instead
 * of silently disabling mutual exclusion at runtime.</p>
 */
@ConfigurationProperties(prefix = "med.lock")
public class MedLockProperties {

    /** Default time a caller queues before giving up on a busy session. */
    public static final Duration DEFAULT_WAIT_TIME = Duration.ofSeconds(3);

    /** Default lease time; zero means "let the Redisson watchdog manage the lease". */
    public static final Duration DEFAULT_LEASE_TIME = Duration.ZERO;

    /** Default lease granted by the watchdog and refreshed every third of that period. */
    public static final Duration DEFAULT_WATCHDOG_TIMEOUT = Duration.ofSeconds(30);

    private Duration waitTime = DEFAULT_WAIT_TIME;

    private Duration leaseTime = DEFAULT_LEASE_TIME;

    private Duration watchdogTimeout = DEFAULT_WATCHDOG_TIMEOUT;

    /**
     * Returns how long an incoming request waits for a session lock held by someone else.
     *
     * @return a non-negative duration, never {@code null}; zero means "fail fast"
     */
    public Duration getWaitTime() {
        return waitTime;
    }

    /**
     * Sets the lock acquisition wait window.
     *
     * @param waitTime a non-negative duration; zero turns acquisition into a single attempt
     * @throws IllegalArgumentException if {@code waitTime} is {@code null} or negative, which
     *                                  Redisson would interpret as an unbounded wait and could pin
     *                                  request threads forever
     */
    public void setWaitTime(Duration waitTime) {
        if (waitTime == null || waitTime.isNegative()) {
            throw new IllegalArgumentException(
                    "med.lock.wait-time must not be negative but was " + waitTime);
        }
        this.waitTime = waitTime;
    }

    /**
     * Returns the fixed lease time of an acquired lock.
     *
     * @return a non-negative duration, never {@code null}; {@link Duration#ZERO} delegates lease
     *         management to the watchdog
     */
    public Duration getLeaseTime() {
        return leaseTime;
    }

    /**
     * Sets the fixed lease time of an acquired lock.
     *
     * @param leaseTime a non-negative duration; zero enables the Redisson watchdog
     * @throws IllegalArgumentException if {@code leaseTime} is {@code null} or negative; a negative
     *                                  lease would create a lock nobody can ever release
     */
    public void setLeaseTime(Duration leaseTime) {
        if (leaseTime == null || leaseTime.isNegative()) {
            throw new IllegalArgumentException(
                    "med.lock.lease-time must not be negative but was " + leaseTime);
        }
        this.leaseTime = leaseTime;
    }

    /**
     * Returns the lease the Redisson watchdog grants and keeps renewing.
     *
     * @return a strictly positive duration, never {@code null}
     */
    public Duration getWatchdogTimeout() {
        return watchdogTimeout;
    }

    /**
     * Sets the watchdog lease.
     *
     * @param watchdogTimeout a strictly positive duration
     * @throws IllegalArgumentException if {@code watchdogTimeout} is {@code null}, zero or
     *                                  negative; Redisson requires a positive watchdog period and a
     *                                  zero value would drop every lock immediately
     */
    public void setWatchdogTimeout(Duration watchdogTimeout) {
        if (watchdogTimeout == null || watchdogTimeout.isZero() || watchdogTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "med.lock.watchdog-timeout must be a positive duration but was " + watchdogTimeout);
        }
        this.watchdogTimeout = watchdogTimeout;
    }

    /**
     * Tells whether Redisson's automatic lease renewal is in charge.
     *
     * @return {@code true} when no fixed lease time is configured
     */
    public boolean isWatchdogEnabled() {
        return leaseTime.isZero();
    }

    @Override
    public String toString() {
        return "MedLockProperties{waitTime=" + waitTime
                + ", leaseTime=" + leaseTime
                + ", watchdogTimeout=" + watchdogTimeout + '}';
    }
}
