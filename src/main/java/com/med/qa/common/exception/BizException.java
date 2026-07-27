package com.med.qa.common.exception;

import java.util.Objects;

/**
 * Business exception carrying a well-defined {@link ErrorCode}.
 *
 * <p>Thrown by service-layer code to signal expected business failures
 * (ownership violation, resource missing, rate limit, etc.). Translated to a
 * unified response by {@code GlobalExceptionHandler}; never used for
 * programming errors.</p>
 */
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    /**
     * Creates a business exception with the error code's default message.
     *
     * @param errorCode error definition, must not be {@code null}
     */
    public BizException(ErrorCode errorCode) {
        super(Objects.requireNonNull(errorCode, "errorCode must not be null").getMessage());
        this.errorCode = errorCode;
    }

    /**
     * Creates a business exception with a custom detail message.
     *
     * @param errorCode error definition, must not be {@code null}
     * @param message   detail message shown to the caller
     */
    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    /**
     * Creates a business exception wrapping a root cause.
     *
     * @param errorCode error definition, must not be {@code null}
     * @param message   detail message shown to the caller
     * @param cause     root cause for logging, may be {@code null}
     */
    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    /**
     * Returns the error code bound to this exception.
     *
     * @return error code, never {@code null}
     */
    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
