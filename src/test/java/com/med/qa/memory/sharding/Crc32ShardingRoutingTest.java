package com.med.qa.memory.sharding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end verification that ShardingSphere-JDBC, driven only by YAML plus the
 * {@link Crc32ShardingAlgorithm} plugin, transparently routes {@code med_message} traffic to
 * {@code med_message_{crc32(session_id) % 16}}.
 *
 * <p>MySQL is replaced by an in-memory H2 schema running in MySQL compatibility mode, so the test
 * needs no external middleware. Only the data source differs from production — the sharding rules
 * are identical (asserted by {@code ShardingRuleConfigTest}).</p>
 */
class Crc32ShardingRoutingTest {

    private static final String H2_URL =
            "jdbc:h2:mem:med_qa_sharding;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;"
                    + "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

    private static final String SHARDING_URL = "jdbc:shardingsphere:classpath:sharding/med-sharding-h2.yaml";

    private static final int SHARDING_COUNT = 16;

    private static final String INSERT_SQL = "INSERT INTO med_message "
            + "(message_id, session_id, tenant_id, dept_id, patient_id, role, content, token_count, masked, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /** Held for the whole class so the in-memory database is not discarded between tests. */
    private static Connection h2KeepAlive;

    @BeforeAll
    static void createShardedSchema() throws SQLException {
        h2KeepAlive = DriverManager.getConnection(H2_URL, "sa", "");
        try (Statement statement = h2KeepAlive.createStatement()) {
            for (int i = 0; i < SHARDING_COUNT; i++) {
                statement.execute("DROP TABLE IF EXISTS med_message_" + i);
                statement.execute("CREATE TABLE med_message_" + i + " ("
                        + "message_id VARCHAR(64) NOT NULL PRIMARY KEY, "
                        + "session_id VARCHAR(64) NOT NULL, "
                        + "tenant_id VARCHAR(64) NOT NULL, "
                        + "dept_id VARCHAR(64) NOT NULL, "
                        + "patient_id VARCHAR(64) NOT NULL, "
                        + "role TINYINT NOT NULL, "
                        + "content VARBINARY(60000) NOT NULL, "
                        + "token_count INT NOT NULL, "
                        + "masked BOOLEAN NOT NULL, "
                        + "created_at BIGINT NOT NULL)");
            }
        }
    }

    @AfterAll
    static void dropShardedSchema() throws SQLException {
        try (Statement statement = h2KeepAlive.createStatement()) {
            for (int i = 0; i < SHARDING_COUNT; i++) {
                statement.execute("DROP TABLE IF EXISTS med_message_" + i);
            }
        } finally {
            h2KeepAlive.close();
        }
    }

    private static void insertMessage(final Connection connection, final String messageId, final String sessionId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            statement.setString(1, messageId);
            statement.setString(2, sessionId);
            statement.setString(3, "tenant-a");
            statement.setString(4, "dept-cardiology");
            statement.setString(5, "patient-001");
            statement.setInt(6, 0);
            statement.setBytes(7, new byte[] {0x08, 0x01});
            statement.setInt(8, 12);
            statement.setBoolean(9, false);
            statement.setLong(10, 1_753_900_000_000L);
            assertEquals(1, statement.executeUpdate());
        }
    }

    /** Counts rows in one physical table using a direct H2 connection that bypasses ShardingSphere. */
    private static int countInPhysicalTable(final int index, final String sessionId) throws SQLException {
        try (PreparedStatement statement = h2KeepAlive
                .prepareStatement("SELECT COUNT(*) FROM med_message_" + index + " WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getInt(1);
            }
        }
    }

    private static List<Integer> physicalTablesHolding(final String sessionId) throws SQLException {
        List<Integer> hit = new ArrayList<>();
        for (int i = 0; i < SHARDING_COUNT; i++) {
            if (countInPhysicalTable(i, sessionId) > 0) {
                hit.add(i);
            }
        }
        return hit;
    }

    @Test
    @DisplayName("insert through the logic table lands in exactly the crc32-selected physical table")
    void insertIsRoutedToSingleCrc32Table() throws SQLException {
        String sessionId = "sess-routing-" + UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(SHARDING_URL)) {
            insertMessage(connection, UUID.randomUUID().toString(), sessionId);
        }
        int expected = Crc32ShardingAlgorithm.shardIndex(sessionId, SHARDING_COUNT);
        assertEquals(List.of(expected), physicalTablesHolding(sessionId),
                "row must exist only in med_message_" + expected);
    }

    @Test
    @DisplayName("select by session_id reads back through the same route")
    void selectByShardingKeyReturnsRoutedRow() throws SQLException {
        String sessionId = "sess-select-" + UUID.randomUUID();
        String messageId = UUID.randomUUID().toString();
        try (Connection connection = DriverManager.getConnection(SHARDING_URL)) {
            insertMessage(connection, messageId, sessionId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT message_id, dept_id, token_count FROM med_message WHERE session_id = ?")) {
                statement.setString(1, sessionId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next(), "routed select must find the inserted row");
                    assertEquals(messageId, resultSet.getString("message_id"));
                    assertEquals("dept-cardiology", resultSet.getString("dept_id"));
                    assertEquals(12, resultSet.getInt("token_count"));
                    assertFalse(resultSet.next(), "only one row expected");
                }
            }
        }
    }

    @Test
    @DisplayName("many sessions are spread over the 16 tables and every row stays addressable")
    void manySessionsSpreadAcrossShards() throws SQLException {
        List<String> sessionIds = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(SHARDING_URL)) {
            for (int i = 0; i < 64; i++) {
                String sessionId = "sess-spread-" + i + "-" + UUID.randomUUID();
                sessionIds.add(sessionId);
                insertMessage(connection, UUID.randomUUID().toString(), sessionId);
            }
        }
        for (String sessionId : sessionIds) {
            int expected = Crc32ShardingAlgorithm.shardIndex(sessionId, SHARDING_COUNT);
            assertEquals(List.of(expected), physicalTablesHolding(sessionId));
        }
        long distinctShards = sessionIds.stream()
                .map(each -> Crc32ShardingAlgorithm.shardIndex(each, SHARDING_COUNT))
                .distinct()
                .count();
        assertTrue(distinctShards > 1, "64 sessions must not collapse into a single shard");
    }

    @Test
    @DisplayName("querying an unknown session id routes cleanly and returns no rows")
    void selectUnknownSessionReturnsEmptyResult() throws SQLException {
        try (Connection connection = DriverManager.getConnection(SHARDING_URL);
             PreparedStatement statement =
                     connection.prepareStatement("SELECT message_id FROM med_message WHERE session_id = ?")) {
            statement.setString(1, "sess-absent-" + UUID.randomUUID());
            try (ResultSet resultSet = statement.executeQuery()) {
                assertFalse(resultSet.next(), "no rows expected for an unknown session");
            }
        }
    }

    @Test
    @DisplayName("inserting the same message id twice violates the routed primary key")
    void duplicateMessageIdIsRejectedByRoutedTable() throws SQLException {
        String sessionId = "sess-duplicate-" + UUID.randomUUID();
        String messageId = UUID.randomUUID().toString();
        try (Connection connection = DriverManager.getConnection(SHARDING_URL)) {
            insertMessage(connection, messageId, sessionId);
            SQLException ex = org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                    () -> insertMessage(connection, messageId, sessionId));
            assertTrue(ex.getMessage().toLowerCase().contains("unique")
                            || ex.getMessage().toLowerCase().contains("duplicate")
                            || ex.getMessage().toLowerCase().contains("primary"),
                    "expected a primary key violation but was: " + ex.getMessage());
        }
    }
}
