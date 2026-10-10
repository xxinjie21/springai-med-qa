package com.med.qa.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.common.result.ApiResult;
import com.med.qa.common.result.PageResult;
import com.med.qa.controller.dto.CreateSessionRequest;
import com.med.qa.controller.dto.SessionResponse;
import com.med.qa.controller.dto.TranscriptResponse;
import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.enums.RoleType;
import com.med.qa.domain.enums.SessionStatus;
import com.med.qa.service.MedChatSessionService;
import com.med.qa.service.MedTranscriptService;
import com.med.qa.service.SessionArchiveChecksum;
import com.med.qa.service.SessionPageQuery;
import com.med.qa.service.SessionTranscript;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests of the consultation session REST controller.
 *
 * <p>The {@link MedChatSessionService} is mocked, so the controller is exercised in isolation: request
 * parsing, boundary validation, scope assembly and response shaping. No middleware is contacted.</p>
 */
class SessionControllerTest {

    private MedChatSessionService sessionService;

    private MedTranscriptService transcriptService;

    private SessionController controller;

    @BeforeEach
    void setUp() {
        sessionService = mock(MedChatSessionService.class);
        transcriptService = mock(MedTranscriptService.class);
        controller = new SessionController(sessionService, transcriptService);
    }

    private static ChatSessionDO session(String id, SessionStatus status) {
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId(id);
        session.setTenantId("hosp-1");
        session.setDeptId("cardiology");
        session.setPatientId("pat-77");
        session.setTitle("chest pain");
        session.setStatus(status);
        session.setCreatedAt(1_700_000_000_000L);
        session.setUpdatedAt(1_700_000_000_000L);
        return session;
    }

    @Nested
    @DisplayName("session creation")
    class Creation {

        @Test
        @DisplayName("opens a session and echoes it as a response")
        void createsSession() {
            when(sessionService.createSession("hosp-1", "cardiology", "pat-77", "chest pain"))
                    .thenReturn(session("sess-1", SessionStatus.ACTIVE));

            ApiResult<SessionResponse> result = controller.create(
                    new CreateSessionRequest("hosp-1", "cardiology", "pat-77", "chest pain"));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getData().sessionId()).isEqualTo("sess-1");
            assertThat(result.getData().status()).isEqualTo(SessionStatus.ACTIVE);
            verify(sessionService).createSession("hosp-1", "cardiology", "pat-77", "chest pain");
        }

        @Test
        @DisplayName("rejects a null request")
        void rejectsNullRequest() {
            assertThatThrownBy(() -> controller.create(null))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }

