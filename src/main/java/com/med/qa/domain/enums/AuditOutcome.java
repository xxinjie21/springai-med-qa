package com.med.qa.domain.enums;

/**
 * Outcome of an audited medical operation: SUCCESS=0 (the method returned normally) /
 * FAILURE=1 (the method threw).
 *
 * <p>The numeric code is what lands in the {@code TINYINT outcome} column of
 * {@code med_audit_log}, following the same "persist the code, not the name" convention as
 * {@link SessionStatus} and {@link RoleType}, so the trail stays readable by the heterogeneous
 * middleware sharing this storage.</p>
 */
public enum AuditOutcome {

    /** The audited method returned normally. */
    SUCCESS(0),

    /** The audited method threw; the audit entry carries the classified error code. */
    FAILURE(1);

    private final int code;

    AuditOutcome(int code) {
        this.code = code;
    }

    /**
     * Returns the numeric code persisted in storage.
     *
     * @return the numeric outcome code
     */
    public int getCode() {
        return code;
    }

    /**
     * Resolves an {@link AuditOutcome} from its numeric code.
     *
     * @param code the numeric code (0-1) read from storage
     * @return the matching outcome
     * @throws IllegalArgumentException if the code maps to no known outcome
     */
    public static AuditOutcome fromCode(int code) {
        for (AuditOutcome outcome : values()) {
            if (outcome.code == code) {
                return outcome;
            }
        }
        throw new IllegalArgumentException("Unknown AuditOutcome code: " + code);
    }

    /**
     * Whether this outcome denotes a successful operation.
     *
     * @return {@code true} only for {@link #SUCCESS}
     */
    public boolean isSuccess() {
        return this == SUCCESS;
    }
}
