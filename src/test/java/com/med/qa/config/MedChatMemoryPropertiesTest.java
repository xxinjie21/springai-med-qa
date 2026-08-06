package com.med.qa.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MedChatMemoryPropertiesTest {

    @Test
    void defaultWindowSizeIsTwenty() {
        assertThat(new MedChatMemoryProperties().getMaxMessages()).isEqualTo(20);
    }

    @Test
    void setterRoundTrips() {
        MedChatMemoryProperties properties = new MedChatMemoryProperties();
        properties.setMaxMessages(8);
        assertThat(properties.getMaxMessages()).isEqualTo(8);
    }

    @Test
    void zeroWindowSizeIsRejected() {
        MedChatMemoryProperties properties = new MedChatMemoryProperties();
        assertThatThrownBy(() -> properties.setMaxMessages(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-messages");
    }

    @Test
    void negativeWindowSizeIsRejected() {
        MedChatMemoryProperties properties = new MedChatMemoryProperties();
        assertThatThrownBy(() -> properties.setMaxMessages(-3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-messages");
    }
}
