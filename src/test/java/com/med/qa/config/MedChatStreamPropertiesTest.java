package com.med.qa.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests of the SSE streaming tuning properties.
 *
 * <p>Default values are positive and the setters reject non-positive input so a misconfiguration
 * fails fast at context startup rather than mid-stream.</p>
 */
class MedChatStreamPropertiesTest {

    @Test
    void defaultsArePositive() {
        MedChatStreamProperties properties = new MedChatStreamProperties();

        assertThat(properties.getHeartbeatIntervalSeconds()).isEqualTo(15);
        assertThat(properties.getSseTimeoutSeconds()).isEqualTo(120);
    }

    @Test
    void rejectsNonPositiveHeartbeat() {
        MedChatStreamProperties properties = new MedChatStreamProperties();

        assertThatThrownBy(() -> properties.setHeartbeatIntervalSeconds(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveTimeout() {
        MedChatStreamProperties properties = new MedChatStreamProperties();

        assertThatThrownBy(() -> properties.setSseTimeoutSeconds(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsCustomPositiveValues() {
        MedChatStreamProperties properties = new MedChatStreamProperties();

        properties.setHeartbeatIntervalSeconds(5);
        properties.setSseTimeoutSeconds(60);

        assertThat(properties.getHeartbeatIntervalSeconds()).isEqualTo(5);
        assertThat(properties.getSseTimeoutSeconds()).isEqualTo(60);
    }
}
