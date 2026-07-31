package com.med.qa.memory.sharding;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.zip.CRC32;

import org.apache.shardingsphere.infra.datanode.DataNodeInfo;
import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.common.collect.Range;

/**
 * Unit tests for {@link Crc32ShardingAlgorithm}: property initialization, precise routing,
 * range fan-out and the error paths that protect against misconfiguration.
 */
class Crc32ShardingAlgorithmTest {

    private static final String LOGIC_TABLE = "med_message";

    private static final String SHARDING_COLUMN = "session_id";

    private static final List<String> ACTUAL_TABLES =
            IntStream.range(0, 16).mapToObj(i -> "med_message_" + i).toList();

    private static final DataNodeInfo NODE_INFO = new DataNodeInfo("med_message_", 1, '0');

    private Crc32ShardingAlgorithm algorithm;

    @BeforeEach
    void setUp() {
        algorithm = new Crc32ShardingAlgorithm();
        algorithm.init(new Properties());
    }

    private static PreciseShardingValue<Comparable<?>> precise(final String value) {
        return new PreciseShardingValue<>(LOGIC_TABLE, SHARDING_COLUMN, NODE_INFO, value);
    }

    private static long crc32(final String value) {
        CRC32 crc32 = new CRC32();
        crc32.update(value.getBytes(StandardCharsets.UTF_8));
        return crc32.getValue();
    }

    @Test
    @DisplayName("SPI type is the value referenced from the sharding YAML")
    void getTypeReturnsSpiType() {
        assertEquals("MED_CRC32_MOD", algorithm.getType());
        assertEquals(Crc32ShardingAlgorithm.TYPE, algorithm.getType());
    }

    @Test
    @DisplayName("empty props keep the 16-table default mandated by the storage spec")
    void initWithEmptyPropsUsesDefaultShardingCount() {
        assertEquals(16, algorithm.getShardingCount());
        assertEquals(Crc32ShardingAlgorithm.DEFAULT_SHARDING_COUNT, algorithm.getShardingCount());
    }

    @Test
    @DisplayName("sharding-count property overrides the default")
    void initWithCustomShardingCount() {
        Properties props = new Properties();
        props.setProperty(Crc32ShardingAlgorithm.SHARDING_COUNT_KEY, " 8 ");
        algorithm.init(props);
        assertEquals(8, algorithm.getShardingCount());
    }

    @Test
    @DisplayName("null props and blank value fall back to the default")
    void initWithNullOrBlankPropsUsesDefault() {
        algorithm.init(null);
        assertEquals(16, algorithm.getShardingCount());
        Properties blank = new Properties();
        blank.setProperty(Crc32ShardingAlgorithm.SHARDING_COUNT_KEY, "   ");
        algorithm.init(blank);
        assertEquals(16, algorithm.getShardingCount());
    }

