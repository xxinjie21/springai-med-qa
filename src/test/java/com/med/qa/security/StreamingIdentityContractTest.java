package com.med.qa.security;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.enums.SessionStatus;
import com.med.qa.service.MedChatSessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Cross-component contract tests of the streaming consultation identity chain (D41).
 *
 * <h2>Why this class exists</h2>
 * <p>The 2026-09-25 review found that 1389 green tests had missed three P0 defects and diagnosed the
 * cause precisely: every test exercised <em>one component</em>, so nothing asserted that the components
 * were actually wired to each other. P0-2 is the clearest example — {@link PatientAccessGuard} and
 * {@link DeptScopeGuard} were both correct and both well tested, yet the streaming path simply never
 * called them, and no test could notice because no test crossed the boundary.</p>
 *
 * <p>This class therefore wires the <strong>real</strong> production chain and asserts the contract
 * between its links rather than any single link's behaviour:</p>
 * <ol>
 *   <li>{@link ApiKeyAuthFilter} resolves the {@code X-API-Key} header and publishes a
 *       {@link MedPrincipal};</li>
 *   <li>the controller hands that principal to the real
 *       {@link com.med.qa.service.ChatStreamService};</li>
 *   <li>{@link RequestIdentityGuard} and {@link PatientAccessGuard} decide whether the turn may run;</li>
 *   <li>only then is the session consulted — and the coordinates it is consulted with are the
 *       principal's, never the body's.</li>
 * </ol>
 *
 * <p>Only {@link MedChatSessionService} is mocked: it is the MySQL-backed link, irrelevant to the
 * contract under test, and mocking it is what lets the assertions observe <em>which</em> coordinates
 * reached it. Every case is arranged to stop at or before that link, so no chat model is ever built and
 * the test stays deterministic and offline.</p>
 *
 * <p>{@code med.alert.enabled=false} is set for the same reason the laziness context guards set it: the
 * scheduled index monitor would otherwise fire on a long-lived cached context and instantiate the lazily
 * wired Jedis client.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "med.rate-limit.enabled=false",
        "med.alert.enabled=false",
        "med.security.enabled=true",
        "med.security.require-auth=true",
        "med.security.keys.staffkey.tenant-id=hosp-1",
        "med.security.keys.staffkey.dept-id=dept-cardio",
        "med.security.keys.staffkey.role=STAFF",
        "med.security.keys.patientkey.tenant-id=hosp-1",
        "med.security.keys.patientkey.dept-id=dept-cardio",
        "med.security.keys.patientkey.role=PATIENT",
        "med.security.keys.patientkey.patient-id=pat-77"
})
class StreamingIdentityContractTest {

    private static final String STAFF_KEY = "staffkey";

    private static final String PATIENT_KEY = "patientkey";

    private static final String SESSION = "sess-1";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MedChatSessionService sessionService;

    private static ChatSessionDO writableSession() {
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId(SESSION);
        session.setTenantId("hosp-1");
        session.setDeptId("dept-cardio");
        session.setPatientId("pat-77");
        session.setStatus(SessionStatus.ACTIVE);
        session.setCreatedAt(1_700_000_000_000L);
        session.setUpdatedAt(1_700_000_000_000L);
        return session;
    }

    private MvcResult stream(String apiKey, String body) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post("/api/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .accept(MediaType.TEXT_EVENT_STREAM);
        if (apiKey != null) {
            request = request.header("X-API-Key", apiKey);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult dispatch(MvcResult result) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(result)).andReturn();
    }

    private static void assertStatus(MvcResult result, int expected) {
        assertThat(result.getResponse().getStatus()).isEqualTo(expected);
    }

    @Nested
    @DisplayName("the API key is the only source of identity")
    class PrincipalIsTheAuthority {

        @Test
        @DisplayName("a body claiming another tenant is refused and never reaches the session store")
        void foreignTenantIsRefused() throws Exception {
            MvcResult result = dispatch(stream(STAFF_KEY,
                    "{\"tenant\":\"hosp-2\",\"dept\":\"dept-cardio\",\"session\":\"sess-1\",\"message\":\"hi\"}"));

            assertStatus(result, 403);
            assertThat(result.getResponse().getContentAsString()).contains("40300");
            verifyNoInteractions(sessionService);
        }

        @Test
        @DisplayName("a body claiming another department is refused")
        void foreignDepartmentIsRefused() throws Exception {
            MvcResult result = dispatch(stream(STAFF_KEY,
                    "{\"tenant\":\"hosp-1\",\"dept\":\"dept-onco\",\"session\":\"sess-1\",\"message\":\"hi\"}"));

            assertStatus(result, 403);
            assertThat(result.getResponse().getContentAsString()).contains("40300");
            verifyNoInteractions(sessionService);
        }

        @Test
        @DisplayName("a patient key claiming another patient is refused")
        void patientClaimingAnotherPatientIsRefused() throws Exception {
            MvcResult result = dispatch(stream(PATIENT_KEY,
                    "{\"session\":\"sess-1\",\"patientId\":\"pat-99\",\"message\":\"hi\"}"));

            assertStatus(result, 403);
            verifyNoInteractions(sessionService);
        }

