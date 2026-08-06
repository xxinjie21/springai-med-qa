package com.med.qa.memory;

import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.enums.RoleType;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end exercise of the official Spring AI {@link MessageWindowChatMemory} driven by the
 * project's {@link MedSpringAiChatMemoryRepository} bridge. The inner two-tier repository is a
 * Mockito stand-in backed by a plain in-memory list, so no MySQL / Redis is touched.
 */
@ExtendWith(MockitoExtension.class)
class ChatMemoryWindowIntegrationTest {

    private static final String CONVERSATION_ID = "tenant-1:dept-2:session-3";

    @Mock
    private MedChatMemoryRepository inner;

    private final List<ChatMessageDO> store = new ArrayList<>();

    private MessageWindowChatMemory window;

    @BeforeEach
    void setUp() {
        when(inner.findAll(any(), any(), any())).thenAnswer(inv -> new ArrayList<>(store));
        when(inner.deleteSession(any(), any(), any())).thenAnswer(inv -> {
            int removed = store.size();
            store.clear();
            return removed;
        });
        doAnswer(inv -> {
            store.addAll(inv.getArgument(0));
            return null;
        }).when(inner).appendAll(any());

        MedSpringAiChatMemoryRepository repository = new MedSpringAiChatMemoryRepository(inner);
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
    @DisplayName("the window trims to maxMessages, keeping the most recent turns")
    void windowTrimsToMaxMessages() {
        window.add(CONVERSATION_ID, new UserMessage("first"));
        window.add(CONVERSATION_ID, new UserMessage("second"));
        window.add(CONVERSATION_ID, new UserMessage("third"));

        List<Message> messages = window.get(CONVERSATION_ID);
        assertThat(messages).hasSize(2);
        assertThat(messages).extracting(Message::getText).containsExactly("second", "third");
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
}
