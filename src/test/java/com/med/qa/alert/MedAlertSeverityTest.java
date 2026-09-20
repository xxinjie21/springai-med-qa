package com.med.qa.alert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MedAlertSeverity}.
 *
 * <p>The ordering is the contract the {@code med.alert.minimum-severity} filter relies on, and the
 * health-status mapping is the contract the storage monitor relies on; both are asserted here so a
 * reordered enum or a relaxed mapping fails the build instead of silently muting pages.</p>
 */
class MedAlertSeverityTest {

    @Test
    @DisplayName("severity ranks increase from INFO to CRITICAL")
    void ranksAreOrdered() {
        assertThat(MedAlertSeverity.INFO.rank()).isZero();
        assertThat(MedAlertSeverity.WARNING.rank()).isEqualTo(1);
        assertThat(MedAlertSeverity.CRITICAL.rank()).isEqualTo(2);
    }

    @Test
    @DisplayName("isAtLeast accepts the same and any lower severity")
    void isAtLeastAcceptsSameAndLower() {
        assertThat(MedAlertSeverity.CRITICAL.isAtLeast(MedAlertSeverity.CRITICAL)).isTrue();
        assertThat(MedAlertSeverity.CRITICAL.isAtLeast(MedAlertSeverity.WARNING)).isTrue();
        assertThat(MedAlertSeverity.CRITICAL.isAtLeast(MedAlertSeverity.INFO)).isTrue();
        assertThat(MedAlertSeverity.WARNING.isAtLeast(MedAlertSeverity.INFO)).isTrue();

        assertThat(MedAlertSeverity.INFO.isAtLeast(MedAlertSeverity.WARNING)).isFalse();
        assertThat(MedAlertSeverity.WARNING.isAtLeast(MedAlertSeverity.CRITICAL)).isFalse();
    }

    @Test
    @DisplayName("boundary: a null threshold is rejected instead of silently disabling the filter")
    void isAtLeastRejectsNull() {
        assertThatThrownBy(() -> MedAlertSeverity.INFO.isAtLeast(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    @DisplayName("UP maps to INFO, DOWN to CRITICAL and every other status to WARNING")
    void mapsHealthStatus() {
        assertThat(MedAlertSeverity.fromHealthStatus(Status.UP)).isEqualTo(MedAlertSeverity.INFO);
        assertThat(MedAlertSeverity.fromHealthStatus(Status.DOWN)).isEqualTo(MedAlertSeverity.CRITICAL);
        assertThat(MedAlertSeverity.fromHealthStatus(Status.OUT_OF_SERVICE))
                .isEqualTo(MedAlertSeverity.WARNING);
        assertThat(MedAlertSeverity.fromHealthStatus(Status.UNKNOWN)).isEqualTo(MedAlertSeverity.WARNING);
        assertThat(MedAlertSeverity.fromHealthStatus(new Status("CUSTOM")))
                .isEqualTo(MedAlertSeverity.WARNING);
    }

    @Test
    @DisplayName("boundary: a null status is rejected")
    void fromHealthStatusRejectsNull() {
        assertThatThrownBy(() -> MedAlertSeverity.fromHealthStatus(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status must not be null");
    }

    @Test
    @DisplayName("the enum exposes exactly the three documented constants")
    void exposesExpectedConstants() {
        assertThat(MedAlertSeverity.values())
                .containsExactly(MedAlertSeverity.INFO, MedAlertSeverity.WARNING, MedAlertSeverity.CRITICAL);
        assertThat(MedAlertSeverity.valueOf("CRITICAL")).isEqualTo(MedAlertSeverity.CRITICAL);
    }
}