        @Test
        @DisplayName("a refusal wins even when the session itself would have been writable")
        void refusalWinsOverAWritableSession() throws Exception {
            MvcResult result = dispatch(stream(STAFF_KEY,
                    "{\"tenant\":\"hosp-2\",\"session\":\"sess-1\",\"message\":\"hi\"}"));

            assertStatus(result, 403);
            verify(sessionService, never()).requireWritableSession("hosp-1", "dept-cardio", SESSION);
        }

        @Test
        @DisplayName("a refusal is a single parseable error event, not a half-open connection")
        void refusalIsAParseableEvent() throws Exception {
            MvcResult result = dispatch(stream(STAFF_KEY,
                    "{\"dept\":\"dept-onco\",\"session\":\"sess-1\",\"message\":\"hi\"}"));

            assertThat(result.getResponse().getContentAsString())
                    .contains("event:error")
                    .contains("40300");
        }

        @Test
        @DisplayName("an unknown key is rejected by the authentication filter before the controller runs")
        void unknownKeyIsUnauthorized() throws Exception {
            MvcResult result = stream("not-a-key", "{\"session\":\"sess-1\",\"message\":\"hi\"}");

            assertStatus(result, 401);
            verifyNoInteractions(sessionService);
        }

        @Test
        @DisplayName("a missing key is rejected by the authentication filter")
        void missingKeyIsUnauthorized() throws Exception {
            MvcResult result = stream(null, "{\"session\":\"sess-1\",\"message\":\"hi\"}");

            assertStatus(result, 401);
            verifyNoInteractions(sessionService);
        }
    }

    @Nested
    @DisplayName("a request that claims nothing runs in the principal's scope")
    class ClaimsAreOptional {

        @Test
        @DisplayName("the session is looked up under the principal's tenant and department")
        void identityComesFromThePrincipal() throws Exception {
            when(sessionService.requireWritableSession("hosp-1", "dept-cardio", SESSION))
                    .thenThrow(new BizException(ErrorCode.NOT_FOUND, "session does not exist"));

            // No tenant, no dept, no patientId: the body carries only the session and the question, so the
            // coordinates the session service sees can only have come from the authenticated principal.
            MvcResult result = dispatch(stream(STAFF_KEY, "{\"session\":\"sess-1\",\"message\":\"hi\"}"));

            verify(sessionService).requireWritableSession("hosp-1", "dept-cardio", SESSION);
            assertStatus(result, 404);
        }

        @Test
        @DisplayName("a patient key is resolved to that patient without the body naming one")
        void patientIdentityComesFromThePrincipal() throws Exception {
            when(sessionService.requireWritableSession("hosp-1", "dept-cardio", SESSION))
                    .thenThrow(new BizException(ErrorCode.NOT_FOUND, "session does not exist"));

            MvcResult result = dispatch(stream(PATIENT_KEY, "{\"session\":\"sess-1\",\"message\":\"hi\"}"));

            verify(sessionService).requireWritableSession("hosp-1", "dept-cardio", SESSION);
            assertStatus(result, 404);
        }

        @Test
        @DisplayName("a session that is not writable stops the turn with 400 before any model is built")
        void nonWritableSessionStopsTheTurn() throws Exception {
            when(sessionService.requireWritableSession("hosp-1", "dept-cardio", SESSION))
                    .thenThrow(new BizException(ErrorCode.BAD_REQUEST, "session is CLOSED"));

            MvcResult result = dispatch(stream(STAFF_KEY, "{\"session\":\"sess-1\",\"message\":\"hi\"}"));

            assertStatus(result, 400);
            assertThat(result.getResponse().getContentAsString()).contains("40000");
        }

        @Test
        @DisplayName("an unknown session is answered with 404, not with a stream")
        void unknownSessionIsNotFound() throws Exception {
            when(sessionService.requireWritableSession("hosp-1", "dept-cardio", SESSION))
                    .thenThrow(new BizException(ErrorCode.NOT_FOUND, "session does not exist"));

            MvcResult result = dispatch(stream(STAFF_KEY, "{\"session\":\"sess-1\",\"message\":\"hi\"}"));

            assertStatus(result, 404);
            assertThat(result.getResponse().getContentAsString()).contains("40400");
        }

        @Test
        @DisplayName("a request without a session is refused as a bad request before any lookup")
        void missingSessionIsRefused() throws Exception {
            MvcResult result = dispatch(stream(STAFF_KEY, "{\"message\":\"hi\"}"));

            assertStatus(result, 400);
            verifyNoInteractions(sessionService);
        }

        @Test
        @DisplayName("a fully authorized turn with no model configured is answered with 503, not a raw 500")
        void missingModelIsAnsweredCleanly() throws Exception {
            when(sessionService.requireWritableSession("hosp-1", "dept-cardio", SESSION))
                    .thenReturn(writableSession());

            MvcResult result = dispatch(stream(STAFF_KEY, "{\"session\":\"sess-1\",\"message\":\"hi\"}"));

            // This is the only offline test that drives the real ObjectProvider: with no ChatModel bean the
            // prototype builder cannot be created, and an ObjectProvider does not swallow that failure.
            // Before D41 it escaped as a bean-creation stack trace and a 500.
            assertStatus(result, 503);
            assertThat(result.getResponse().getContentAsString()).contains("50201");
        }
    }
}
