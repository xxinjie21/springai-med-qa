package com.med.qa.alert;

import com.med.qa.actuator.MedVectorIndexHealthIndicator;
import com.med.qa.rag.MedRagIndexProperties;
import com.med.qa.rag.MedVectorIndexProbe;
import com.med.qa.rag.MedVectorStoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cross-component contract tests for the alert chain: real probe, real health indicator, real monitor,
 * real notifier, real sink.
 *
 * <p>It exists because of what the 2026-09-25 review called the structural gap behind three P0s and
 * six P1s in a fully green 1389-test suite: every test exercised one component against mocks, so
 * nothing asserted that the components actually agree with each other. Both defects this class pins
 * are of exactly that shape:</p>
 *
 * <ul>
 *   <li><b>P1-6</b> — {@code MedVectorIndexAlertMonitor}'s {@code CRITICAL} branch was unreachable.
 *       {@code AbstractHealthIndicator#health()} is {@code final} and converts a thrown exception into
 *       a plain {@code DOWN}, so a monitor that caught around {@code health()} never saw the failure
 *       and a total Redis outage was filed as a {@code WARNING}. {@code MedVectorIndexAlertMonitorTest}
 *       cannot catch that: it hands the monitor a hand-written {@code Health}, so it asserts the
 *       classifier but never that the real indicator <em>produces</em> the classified shape. This class
 *       drives the real indicator over a real probe and asserts the alert that comes out the other end.</li>
 *   <li><b>P1-5</b> — the notifier wrote the cooldown fingerprint before delivering, so an alert whose
 *       every sink failed was silently dropped for the whole cooldown window. Asserting the order
 *       requires the dispatcher and the suppression store to be wired together, which no single-component
 *       test does.</li>
 * </ul>
 *
 * <p>Only the two true externals are mocked: the Jedis client (so the probe fails or reports an absent
 * index on demand) and the Redisson deduplication map. Everything the project owns is the production
 * implementation.</p>
 */
class AlertDeliveryContractTest {

    /** Sink that records what reached it, and can be told to fail like a dead collector. */
    private static final class RecordingSink implements MedAlertSink {

        private final List<MedAlert> published = new ArrayList<>();

        private final boolean fail;

        RecordingSink(boolean fail) {
            this.fail = fail;
        }

        @Override
        public void publish(MedAlert alert) {
            if (fail) {
                throw new IllegalStateException("collector is down");
            }
            published.add(alert);
        }
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MedVectorIndexHealthIndicator> providerOf(MedVectorIndexHealthIndicator value) {
        ObjectProvider<MedVectorIndexHealthIndicator> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    /** The real indicator over a real probe whose Jedis client behaves as the test dictates. */
    private static MedVectorIndexHealthIndicator indicatorOver(JedisPooled jedis) {
        MedVectorStoreProperties storeProperties = new MedVectorStoreProperties();
        MedVectorIndexProbe probe = new MedVectorIndexProbe(jedis, storeProperties);
        return new MedVectorIndexHealthIndicator(probe, new MedRagIndexProperties());
    }

    /** The real notifier over one sink and, optionally, a mocked deduplication map. */
    @SuppressWarnings("unchecked")
    private static MedAlertNotifier notifierOver(RecordingSink sink, RMapCache<String, Long> dedupeMap) {
        RedissonClient redissonClient = null;
        if (dedupeMap != null) {
            redissonClient = mock(RedissonClient.class);
            when(redissonClient.<String, Long>getMapCache(anyString())).thenReturn(dedupeMap);
        }
        return new MedAlertNotifier(new MedAlertProperties(), List.of(sink), redissonClient);
    }

    // ---------------------------------------------------------------- P1-6: probe failure

    @Test
    @DisplayName("contract: an unreachable Redis becomes a critical probe-failure alert, not a warning")
    void unreachableRedisEscalatesToCritical() {
        JedisPooled jedis = mock(JedisPooled.class);
        when(jedis.ftList()).thenThrow(new JedisConnectionException("connection refused"));

        RecordingSink sink = new RecordingSink(false);
        MedVectorIndexAlertMonitor monitor =
                new MedVectorIndexAlertMonitor(providerOf(indicatorOver(jedis)), notifierOver(sink, null));

        assertThat(monitor.checkNow()).isTrue();

        assertThat(sink.published).hasSize(1);
        MedAlert alert = sink.published.get(0);
        assertThat(alert.code()).isEqualTo(MedVectorIndexAlertMonitor.INDEX_PROBE_FAILED);
        assertThat(alert.severity()).isEqualTo(MedAlertSeverity.CRITICAL);
        assertThat(alert.component()).isEqualTo(MedVectorIndexAlertMonitor.INDEX_COMPONENT);
        // The reason recorded by the real indicator is what the monitor classified on, so asserting it
        // here is what proves the two ends of the contract agree.
        assertThat(alert.message()).contains(MedVectorIndexHealthIndicator.REASON_UNREACHABLE);
    }

    @Test
    @DisplayName("contract: an index that is merely absent stays a warning through the same chain")
    void absentIndexStaysAWarning() {
        JedisPooled jedis = mock(JedisPooled.class);
        when(jedis.ftList()).thenReturn(Set.of());

        RecordingSink sink = new RecordingSink(false);
        MedVectorIndexAlertMonitor monitor =
                new MedVectorIndexAlertMonitor(providerOf(indicatorOver(jedis)), notifierOver(sink, null));

        assertThat(monitor.checkNow()).isTrue();

        assertThat(sink.published).hasSize(1);
        MedAlert alert = sink.published.get(0);
        assertThat(alert.code()).isEqualTo(MedVectorIndexAlertMonitor.INDEX_DEGRADED);
        assertThat(alert.severity()).isEqualTo(MedAlertSeverity.WARNING);
        assertThat(alert.message()).contains(MedVectorIndexHealthIndicator.REASON_INDEX_MISSING);
    }

    @Test
    @DisplayName("contract: a reachable Redis with a healthy index raises nothing at all")
    void healthyIndexRaisesNothing() {
        JedisPooled jedis = mock(JedisPooled.class);
        MedVectorStoreProperties storeProperties = new MedVectorStoreProperties();
        when(jedis.ftList()).thenReturn(Set.of(storeProperties.getIndexName()));
        when(jedis.ftInfo(storeProperties.getIndexName())).thenReturn(Map.of(
                MedVectorIndexProbe.FT_INFO_NUM_DOCS, 12L,
                MedVectorIndexProbe.FT_INFO_INDEX_DEFINITION,
                List.of(MedVectorIndexProbe.FT_INFO_PREFIXES, List.of(storeProperties.getPrefix())),
                MedVectorIndexProbe.FT_INFO_ATTRIBUTES, List.of(
                        List.of(MedVectorIndexProbe.ATTRIBUTE_NAME, "tenant_id",
                                MedVectorIndexProbe.ATTRIBUTE_TYPE, MedVectorIndexProbe.TAG_TYPE),
                        List.of(MedVectorIndexProbe.ATTRIBUTE_NAME, "dept_id",
                                MedVectorIndexProbe.ATTRIBUTE_TYPE, MedVectorIndexProbe.TAG_TYPE),
                        List.of(MedVectorIndexProbe.ATTRIBUTE_NAME, "patient_id",
                                MedVectorIndexProbe.ATTRIBUTE_TYPE, MedVectorIndexProbe.TAG_TYPE))));

        RecordingSink sink = new RecordingSink(false);
        MedVectorIndexAlertMonitor monitor =
                new MedVectorIndexAlertMonitor(providerOf(indicatorOver(jedis)), notifierOver(sink, null));

        assertThat(monitor.checkNow()).isFalse();
        assertThat(monitor.isDegraded()).isFalse();
        assertThat(sink.published).isEmpty();
    }

    // ---------------------------------------------------------------- P1-5: delivery before suppression

    @Test
    @DisplayName("contract: an alert whose every sink failed is raised again on the next pass")
    void failedDeliveryIsNotSuppressed() {
        JedisPooled jedis = mock(JedisPooled.class);
        when(jedis.ftList()).thenReturn(Set.of());

        @SuppressWarnings("unchecked")
        RMapCache<String, Long> dedupeMap = mock(RMapCache.class);
        RecordingSink sink = new RecordingSink(true);
        MedVectorIndexAlertMonitor monitor =
                new MedVectorIndexAlertMonitor(providerOf(indicatorOver(jedis)), notifierOver(sink, dedupeMap));

        assertThat(monitor.checkNow()).isTrue();
        assertThat(monitor.checkNow()).isTrue();

        // Two passes, two attempts: nothing was ever recorded as delivered.
        verify(dedupeMap, never()).putIfAbsent(anyString(), anyLong(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("contract: a delivered alert is recorded and then suppressed on the next pass")
    void deliveredAlertIsRecordedThenSuppressed() {
        JedisPooled jedis = mock(JedisPooled.class);
        when(jedis.ftList()).thenReturn(Set.of());

        @SuppressWarnings("unchecked")
        RMapCache<String, Long> dedupeMap = mock(RMapCache.class);
        // First pass: nothing recorded yet. Second pass: the first pass's write is visible.
        when(dedupeMap.containsKey(anyString())).thenReturn(false, true);

        RecordingSink sink = new RecordingSink(false);
        MedVectorIndexAlertMonitor monitor =
                new MedVectorIndexAlertMonitor(providerOf(indicatorOver(jedis)), notifierOver(sink, dedupeMap));

        assertThat(monitor.checkNow()).isTrue();
        assertThat(monitor.checkNow()).isTrue();

        assertThat(sink.published).hasSize(1);
        assertThat(sink.published.get(0).code()).isEqualTo(MedVectorIndexAlertMonitor.INDEX_DEGRADED);
        verify(dedupeMap).putIfAbsent(anyString(), anyLong(), anyLong(), any(TimeUnit.class));
    }
}
