package com.med.qa.memory.sharding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.shardingsphere.infra.spi.type.typed.TypedSPILoader;
import org.apache.shardingsphere.sharding.spi.ShardingAlgorithm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Guards the ShardingSphere configuration against drift from the unified storage spec:
 * 16 actual data nodes named {@code med_message_{0..15}}, sharded by {@code session_id}
 * through the {@code MED_CRC32_MOD} plugin.
 */
class ShardingRuleConfigTest {

    private static final String MAIN_CONFIG = "sharding/med-sharding.yaml";

    private static final String TEST_CONFIG = "sharding/med-sharding-h2.yaml";

    private static final String SPI_RESOURCE =
            "META-INF/services/org.apache.shardingsphere.sharding.spi.ShardingAlgorithm";

    private static String readResource(final String name) throws IOException {
        try (InputStream in = ShardingRuleConfigTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, "classpath resource not found: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Loads a ShardingSphere YAML file with the {@code !SHARDING} and {@code !SINGLE} rule tags
     * stripped, so it can be inspected by a plain safe SnakeYAML constructor. The production config
     * carries both a sharding rule (for {@code med_message}) and a single-table rule (for
     * {@code med_session}); the tags are only syntactic markers here, the structure is what we assert.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(final String name) throws IOException {
        String sanitized = readResource(name).replace("!SHARDING", "").replace("!SINGLE", "");
        return (Map<String, Object>) new Yaml(new SafeConstructor(new LoaderOptions())).load(sanitized);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> shardingRule(final Map<String, Object> root) {
        List<Object> rules = (List<Object>) root.get("rules");
        assertNotNull(rules, "rules block missing");
        return rules.stream()
                .map(rule -> (Map<String, Object>) rule)
                .filter(rule -> rule.containsKey("shardingAlgorithms"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("sharding rule (with shardingAlgorithms) not found"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> medMessageTable(final Map<String, Object> root) {
        Map<String, Object> tables = (Map<String, Object>) shardingRule(root).get("tables");
        assertNotNull(tables, "tables block missing");
        assertTrue(tables.containsKey("med_message"), "med_message logic table missing");
        return (Map<String, Object>) tables.get("med_message");
    }

    @Test
    @DisplayName("production config declares the 16 spec-mandated data nodes")
    void mainConfigDeclaresSixteenActualDataNodes() throws IOException {
        Map<String, Object> table = medMessageTable(loadYaml(MAIN_CONFIG));
        assertEquals("med_ds.med_message_${0..15}", table.get("actualDataNodes"));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("production config shards med_message by session_id via the crc32 plugin")
    void mainConfigUsesSessionIdWithCrc32Algorithm() throws IOException {
        Map<String, Object> root = loadYaml(MAIN_CONFIG);
        Map<String, Object> strategy =
                (Map<String, Object>) ((Map<String, Object>) medMessageTable(root).get("tableStrategy")).get("standard");
        assertNotNull(strategy, "standard table strategy missing");
        assertEquals("session_id", strategy.get("shardingColumn"));
        assertEquals("med_message_crc32_mod", strategy.get("shardingAlgorithmName"));

        Map<String, Object> algorithms = (Map<String, Object>) shardingRule(root).get("shardingAlgorithms");
        Map<String, Object> algorithm = (Map<String, Object>) algorithms.get("med_message_crc32_mod");
        assertNotNull(algorithm, "med_message_crc32_mod algorithm missing");
        assertEquals(Crc32ShardingAlgorithm.TYPE, algorithm.get("type"));
        Map<String, Object> props = (Map<String, Object>) algorithm.get("props");
        assertEquals(Integer.valueOf(Crc32ShardingAlgorithm.DEFAULT_SHARDING_COUNT),
                props.get(Crc32ShardingAlgorithm.SHARDING_COUNT_KEY));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("production data source targets MySQL through Hikari with env placeholders")
    void mainConfigTargetsMysqlDataSource() throws IOException {
        Map<String, Object> root = loadYaml(MAIN_CONFIG);
        assertEquals("med_qa", root.get("databaseName"));
        Map<String, Object> dataSources = (Map<String, Object>) root.get("dataSources");
        Map<String, Object> medDs = (Map<String, Object>) dataSources.get("med_ds");
        assertNotNull(medDs, "med_ds data source missing");
        assertEquals("com.zaxxer.hikari.HikariDataSource", medDs.get("dataSourceClassName"));
        assertEquals("com.mysql.cj.jdbc.Driver", medDs.get("driverClassName"));
        assertTrue(String.valueOf(medDs.get("jdbcUrl")).startsWith("jdbc:mysql://"));
        assertTrue(String.valueOf(medDs.get("jdbcUrl")).contains("$${MED_MYSQL_HOST::127.0.0.1}"));
    }

    @Test
    @DisplayName("the h2 test config mirrors the production sharding rules exactly")
    void testConfigMirrorsProductionShardingRules() throws IOException {
        Map<String, Object> main = loadYaml(MAIN_CONFIG);
        Map<String, Object> test = loadYaml(TEST_CONFIG);
        assertEquals(shardingRule(main), shardingRule(test),
                "h2 test config must only swap the data source, never the sharding rules");
    }

    @Test
    @DisplayName("spring wires its datasource through the ShardingSphere driver")
    void applicationYamlUsesShardingSphereDriver() throws IOException {
        String applicationYaml = readResource("application.yml");
        assertTrue(applicationYaml.contains("org.apache.shardingsphere.driver.ShardingSphereDriver"),
                "spring.datasource.driver-class-name must be the ShardingSphere driver");
        assertTrue(applicationYaml.contains("jdbc:shardingsphere:classpath:" + MAIN_CONFIG),
                "spring.datasource.url must point at the sharding rule file");
        assertTrue(applicationYaml.contains("placeholder-type=environment"),
                "env placeholders in the sharding yaml require placeholder-type=environment");
    }

    @Test
    @DisplayName("the algorithm is registered as a ShardingSphere SPI plugin")
    void algorithmIsRegisteredAsSpiPlugin() throws IOException {
        String spiFile = readResource(SPI_RESOURCE);
        assertEquals(Crc32ShardingAlgorithm.class.getName(), spiFile.trim());
    }

    @Test
    @DisplayName("ShardingSphere resolves MED_CRC32_MOD through its own SPI loader")
    void shardingSphereResolvesAlgorithmByType() {
        Properties props = new Properties();
        props.setProperty(Crc32ShardingAlgorithm.SHARDING_COUNT_KEY, "16");
        ShardingAlgorithm resolved =
                TypedSPILoader.getService(ShardingAlgorithm.class, Crc32ShardingAlgorithm.TYPE, props);
        Crc32ShardingAlgorithm algorithm = assertInstanceOf(Crc32ShardingAlgorithm.class, resolved);
        assertEquals(16, algorithm.getShardingCount());
        assertFalse(algorithm.isDefault());
    }

    @Test
    @DisplayName("an unknown algorithm type is rejected by the SPI loader")
    void unknownAlgorithmTypeIsRejected() {
        assertThrows(RuntimeException.class,
                () -> TypedSPILoader.getService(ShardingAlgorithm.class, "MED_NO_SUCH_ALGORITHM", new Properties()));
    }
}
