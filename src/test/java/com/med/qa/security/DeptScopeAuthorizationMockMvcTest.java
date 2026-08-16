package com.med.qa.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.med.qa.common.exception.GlobalExceptionHandler;
import com.med.qa.config.MedSecurityProperties;
import com.med.qa.config.WebMvcConfig;
import com.med.qa.controller.SessionController;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.enums.SessionStatus;
import com.med.qa.service.MedChatSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * End-to-end authorization tests of the {@code @RequireDept} chain over real HTTP semantics.
 *
 * <p>A standalone MockMvc is assembled from the annotated {@link SessionController}, the
 * {@link DeptScopeInterceptor} registered exactly as {@link WebMvcConfig} does it, and the global
 * exception advice. That makes the department rule observable the way a caller sees it — an HTTP
 * {@code 403} with the unified error envelope — while the session service stays mocked, so no MySQL, Redis
 * or LLM is involved.</p>
 */
class DeptScopeAuthorizationMockMvcTest {

    private static final String OWN_DEPT = "cardiology";

    private static final String OTHER_DEPT = "neurology";

    private MedChatSessionService sessionService;

    private MedSecurityProperties properties;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        sessionService = mock(MedChatSessionService.class);
        properties = new MedSecurityProperties();
        DeptScopeInterceptor interceptor = new DeptScopeInterceptor(
                properties, new DeptScopeGuard(), new DeptIdResolver());
        mockMvc = MockMvcBuilders.standaloneSetup(new SessionController(sessionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(interceptor)
                .build();
    }

    @AfterEach
    void tearDown() {
        MedSecurityContext.clear();
    }

    private static ChatSessionDO session() {
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId("s-1");
        session.setTenantId("hosp-1");
        session.setDeptId(OWN_DEPT);
        session.setPatientId("pat-77");
        session.setTitle("chest pain");
        session.setStatus(SessionStatus.ACTIVE);
        session.setCreatedAt(1_700_000_000_000L);
        session.setUpdatedAt(1_700_000_000_000L);
        return session;
    }

    private static void authenticateStaff() {
        MedSecurityContext.setPrincipal(new MedPrincipal("hosp-1", OWN_DEPT, MedRole.STAFF, null));
    }

    private static void authenticatePatient() {
        MedSecurityContext.setPrincipal(new MedPrincipal("hosp-1", OWN_DEPT, MedRole.PATIENT, "pat-77"));
    }

    @Nested
    @DisplayName("query-scoped endpoints")
    class QueryScoped {

        @Test
        @DisplayName("staff of the department gets 200 and reaches the service")
        void ownDepartmentIsAllowed() throws Exception {
            authenticateStaff();
            when(sessionService.getSession("hosp-1", OWN_DEPT, "s-1")).thenReturn(session());

            MvcResult result = mockMvc.perform(get("/api/sessions/{sessionId}", "s-1")
                            .accept(MediaType.APPLICATION_JSON)
                            .param("tenantId", "hosp-1")
                            .param("deptId", OWN_DEPT))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(result.getResponse().getContentAsString()).contains("\"code\":0");
        }

        @Test
        @DisplayName("another department gets 403 and never reaches the service")
        void otherDepartmentIsForbidden() throws Exception {
            authenticateStaff();

            MvcResult result = mockMvc.perform(get("/api/sessions/{sessionId}", "s-1")
                            .param("tenantId", "hosp-1")
                            .param("deptId", OTHER_DEPT))
                    .andExpect(status().isForbidden())
                    .andReturn();

            assertThat(result.getResponse().getContentAsString())
                    .contains("\"code\":40300")
                    .contains("department mismatch");
            verifyNoInteractions(sessionService);
        }

        @Test
        @DisplayName("an anonymous listing request gets 403")
        void anonymousListingIsForbidden() throws Exception {
            mockMvc.perform(get("/api/sessions")
                            .param("tenantId", "hosp-1")
                            .param("deptId", OWN_DEPT))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(sessionService);
        }

        @Test
        @DisplayName("closing a session of another department gets 403")
        void closeOtherDepartmentIsForbidden() throws Exception {
            authenticatePatient();

            mockMvc.perform(post("/api/sessions/{sessionId}/close", "s-1")
                            .param("tenantId", "hosp-1")
                            .param("deptId", OTHER_DEPT))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(sessionService);
        }

        @Test
        @DisplayName("archiving inside the own department is allowed")
        void archiveOwnDepartmentIsAllowed() throws Exception {
            authenticateStaff();
            ChatSessionDO archived = session();
            archived.setStatus(SessionStatus.ARCHIVED);
            when(sessionService.archiveSession("hosp-1", OWN_DEPT, "s-1")).thenReturn(archived);

            mockMvc.perform(post("/api/sessions/{sessionId}/archive", "s-1")
                            .param("tenantId", "hosp-1")
                            .param("deptId", OWN_DEPT))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("body-scoped creation")
    class BodyScoped {

        private static final String BODY = """
                {"tenantId":"hosp-1","deptId":"cardiology","patientId":"pat-77","title":"chest pain"}
                """;

        @Test
        @DisplayName("an authenticated creation passes: the body scope is authorized by the service layer")
        void authenticatedCreationIsAllowed() throws Exception {
            authenticatePatient();
            when(sessionService.createSession(anyString(), anyString(), anyString(), any()))
                    .thenReturn(session());

            mockMvc.perform(post("/api/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("an anonymous creation is refused with 403 before the body is even read")
        void anonymousCreationIsForbidden() throws Exception {
            MvcResult result = mockMvc.perform(post("/api/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isForbidden())
                    .andReturn();

            assertThat(result.getResponse().getContentAsString()).contains("authentication required");
            verifyNoInteractions(sessionService);
        }
    }

    @Nested
    @DisplayName("toggles")
    class Toggles {

        @Test
        @DisplayName("disabling department scoping lets an anonymous request through")
        void deptScopeDisabled() throws Exception {
            properties.setDeptScopeEnabled(false);
            when(sessionService.getSession("hosp-1", OTHER_DEPT, "s-1")).thenReturn(session());

            mockMvc.perform(get("/api/sessions/{sessionId}", "s-1")
                            .param("tenantId", "hosp-1")
                            .param("deptId", OTHER_DEPT))
                    .andExpect(status().isOk());
        }
    }
}
