package com.med.qa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * Tuning of the {@code @MedAudit} audit trail.
 *
 * <p>Only the knobs the code cannot infer are configurable: the master switch, the advice order of
 * the aspect, and the truncation limits that keep an over-long action code or failure message from
 * turning a completed medical operation into a failed insert. The limits are capped by the physical
 * column widths of {@code med_audit_log}, so a misconfiguration is rejected at startup rather than
 * discovered by MySQL at 3 a.m.</p>
 *
 * <p>{@code enabled} exists for local development and for the offline test suite, not as an operating
 * lever: switching the trail off in a hospital deployment removes the evidence that an access was
 * ever attempted. It therefore defaults to {@code true}.</p>
 */
@ConfigurationProperties(prefix = "med.audit")
public class MedAuditProperties {

    /** Hard ceiling of {@link #maxActionLength}, imposed by the {@code VARCHAR(64)} column. */
    public static final int ACTION_COLUMN_LENGTH = 64;

    /** Hard ceiling of {@link #maxResourceTypeLength}, imposed by the {@code VARCHAR(32)} column. */
    public static final int RESOURCE_TYPE_COLUMN_LENGTH = 32;

    /** Hard ceiling of {@link #maxResourceIdLength}, imposed by the {@code VARCHAR(128)} column. */
    public static final int RESOURCE_ID_COLUMN_LENGTH = 128;

    /** Hard ceiling of {@link #maxMessageLength}, imposed by the {@code VARCHAR(500)} column. */
    public static final int MESSAGE_COLUMN_LENGTH = 500;

    /** Whether annotated operations are recorded at all. */
    private boolean enabled = true;

    /**
     * Advice order of the audit aspect. A low value keeps the aspect on the outside of the advice
     * chain, so the recorded latency covers everything the call really did.
     */
    private int order = 100;

    /** Largest persisted action code length in characters. */
    private int maxActionLength = ACTION_COLUMN_LENGTH;

    /** Largest persisted resource type length in characters. */
    private int maxResourceTypeLength = RESOURCE_TYPE_COLUMN_LENGTH;

    /** Largest persisted resource id length in characters. */
    private int maxResourceIdLength = RESOURCE_ID_COLUMN_LENGTH;

    /** Largest persisted description/failure message length in characters. */
    private int maxMessageLength = MESSAGE_COLUMN_LENGTH;

    /**
     * Creates the properties with their safe defaults.
     */
    public MedAuditProperties() {
    }

    /**
     * Whether the audit trail is active.
     *
     * @return {@code true} when annotated operations are recorded
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables or disables audit recording.
     *
     * @param enabled {@code true} to record annotated operations
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns the advice order of the audit aspect.
     *
     * @return the order value, lower meaning further outside in the advice chain
     */
    public int getOrder() {
        return order;
    }

    /**
     * Sets the advice order of the audit aspect.
     *
     * @param order order value, lower meaning further outside in the advice chain
     */
    public void setOrder(int order) {
        this.order = order;
    }

    /**
     * Returns the action-code truncation limit.
     *
     * @return maximum action length in characters, always positive
     */
    public int getMaxActionLength() {
        return maxActionLength;
    }

    /**
     * Sets the action-code truncation limit.
     *
     * @param maxActionLength largest persisted action length, must be positive and must fit the
     *                        {@value #ACTION_COLUMN_LENGTH} character column
     * @throws IllegalArgumentException when the value is not positive or exceeds the column width
     */
    public void setMaxActionLength(int maxActionLength) {
        Assert.isTrue(maxActionLength > 0, "maxActionLength must be positive");
        Assert.isTrue(maxActionLength <= ACTION_COLUMN_LENGTH,
                "maxActionLength must not exceed the " + ACTION_COLUMN_LENGTH + " character column");
        this.maxActionLength = maxActionLength;
    }

    /**
     * Returns the resource-type truncation limit.
     *
     * @return maximum resource type length in characters, always positive
     */
    public int getMaxResourceTypeLength() {
        return maxResourceTypeLength;
    }

    /**
     * Sets the resource-type truncation limit.
     *
     * @param maxResourceTypeLength largest persisted resource type length, must be positive and must
     *                              fit the {@value #RESOURCE_TYPE_COLUMN_LENGTH} character column
     * @throws IllegalArgumentException when the value is not positive or exceeds the column width
     */
    public void setMaxResourceTypeLength(int maxResourceTypeLength) {
        Assert.isTrue(maxResourceTypeLength > 0, "maxResourceTypeLength must be positive");
        Assert.isTrue(maxResourceTypeLength <= RESOURCE_TYPE_COLUMN_LENGTH,
                "maxResourceTypeLength must not exceed the " + RESOURCE_TYPE_COLUMN_LENGTH
                        + " character column");
        this.maxResourceTypeLength = maxResourceTypeLength;
    }

    /**
     * Returns the resource-id truncation limit.
     *
     * @return maximum resource id length in characters, always positive
     */
    public int getMaxResourceIdLength() {
        return maxResourceIdLength;
    }

    /**
     * Sets the resource-id truncation limit.
     *
     * @param maxResourceIdLength largest persisted resource id length, must be positive and must fit
     *                            the {@value #RESOURCE_ID_COLUMN_LENGTH} character column
     * @throws IllegalArgumentException when the value is not positive or exceeds the column width
     */
    public void setMaxResourceIdLength(int maxResourceIdLength) {
        Assert.isTrue(maxResourceIdLength > 0, "maxResourceIdLength must be positive");
        Assert.isTrue(maxResourceIdLength <= RESOURCE_ID_COLUMN_LENGTH,
                "maxResourceIdLength must not exceed the " + RESOURCE_ID_COLUMN_LENGTH
                        + " character column");
        this.maxResourceIdLength = maxResourceIdLength;
    }

    /**
     * Returns the message truncation limit.
     *
     * @return maximum message length in characters, always positive
     */
    public int getMaxMessageLength() {
        return maxMessageLength;
    }

    /**
     * Sets the message truncation limit.
     *
     * @param maxMessageLength largest persisted message length, must be positive and must fit the
     *                         {@value #MESSAGE_COLUMN_LENGTH} character column
     * @throws IllegalArgumentException when the value is not positive or exceeds the column width
     */
    public void setMaxMessageLength(int maxMessageLength) {
        Assert.isTrue(maxMessageLength > 0, "maxMessageLength must be positive");
        Assert.isTrue(maxMessageLength <= MESSAGE_COLUMN_LENGTH,
                "maxMessageLength must not exceed the " + MESSAGE_COLUMN_LENGTH + " character column");
        this.maxMessageLength = maxMessageLength;
    }
}
