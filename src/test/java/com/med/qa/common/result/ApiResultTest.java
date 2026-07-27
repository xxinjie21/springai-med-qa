package com.med.qa.common.result;

import com.med.qa.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ApiResultTest {

    @Test
    @DisplayName("ok(data): carries payload with success code 0")
    void okWithData() {
        ApiResult<String> result = ApiResult.ok("hello");

        assertThat(result.getCode()).isZero();
        assertThat(result.getMessage()).isEqualTo(ErrorCode.SUCCESS.getMessage());
        assertThat(result.getData()).isEqualTo("hello");
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("ok(): null payload is allowed for void operations")
    void okWithoutData() {
        ApiResult<Void> result = ApiResult.ok();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isNull();
    }

    @Test
    @DisplayName("fail(errorCode): uses the error code default message")
    void failWithDefaultMessage() {
        ApiResult<Void> result = ApiResult.fail(ErrorCode.NOT_FOUND);

        assertThat(result.getCode()).isEqualTo(ErrorCode.NOT_FOUND.getCode());
        assertThat(result.getMessage()).isEqualTo(ErrorCode.NOT_FOUND.getMessage());
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getData()).isNull();
    }

    @Test
    @DisplayName("fail(errorCode, message): custom message overrides default")
    void failWithCustomMessage() {
        ApiResult<Void> result = ApiResult.fail(ErrorCode.BAD_REQUEST, "sessionId must not be blank");

        assertThat(result.getCode()).isEqualTo(ErrorCode.BAD_REQUEST.getCode());
        assertThat(result.getMessage()).isEqualTo("sessionId must not be blank");
    }

    @Test
    @DisplayName("fail(null): rejects null error code")
    void failRejectsNullErrorCode() {
        assertThatNullPointerException().isThrownBy(() -> ApiResult.fail(null));
        assertThatNullPointerException().isThrownBy(() -> ApiResult.fail(null, "boom"));
    }

    @Test
    @DisplayName("toString(): contains code, message and data for diagnostics")
    void toStringContainsFields() {
        String text = ApiResult.ok("payload").toString();

        assertThat(text).contains("code=0").contains("payload");
    }
}
