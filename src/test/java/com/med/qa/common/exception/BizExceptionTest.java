package com.med.qa.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class BizExceptionTest {

    @Test
    @DisplayName("single-arg constructor uses the error code default message")
    void constructorWithErrorCodeOnly() {
        BizException ex = new BizException(ErrorCode.FORBIDDEN);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(ex.getMessage()).isEqualTo(ErrorCode.FORBIDDEN.getMessage());
    }

    @Test
    @DisplayName("custom message constructor keeps both code and message")
    void constructorWithCustomMessage() {
        BizException ex = new BizException(ErrorCode.NOT_FOUND, "session s-1 not found");

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(ex.getMessage()).isEqualTo("session s-1 not found");
    }

    @Test
    @DisplayName("cause constructor preserves the root cause chain")
    void constructorWithCause() {
        IllegalStateException cause = new IllegalStateException("redis down");
        BizException ex = new BizException(ErrorCode.STORAGE_ERROR, "cache write failed", cause);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.STORAGE_ERROR);
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("null error code is rejected by every constructor")
    void nullErrorCodeRejected() {
        assertThatNullPointerException().isThrownBy(() -> new BizException(null));
        assertThatNullPointerException().isThrownBy(() -> new BizException(null, "m"));
        assertThatNullPointerException().isThrownBy(() -> new BizException(null, "m", new RuntimeException()));
    }
}
