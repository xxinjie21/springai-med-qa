package com.med.qa.common.exception;

import com.med.qa.common.result.ApiResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("BizException: keeps its own error code and message")
    void handleBizException() {
        ApiResult<Void> result = handler.handleBizException(
                new BizException(ErrorCode.FORBIDDEN, "patient p-2 cannot read session s-1"));

        assertThat(result.getCode()).isEqualTo(ErrorCode.FORBIDDEN.getCode());
        assertThat(result.getMessage()).isEqualTo("patient p-2 cannot read session s-1");
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("BizException: default message flows through when not customized")
    void handleBizExceptionDefaultMessage() {
        ApiResult<Void> result = handler.handleBizException(new BizException(ErrorCode.RATE_LIMITED));

        assertThat(result.getCode()).isEqualTo(ErrorCode.RATE_LIMITED.getCode());
        assertThat(result.getMessage()).isEqualTo(ErrorCode.RATE_LIMITED.getMessage());
    }

    @Test
    @DisplayName("MethodArgumentNotValidException: joins field errors into the message")
    void handleMethodArgumentNotValid() {
        BindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "chatRequest");
        bindingResult.addError(new FieldError("chatRequest", "sessionId", "must not be blank"));
        bindingResult.addError(new FieldError("chatRequest", "content", "size must be <= 4096"));
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);

        ApiResult<Void> result = handler.handleMethodArgumentNotValid(ex);

        assertThat(result.getCode()).isEqualTo(ErrorCode.BAD_REQUEST.getCode());
        assertThat(result.getMessage())
                .contains("sessionId: must not be blank")
                .contains("content: size must be <= 4096");
    }

    @Test
    @DisplayName("MethodArgumentNotValidException: empty field errors falls back to default message")
    void handleMethodArgumentNotValidWithoutFieldErrors() {
        BindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "chatRequest");
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);

        ApiResult<Void> result = handler.handleMethodArgumentNotValid(ex);

        assertThat(result.getCode()).isEqualTo(ErrorCode.BAD_REQUEST.getCode());
        assertThat(result.getMessage()).isEqualTo(ErrorCode.BAD_REQUEST.getMessage());
    }

    @Test
    @DisplayName("MissingServletRequestParameterException: names the missing parameter")
    void handleMissingParameter() {
        ApiResult<Void> result = handler.handleMissingParameter(
                new MissingServletRequestParameterException("patientId", "String"));

        assertThat(result.getCode()).isEqualTo(ErrorCode.BAD_REQUEST.getCode());
        assertThat(result.getMessage()).contains("patientId");
    }

    @Test
    @DisplayName("HttpMessageNotReadableException: generic malformed body message, no internals leaked")
    void handleNotReadable() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error: unexpected token",
                new MockHttpInputMessage("{bad json".getBytes(StandardCharsets.UTF_8)));

        ApiResult<Void> result = handler.handleNotReadable(ex);

        assertThat(result.getCode()).isEqualTo(ErrorCode.BAD_REQUEST.getCode());
        assertThat(result.getMessage()).isEqualTo("malformed request body");
        assertThat(result.getMessage()).doesNotContain("JSON parse error");
    }

    @Test
    @DisplayName("HttpRequestMethodNotSupportedException: maps to METHOD_NOT_ALLOWED")
    void handleMethodNotSupported() {
        ApiResult<Void> result = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("DELETE"));

        assertThat(result.getCode()).isEqualTo(ErrorCode.METHOD_NOT_ALLOWED.getCode());
        assertThat(result.getMessage()).contains("DELETE");
    }

    @Test
    @DisplayName("NoResourceFoundException: maps to NOT_FOUND with the request path")
    void handleNoResourceFound() {
        ApiResult<Void> result = handler.handleNoResourceFound(
                new NoResourceFoundException(HttpMethod.GET, "api/v1/unknown"));

        assertThat(result.getCode()).isEqualTo(ErrorCode.NOT_FOUND.getCode());
        assertThat(result.getMessage()).contains("api/v1/unknown");
    }

    @Test
    @DisplayName("unexpected exception: falls back to INTERNAL_ERROR without leaking details")
    void handleUnexpected() {
        ApiResult<Void> result = handler.handleUnexpected(new IllegalStateException("connection pool exhausted"));

        assertThat(result.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR.getCode());
        assertThat(result.getMessage()).isEqualTo(ErrorCode.INTERNAL_ERROR.getMessage());
        assertThat(result.getMessage()).doesNotContain("connection pool");
    }

    @Test
    @DisplayName("null-message unexpected exception is still handled safely")
    void handleUnexpectedWithNullMessage() {
        ApiResult<Void> result = handler.handleUnexpected(new RuntimeException());

        assertThat(result.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR.getCode());
    }
}
