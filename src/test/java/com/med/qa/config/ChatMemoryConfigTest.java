package com.med.qa.config;

import com.med.qa.MedQaApplication;
import com.med.qa.memory.MedSpringAiChatMemoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link ChatMemoryConfig} assembles the Spring AI short-term memory beans and wires
 * the memory advisor into the ChatClient chain. The full application context is loaded (shared with
 * {@code MedQaApplicationTests}), so no real MySQL / Redis / LLM is required.
 */
@SpringBootTest(classes = MedQaApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "spring.flyway.enabled=false")
class ChatMemoryConfigTest {

    @Autowired
    private MedSpringAiChatMemoryRepository springAiChatMemoryRepository;

    @Autowired
    private MessageWindowChatMemory medChatMemory;

    @Autowired
    private MessageChatMemoryAdvisor medChatMemoryAdvisor;

    @Test
    void chatMemoryRepositoryBridgeIsRegistered() {
        assertThat(springAiChatMemoryRepository).isNotNull();
    }

    @Test
    void rollingWindowMemoryIsConfigured() {
        assertThat(medChatMemory).isNotNull();
    }

    @Test
    void memoryAdvisorIsWiredWithConfiguredOrder() {
        assertThat(medChatMemoryAdvisor).isNotNull();
        assertThat(medChatMemoryAdvisor.getOrder()).isEqualTo(ChatMemoryConfig.ADVISOR_ORDER);
    }
}
