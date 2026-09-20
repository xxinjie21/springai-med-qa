package com.med.qa.alert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MedAlertNotifier}.
 *
 * <p>These pin the two behaviours that decide whether an incident is noticed: the policy filters
 * (disabled / muted / below the severity floor) and the fail-open rule. A suppression store that is
 * down must never swallow an alert -- an unreachable Redis is itself one of the conditions this
 * chain reports.</p>
 */
class MedAlertNotifierTest {

    private final MedAlertSink firstSink = mock(MedAlertSink.class);
    private final MedAlertSink secondSink = mock(MedAlertSink.class);
    private final RedissonClient redissonClient = mock(RedissonClient.class);

    @SuppressWarnings("unchecked")
    private final RMapCache<String, Long> dedupeMap = mock(RMapCache.class);

    private static final MedAlert ALERT =
            MedAlert.of(MedAlertSeverity.CRITICAL, "storage-down", "mysql", "mysql is down");

    /** Notifier over the two sinks and the mocked suppression store. */
    private MedAlertNotifier notifier(MedAlertProperties properties, RedissonClient client) {
        return new MedAlertNotifier(properties, List.of(firstSink, secondSink), client);
    }

    private MedAlertNotifier notifier(MedAlertProperties properties) {
        return notifier(properties, redissonClient);
    }

    // ---------------------------------------------------------------- happy path

