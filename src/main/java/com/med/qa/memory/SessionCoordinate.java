package com.med.qa.memory;

import java.util.Objects;

/**
 * Value object that binds a single consultation session to the conversation-id string used by
 * Spring AI's {@code ChatMemory} (the {@code conversationId} handed to the
 * {@code MessageChatMemoryAdvisor}).
 *
 * <p>The three identity segments ({@code tenantId} / {@code deptId} / {@code sessionId}) are
 * exactly the same fields the unified storage specification (ROADMAP section 4) derives the Redis
 * key {@code med:chat:{tenant_id}:{dept_id}:{session_id}} from, so one composite id maps 1:1 to a
 * stored session. Encoding them as {@code tenantId:deptId:sessionId} keeps the wire form a plain
 * string that survives being carried in HTTP headers / request params without escaping.</p>
 *
 * <p>Segment values must not contain the {@link #SEPARATOR} character for the round-trip to be
 * unambiguous; identity ids in this project are UUIDs or short codes, so this holds in practice.</p>
 */
public final class SessionCoordinate {

    /** Separator between the three identity segments in the encoded conversation id. */
    public static final String SEPARATOR = ":";

    private final String tenantId;

    private final String deptId;

    private final String sessionId;

    private SessionCoordinate(String tenantId, String deptId, String sessionId) {
        this.tenantId = tenantId;
        this.deptId = deptId;
        this.sessionId = sessionId;
    }

    /**
     * Builds a coordinate from the three identity segments.
     *
     * @param tenantId  hospital/tenant id, must not be blank
     * @param deptId    department id, must not be blank
     * @param sessionId consultation session id, must not be blank
     * @return the coordinate
     * @throws IllegalArgumentException if any segment is {@code null} or blank
     */
    public static SessionCoordinate of(String tenantId, String deptId, String sessionId) {
        require(tenantId, "tenantId");
        require(deptId, "deptId");
        require(sessionId, "sessionId");
        return new SessionCoordinate(tenantId, deptId, sessionId);
    }

    /**
     * Decodes a {@code conversationId} previously produced by {@link #toConversationId()}.
     *
     * @param conversationId the encoded id, must be {@code tenantId:deptId:sessionId}
     * @return the decoded coordinate
     * @throws IllegalArgumentException if the string is blank, has a wrong number of segments, or
     *                                  holds a blank segment
     */
    public static SessionCoordinate parse(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        String[] parts = conversationId.split(SEPARATOR);
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                    "conversationId must be tenant" + SEPARATOR + "dept" + SEPARATOR
                            + "sessionId, but got: " + conversationId);
        }
        for (String part : parts) {
            if (part.isBlank()) {
                throw new IllegalArgumentException(
                        "conversationId segments must not be blank: " + conversationId);
            }
        }
        return new SessionCoordinate(parts[0], parts[1], parts[2]);
    }

    /**
     * Encodes this coordinate into the Spring AI conversation id.
     *
     * @return {@code tenantId:deptId:sessionId}
     */
    public String toConversationId() {
        return tenantId + SEPARATOR + deptId + SEPARATOR + sessionId;
    }

    public String tenantId() {
        return tenantId;
    }

    public String deptId() {
        return deptId;
    }

    public String sessionId() {
        return sessionId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SessionCoordinate that)) {
            return false;
        }
        return Objects.equals(tenantId, that.tenantId)
                && Objects.equals(deptId, that.deptId)
                && Objects.equals(sessionId, that.sessionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, deptId, sessionId);
    }

    @Override
    public String toString() {
        return "SessionCoordinate{tenantId='" + tenantId + "', deptId='" + deptId
                + "', sessionId='" + sessionId + "'}";
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
