package com.med.qa.service;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.controller.dto.ChatStreamRequest;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.enums.SessionStatus;
import com.med.qa.rag.MedDocumentScope;
import com.med.qa.rag.MedRagAdvisorFactory;
import com.med.qa.rag.MedRetrievalQuery;
import com.med.qa.security.MedPrincipal;
import com.med.qa.security.MedRole;
import com.med.qa.security.PatientAccessGuard;
import com.med.qa.security.RequestIdentityGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the streaming consultation orchestration (D41).
 *
 * <p>The official {@link ChatClient} chain, the RAG advisor factory and the session service are mocked,
 * so the test exercises only what this class is responsible for: reconciling the authenticated principal
 * with the identity the request body claims, running the two authorization checks <em>before</em> the
 * model is consulted, and assembling the consultation prompt from the principal-derived scope. No model,
 * embedding endpoint, MySQL, Redis or vector store is contacted.</p>
 *
 * <p>{@link RequestIdentityGuard} and {@link PatientAccessGuard} are real instances on purpose: they are
 * pure and IO-free, and using the production ones is what makes these assertions about the actual
 * authorization policy rather than about a stub's behaviour.</p>
 */
class ChatStreamServiceTest {

    private static final String TENANT = "hosp-1";

    private static final String DEPT = "dept-cardio";

    private static final String SESSION = "sess-1";

    private ObjectProvider<ChatClient.Builder> builderProvider;

    private ChatClient.Builder builder;

    private ChatClient chatClient;

    private ChatClient.ChatClientRequestSpec requestSpec;

    private ChatClient.StreamResponseSpec streamSpec;

    private MedRagAdvisorFactory ragAdvisorFactory;

    private Advisor ragAdvisor;

    private MedChatSessionService sessionService;

    private ChatStreamService service;

    @BeforeEach
    void setUp() {
        builderProvider = mock(ObjectProvider.class);
        builder = mock(ChatClient.Builder.class);
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        streamSpec = mock(ChatClient.StreamResponseSpec.class);
        ragAdvisorFactory = mock(MedRagAdvisorFactory.class);
        ragAdvisor = mock(Advisor.class);
        sessionService = mock(MedChatSessionService.class);
        service = new ChatStreamService(builderProvider, ragAdvisorFactory,
                new RequestIdentityGuard(), new PatientAccessGuard(), sessionService);
    }

    private static MedPrincipal staff() {
        return new MedPrincipal(TENANT, DEPT, MedRole.STAFF, null);
    }

    private static MedPrincipal patient(String patientId) {
        return new MedPrincipal(TENANT, DEPT, MedRole.PATIENT, patientId);
    }

    private static ChatSessionDO writableSession() {
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId(SESSION);
        session.setTenantId(TENANT);
        session.setDeptId(DEPT);
        session.setPatientId("pat-77");
        session.setStatus(SessionStatus.ACTIVE);
        session.setCreatedAt(1_700_000_000_000L);
        session.setUpdatedAt(1_700_000_000_000L);
        return session;
    }

    private void stubWritableSession() {
        when(sessionService.requireWritableSession(TENANT, DEPT, SESSION)).thenReturn(writableSession());
    }