    @Test
    @DisplayName("a reportable alert is fanned out to every sink and reported as delivered")
    void dispatchesToEverySink() {
        MedAlertProperties properties = new MedAlertProperties();
        when(redissonClient.<String, Long>getMapCache(anyString())).thenReturn(dedupeMap);
        when(dedupeMap.putIfAbsent(anyString(), anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(null);

        assertThat(notifier(properties).raise(ALERT)).isTrue();

        verify(firstSink).publish(ALERT);
        verify(secondSink).publish(ALERT);
    }

    @Test
    @DisplayName("the convenience overload builds the alert from its parts")
    void convenienceOverloadRaises() {
        MedAlertProperties properties = new MedAlertProperties();
        properties.setCooldown(Duration.ZERO);

        assertThat(notifier(properties).raise(MedAlertSeverity.CRITICAL, "storage-down", "mysql", "down"))
                .isTrue();
        verify(firstSink).publish(any(MedAlert.class));
    }

    @Test
    @DisplayName("sinkCount exposes how many sinks the chain fans out to")
    void reportsSinkCount() {
        assertThat(notifier(new MedAlertProperties()).sinkCount()).isEqualTo(2);
        assertThat(new MedAlertNotifier(new MedAlertProperties(), null, null).sinkCount()).isZero();
    }

    // ---------------------------------------------------------------- policy filters

    @Test
    @DisplayName("a disabled chain drops the alert without touching a sink or the store")
    void disabledChainDropsEverything() {
        MedAlertProperties properties = new MedAlertProperties();
        properties.setEnabled(false);

        assertThat(notifier(properties).raise(ALERT)).isFalse();

        verifyNoInteractions(firstSink, secondSink, redissonClient);
    }

    @Test
    @DisplayName("a muted code is dropped even at CRITICAL")
    void mutedCodeIsDropped() {
        MedAlertProperties properties = new MedAlertProperties();
        properties.setMutedCodes(List.of("STORAGE-DOWN"));

        assertThat(notifier(properties).raise(ALERT)).isFalse();

        verifyNoInteractions(firstSink, secondSink);
    }

    @Test
    @DisplayName("an alert below the severity floor is dropped")
    void severityFloorIsApplied() {
        MedAlertProperties properties = new MedAlertProperties();
        properties.setMinimumSeverity(MedAlertSeverity.WARNING);

        assertThat(notifier(properties).raise(
                MedAlert.of(MedAlertSeverity.INFO, "storage-recovered", "redis", "back"))).isFalse();

        verifyNoInteractions(firstSink, secondSink);
    }

    // ---------------------------------------------------------------- deduplication

    @Test
    @DisplayName("an alert already inside its cooldown window is suppressed")
    void suppressesDuplicateInsideCooldown() {
        MedAlertProperties properties = new MedAlertProperties();
        when(redissonClient.<String, Long>getMapCache(anyString())).thenReturn(dedupeMap);
        when(dedupeMap.putIfAbsent(anyString(), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(1_700_000_000_000L);

        assertThat(notifier(properties).raise(ALERT)).isFalse();

        verify(firstSink, never()).publish(any(MedAlert.class));
        verify(secondSink, never()).publish(any(MedAlert.class));
    }

    @Test
    @DisplayName("the suppression entry is written with the fingerprint and the configured TTL")
    void suppressionUsesFingerprintAndTtl() {
        MedAlertProperties properties = new MedAlertProperties();
        properties.setCooldown(Duration.ofMinutes(7));
        properties.setKeyPrefix("med:alert:");
        when(redissonClient.<String, Long>getMapCache("med:alert:dedupe")).thenReturn(dedupeMap);
        when(dedupeMap.putIfAbsent(anyString(), anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(null);

        notifier(properties).raise(ALERT);

        verify(dedupeMap).putIfAbsent(eq("storage-down:mysql"), anyLong(),
                eq(Duration.ofMinutes(7).toMillis()), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("a zero cooldown disables deduplication and never touches the store")
    void zeroCooldownSkipsTheStore() {
        MedAlertProperties properties = new MedAlertProperties();
        properties.setCooldown(Duration.ZERO);

        assertThat(notifier(properties).raise(ALERT)).isTrue();

        verifyNoInteractions(redissonClient);
    }

    @Test
    @DisplayName("without a Redisson client the alert is dispatched unsuppressed")
    void missingStoreDispatchesUnsuppressed() {
        assertThat(notifier(new MedAlertProperties(), null).raise(ALERT)).isTrue();

        verify(firstSink).publish(ALERT);
    }

    @Test
    @DisplayName("fail-open: a broken suppression store dispatches the alert instead of losing it")
    void brokenStoreFailsOpen() {
        MedAlertProperties properties = new MedAlertProperties();
        when(redissonClient.<String, Long>getMapCache(anyString()))
                .thenThrow(new IllegalStateException("redis is unreachable"));

        assertThat(notifier(properties).raise(ALERT)).isTrue();

        verify(firstSink).publish(ALERT);
        verify(secondSink).publish(ALERT);
    }

    // ---------------------------------------------------------------- sink isolation

    @Test
    @DisplayName("one failing sink does not stop the others")
    void failingSinkIsIsolated() {
        MedAlertProperties properties = new MedAlertProperties();
        properties.setCooldown(Duration.ZERO);
        org.mockito.Mockito.doThrow(new IllegalStateException("collector down"))
                .when(firstSink).publish(any(MedAlert.class));

        assertThat(notifier(properties).raise(ALERT)).isTrue();

        verify(secondSink).publish(ALERT);
    }

    @Test
    @DisplayName("boundary: when every sink fails the alert is reported as not delivered")
    void allSinksFailingIsReported() {
        MedAlertProperties properties = new MedAlertProperties();
        properties.setCooldown(Duration.ZERO);
        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
                .when(firstSink).publish(any(MedAlert.class));
        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
                .when(secondSink).publish(any(MedAlert.class));

        assertThat(notifier(properties).raise(ALERT)).isFalse();
    }

    @Test
    @DisplayName("boundary: a chain with no sink reports the alert as not delivered")
    void noSinkReportsNotDelivered() {
        MedAlertProperties properties = new MedAlertProperties();
        properties.setCooldown(Duration.ZERO);

        assertThat(new MedAlertNotifier(properties, List.of(), null).raise(ALERT)).isFalse();
    }

    @Test
    @DisplayName("boundary: null arguments are rejected instead of silently disabling the chain")
    void rejectsNullArguments() {
        assertThatThrownBy(() -> new MedAlertNotifier(null, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("properties must not be null");

        MedAlertNotifier notifier = new MedAlertNotifier(new MedAlertProperties(), List.of(), null);
        assertThatThrownBy(() -> notifier.raise((MedAlert) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alert must not be null");
    }
}
