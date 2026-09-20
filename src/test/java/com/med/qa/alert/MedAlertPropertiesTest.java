package com.med.qa.alert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MedAlertProperties}.
 *
 * <p>Alerting configuration is the one place where a typo is invisible: a bad value does not crash
 * anything, it just makes the paging channel quiet. Every setter is therefore asserted to reject an
 * unusable value, and the two derived predicates ({@code isMuted}, {@code isReportable}) are pinned
 * because they decide whether an alert survives.</p>
 */
class MedAlertPropertiesTest {

    @Test
    @DisplayName("defaults describe a chain that is on, probes every minute and suppresses for ten")
    void defaults() {
        MedAlertProperties properties = new MedAlertProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getCheckInterval()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.getInitialDelay()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getCooldown()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.getMinimumSeverity()).isEqualTo(MedAlertSeverity.INFO);
        assertThat(properties.getKeyPrefix()).isEqualTo("med:alert:");
        assertThat(properties.getMutedCodes()).isEmpty();
        assertThat(properties.isDeduplicationEnabled()).isTrue();
    }

    @Test
    @DisplayName("the master switch can be turned off")
    void enabledCanBeTurnedOff() {
        MedAlertProperties properties = new MedAlertProperties();
        properties.setEnabled(false);

        assertThat(properties.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("durations accept operator-friendly values")
    void durationsAreSettable() {
        MedAlertProperties properties = new MedAlertProperties();
        properties.setCheckInterval(Duration.ofSeconds(15));
        properties.setInitialDelay(Duration.ZERO);
        properties.setCooldown(Duration.ofHours(1));

        assertThat(properties.getCheckInterval()).isEqualTo(Duration.ofSeconds(15));
        assertThat(properties.getInitialDelay()).isZero();
        assertThat(properties.getCooldown()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("boundary: a zero or negative check interval is rejected")
    void rejectsNonPositiveCheckInterval() {
        MedAlertProperties properties = new MedAlertProperties();

        assertThatThrownBy(() -> properties.setCheckInterval(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("check-interval");
        assertThatThrownBy(() -> properties.setCheckInterval(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("check-interval");
        assertThatThrownBy(() -> properties.setCheckInterval(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("check-interval");
    }

    @Test
    @DisplayName("boundary: a negative initial delay or cooldown is rejected")
    void rejectsNegativeDurations() {
        MedAlertProperties properties = new MedAlertProperties();

        assertThatThrownBy(() -> properties.setInitialDelay(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initial-delay");
        assertThatThrownBy(() -> properties.setInitialDelay(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initial-delay");
        assertThatThrownBy(() -> properties.setCooldown(Duration.ofSeconds(-5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cooldown");
        assertThatThrownBy(() -> properties.setCooldown(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cooldown");
    }

    @Test
    @DisplayName("a zero cooldown disables deduplication instead of muting everything")
    void zeroCooldownDisablesDeduplication() {
        MedAlertProperties properties = new MedAlertProperties();
        properties.setCooldown(Duration.ZERO);

        assertThat(properties.isDeduplicationEnabled()).isFalse();
    }

    @Test
    @DisplayName("boundary: a blank key prefix or null severity is rejected")
    void rejectsBlankPrefixAndNullSeverity() {
        MedAlertProperties properties = new MedAlertProperties();

        assertThatThrownBy(() -> properties.setKeyPrefix(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key-prefix");
        assertThatThrownBy(() -> properties.setKeyPrefix(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key-prefix");
        assertThatThrownBy(() -> properties.setMinimumSeverity(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimum-severity");
    }

    @Test
    @DisplayName("muted codes match case-insensitively and ignore whitespace")
    void mutedCodesAreNormalized() {
        MedAlertProperties properties = new MedAlertProperties();
        properties.setMutedCodes(List.of(" STORAGE-DOWN ", "planned-maintenance"));

        assertThat(properties.isMuted("storage-down")).isTrue();
        assertThat(properties.isMuted("Storage-Down")).isTrue();
        assertThat(properties.isMuted("storage-recovered")).isFalse();
    }

    @Test
    @DisplayName("boundary: an empty, null or null-containing mute list never mutes anything")
    void muteListEdgeCases() {
        MedAlertProperties properties = new MedAlertProperties();
        properties.setMutedCodes(null);
        assertThat(properties.getMutedCodes()).isEmpty();
        assertThat(properties.isMuted("storage-down")).isFalse();

        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        properties.setMutedCodes(withNull);
        assertThat(properties.isMuted(null)).isFalse();
        assertThat(properties.isMuted("storage-down")).isFalse();
    }

    @Test
    @DisplayName("the mute list is copied so later caller mutations cannot change the policy")
    void muteListIsCopied() {
        MedAlertProperties properties = new MedAlertProperties();
        List<String> source = new ArrayList<>(List.of("storage-down"));
        properties.setMutedCodes(source);
        source.add("storage-recovered");

        assertThat(properties.getMutedCodes()).containsExactly("storage-down");
    }

    @Test
    @DisplayName("isReportable applies the severity floor")
    void reportableAppliesSeverityFloor() {
        MedAlertProperties properties = new MedAlertProperties();

        assertThat(properties.isReportable(MedAlertSeverity.INFO)).isTrue();
        assertThat(properties.isReportable(MedAlertSeverity.WARNING)).isTrue();
        assertThat(properties.isReportable(MedAlertSeverity.CRITICAL)).isTrue();

        properties.setMinimumSeverity(MedAlertSeverity.WARNING);
        assertThat(properties.isReportable(MedAlertSeverity.INFO)).isFalse();
        assertThat(properties.isReportable(MedAlertSeverity.WARNING)).isTrue();
        assertThat(properties.isReportable(MedAlertSeverity.CRITICAL)).isTrue();
    }

    @Test
    @DisplayName("boundary: isReportable rejects a null severity")
    void reportableRejectsNull() {
        MedAlertProperties properties = new MedAlertProperties();

        assertThatThrownBy(() -> properties.isReportable(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("severity must not be null");
    }

    @Test
    @DisplayName("toString reports the effective policy for log forensics")
    void toStringReportsPolicy() {
        MedAlertProperties properties = new MedAlertProperties();
        properties.setMutedCodes(List.of("x"));

        assertThat(properties.toString())
                .contains("enabled=true")
                .contains("minimumSeverity=INFO")
                .contains("mutedCodes=[x]");
    }
}
