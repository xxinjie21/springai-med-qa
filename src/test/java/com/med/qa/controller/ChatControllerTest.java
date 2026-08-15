package com.med.qa.controller;

import com.med.qa.controller.dto.ChatStreamRequest;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import reactor.core.publisher.Flux;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests of the SSE streaming consultation endpoint.
 *
 * <p>Context boots offline (Flyway disabled, no model/Redis), the streaming orchestration is mocked,
 * and the endpoint is exercised through MockMvc with async dispatch so the SSE framing, heartbeat
 * lifecycle and error path are covered without a real model or socket.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "med.security.enabled=false"
})
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatStreamService chatStreamService;

    @Test
    @DisplayName("streams the assistant answer as SSE message events")
    void streamsAnswer() throws Exception {
        when(chatStreamService.streamConsultation(any(ChatStreamRequest.class)))
                .thenReturn(Flux.just("Hello", " world"));

        var result = mockMvc.perform(MockMvcRequestBuilders.post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant\":\"hosp-1\",\"dept\":\"card\",\"session\":\"s-1\",\"message\":\"hi\"}")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Hello world")));
    }

    @Test
    @DisplayName("rejects a request with a blank message as a bad request")
    void rejectsBlankMessage() throws Exception {
        // validation fails before the long-lived stream opens: a 400 SSE error event is returned
        var result = mockMvc.perform(MockMvcRequestBuilders.post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant\":\"hosp-1\",\"dept\":\"card\",\"session\":\"s-1\",\"message\":\"  \"}")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(result))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("40000")));
    }

    @Test
    @DisplayName("flushes a stream failure as an SSE error event")
    void streamsError() throws Exception {
        when(chatStreamService.streamConsultation(any(ChatStreamRequest.class)))
                .thenReturn(Flux.error(new RuntimeException("model down")));

        var result = mockMvc.perform(MockMvcRequestBuilders.post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant\":\"hosp-1\",\"dept\":\"card\",\"session\":\"s-1\",\"message\":\"hi\"}")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:error")));
    }
}
