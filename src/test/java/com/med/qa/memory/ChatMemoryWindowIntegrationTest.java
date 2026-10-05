package com.med.qa.memory;

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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end exercise of the official Spring AI {@link MessageWindowChatMemory} driven by the
 * project's {@link MedSpringAiChatMemoryRepository} bridge. The inner two-tier repository is a
 * Mockito stand-in backed by a plain in-memory list, so no MySQL / Redis is touched.
 *
 * <p>This is the cross-component contract test of the D44 persistence rule: the memory window decides
 * what the model sees, and the durable transcript must keep everything the window has trimmed away.
 * A single-component test cannot see the difference — {@code MedSpringAiChatMemoryRepositoryTest}
 * only observes the messages handed to the repository, and
 * {@code MedChatMemoryRepositoryTest} never runs the window.</p>
 */
@ExtendWith(MockitoExtension.class)
class ChatMemoryWindowIntegrationTest {

    private static final String CONVERSATION_ID = "tenant-1:dept-2:session-3";

    @Mock
    private MedChatMemoryRepository inner;

    @Mock
    private SessionLockService sessionLockService;

    @Mock
    private ChatSessionMapper sessionMapper;

    private final List<ChatMessageDO> store = new ArrayList<>();

    /** Every window the memory handed to the durable store, in order. */
    private final List<List<ChatMessageDO>> writtenWindows = new ArrayList<>();

    private MessageWindowChatMemory window;

    @BeforeEach
    void setUp() {
        lenient().doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(3)).run();
            return null;
        }).when(sessionLockService).runLocked(any(), any(), any(), any());
        lenient().when(sessionMapper.selectById("session-3")).thenReturn(session("session-3", "pat-1"));
        when(inner.findAll(any(), any(), any())).thenAnswer(inv -> new ArrayList<>(store));
        lenient().when(inner.deleteSession(any(), any(), any())).thenAnswer(inv -> {
            int removed = store.size();
            store.clear();
            return removed;
        });
        // The production write primitive: additive and idempotent by message id (D44).
        lenient().when(inner.saveWindow(any(), any(), any(), any())).thenAnswer(inv -> {
            List<ChatMessageDO> offered = inv.getArgument(3);
            writtenWindows.add(new ArrayList<>(offered));
            int inserted = 0;
            for (ChatMessageDO message : offered) {
                boolean stored = store.stream()
                        .anyMatch(existing -> existing.getMessageId().equals(message.getMessageId()));
                if (!stored) {
                    store.add(message);
                    inserted++;
                }
            }
            return inserted;
        });

        MedSpringAiChatMemoryRepository repository =
                new MedSpringAiChatMemoryRepository(inner, sessionLockService, sessionMapper);
        window = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(2)
                .build();
    }

    @Test
    @DisplayName("a single added message is retrievable through the window")
    void addThenGetReturnsMessage() {
        window.add(CONVERSATION_ID, new UserMessage("hello"));

        List<Message> messages = window.get(CONVERSATION_ID);
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(0).getText()).isEqualTo("hello");
    }

    @Test
    @DisplayName("the window trims to maxMessages before it is handed to the durable store")
    void windowTrimsToMaxMessages() {
        window.add(CONVERSATION_ID, new UserMessage("first"));
        window.add(CONVERSATION_ID, new UserMessage("second"));
        window.add(CONVERSATION_ID, new UserMessage("third"));

        // The memory only ever persists the trimmed window...
        assertThat(lastWrittenWindow()).extracting(ChatMessageDO::getContent)
                .containsExactly("second", "third");
        // ...while the store it persists into has kept every turn (D44).
        assertThat(store).extracting(ChatMessageDO::getContent)
                .containsExactly("first", "second", "third");
    }

    @Test
    @DisplayName("message ids survive the read→write round-trip through the bridge")
    void messageIdPreservedAcrossTurns() {
        window.add(CONVERSATION_ID, new UserMessage("first"));
        String firstId = store.get(0).getMessageId();

        window.add(CONVERSATION_ID, new UserMessage("second"));

        assertThat(store).hasSize(2);
        assertThat(store.get(0).getMessageId()).isEqualTo(firstId);
    }

    @Test
    @DisplayName("medical role is preserved when persisted through the bridge")
    void rolePreservedWhenPersisted() {
        window.add(CONVERSATION_ID, new AssistantMessage("answer", new HashMap<>()));
        window.add(CONVERSATION_ID, SystemMessage.builder().text("directive").build());

        assertThat(store).extracting(ChatMessageDO::getRole)
                .containsExactly(RoleType.ASSISTANT, RoleType.SYSTEM);
    }

    @Test
    @DisplayName("clear empties the window and the backing storage")
    void clearEmptiesWindow() {
        window.add(CONVERSATION_ID, new UserMessage("hello"));
        window.clear(CONVERSATION_ID);

        assertThat(window.get(CONVERSATION_ID)).isEmpty();
        assertThat(store).isEmpty();
    }

    @Test
    @DisplayName("the conversation id is decoded into storage coordinates on every call")
    void conversationIdDecodedIntoCoordinates() {
        window.add(CONVERSATION_ID, new UserMessage("hello"));

        verify(inner).findAll(eq("tenant-1"), eq("dept-2"), eq("session-3"));
    }

    @Test
    @DisplayName("D44: the transcript keeps every turn the window has trimmed away")
    void transcriptKeepsMessagesTheWindowDropped() {
        window.add(CONVERSATION_ID, new UserMessage("first"));
        window.add(CONVERSATION_ID, new UserMessage("second"));
        window.add(CONVERSATION_ID, new UserMessage("third"));
        window.add(CONVERSATION_ID, new UserMessage("fourth"));
        window.add(CONVERSATION_ID, new UserMessage("fifth"));

        // The memory hands only the last two messages to the store...
        assertThat(lastWrittenWindow()).extracting(ChatMessageDO::getContent)
                .containsExactly("fourth", "fifth");
        // ...but the durable transcript still holds the whole consultation.
        assertThat(store).extracting(ChatMessageDO::getContent)
                .containsExactly("first", "second", "third", "fourth", "fifth");
        assertThat(store).extracting(ChatMessageDO::getMessageId).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("D44: persisting a turn never deletes the session")
    void persistingATurnNeverDeletes() {
        window.add(CONVERSATION_ID, new UserMessage("first"));
        window.add(CONVERSATION_ID, new UserMessage("second"));
        window.add(CONVERSATION_ID, new UserMessage("third"));

        verify(inner, never()).deleteSession(any(), any(), any());
    }

    /**
     * @return the window the memory last handed to the durable store
     */
    private List<ChatMessageDO> lastWrittenWindow() {
        assertThat(writtenWindows).isNotEmpty();
        return writtenWindows.get(writtenWindows.size() - 1);
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
}
