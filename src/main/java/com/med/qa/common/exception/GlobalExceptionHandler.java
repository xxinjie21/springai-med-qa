package com.med.qa.common.exception;

import com.med.qa.common.result.ApiResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * Global exception translator converting every uncaught exception into the
 * unified {@link ApiResult} envelope.
 *
 * <p>Mapping strategy: business failures ({@link BizException}) keep their own
 * {@link ErrorCode}; well-known Spring MVC exceptions map to the 4xxxx client
 * segment; anything else falls back to {@link ErrorCode#INTERNAL_ERROR} and is
 * logged at ERROR level without leaking internals to the caller.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles expected business failures thrown by service code.
     *
     * @param ex business exception carrying its error code
     * @return failure result with the exception's code and message
     */
    @ExceptionHandler(BizException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<Void> handleBizException(BizException ex) {
        log.warn("business failure: code={}, message={}", ex.getErrorCode().getCode(), ex.getMessage());
        return ApiResult.fail(ex.getErrorCode(), ex.getMessage());
    }

    /**
     * Handles {@code @Valid} body binding failures.
     *
     * @param ex validation exception with field errors
     * @return failure result listing violated fields
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResult<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + defaultIfNull(fe.getDefaultMessage()))
                .collect(Collectors.joining("; "));
        log.warn("request body validation failed: {}", detail);
        return ApiResult.fail(ErrorCode.BAD_REQUEST, detail.isEmpty() ? ErrorCode.BAD_REQUEST.getMessage() : detail);
    }

    /**
     * Handles missing required query/form parameters.
     *
     * @param ex missing parameter exception
     * @return failure result naming the missing parameter
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResult<Void> handleMissingParameter(MissingServletRequestParameterException ex) {
        log.warn("missing request parameter: {}", ex.getParameterName());
        return ApiResult.fail(ErrorCode.BAD_REQUEST, "missing required parameter: " + ex.getParameterName());
    }

    /**
     * Handles unreadable / malformed request bodies (e.g. broken JSON).
     *
     * @param ex message conversion exception
     * @return failure result with a generic bad-request message
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResult<Void> handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn("malformed request body: {}", ex.getMessage());
        return ApiResult.fail(ErrorCode.BAD_REQUEST, "malformed request body");
    }

    /**
     * Handles requests hitting an endpoint with an unsupported HTTP method.
     *
     * @param ex method-not-supported exception
     * @return failure result with method-not-allowed code
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ApiResult<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("method not allowed: {}", ex.getMethod());
        return ApiResult.fail(ErrorCode.METHOD_NOT_ALLOWED, "method not allowed: " + ex.getMethod());
    }

    /**
     * Handles requests to unknown paths (Spring MVC 6.1+ static/handler miss).
     *
     * @param ex no-resource exception
     * @return failure result with not-found code
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResult<Void> handleNoResourceFound(NoResourceFoundException ex) {
        log.warn("resource not found: {}", ex.getResourcePath());
        return ApiResult.fail(ErrorCode.NOT_FOUND, "resource not found: /" + ex.getResourcePath());
    }

    /**
     * Last-resort handler for unexpected server errors. Internals are logged
     * but never exposed to the caller.
     *
     * @param ex unexpected exception
     * @return failure result with the generic internal-error code
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResult<Void> handleUnexpected(Exception ex) {
        log.error("unexpected server error", ex);
        return ApiResult.fail(ErrorCode.INTERNAL_ERROR);
    }

    private static String defaultIfNull(String message) {
        return message == null ? "invalid" : message;
    }
}
