package com.med.qa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * Tuning of the Server-Sent Events streaming consultation endpoint.
 *
 * <p>Two knobs only: how often a heartbeat comment is pushed to keep a slow or quiet connection open,
 * and how long the SSE exchange may stay open before Spring forces it closed. Both are expressed in
 * seconds and must be strictly positive; a bad value fails context startup rather than surfacing
 * mid-call, because an invalid timeout would let a connection hang forever and an invalid heartbeat
 * cadence is meaningless.</p>
 */
@ConfigurationProperties(prefix = "med.chat.stream")
public class MedChatStreamProperties {

    /** Seconds between keep-alive comments on an open SSE stream. */
    private long heartbeatIntervalSeconds = 15;

    /** Maximum seconds an SSE exchange may stay open before Spring times it out. */
    private long sseTimeoutSeconds = 120;

    /**
     * Creates the properties with their safe defaults.
     */
    public MedChatStreamProperties() {
    }

    /**
     * Returns the keep-alive cadence in seconds.
     *
     * @return seconds between heartbeat comments, always positive
     */
    public long getHeartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
    }

    /**
     * Sets the keep-alive cadence, failing fast on a non-positive value.
     *
     * @param heartbeatIntervalSeconds seconds between heartbeat comments, must be {@code > 0}
     * @throws IllegalArgumentException when the value is not positive
     */
    public void setHeartbeatIntervalSeconds(long heartbeatIntervalSeconds) {
        Assert.isTrue(heartbeatIntervalSeconds > 0, "heartbeatIntervalSeconds must be positive");
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
    }

    /**
     * Returns the maximum SSE exchange lifetime in seconds.
     *
     * @return timeout in seconds, always positive
     */
    public long getSseTimeoutSeconds() {
        return sseTimeoutSeconds;
    }

    /**
     * Sets the maximum SSE exchange lifetime, failing fast on a non-positive value.
     *
     * @param sseTimeoutSeconds timeout in seconds, must be {@code > 0}
     * @throws IllegalArgumentException when the value is not positive
     */
    public void setSseTimeoutSeconds(long sseTimeoutSeconds) {
        Assert.isTrue(sseTimeoutSeconds > 0, "sseTimeoutSeconds must be positive");
        this.sseTimeoutSeconds = sseTimeoutSeconds;
    }
}
