package com.med.qa.alert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MedAlert}.
 *
 * <p>The record is the only carrier between the monitor and the sinks, so its validation and its
 * defensive copying are asserted rather than assumed: a mutable detail map leaking into a sink
 * would let a caller rewrite an alert after it was logged.</p>
 */
class MedAlertTest {

    @Test
    @DisplayName("of() stamps the current instant and defaults the details to an empty map")
    void ofStampsNowAndEmptyDetails() {
        Instant before = Instant.now();

        MedAlert alert = MedAlert.of(MedAlertSeverity.CRITICAL, "storage-down", "mysql", "mysql is down");

        assertThat(alert.severity()).isEqualTo(MedAlertSeverity.CRITICAL);
        assertThat(alert.code()).isEqualTo("storage-down");
        assertThat(alert.component()).isEqualTo("mysql");
        assertThat(alert.message()).isEqualTo("mysql is down");
        assertThat(alert.details()).isEmpty();
        assertThat(alert.occurredAt()).isBetween(before, Instant.now());
    }

    @Test
    @DisplayName("of() with details keeps them and treats null as 'none'")
    void ofWithDetailsHandlesNull() {
        MedAlert withDetails = MedAlert.of(MedAlertSeverity.WARNING, "slow", "redis", "slow",
                Map.of("latencyMs", 1200));
        assertThat(withDetails.details()).containsEntry("latencyMs", 1200);

        MedAlert withoutDetails = MedAlert.of(MedAlertSeverity.WARNING, "slow", "redis", "slow", null);
        assertThat(withoutDetails.details()).isEmpty();
    }

    @Test
    @DisplayName("the detail map is copied, so mutating the source does not rewrite the alert")
    void detailsAreDefensivelyCopied() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("first", 1);

        MedAlert alert = MedAlert.of(MedAlertSeverity.INFO, "x", "y", "z", mutable);
        mutable.put("second", 2);

        assertThat(alert.details()).containsOnlyKeys("first");
        assertThatThrownBy(() -> alert.details().put("third", 3))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("the fingerprint is code plus component and ignores severity and wording")
    void fingerprintIsStableAcrossRewording() {
        MedAlert first = MedAlert.of(MedAlertSeverity.CRITICAL, "storage-down", "mysql", "mysql is down");
        MedAlert reworded = MedAlert.of(MedAlertSeverity.WARNING, "storage-down", "mysql", "MySQL unreachable");
        MedAlert otherComponent = MedAlert.of(MedAlertSeverity.CRITICAL, "storage-down", "redis", "redis is down");

        assertThat(first.fingerprint()).isEqualTo("storage-down:mysql");
        assertThat(first.fingerprint()).isEqualTo(reworded.fingerprint());
        assertThat(first.fingerprint()).isNotEqualTo(otherComponent.fingerprint());
    }

    @Test
    @DisplayName("boundary: every required field is validated")
    void rejectsMissingFields() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> new MedAlert(null, "c", "k", "m", now, Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("severity");
        assertThatThrownBy(() -> new MedAlert(MedAlertSeverity.INFO, null, "k", "m", now, Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("code");
        assertThatThrownBy(() -> new MedAlert(MedAlertSeverity.INFO, "c", "  ", "m", now, Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("component");
        assertThatThrownBy(() -> new MedAlert(MedAlertSeverity.INFO, "c", "k", null, now, Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("message");
        assertThatThrownBy(() -> new MedAlert(MedAlertSeverity.INFO, "c", "k", "m", null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("occurredAt");
    }

    @Test
    @DisplayName("boundary: records compare by value, so equal alerts are interchangeable")
    void recordEquality() {
        Instant now = Instant.ofEpochMilli(1_700_000_000_000L);
        MedAlert left = new MedAlert(MedAlertSeverity.INFO, "c", "k", "m", now, Map.of("a", 1));
        MedAlert right = new MedAlert(MedAlertSeverity.INFO, "c", "k", "m", now, Map.of("a", 1));

        assertThat(left).isEqualTo(right).hasSameHashCodeAs(right);
    }
}
