package com.med.qa.memory.sharding;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.Properties;
import java.util.zip.CRC32;

import org.apache.shardingsphere.infra.datanode.DataNodeInfo;
import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.StandardShardingAlgorithm;

/**
 * ShardingSphere table sharding algorithm plugin implementing the unified storage spec rule
 * {@code med_message_{crc32(session_id) % 16}}.
 *
 * <p>This class only answers the question &quot;which physical table does this sharding key belong
 * to&quot;. SQL rewriting, routing, result merging and connection management are entirely handled by
 * ShardingSphere-JDBC — no hand written routing exists in this project.</p>
 *
 * <p>The digest is the standard CRC-32 (IEEE 802.3) checksum over the UTF-8 bytes of the sharding
 * key, which is byte-for-byte identical to Python's {@code zlib.crc32(session_id.encode())}. That
 * equality is what allows the Java service and the external Python storage middleware to resolve
 * the very same physical table for a given session.</p>
 *
 * <p>Registered as an SPI plugin under
 * {@code META-INF/services/org.apache.shardingsphere.sharding.spi.ShardingAlgorithm} and referenced
 * from YAML by {@link #TYPE}:</p>
 *
 * <pre>
 * shardingAlgorithms:
 *   med_message_crc32_mod:
 *     type: MED_CRC32_MOD
 *     props:
 *       sharding-count: 16
 * </pre>
 */
public final class Crc32ShardingAlgorithm implements StandardShardingAlgorithm<Comparable<?>> {

    /** SPI type referenced by {@code type:} in the ShardingSphere YAML rule configuration. */
    public static final String TYPE = "MED_CRC32_MOD";

    /** Property key carrying the number of physical tables. */
    public static final String SHARDING_COUNT_KEY = "sharding-count";

    /** Table count mandated by the unified storage spec when the property is absent. */
    public static final int DEFAULT_SHARDING_COUNT = 16;

    private static final char SUFFIX_SEPARATOR = '_';

    private int shardingCount = DEFAULT_SHARDING_COUNT;

    /**
     * Initializes the algorithm from the YAML {@code props} block.
     *
     * @param props algorithm properties; may be {@code null} or empty, in which case
     *              {@link #DEFAULT_SHARDING_COUNT} is used
     * @throws IllegalArgumentException if {@code sharding-count} is not a positive integer
     */
    @Override
    public void init(final Properties props) {
        if (null == props) {
            this.shardingCount = DEFAULT_SHARDING_COUNT;
            return;
        }
        String raw = props.getProperty(SHARDING_COUNT_KEY);
        if (null == raw || raw.isBlank()) {
            this.shardingCount = DEFAULT_SHARDING_COUNT;
            return;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    "sharding property '" + SHARDING_COUNT_KEY + "' must be an integer, but was: " + raw, ex);
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException(
                    "sharding property '" + SHARDING_COUNT_KEY + "' must be positive, but was: " + parsed);
        }
        this.shardingCount = parsed;
    }

    /**
     * Routes an equality predicate (for example {@code WHERE session_id = ?}) to a single table.
     *
     * @param availableTargetNames every physical table declared by {@code actualDataNodes}
     * @param shardingValue        the sharding column value carried by the SQL statement
     * @return the single physical table name owning the given sharding key
     * @throws IllegalArgumentException if the sharding value is {@code null} or blank
     * @throws IllegalStateException    if no configured table matches the computed shard index
     */
    @Override
    public String doSharding(final Collection<String> availableTargetNames,
                             final PreciseShardingValue<Comparable<?>> shardingValue) {
        if (null == shardingValue || null == shardingValue.getValue()) {
            throw new IllegalArgumentException("sharding value must not be null for crc32 table routing");
        }
        String shardingKey = String.valueOf(shardingValue.getValue());
        int index = shardIndex(shardingKey, shardingCount);
        return matchTargetName(availableTargetNames, index, shardingValue.getDataNodeInfo());
    }

    /**
     * Handles range predicates (for example {@code BETWEEN} / {@code &gt;}) on the sharding column.
     *
     * <p>A CRC-32 digest destroys ordering, so a value range cannot be pruned: every physical table
     * is returned and ShardingSphere fans the query out and merges the results.</p>
     *
     * @param availableTargetNames every physical table declared by {@code actualDataNodes}
     * @param shardingValue        the sharding column range carried by the SQL statement
     * @return all available table names, never {@code null}
     */
    @Override
    public Collection<String> doSharding(final Collection<String> availableTargetNames,
                                         final RangeShardingValue<Comparable<?>> shardingValue) {
        return null == availableTargetNames ? Collections.emptyList() : availableTargetNames;
    }

    /**
     * Returns the SPI type used to reference this plugin from YAML.
     *
     * @return {@value #TYPE}
     */
    @Override
    public String getType() {
        return TYPE;
    }

    /**
     * Returns the effective number of physical tables this algorithm routes across.
     *
     * @return positive table count, {@value #DEFAULT_SHARDING_COUNT} unless overridden by props
     */
    public int getShardingCount() {
        return shardingCount;
    }

    /**
     * Computes {@code crc32(shardingKey) % shardingCount} — the canonical shard index shared with
     * the external Python storage middleware.
     *
     * @param shardingKey   sharding column value, typically a session id
     * @param shardingCount number of physical tables, must be positive
     * @return shard index in {@code [0, shardingCount)}
     * @throws IllegalArgumentException if the key is {@code null}/blank or the count is not positive
     */
    public static int shardIndex(final String shardingKey, final int shardingCount) {
        if (null == shardingKey || shardingKey.isEmpty()) {
            throw new IllegalArgumentException("sharding key must not be null or empty");
        }
        if (shardingCount <= 0) {
            throw new IllegalArgumentException("sharding count must be positive, but was: " + shardingCount);
        }
        CRC32 crc32 = new CRC32();
        crc32.update(shardingKey.getBytes(StandardCharsets.UTF_8));
        return (int) (crc32.getValue() % shardingCount);
    }

    private String matchTargetName(final Collection<String> availableTargetNames,
                                   final int index,
                                   final DataNodeInfo dataNodeInfo) {
        if (null == availableTargetNames || availableTargetNames.isEmpty()) {
            throw new IllegalStateException("no actual data nodes configured for crc32 table routing");
        }
        String suffix = String.valueOf(index);
        if (null != dataNodeInfo) {
            String expected = dataNodeInfo.getPrefix() + pad(suffix, dataNodeInfo);
            for (String each : availableTargetNames) {
                if (each.equals(expected)) {
                    return each;
                }
            }
        }
        String fallbackSuffix = SUFFIX_SEPARATOR + suffix;
        for (String each : availableTargetNames) {
            if (each.endsWith(fallbackSuffix)) {
                return each;
            }
        }
        throw new IllegalStateException(
                "no actual table matches shard index " + index + " among " + availableTargetNames);
    }

    private String pad(final String suffix, final DataNodeInfo dataNodeInfo) {
        if (suffix.length() >= dataNodeInfo.getSuffixMinLength()) {
            return suffix;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = suffix.length(); i < dataNodeInfo.getSuffixMinLength(); i++) {
            builder.append(dataNodeInfo.getPaddingChar());
        }
        return builder.append(suffix).toString();
    }
}
