package com.med.qa.controller.dto;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests of the streaming consultation request record and its boundary validation.
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
}