    @Test
    @DisplayName("non-numeric sharding-count is rejected at init time")
    void initRejectsNonNumericShardingCount() {
        Properties props = new Properties();
        props.setProperty(Crc32ShardingAlgorithm.SHARDING_COUNT_KEY, "sixteen");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> algorithm.init(props));
        assertTrue(ex.getMessage().contains("sharding-count"));
    }

    @Test
    @DisplayName("zero or negative sharding-count is rejected at init time")
    void initRejectsNonPositiveShardingCount() {
        Properties zero = new Properties();
        zero.setProperty(Crc32ShardingAlgorithm.SHARDING_COUNT_KEY, "0");
        assertThrows(IllegalArgumentException.class, () -> algorithm.init(zero));
        Properties negative = new Properties();
        negative.setProperty(Crc32ShardingAlgorithm.SHARDING_COUNT_KEY, "-4");
        assertThrows(IllegalArgumentException.class, () -> algorithm.init(negative));
    }

    @Test
    @DisplayName("digest is the standard CRC-32 check value shared with the Python middleware")
    void shardIndexUsesStandardCrc32CheckValue() {
        // 0xCBF43926 is the canonical CRC-32 (IEEE 802.3) check value for "123456789".
        assertEquals(0xCBF43926L, crc32("123456789"));
        assertEquals(6, Crc32ShardingAlgorithm.shardIndex("123456789", 16));
    }

    @Test
    @DisplayName("precise routing lands on med_message_{crc32(session_id) % 16}")
    void doShardingRoutesToExpectedTable() {
        for (String sessionId : List.of("sess-0001", "sess-0002", "0197f0c2-9f4c-7d21-8e1d-6f0a1b2c3d4e", "会话-中文")) {
            int expectedIndex = (int) (crc32(sessionId) % 16);
            assertEquals("med_message_" + expectedIndex, algorithm.doSharding(ACTUAL_TABLES, precise(sessionId)),
                    "unexpected route for " + sessionId);
        }
    }

    @Test
    @DisplayName("routing is deterministic across algorithm instances")
    void doShardingIsDeterministic() {
        Crc32ShardingAlgorithm other = new Crc32ShardingAlgorithm();
        other.init(new Properties());
        String sessionId = "sess-determinism";
        String first = algorithm.doSharding(ACTUAL_TABLES, precise(sessionId));
        assertEquals(first, algorithm.doSharding(ACTUAL_TABLES, precise(sessionId)));
        assertEquals(first, other.doSharding(ACTUAL_TABLES, precise(sessionId)));
    }

    @Test
    @DisplayName("random session ids spread over all 16 tables")
    void doShardingCoversEveryTable() {
        Set<String> hit = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            hit.add(algorithm.doSharding(ACTUAL_TABLES, precise(UUID.randomUUID().toString())));
        }
        assertEquals(16, hit.size());
        assertTrue(hit.containsAll(ACTUAL_TABLES));
    }

    @Test
    @DisplayName("custom sharding-count changes the modulus used for routing")
    void doShardingHonoursCustomShardingCount() {
        Properties props = new Properties();
        props.setProperty(Crc32ShardingAlgorithm.SHARDING_COUNT_KEY, "4");
        algorithm.init(props);
        List<String> tables = List.of("med_message_0", "med_message_1", "med_message_2", "med_message_3");
        String sessionId = "sess-mod-4";
        assertEquals("med_message_" + (crc32(sessionId) % 4), algorithm.doSharding(tables, precise(sessionId)));
    }

    @Test
    @DisplayName("zero-padded table suffixes are matched through DataNodeInfo")
    void doShardingMatchesPaddedSuffix() {
        DataNodeInfo padded = new DataNodeInfo("med_message_", 2, '0');
        List<String> tables = IntStream.range(0, 16)
                .mapToObj(i -> String.format("med_message_%02d", i))
                .toList();
        String sessionId = "sess-padded";
        int expectedIndex = (int) (crc32(sessionId) % 16);
        String actual = algorithm.doSharding(tables,
                new PreciseShardingValue<>(LOGIC_TABLE, SHARDING_COLUMN, padded, sessionId));
        assertEquals(String.format("med_message_%02d", expectedIndex), actual);
    }

    @Test
    @DisplayName("null sharding value is rejected instead of silently mis-routing")
    void doShardingRejectsNullValue() {
        PreciseShardingValue<Comparable<?>> nullValue =
                new PreciseShardingValue<>(LOGIC_TABLE, SHARDING_COLUMN, NODE_INFO, null);
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> algorithm.doSharding(ACTUAL_TABLES, nullValue)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> algorithm.doSharding(ACTUAL_TABLES, (PreciseShardingValue<Comparable<?>>) null)));
    }

    @Test
    @DisplayName("missing target table surfaces as a configuration error")
    void doShardingThrowsWhenNoTargetMatches() {
        String sessionId = "sess-0001";
        int expectedIndex = (int) (crc32(sessionId) % 16);
        List<String> incomplete = ACTUAL_TABLES.stream()
                .filter(each -> !each.equals("med_message_" + expectedIndex))
                .toList();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> algorithm.doSharding(incomplete, precise(sessionId)));
        assertTrue(ex.getMessage().contains(String.valueOf(expectedIndex)));
        assertThrows(IllegalStateException.class, () -> algorithm.doSharding(List.of(), precise(sessionId)));
    }

    @Test
    @DisplayName("range predicates fan out to every table because crc32 destroys ordering")
    void rangeShardingReturnsAllTables() {
        RangeShardingValue<Comparable<?>> rangeValue = new RangeShardingValue<>(
                LOGIC_TABLE, SHARDING_COLUMN, NODE_INFO, Range.closed("sess-0001", "sess-9999"));
        Collection<String> actual = algorithm.doSharding(ACTUAL_TABLES, rangeValue);
        assertNotNull(actual);
        assertEquals(16, actual.size());
        assertTrue(actual.containsAll(ACTUAL_TABLES));
        assertSame(ACTUAL_TABLES, actual);
    }

    @Test
    @DisplayName("range sharding with no configured tables yields an empty result")
    void rangeShardingWithNullTargetsReturnsEmpty() {
        RangeShardingValue<Comparable<?>> rangeValue = new RangeShardingValue<>(
                LOGIC_TABLE, SHARDING_COLUMN, NODE_INFO, Range.all());
        assertTrue(algorithm.doSharding(null, rangeValue).isEmpty());
    }

    @Test
    @DisplayName("shardIndex validates its inputs")
    void shardIndexRejectsInvalidArguments() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> Crc32ShardingAlgorithm.shardIndex(null, 16)),
                () -> assertThrows(IllegalArgumentException.class, () -> Crc32ShardingAlgorithm.shardIndex("", 16)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> Crc32ShardingAlgorithm.shardIndex("sess-0001", 0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> Crc32ShardingAlgorithm.shardIndex("sess-0001", -1)));
    }

    @Test
    @DisplayName("shardIndex always stays inside [0, shardingCount)")
    void shardIndexStaysInRange() {
        for (int i = 0; i < 500; i++) {
            int index = Crc32ShardingAlgorithm.shardIndex(UUID.randomUUID().toString(), 16);
            assertTrue(index >= 0 && index < 16, "index out of range: " + index);
        }
    }
}
