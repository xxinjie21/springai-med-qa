package com.med.qa.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeTest {

    @Test
    @DisplayName("SUCCESS is the only code equal to zero")
    void successIsZero() {
        assertThat(ErrorCode.SUCCESS.getCode()).isZero();

        Arrays.stream(ErrorCode.values())
                .filter(ec -> ec != ErrorCode.SUCCESS)
                .forEach(ec -> assertThat(ec.getCode()).isNotZero());
    }

    @Test
    @DisplayName("all codes are unique and messages are non-blank")
    void codesUniqueAndMessagesPresent() {
        long distinct = Arrays.stream(ErrorCode.values())
                .map(ErrorCode::getCode)
                .collect(Collectors.toSet())
                .size();

        assertThat(distinct).isEqualTo(ErrorCode.values().length);
        Arrays.stream(ErrorCode.values())
                .forEach(ec -> assertThat(ec.getMessage()).isNotBlank());
    }

    @Test
    @DisplayName("client errors live in 4xxxx segment, server errors in 5xxxx")
    void codeSegmentsFollowConvention() {
        assertThat(ErrorCode.BAD_REQUEST.getCode()).isBetween(40000, 49999);
        assertThat(ErrorCode.UNAUTHORIZED.getCode()).isBetween(40000, 49999);
        assertThat(ErrorCode.FORBIDDEN.getCode()).isBetween(40000, 49999);
        assertThat(ErrorCode.NOT_FOUND.getCode()).isBetween(40000, 49999);
        assertThat(ErrorCode.METHOD_NOT_ALLOWED.getCode()).isBetween(40000, 49999);
        assertThat(ErrorCode.RATE_LIMITED.getCode()).isBetween(40000, 49999);
        assertThat(ErrorCode.INTERNAL_ERROR.getCode()).isBetween(50000, 59999);
        assertThat(ErrorCode.LLM_SERVICE_ERROR.getCode()).isBetween(50000, 59999);
        assertThat(ErrorCode.STORAGE_ERROR.getCode()).isBetween(50000, 59999);
    }
}