        @Test
        @DisplayName("rejects a request with a blank tenant")
        void rejectsBlankTenant() {
            assertThatThrownBy(() -> controller.create(
                    new CreateSessionRequest("", "cardiology", "pat-77", null)))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("session retrieval")
    class Retrieval {

        @Test
        @DisplayName("returns the requested session")
        void getsSession() {
            when(sessionService.getSession("hosp-1", "cardiology", "sess-1"))
                    .thenReturn(session("sess-1", SessionStatus.ACTIVE));

            ApiResult<SessionResponse> result = controller.get("sess-1", "hosp-1", "cardiology");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getData().sessionId()).isEqualTo("sess-1");
        }

        @Test
        @DisplayName("rejects a blank department scope")
        void rejectsBlankScope() {
            assertThatThrownBy(() -> controller.get("sess-1", "hosp-1", "  "))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("transcript reading")
    class Transcript {

        @Test
        @DisplayName("returns the transcript with the tier it was read from")
        void readsTranscript() {
            ChatMessageDO stored = ChatMessageDO.builder()
                    .messageId("m-1")
                    .sessionId("sess-1")
                    .tenantId("hosp-1")
                    .deptId("cardiology")
                    .patientId("pat-77")
                    .role(RoleType.PATIENT)
                    .content("chest pain")
                    .createdAt(1_700_000_000_000L)
                    .build();
            when(transcriptService.read("hosp-1", "cardiology", "sess-1"))
                    .thenReturn(new SessionTranscript("sess-1", SessionStatus.ARCHIVED,
                            SessionTranscript.Source.COLD,
                            SessionArchiveChecksum.EMPTY_TRANSCRIPT, List.of(stored)));

            ApiResult<TranscriptResponse> result =
                    controller.transcript("sess-1", "hosp-1", "cardiology");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getData().sessionId()).isEqualTo("sess-1");
            assertThat(result.getData().source()).isEqualTo(SessionTranscript.Source.COLD);
            assertThat(result.getData().messageCount()).isEqualTo(1);
            assertThat(result.getData().messages().get(0).content()).isEqualTo("chest pain");
            verify(transcriptService).read("hosp-1", "cardiology", "sess-1");
        }

        @Test
        @DisplayName("rejects a blank department scope before reading anything")
        void rejectsBlankScope() {
            assertThatThrownBy(() -> controller.transcript("sess-1", "hosp-1", "  "))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
            verifyNoInteractions(transcriptService);
        }
    }

    @Nested
    @DisplayName("lifecycle transitions")
    class Transitions {

        @Test
        @DisplayName("closes a session and reports the CLOSED status")
        void closesSession() {
            when(sessionService.closeSession("hosp-1", "cardiology", "sess-1"))
                    .thenReturn(session("sess-1", SessionStatus.CLOSED));

            ApiResult<SessionResponse> result =
                    controller.close("sess-1", "hosp-1", "cardiology");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getData().status()).isEqualTo(SessionStatus.CLOSED);
        }

        @Test
        @DisplayName("archives a session and evicts its window")
        void archivesSession() {
            when(sessionService.archiveSession("hosp-1", "cardiology", "sess-1"))
                    .thenReturn(session("sess-1", SessionStatus.ARCHIVED));

            ApiResult<SessionResponse> result =
                    controller.archive("sess-1", "hosp-1", "cardiology");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getData().status()).isEqualTo(SessionStatus.ARCHIVED);
            verify(sessionService).archiveSession("hosp-1", "cardiology", "sess-1");
        }
    }

    @Nested
    @DisplayName("session listing")
    class Listing {

        @Test
        @DisplayName("returns a page of sessions shaped as SessionResponse")
        void listsSessions() {
            ChatSessionDO stored = session("sess-1", SessionStatus.ACTIVE);
            when(sessionService.pageSessions(any(SessionPageQuery.class)))
                    .thenReturn(PageResult.of(1, 20, 1L, List.of(stored)));

            ApiResult<PageResult<SessionResponse>> result = controller.list(
                    "hosp-1", "cardiology", null, null, 1, null);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getData().total()).isEqualTo(1);
            assertThat(result.getData().records()).hasSize(1);
            assertThat(result.getData().records().get(0).sessionId()).isEqualTo("sess-1");
        }

        @Test
        @DisplayName("forwards the patient, status and size filters to the service")
        void forwardsFilters() {
            when(sessionService.pageSessions(any(SessionPageQuery.class)))
                    .thenReturn(PageResult.of(1, 5, 0L, List.of()));
            ArgumentCaptor<SessionPageQuery> captor = forClass(SessionPageQuery.class);

            controller.list("hosp-1", "cardiology", "pat-77", SessionStatus.CLOSED, 2, 5);

            verify(sessionService).pageSessions(captor.capture());
            SessionPageQuery query = captor.getValue();
            assertThat(query.tenantId()).isEqualTo("hosp-1");
            assertThat(query.deptId()).isEqualTo("cardiology");
            assertThat(query.patientId()).isEqualTo("pat-77");
            assertThat(query.status()).isEqualTo(SessionStatus.CLOSED);
            assertThat(query.page()).isEqualTo(2);
            assertThat(query.size()).isEqualTo(5);
        }

        @Test
        @DisplayName("rejects a listing without a tenant")
        void rejectsMissingTenant() {
            assertThatThrownBy(() -> controller.list("", "cardiology", null, null, 1, null))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }
    }
}
