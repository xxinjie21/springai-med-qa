package com.med.qa.domain.enums;

/**
 * Conversation participant role, aligned with the unified medical storage
 * specification (ROADMAP section 4): PATIENT=0 / DOCTOR=1 / ASSISTANT=2 / SYSTEM=3.
 *
 * <p>The numeric code is the wire/storage value shared with the heterogeneous
 * Python middleware, so it MUST NOT be changed.</p>
 */
public enum RoleType {

    /** The patient asking questions. */
    PATIENT(0),

    /** A human doctor participating in the consultation. */
    DOCTOR(1),

    /** The LLM assistant reply. */
    ASSISTANT(2),

    /** System / prompt-injected messages. */
    SYSTEM(3);

    private final int code;

    RoleType(int code) {
        this.code = code;
    }

    /**
     * Returns the storage-spec numeric code of this role.
     *
     * @return the numeric code persisted in MySQL / Protobuf
     */
    public int getCode() {
        return code;
    }

    /**
     * Resolves a {@link RoleType} from its storage-spec numeric code.
     *
     * @param code the numeric code (0-3) read from storage
     * @return the matching role
     * @throws IllegalArgumentException if the code maps to no known role
     */
    public static RoleType fromCode(int code) {
        for (RoleType role : values()) {
            if (role.code == code) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unknown RoleType code: " + code);
    }
}
