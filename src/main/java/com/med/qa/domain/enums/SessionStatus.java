package com.med.qa.domain.enums;

/**
 * Lifecycle status of a consultation session:
 * ACTIVE=0 (ongoing) / CLOSED=1 (finished by user or timeout) / ARCHIVED=2 (cold data).
 */
public enum SessionStatus {

    /** Session is ongoing and accepts new messages. */
    ACTIVE(0),

    /** Session has been closed; no new messages are accepted. */
    CLOSED(1),

    /** Session has been archived as cold data. */
    ARCHIVED(2);

    private final int code;

    SessionStatus(int code) {
        this.code = code;
    }

    /**
     * Returns the numeric code persisted in storage.
     *
     * @return the numeric status code
     */
    public int getCode() {
        return code;
    }

    /**
     * Resolves a {@link SessionStatus} from its numeric code.
     *
     * @param code the numeric code (0-2) read from storage
     * @return the matching status
     * @throws IllegalArgumentException if the code maps to no known status
     */
    public static SessionStatus fromCode(int code) {
        for (SessionStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown SessionStatus code: " + code);
    }

    /**
     * Whether this session can still accept new chat messages.
     *
     * @return {@code true} only for {@link #ACTIVE}
     */
    public boolean isWritable() {
        return this == ACTIVE;
    }
}
