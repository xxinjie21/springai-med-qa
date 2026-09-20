package com.med.qa.alert;

import org.springframework.boot.actuate.health.Status;

/**
 * Severity of a medical alert, ordered from least to most urgent.
 *
 * <p>The rank order is what makes the {@code med.alert.minimum-severity} knob meaningful: an
 * operator can silence purely informational traffic (recoveries, routine probes) without touching
 * the code, while a critical store outage always reaches the paging channel.</p>
 *
 * <p>The mapping from an Actuator {@link Status} is deliberately conservative: anything that is not
 * an explicit {@code UP} is treated as reportable, because a health endpoint that answers
 * {@code UNKNOWN} is exactly the case an on-call engineer wants to hear about.</p>
 */
public enum MedAlertSeverity {

    /** Contextual information only: recoveries, configuration changes, dry runs. */
    INFO(0),

    /** Degraded but still serving: a fallback path is active, a dependency is slow. */
    WARNING(1),

    /** The consultation path is broken for at least one component and needs a human. */
    CRITICAL(2);

    private final int rank;

    MedAlertSeverity(int rank) {
        this.rank = rank;
    }

    /**
     * Returns the ordinal rank used for comparisons.
     *
     * @return {@code 0} for {@link #INFO}, {@code 1} for {@link #WARNING}, {@code 2} for
     *         {@link #CRITICAL}
     */
    public int rank() {
        return rank;
    }

    /**
     * Tells whether this severity is at least as urgent as {@code other}.
     *
     * @param other the threshold severity to compare against
     * @return {@code true} when {@code this.rank() >= other.rank()}
     * @throws IllegalArgumentException if {@code other} is {@code null}, which would silently
     *                                  disable every severity filter
     */
    public boolean isAtLeast(MedAlertSeverity other) {
        if (other == null) {
            throw new IllegalArgumentException("other severity must not be null");
        }
        return rank >= other.rank;
    }

    /**
     * Translates an Actuator health status into a severity.
     *
     * <p>Only {@link Status#UP} maps to {@link #INFO}; {@link Status#OUT_OF_SERVICE} and
     * {@link Status#UNKNOWN} map to {@link #WARNING}, and {@link Status#DOWN} maps to
     * {@link #CRITICAL}.</p>
     *
     * @param status the health status reported by a probe, must not be {@code null}
     * @return the matching severity, never {@code null}
     * @throws IllegalArgumentException if {@code status} is {@code null}
     */
    public static MedAlertSeverity fromHealthStatus(Status status) {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (Status.UP.equals(status)) {
            return INFO;
        }
        if (Status.DOWN.equals(status)) {
            return CRITICAL;
        }
        // OUT_OF_SERVICE and UNKNOWN: reachable but not healthy, so a human should look.
        return WARNING;
    }
}
