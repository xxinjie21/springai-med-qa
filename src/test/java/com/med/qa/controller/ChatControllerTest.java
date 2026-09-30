package com.med.qa.controller;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.controller.dto.ChatStreamRequest;
import com.med.qa.security.MedPrincipal;
import com.med.qa.service.ChatStreamService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import reactor.core.publisher.Flux;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests of the SSE streaming consultation endpoint.
 *
 * <p>Context boots offline (Flyway disabled, no model/Redis), the streaming orchestration is mocked,
 * and the endpoint is exercised through MockMvc with async dispatch so the SSE framing, heartbeat
 * lifecycle and error path are covered without a real model or socket.</p>
 *
 * <p>D41 added the two refusal paths a caller sees when the request must not be served: the service
 * raises them synchronously, and the controller answers with the matching HTTP status and a single SSE
 * {@code error} event <em>before</em> any long-lived stream is opened.</p>
 *
 * <p>Authentication is switched off in this context, so the controller hands a {@code null} principal to
 * the service — hence the {@code nullable(MedPrincipal.class)} matcher. That the principal is really
 * resolved from the API key and really reaches the service is asserted by
 * {@link com.med.qa.security.StreamingIdentityContractTest}, which runs with authentication on.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "med.security.enabled=false",
        "med.rate-limit.enabled=false",
        "med.alert.enabled=false"
})
class ChatControllerTest {

    private static final String BODY =
            "{\"tenant\":\"hosp-1\",\"dept\":\"card\",\"session\":\"s-1\",\"message\":\"hi\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatStreamService chatStreamService;

    private MvcResult performStream(String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andReturn();
    }

    @Test
    @DisplayName("streams the assistant answer as SSE message events")
    void streamsAnswer() throws Exception {
        when(chatStreamService.streamConsultation(any(ChatStreamRequest.class), nullable(MedPrincipal.class)))
                .thenReturn(Flux.just("Hello", " world"));

        MvcResult result = performStream(BODY);

        mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Hello world")));
    }

    @Test
    @DisplayName("rejects a request with a blank message as a bad request")
    void rejectsBlankMessage() throws Exception {
        // validation fails before the long-lived stream opens: a 400 SSE error event is returned
        MvcResult result = performStream(
                "{\"tenant\":\"hosp-1\",\"dept\":\"card\",\"session\":\"s-1\",\"message\":\"  \"}");

        mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(result))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("40000")));
    }

    @Test
    @DisplayName("rejects a request without a session as a bad request")
    void rejectsMissingSession() throws Exception {
        MvcResult result = performStream(
                "{\"tenant\":\"hosp-1\",\"dept\":\"card\",\"message\":\"hi\"}");

        mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(result))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("40000")));
    }

    @Test
    @DisplayName("answers an identity refusal with 403 and a single SSE error event (D41)")
    void answersIdentityRefusalWithForbidden() throws Exception {
        when(chatStreamService.streamConsultation(any(ChatStreamRequest.class), nullable(MedPrincipal.class)))
                .thenThrow(new BizException(ErrorCode.FORBIDDEN, "tenant mismatch"));

        MvcResult result = performStream(BODY);

        mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(result))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("event:error")))
                .andExpect(content().string(containsString("40300")));
    }

    @Test
    @DisplayName("answers an unknown session with 404 before the stream opens (D41)")
    void answersUnknownSessionWithNotFound() throws Exception {
        when(chatStreamService.streamConsultation(any(ChatStreamRequest.class), nullable(MedPrincipal.class)))
                .thenThrow(new BizException(ErrorCode.NOT_FOUND, "session does not exist"));

        MvcResult result = performStream(BODY);

        mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(result))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("40400")));
    }

    @Test
    @DisplayName("a missing chat model is answered with 503 and a parseable error event, not a JSON body")
    void missingModelIsAnsweredWithServiceUnavailable() throws Exception {
        when(chatStreamService.streamConsultation(any(ChatStreamRequest.class), nullable(MedPrincipal.class)))
                .thenThrow(new BizException(ErrorCode.LLM_SERVICE_ERROR, "no model configured"));

        MvcResult result = performStream(BODY);

        mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(result))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(containsString("event:error")))
                .andExpect(content().string(containsString("50201")));
    }

    @Test
    @DisplayName("a caller-side scope error is answered with 400 rather than an unrenderable JSON body")
    void callerScopeErrorIsAnsweredWithBadRequest() throws Exception {
        when(chatStreamService.streamConsultation(any(ChatStreamRequest.class), nullable(MedPrincipal.class)))
                .thenThrow(new IllegalArgumentException("department scope cannot exclude shared"));

        MvcResult result = performStream(BODY);

        mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(result))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("40000")));
    }

    @Test
    @DisplayName("flushes a stream failure as an SSE error event")
    void streamsError() throws Exception {
        when(chatStreamService.streamConsultation(any(ChatStreamRequest.class), nullable(MedPrincipal.class)))
                .thenReturn(Flux.error(new RuntimeException("model down")));

        MvcResult result = performStream(BODY);

        mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:error")));
    }
}
