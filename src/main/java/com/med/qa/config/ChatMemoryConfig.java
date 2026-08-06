package com.med.qa.config;

import com.med.qa.memory.MedSpringAiChatMemoryRepository;
import com.med.qa.memory.repository.MedChatMemoryRepository;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles the project's short-term conversation memory from the official Spring AI building
 * blocks and plugs it into the {@code ChatClient} chain.
 *
 * <p>Nothing here is hand-rolled: the rolling window is Spring AI's {@link MessageWindowChatMemory}
 * backed by our {@link MedSpringAiChatMemoryRepository} bridge, and the memory is injected into the
 * chat pipeline through the official {@link MessageChatMemoryAdvisor}. A {@link ChatClientCustomizer}
 * is published so that, once a {@code ChatClient.Builder} exists (later iterations bring the LLM
 * model), the advisor is applied automatically — the memory layer is therefore already wired into
 * the session flow, not bolted on later.</p>
 */
@Configuration
@EnableConfigurationProperties(MedChatMemoryProperties.class)
public class ChatMemoryConfig {

    /** Advisor execution order; runs early so the remembered transcript precedes the prompt. */
    public static final int ADVISOR_ORDER = 0;

    /**
     * The bridge that lets the official window memory read/write through the project's two-tier
     * repository (Redis cache + sharded MySQL).
     *
     * @param repository the underlying conversation repository, must not be {@code null}
     * @return the Spring AI {@code ChatMemoryRepository} implementation
     */
    @Bean
    public MedSpringAiChatMemoryRepository springAiChatMemoryRepository(MedChatMemoryRepository repository) {
        return new MedSpringAiChatMemoryRepository(repository);
    }

    /**
     * The rolling short-term memory window sized from {@link MedChatMemoryProperties}.
     *
     * @param repository the memory repository bridge, must not be {@code null}
     * @param properties memory window tuning, must not be {@code null}
     * @return the configured {@link MessageWindowChatMemory}
     */
    @Bean
    public MessageWindowChatMemory medChatMemory(MedSpringAiChatMemoryRepository repository,
                                                 MedChatMemoryProperties properties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(properties.getMaxMessages())
                .build();
    }

    /**
     * Advisor that loads the remembered transcript into each request and stores the new turn
     * afterwards, keyed by the {@code chat_memory_conversation_id} request param.
     *
     * @param chatMemory the window memory, must not be {@code null}
     * @return the {@link MessageChatMemoryAdvisor}
     */
    @Bean
    public MessageChatMemoryAdvisor medChatMemoryAdvisor(MessageWindowChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory)
                .order(ADVISOR_ORDER)
                .build();
    }

    /**
     * Publishes the memory advisor to every {@code ChatClient.Builder}, completing the "memory
     * wired into the ChatClient session flow" goal ahead of the model starter arriving.
     *
     * @param advisor the memory advisor, must not be {@code null}
     * @return a {@link ChatClientCustomizer} applying the advisor
     */
    @Bean
    public ChatClientCustomizer medChatMemoryClientCustomizer(MessageChatMemoryAdvisor advisor) {
        return builder -> {
            builder.defaultAdvisors(advisor);
        };
    }
}
