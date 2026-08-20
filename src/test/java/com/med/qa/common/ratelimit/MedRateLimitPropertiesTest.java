package com.med.qa.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.med.qa.config.MedRateLimitProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link MedRateLimitProperties}: sane defaults and fail-fast setters.
 */
class MedRateLimitPropertiesTest {

    @Test
    @DisplayName("exposes safe defaults")
    void defaults() {
        MedRateLimitProperties props = new MedRateLimitProperties();
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getOrder()).isEqualTo(MedRateLimitProperties.DEFAULT_ORDER);
        assertThat(props.getKeyPrefix()).isEqualTo(MedRateLimitProperties.KEY_PREFIX_DEFAULT);
        assertThat(props.getAcquireTimeoutMillis()).isEqualTo(MedRateLimitProperties.DEFAULT_ACQUIRE_TIMEOUT_MILLIS);
        assertThat(props.getDefaultRate()).isEqualTo(MedRateLimitProperties.DEFAULT_RATE);
        assertThat(props.getDefaultDurationSeconds()).isEqualTo(MedRateLimitProperties.DEFAULT_DURATION_SECONDS);
    }

    @Test
    @DisplayName("accepts valid overrides")
    void validOverrides() {
        MedRateLimitProperties props = new MedRateLimitProperties();
        props.setEnabled(false);
        props.setOrder(7);
        props.setKeyPrefix("rl:");
        props.setAcquireTimeoutMillis(200L);
        props.setDefaultRate(3);
        props.setDefaultDurationSeconds(5);
        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getOrder()).isEqualTo(7);
        assertThat(props.getKeyPrefix()).isEqualTo("rl:");
        assertThat(props.getAcquireTimeoutMillis()).isEqualTo(200L);
        assertThat(props.getDefaultRate()).isEqualTo(3);
        assertThat(props.getDefaultDurationSeconds()).isEqualTo(5);
    }

    @Test
    @DisplayName("rejects a negative order")
    void negativeOrder() {
        MedRateLimitProperties props = new MedRateLimitProperties();
        assertThatThrownBy(() -> props.setOrder(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects a blank key prefix")
    void blankKeyPrefix() {
        MedRateLimitProperties props = new MedRateLimitProperties();
        assertThatThrownBy(() -> props.setKeyPrefix("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects a negative acquire timeout")
    void negativeTimeout() {
        MedRateLimitProperties props = new MedRateLimitProperties();
        assertThatThrownBy(() -> props.setAcquireTimeoutMillis(-1L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects a non-positive default rate")
    void nonPositiveRate() {
        MedRateLimitProperties props = new MedRateLimitProperties();
        assertThatThrownBy(() -> props.setDefaultRate(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects a non-positive default duration")
    void nonPositiveDuration() {
        MedRateLimitProperties props = new MedRateLimitProperties();
        assertThatThrownBy(() -> props.setDefaultDurationSeconds(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
