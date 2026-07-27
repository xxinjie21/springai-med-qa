package com.med.qa.common.result;

import com.med.qa.common.exception.ErrorCode;

import java.util.Objects;

/**
 * Unified API response envelope shared by all REST endpoints.
 *
 * <p>Contract: {@code code == 0} means success; any non-zero code maps to an
 * entry of {@link ErrorCode}. The {@code data} payload is only meaningful on
 * success and may be {@code null} for void operations.</p>
 *
 * @param <T> payload type
 */
public final class ApiResult<T> {

    /** Business code, {@code 0} for success. */
    private final int code;

    /** Human readable message, never {@code null}. */
    private final String message;

    /** Response payload, may be {@code null}. */
    private final T data;

    private ApiResult(int code, String message, T data) {
        this.code = code;
        this.message = Objects.requireNonNull(message, "message must not be null");
        this.data = data;
    }

    /**
     * Builds a success result carrying the given payload.
     *
     * @param data payload, may be {@code null}
     * @param <T>  payload type
     * @return success result with code {@code 0}
     */
    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    /**
     * Builds a success result without payload.
     *
     * @param <T> payload type
     * @return success result with code {@code 0} and {@code null} data
     */
    public static <T> ApiResult<T> ok() {
        return ok(null);
    }

    /**
     * Builds a failure result from an {@link ErrorCode}, using its default message.
     *
     * @param errorCode error definition, must not be {@code null}
     * @param <T>       payload type
     * @return failure result carrying the error code and default message
     */
    public static <T> ApiResult<T> fail(ErrorCode errorCode) {
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        return new ApiResult<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /**
     * Builds a failure result from an {@link ErrorCode} with an overridden message.
     *
     * @param errorCode error definition, must not be {@code null}
     * @param message   detail message overriding the default one, must not be {@code null}
     * @param <T>       payload type
     * @return failure result carrying the error code and custom message
     */
    public static <T> ApiResult<T> fail(ErrorCode errorCode, String message) {
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        return new ApiResult<>(errorCode.getCode(), message, null);
    }

    /**
     * Returns whether this result represents success.
     *
     * @return {@code true} if {@code code == 0}
     */
    public boolean isSuccess() {
        return code == ErrorCode.SUCCESS.getCode();
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    @Override
    public String toString() {
        return "ApiResult{code=" + code + ", message='" + message + "', data=" + data + '}';
    }
}
