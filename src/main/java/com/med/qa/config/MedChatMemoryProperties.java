package com.med.qa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized tuning of the Spring AI short-term memory window.
 *
 * <p>Bound from {@code med.chat.*} in {@code application.yml}. The window size is the only knob the
 * chat layer needs; the two-tier repository decides how the window is persisted (Redis + sharded
 * MySQL), so storage concerns stay out of this configuration.</p>
 *
 * <p><b>{@code max-messages} bounds the prompt, not the record (D44).</b> Spring AI trims the rolling
 * window before handing it to the model, and that is the only thing this value controls: the durable
 * transcript (Redis window + sharded MySQL) is append-only and is never trimmed to it, so lowering
 * this value makes the model see fewer turns and cannot lose one.</p>
 */
@ConfigurationProperties(prefix = "med.chat")
public class MedChatMemoryProperties {

    /**
     * Maximum number of messages kept in the rolling short-term memory window handed to the LLM.
     * Mirrors {@code MessageWindowChatMemory}'s {@code maxMessages}. Bounds what the model is shown;
     * the stored transcript is governed by the two-tier repository, not by this value (D44).
     */
    private int maxMessages = 20;

    public int getMaxMessages() {
        return maxMessages;
    }

    /**
     * Sets the window size, failing fast on an invalid value so a misconfiguration surfaces at
     * context startup rather than in the middle of a consultation.
     *
     * @param maxMessages strictly positive message count
     * @throws IllegalArgumentException when {@code maxMessages < 1}
     */
    public void setMaxMessages(int maxMessages) {
        if (maxMessages < 1) {
            throw new IllegalArgumentException("med.chat.max-messages must be >= 1");
        }
        this.maxMessages = maxMessages;
    }
}
