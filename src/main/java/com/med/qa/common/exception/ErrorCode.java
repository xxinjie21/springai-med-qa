package com.med.qa.common.exception;

/**
 * Central registry of business error codes.
 *
 * <p>Code segments:</p>
 * <ul>
 *   <li>{@code 0}      — success</li>
 *   <li>{@code 4xxxx}  — client-side errors (bad request, auth, not found, rate limit)</li>
 *   <li>{@code 5xxxx}  — server-side errors (internal, downstream LLM/storage failures)</li>
 * </ul>
 */
public enum ErrorCode {

    /** Operation succeeded. */
    SUCCESS(0, "success"),

    /** Request parameters failed validation. */
    BAD_REQUEST(40000, "invalid request parameter"),

    /** Missing or invalid credentials. */
    UNAUTHORIZED(40100, "unauthorized"),

    /** Authenticated but not allowed to access the resource. */
    FORBIDDEN(40300, "access denied"),

    /** Requested resource does not exist. */
    NOT_FOUND(40400, "resource not found"),

    /** HTTP method not allowed on this endpoint. */
    METHOD_NOT_ALLOWED(40500, "method not allowed"),

    /**
     * The consultation session is already being mutated by another request and the distributed
     * lock could not be acquired within the configured wait window.
     */
    SESSION_LOCKED(40900, "session is busy, please retry"),

    /** Too many requests, rejected by rate limiter. */
    RATE_LIMITED(42900, "too many requests"),

    /** Unclassified server error. */
    INTERNAL_ERROR(50000, "internal server error"),

    /** Downstream LLM service failed or timed out. */
    LLM_SERVICE_ERROR(50201, "llm service unavailable"),

    /** Storage layer (MySQL/Redis) failed. */
    STORAGE_ERROR(50301, "storage service unavailable");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * Returns the numeric business code.
     *
     * @return numeric code, {@code 0} for success
     */
    public int getCode() {
        return code;
    }

    /**
     * Returns the default human readable message.
     *
     * @return default message, never {@code null}
     */
    public String getMessage() {
        return message;
    }
}