    private void stubHappyChain() {
        stubWritableSession();
        when(builderProvider.getIfAvailable()).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Advisor.class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.just("Hello", " world"));
        when(ragAdvisorFactory.createAdvisor(any(MedRetrievalQuery.class))).thenReturn(ragAdvisor);
    }

    @Nested
    @DisplayName("streaming assembly")
    class Streaming {

        @Test
        @DisplayName("returns the streamed content and wires the RAG advisor")
        void streamsAndAddsRagAdvisor() {
            stubHappyChain();
            ChatStreamRequest request =
                    new ChatStreamRequest(TENANT, DEPT, SESSION, "pat-77", "what dose", true, null);

            Flux<String> flux = service.streamConsultation(request, staff());

            assertThat(flux.collectList().block()).containsExactly("Hello", " world");
            verify(chatClient).prompt();
            verify(streamSpec).content();
            verify(ragAdvisorFactory).createAdvisor(any(MedRetrievalQuery.class));
            verify(requestSpec).advisors(ragAdvisor);
        }

        @Test
        @DisplayName("a staff turn narrowed to a patient retrieves that patient's own and shared documents")
        void patientScope() {
            stubHappyChain();
            ChatStreamRequest request =
                    new ChatStreamRequest(null, null, SESSION, "pat-77", "what dose", null, null);
            ArgumentCaptor<MedRetrievalQuery> captor = ArgumentCaptor.forClass(MedRetrievalQuery.class);

            service.streamConsultation(request, staff());

            verify(ragAdvisorFactory).createAdvisor(captor.capture());
            assertThat(captor.getValue().getScope().isPatientScoped()).isTrue();
            assertThat(captor.getValue().getScope().getPatientId()).isEqualTo("pat-77");
            assertThat(captor.getValue().isIncludeSharedDocuments()).isTrue();
        }

        @Test
        @DisplayName("a staff turn without a patient falls back to department-wide shared documents")
        void departmentScope() {
            stubHappyChain();
            ChatStreamRequest request =
                    new ChatStreamRequest(TENANT, DEPT, SESSION, null, "guideline", null, null);
            ArgumentCaptor<MedRetrievalQuery> captor = ArgumentCaptor.forClass(MedRetrievalQuery.class);

            service.streamConsultation(request, staff());

            verify(ragAdvisorFactory).createAdvisor(captor.capture());
            assertThat(captor.getValue().getScope().isPatientScoped()).isFalse();
        }

        @Test
        @DisplayName("a patient principal is scoped to itself even when the body names no patient")
        void patientPrincipalDrivesTheScope() {
            stubHappyChain();
            ChatStreamRequest request =
                    new ChatStreamRequest(null, null, SESSION, null, "my results", null, null);
            ArgumentCaptor<MedRetrievalQuery> captor = ArgumentCaptor.forClass(MedRetrievalQuery.class);

            service.streamConsultation(request, patient("pat-77"));

            verify(ragAdvisorFactory).createAdvisor(captor.capture());
            assertThat(captor.getValue().getScope().getPatientId()).isEqualTo("pat-77");
        }
    }

    @Nested
    @DisplayName("identity is taken from the principal, not from the body (D41)")
    class Identity {

        @Test
        @DisplayName("the body cannot redirect the turn to another tenant")
        void refusesForeignTenantInBody() {
            ChatStreamRequest request =
                    new ChatStreamRequest("hosp-2", DEPT, SESSION, null, "hi", null, null);

            assertThatThrownBy(() -> service.streamConsultation(request, staff()))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("tenant mismatch")
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
            verifyNoInteractions(sessionService, ragAdvisorFactory);
            verify(builderProvider, never()).getIfAvailable();
        }

        @Test
        @DisplayName("the body cannot redirect the turn to another department")
        void refusesForeignDepartmentInBody() {
            ChatStreamRequest request =
                    new ChatStreamRequest(TENANT, "dept-onco", SESSION, null, "hi", null, null);

            assertThatThrownBy(() -> service.streamConsultation(request, staff()))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("department mismatch");
            verifyNoInteractions(sessionService, ragAdvisorFactory);
        }

        @Test
        @DisplayName("a patient cannot stream against another patient's session")
        void refusesPatientClaimingAnotherPatient() {
            ChatStreamRequest request =
                    new ChatStreamRequest(TENANT, DEPT, SESSION, "pat-99", "hi", null, null);

            assertThatThrownBy(() -> service.streamConsultation(request, patient("pat-77")))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("patient mismatch");
            verifyNoInteractions(sessionService, ragAdvisorFactory);
        }

        @Test
        @DisplayName("an anonymous request is refused before anything else happens")
        void refusesAnonymousCaller() {
            ChatStreamRequest request =
                    new ChatStreamRequest(TENANT, DEPT, SESSION, null, "hi", null, null);

            assertThatThrownBy(() -> service.streamConsultation(request, null))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("authentication required");
            verifyNoInteractions(sessionService, ragAdvisorFactory);
        }

        @Test
        @DisplayName("the session is looked up under the principal's coordinates, never the body's")
        void usesPrincipalCoordinatesForTheSessionLookup() {
            stubHappyChain();
            // A body that omits the identity entirely: only the principal can supply the scope.
            ChatStreamRequest request =
                    new ChatStreamRequest(null, null, SESSION, null, "hi", null, null);

            service.streamConsultation(request, staff());

            verify(sessionService).requireWritableSession(TENANT, DEPT, SESSION);
        }

        @Test
        @DisplayName("rejects a null request")
        void rejectsNullRequest() {
            assertThatThrownBy(() -> service.streamConsultation(null, staff()))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("authorization runs before the model (D41)")
    class GuardOrder {

        @Test
        @DisplayName("a session that is not writable aborts the turn and no model is ever built")
        void nonWritableSessionAbortsBeforeTheModel() {
            when(sessionService.requireWritableSession(TENANT, DEPT, SESSION))
                    .thenThrow(new BizException(ErrorCode.BAD_REQUEST, "session is CLOSED"));
            ChatStreamRequest request =
                    new ChatStreamRequest(TENANT, DEPT, SESSION, null, "hi", null, null);

            assertThatThrownBy(() -> service.streamConsultation(request, staff()))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
            verify(builderProvider, never()).getIfAvailable();
            verifyNoInteractions(ragAdvisorFactory);
        }

        @Test
        @DisplayName("an unknown session aborts the turn with not-found")
        void unknownSessionAbortsTheTurn() {
            when(sessionService.requireWritableSession(TENANT, DEPT, SESSION))
                    .thenThrow(new BizException(ErrorCode.NOT_FOUND, "session does not exist"));
            ChatStreamRequest request =
                    new ChatStreamRequest(TENANT, DEPT, SESSION, null, "hi", null, null);

            assertThatThrownBy(() -> service.streamConsultation(request, staff()))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.NOT_FOUND);
            verify(builderProvider, never()).getIfAvailable();
        }

        @Test
        @DisplayName("the writability check is asked for the session the caller named")
        void asksTheSessionServiceForTheNamedSession() {
            stubHappyChain();
            ChatStreamRequest request =
                    new ChatStreamRequest(null, null, "sess-42", null, "hi", null, null);

            service.streamConsultation(request, staff());

            verify(sessionService).requireWritableSession(eq(TENANT), eq(DEPT), eq("sess-42"));
        }
    }

    @Nested
    @DisplayName("failure handling")
    class Failures {

        @Test
        @DisplayName("rejects the turn when no chat client is configured")
        void noChatClient() {
            stubWritableSession();
            when(builderProvider.getIfAvailable()).thenReturn(null);
            ChatStreamRequest request =
                    new ChatStreamRequest(TENANT, DEPT, SESSION, null, "q", null, null);

            assertThatThrownBy(() -> service.streamConsultation(request, staff()))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.LLM_SERVICE_ERROR);
        }

        @Test
        @DisplayName("a builder bean that cannot be created is reported as a missing model, not as a raw failure")
        void unusableBuilderBeanIsReportedAsAMissingModel() {
            // The shape a misconfigured deployment really hits: Spring AI declares the builder with a
            // mandatory ChatModel parameter, and an ObjectProvider does not swallow the resulting
            // instantiation failure. Without the catch this escaped as a bean-creation stack trace.
            stubWritableSession();
            when(builderProvider.getIfAvailable())
                    .thenThrow(new org.springframework.beans.factory.NoSuchBeanDefinitionException("ChatModel"));
            ChatStreamRequest request =
                    new ChatStreamRequest(TENANT, DEPT, SESSION, null, "q", null, null);

            assertThatThrownBy(() -> service.streamConsultation(request, staff()))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("chat client not configured")
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.LLM_SERVICE_ERROR);
        }

        @Test
        @DisplayName("forwards an invalid scope combination as a caller error")
        void invalidScope() {
            stubWritableSession();
            when(builderProvider.getIfAvailable()).thenReturn(builder);
            when(builder.build()).thenReturn(chatClient);
            when(chatClient.prompt()).thenReturn(requestSpec);
            when(requestSpec.user(anyString())).thenReturn(requestSpec);
            when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
            when(requestSpec.advisors(any(Advisor.class))).thenReturn(requestSpec);
            when(requestSpec.stream()).thenReturn(streamSpec);
            when(streamSpec.content()).thenReturn(Flux.just("x"));
            when(ragAdvisorFactory.createAdvisor(any(MedRetrievalQuery.class)))
                    .thenThrow(new IllegalArgumentException("department scope cannot exclude shared"));

            ChatStreamRequest request =
                    new ChatStreamRequest(TENANT, DEPT, SESSION, null, "q", false, null);

            assertThatThrownBy(() -> service.streamConsultation(request, staff()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
