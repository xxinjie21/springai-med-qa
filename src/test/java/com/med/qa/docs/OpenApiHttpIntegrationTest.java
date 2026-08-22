package com.med.qa.docs;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end check that SpringDoc actually serves the OpenAPI document and the three controller groups.
 *
 * <p>API-key authentication and rate limiting are switched off so the documentation endpoints can be
 * exercised without credentials or Redis; this mirrors the offline test posture used elsewhere in the
 * suite. The full application context is booted so the controllers, DTOs and {@link
 * com.med.qa.config.OpenApiConfig} are all exercised together.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "med.security.enabled=false",
        "med.rate-limit.enabled=false",
        "spring.flyway.enabled=false"
})
class OpenApiHttpIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void servesDefaultOpenApiDocument() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").exists())
                .andExpect(jsonPath("$.components.securitySchemes.apiKeyAuth").exists());
    }

    @Test
    void servesChatGroupDocument() throws Exception {
        mockMvc.perform(get("/v3/api-docs/chat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").exists());
    }

    @Test
    void servesSessionAndRagGroupDocuments() throws Exception {
        mockMvc.perform(get("/v3/api-docs/session")).andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs/rag")).andExpect(status().isOk());
    }
}
