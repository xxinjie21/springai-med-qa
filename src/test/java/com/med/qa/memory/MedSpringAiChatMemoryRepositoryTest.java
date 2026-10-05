package com.med.qa.memory;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.enums.RoleType;
import com.med.qa.mapper.ChatSessionMapper;
import com.med.qa.memory.lock.SessionLockService;
import com.med.qa.memory.repository.MedChatMemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MedSpringAiChatMemoryRepositoryTest {

    private static final String CONVERSATION_ID = "tenant-1:dept-2:session-3";

    @Mock
    private MedChatMemoryRepository inner;

    @Mock
    private SessionLockService sessionLockService;

    @Mock
    private ChatSessionMapper sessionMapper;

    private MedSpringAiChatMemoryRepository repository;

    @BeforeEach
    void setUp() {
        repository = new MedSpringAiChatMemoryRepository(inner, sessionLockService, sessionMapper);
        // The lock itself is covered by SessionLockServiceTest; here it only has to run the action so
        // the bridge's own behaviour is observable. lenient() because the rejection cases never reach
        // the lock at all.
        lenient().doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(3)).run();
            return null;
        }).when(sessionLockService).runLocked(any(), any(), any(), any());
        // The session is the only source of the patient a framework message belongs to (D44).
        lenient().when(sessionMapper.selectById("session-3")).thenReturn(session("session-3", "pat-1"));
    }

    private static ChatSessionDO session(String sessionId, String patientId) {
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId(sessionId);
        session.setTenantId("tenant-1");
        session.setDeptId("dept-2");
        session.setPatientId(patientId);
        session.setTitle("consultation");
        return session;
    }

    @Test
    @DisplayName("findByConversationId maps stored DOs to typed messages and carries bookkeeping metadata")
    void findByConversationIdMapsRolesAndMetadata() {
        when(inner.findAll("tenant-1", "dept-2", "session-3")).thenReturn(List.of(
                patient("m-1", "patient says hi"),
                assistant("m-2", "assistant replies"),
                system("m-3", "system prompt")));

        List<Message> messages = repository.findByConversationId(CONVERSATION_ID);

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(messages.get(2)).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(1).getText()).isEqualTo("assistant replies");
        assertThat(messages.get(1).getMetadata())
                .containsEntry(MedSpringAiChatMemoryRepository.MED_MESSAGE_ID, "m-2")
                .containsEntry(MedSpringAiChatMemoryRepository.MED_ROLE_CODE, 2);
    }

    @Test
    @DisplayName("findByConversationId decodes the conversation id into the storage coordinates")
    void findByConversationIdDecodesCoordinates() {
        repository.findByConversationId(CONVERSATION_ID);

        verify(inner).findAll("tenant-1", "dept-2", "session-3");
    }

    @Test
    @DisplayName("saveAll merges the window into the transcript and never deletes the session (D44)")
    void saveAllMergesWithoutDeleting() {
        Message existing = UserMessage.builder()
                .text("kept")
                .metadata(Map.of(MedSpringAiChatMemoryRepository.MED_MESSAGE_ID, "m-kept"))
                .build();
        Message fresh = new UserMessage("new");

        repository.saveAll(CONVERSATION_ID, List.of(existing, fresh));

        ArgumentCaptor<List<ChatMessageDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(inner).saveWindow(eq("tenant-1"), eq("dept-2"), eq("session-3"), captor.capture());
        verify(inner, never()).deleteSession(any(), any(), any());
        verify(inner, never()).appendAll(any());
        List<ChatMessageDO> saved = captor.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getMessageId()).isEqualTo("m-kept");
        assertThat(saved.get(1).getMessageId()).isNotBlank().isNotEqualTo("m-kept");
        assertThat(saved.get(0).getRole()).isEqualTo(RoleType.PATIENT);
    }

    @Test
    @DisplayName("saveAll runs the window write under the session lock")
    void saveAllRunsUnderTheSessionLock() {
        repository.saveAll(CONVERSATION_ID, List.of(new UserMessage("hello")));

        verify(sessionLockService).runLocked(eq("tenant-1"), eq("dept-2"), eq("session-3"), any());
    }

    @Test
    @DisplayName("a session busy elsewhere is refused and the transcript is left untouched")
    void saveAllPropagatesLockConflict() {
        doThrow(new BizException(ErrorCode.SESSION_LOCKED, "busy"))
                .when(sessionLockService).runLocked(any(), any(), any(), any());

        assertThatThrownBy(() -> repository.saveAll(CONVERSATION_ID, List.of(new UserMessage("hi"))))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SESSION_LOCKED);
        verifyNoInteractions(inner);
    }

    @Test
    @DisplayName("D44: a framework message that names no patient inherits the session's patient")
    void saveAllAttributesMessagesToTheSessionPatient() {
        repository.saveAll(CONVERSATION_ID, List.of(new UserMessage("no patient in metadata")));

        ArgumentCaptor<List<ChatMessageDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(inner).saveWindow(any(), any(), any(), captor.capture());
        assertThat(captor.getValue()).extracting(ChatMessageDO::getPatientId).containsExactly("pat-1");
    }

    @Test
    @DisplayName("D44: a message that names its own patient keeps it")
    void saveAllKeepsAnExplicitPatient() {
        Message carrying = UserMessage.builder()
                .text("read back from storage")
                .metadata(Map.of(MedSpringAiChatMemoryRepository.MED_PATIENT_ID, "pat-own"))
                .build();

        repository.saveAll(CONVERSATION_ID, List.of(carrying));

        ArgumentCaptor<List<ChatMessageDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(inner).saveWindow(any(), any(), any(), captor.capture());
        assertThat(captor.getValue()).extracting(ChatMessageDO::getPatientId).containsExactly("pat-own");
    }

    @Test
    @DisplayName("D44: an unknown session refuses the write instead of storing an unattributable message")
    void saveAllRefusesWhenTheSessionIsUnknown() {
        when(sessionMapper.selectById("session-3")).thenReturn(null);

        assertThatThrownBy(() -> repository.saveAll(CONVERSATION_ID, List.of(new UserMessage("hi"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("does not exist");
        verifyNoInteractions(inner);
    }

    @Test
    @DisplayName("D44: a session without a patient refuses the write")
    void saveAllRefusesWhenTheSessionNamesNoPatient() {
        when(sessionMapper.selectById("session-3")).thenReturn(session("session-3", null));

        assertThatThrownBy(() -> repository.saveAll(CONVERSATION_ID, List.of(new UserMessage("hi"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("names no patient");
        verifyNoInteractions(inner);
    }

    @Test
    @DisplayName("role is inferred from the Spring AI message type when no stored code is present")
    void saveAllInfersRoleFromMessageType() {
        repository.saveAll(CONVERSATION_ID, List.of(
                new AssistantMessage("a", Map.of()),
                SystemMessage.builder().text("s").build()));

        ArgumentCaptor<List<ChatMessageDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(inner).saveWindow(any(), any(), any(), captor.capture());
        assertThat(captor.getValue().get(0).getRole()).isEqualTo(RoleType.ASSISTANT);
        assertThat(captor.getValue().get(1).getRole()).isEqualTo(RoleType.SYSTEM);
    }

    @Test
    @DisplayName("deleteByConversationId delegates to the two-tier repository")
    void deleteByConversationIdDelegates() {
        repository.deleteByConversationId(CONVERSATION_ID);

        verify(inner).deleteSession("tenant-1", "dept-2", "session-3");
    }

    @Test
    @DisplayName("findConversationIds is not enumerated and returns empty")
    void findConversationIdsEmpty() {
        assertThat(repository.findConversationIds()).isEmpty();
        verify(inner, never()).findAll(any(), any(), any());
    }

    @Test
    @DisplayName("saveAll with null messages is rejected as a caller error")
    void saveAllRejectsNullMessages() {
        assertThatThrownBy(() -> repository.saveAll(CONVERSATION_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messages");
    }

    @Test
    @DisplayName("malformed conversation id is rejected")
    void rejectsMalformedConversationId() {
        assertThatThrownBy(() -> repository.saveAll("tenant:dept", List.of(new UserMessage("x"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant:dept:sessionId");
        verifyNoInteractions(inner);
    }

    private static ChatMessageDO patient(String id, String content) {
        return ChatMessageDO.builder()
                .messageId(id).sessionId("session-3").tenantId("tenant-1").deptId("dept-2")
                .role(RoleType.PATIENT).content(content).createdAt(1000L).build();
    }

    private static ChatMessageDO assistant(String id, String content) {
        return ChatMessageDO.builder()
                .messageId(id).sessionId("session-3").tenantId("tenant-1").deptId("dept-2")
                .role(RoleType.ASSISTANT).content(content).createdAt(2000L).build();
    }

    private static ChatMessageDO system(String id, String content) {
        return ChatMessageDO.builder()
                .messageId(id).sessionId("session-3").tenantId("tenant-1").deptId("dept-2")
                .role(RoleType.SYSTEM).content(content).createdAt(3000L).build();
    }
}
