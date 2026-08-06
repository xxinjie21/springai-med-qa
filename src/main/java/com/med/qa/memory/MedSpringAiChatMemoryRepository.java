package com.med.qa.memory;

import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.enums.RoleType;
import com.med.qa.memory.repository.MedChatMemoryRepository;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Bridges Spring AI's {@link ChatMemoryRepository} contract onto the project's two-tier
 * {@link MedChatMemoryRepository} (Redis cache + sharded MySQL), so the official
 * {@code MessageWindowChatMemory} can manage short-term consultation memory without any
 * hand-rolled windowing, storage or serialization logic.
 *
 * <h2>Conversation id</h2>
 * The Spring AI {@code conversationId} is the {@link SessionCoordinate#toConversationId()} form
 * {@code tenantId:deptId:sessionId}, decoded on every call to locate the right
 * {@code med:chat:{tenant_id}:{dept_id}:{session_id}} window and the
 * {@code med_message_{crc32(session_id) % 16}} shards.
 *
 * <h2>Message bridging</h2>
 * <ul>
 *   <li>A {@link Message} carries no project {@code messageId} of its own, so the id generated on a
 *       previous read is carried in message metadata ({@link #MED_MESSAGE_ID}) and reused on the
 *       next write; a brand-new message (e.g. the just-typed patient question) gets a fresh id.</li>
 *   <li>Spring AI collapses every human turn into a single {@link MessageType#USER} message, so the
 *       original medical {@link RoleType} (PATIENT vs DOCTOR) is preserved in metadata
 *       ({@link #MED_ROLE_CODE}) and restored when persisting.</li>
 *   <li>Application metadata attached to a message is round-tripped through the DO, but the reserved
 *       {@code med.*} keys are never stored into the DO metadata map.</li>
 * </ul>
 *
 * <h2>saveAll semantics</h2>
 * Spring AI calls {@code saveAll} to <em>replace</em> the whole window after each turn. This
 * repository honours that by deleting the session's messages and re-inserting the trimmed set,
 * which keeps MySQL (the authoritative copy) and the Redis window perfectly in sync.
 */
public class MedSpringAiChatMemoryRepository implements ChatMemoryRepository {

    /** Metadata key holding the project message id, so identities survive a read→write round-trip. */
    static final String MED_MESSAGE_ID = "med.messageId";

    /** Metadata key holding the numeric {@link RoleType} code (PATIENT/DOCTOR disambiguation). */
    static final String MED_ROLE_CODE = "med.roleCode";

    /** Metadata key holding the creation epoch millis, so message ordering is preserved. */
    static final String MED_CREATED_AT = "med.createdAt";

    /** Metadata key holding the patient id when known. */
    static final String MED_PATIENT_ID = "med.patientId";

    private final MedChatMemoryRepository repository;

    /**
     * Creates the bridge.
     *
     * @param repository the underlying two-tier conversation repository, must not be {@code null}
     */
    public MedSpringAiChatMemoryRepository(MedChatMemoryRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    /**
     * No enumeration across the sharded tables is exposed; the window memory only ever queries by a
     * known {@code conversationId}.
     *
     * @return an empty list
     */
    @Override
    public List<String> findConversationIds() {
        return Collections.emptyList();
    }

    /**
     * Loads a session's conversation from the two-tier repository and converts each persisted
     * message into a Spring AI {@link Message}, attaching the bookkeeping metadata needed later.
     *
     * @param conversationId must decode to {@code tenantId:deptId:sessionId}
     * @return the messages, oldest first; empty when the session holds none
     * @throws IllegalArgumentException if the conversation id is malformed
     */
    @Override
    public List<Message> findByConversationId(String conversationId) {
        SessionCoordinate coord = SessionCoordinate.parse(conversationId);
        List<ChatMessageDO> stored =
                repository.findAll(coord.tenantId(), coord.deptId(), coord.sessionId());
        List<Message> messages = new ArrayList<>(stored.size());
        for (ChatMessageDO message : stored) {
            messages.add(toMessage(message));
        }
        return messages;
    }

    /**
     * Replaces the whole window of a session with the given messages. Each message is converted to a
     * {@link ChatMessageDO}; existing message ids are reused and new ids are minted on demand.
     *
     * @param conversationId must decode to {@code tenantId:deptId:sessionId}
     * @param messages       the new full window, must not be {@code null}
     * @throws IllegalArgumentException if the conversation id is malformed or {@code messages} is
     *                                  {@code null}
     */
    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        if (messages == null) {
            throw new IllegalArgumentException("messages must not be null");
        }
        SessionCoordinate coord = SessionCoordinate.parse(conversationId);
        List<ChatMessageDO> entities = new ArrayList<>(messages.size());
        for (Message message : messages) {
            entities.add(toChatMessageDO(message, coord));
        }
        repository.deleteSession(coord.tenantId(), coord.deptId(), coord.sessionId());
        repository.appendAll(entities);
    }

    /**
     * Deletes every message of a session.
     *
     * @param conversationId must decode to {@code tenantId:deptId:sessionId}
     * @throws IllegalArgumentException if the conversation id is malformed
     */
    @Override
    public void deleteByConversationId(String conversationId) {
        SessionCoordinate coord = SessionCoordinate.parse(conversationId);
        repository.deleteSession(coord.tenantId(), coord.deptId(), coord.sessionId());
    }

    /**
     * Converts a persisted message into a Spring AI message, choosing the message type from the
     * medical role and carrying the id/role/timestamp/patient metadata forward.
     *
     * @param entity the domain message, must not be {@code null}
     * @return the equivalent Spring AI {@link Message}
     */
    static Message toMessage(ChatMessageDO entity) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(MED_MESSAGE_ID, entity.getMessageId());
        metadata.put(MED_ROLE_CODE, entity.getRole().getCode());
        metadata.put(MED_CREATED_AT, String.valueOf(entity.getCreatedAt()));
        if (entity.getPatientId() != null) {
            metadata.put(MED_PATIENT_ID, entity.getPatientId());
        }
        for (Map.Entry<String, String> entry : entity.getMetadata().entrySet()) {
            metadata.put(entry.getKey(), entry.getValue());
        }
        String content = entity.getContent() == null ? "" : entity.getContent();
        return switch (entity.getRole()) {
            case ASSISTANT -> new AssistantMessage(content, metadata);
            case SYSTEM -> SystemMessage.builder().text(content).metadata(metadata).build();
            default -> UserMessage.builder().text(content).metadata(metadata).build();
        };
    }

    /**
     * Converts a Spring AI message into a persisted entity for the given session.
     *
     * @param message the Spring AI message, must not be {@code null}
     * @param coord   the owning session coordinate
     * @return the equivalent {@link ChatMessageDO}
     */
    static ChatMessageDO toChatMessageDO(Message message, SessionCoordinate coord) {
        Map<String, Object> metadata = message.getMetadata() == null
                ? Collections.emptyMap() : message.getMetadata();
        String messageId = asString(metadata.get(MED_MESSAGE_ID));
        if (messageId == null || messageId.isBlank()) {
            messageId = UUID.randomUUID().toString();
        }
        RoleType role = resolveRole(message);
        long createdAt = parseLong(metadata.get(MED_CREATED_AT), System.currentTimeMillis());
        String patientId = asString(metadata.get(MED_PATIENT_ID));
        ChatMessageDO.Builder builder = ChatMessageDO.builder()
                .messageId(messageId)
                .sessionId(coord.sessionId())
                .tenantId(coord.tenantId())
                .deptId(coord.deptId())
                .role(role)
                .content(message.getText() == null ? "" : message.getText())
                .createdAt(createdAt)
                .metadata(extractUserMetadata(metadata));
        if (patientId != null) {
            builder.patientId(patientId);
        }
        return builder.build();
    }

    /**
     * Resolves the medical role: prefer the code we persisted in metadata; otherwise map the Spring
     * AI message type (ASSISTANT/SYSTEM), defaulting a plain USER message to PATIENT.
     */
    private static RoleType resolveRole(Message message) {
        Map<String, Object> metadata = message.getMetadata();
        if (metadata != null) {
            String code = asString(metadata.get(MED_ROLE_CODE));
            if (code != null) {
                try {
                    return RoleType.fromCode(Integer.parseInt(code.trim()));
                } catch (IllegalArgumentException ignored) {
                    // fall through to type-based resolution
                }
            }
        }
        return switch (message.getMessageType()) {
            case ASSISTANT -> RoleType.ASSISTANT;
            case SYSTEM -> RoleType.SYSTEM;
            default -> RoleType.PATIENT;
        };
    }

    /**
     * Strips the reserved {@code med.*} bookkeeping keys so they never leak into the stored
     * application metadata of a message.
     */
    private static Map<String, String> extractUserMetadata(Map<String, Object> metadata) {
        Map<String, String> result = new LinkedHashMap<>();
        if (metadata == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            String key = entry.getKey();
            if (key != null && key.startsWith("med.")) {
                continue;
            }
            if (entry.getValue() != null) {
                result.put(key, String.valueOf(entry.getValue()));
            }
        }
        return result;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static long parseLong(Object value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
