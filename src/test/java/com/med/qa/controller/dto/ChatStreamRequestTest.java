package com.med.qa.controller.dto;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests of the streaming consultation request record and its boundary validation.
 *
 * <p>D41 demoted the identity fields to optional consistency claims: authority now comes from the
 * authenticated principal, so only the two fields the principal cannot supply — the session key and the
 * question — are required. These tests pin that change, because re-requiring the identity fields would
 * quietly re-suggest that the client has a say in its own scope.</p>
 */
class ChatStreamRequestTest {

    @Test
    void exposesAccessorsAndAllowsNullOptionals() {
        ChatStreamRequest request = new ChatStreamRequest("hosp", "card", "s1", null, "hi", null, null);

        assertThat(request.tenant()).isEqualTo("hosp");
        assertThat(request.dept()).isEqualTo("card");
        assertThat(request.session()).isEqualTo("s1");
        assertThat(request.patientId()).isNull();
        assertThat(request.includeSharedDocuments()).isNull();
        assertThat(request.topK()).isNull();
    }

    @Test
    void validatePassesForAWellFormedRequest() {
        ChatStreamRequest request = new ChatStreamRequest("hosp", "card", "s1", "P1", "hi", true, 4);

        request.validate();
    }

    @Test
    @DisplayName("the identity fields are optional: the principal supplies the scope")
    void validateAcceptsARequestWithoutIdentityClaims() {
        ChatStreamRequest request = new ChatStreamRequest(null, null, "s1", null, "hi", null, null);

        assertThatCode(request::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("blank identity claims are accepted as 'nothing claimed'")
    void validateAcceptsBlankIdentityClaims() {
        ChatStreamRequest request = new ChatStreamRequest("  ", "", "s1", "  ", "hi", null, null);

        assertThatCode(request::validate).doesNotThrowAnyException();
    }

    @Test
    void validateRejectsBlankMessage() {
        ChatStreamRequest request = new ChatStreamRequest("hosp", "card", "s1", null, "  ", null, null);

        assertThatThrownBy(request::validate)
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void validateRejectsMissingSession() {
        ChatStreamRequest request = new ChatStreamRequest("hosp", "card", "", null, "hi", null, null);

        assertThatThrownBy(request::validate)
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void validateRejectsNullSession() {
        ChatStreamRequest request = new ChatStreamRequest("hosp", "card", null, null, "hi", null, null);

        assertThatThrownBy(request::validate)
                .isInstanceOf(BizException.class)
                .hasMessageContaining("session");
    }

    @Test
    void validateRejectsNullMessage() {
        ChatStreamRequest request = new ChatStreamRequest("hosp", "card", "s1", null, null, null, null);

        assertThatThrownBy(request::validate)
                .isInstanceOf(BizException.class)
                .hasMessageContaining("message");
    }
}
