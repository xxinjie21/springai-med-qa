package com.med.qa.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.enums.RoleType;
import com.med.qa.mapper.typehandler.MetadataTypeHandler;
import com.med.qa.mapper.typehandler.RoleTypeTypeHandler;
import com.med.qa.memory.sharding.Crc32ShardingAlgorithm;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * End-to-end verification of the MyBatis {@link ChatMessageMapper} over the ShardingSphere-JDBC
 * {@code med_message} logical table.
 *
 * <p>The 16 physical shards are created by Flyway against an in-memory H2 schema (MySQL compatibility
 * mode), then all CRUD goes through ShardingSphere so routing to
 * {@code med_message_{crc32(session_id) % 16}} is exercised without any real middleware.</p>
 *
 * <p>Direct queries against the underlying H2 connection prove that a row inserted through the logical
 * table actually lands in exactly the crc32-selected physical shard and nowhere else.</p>
 */
class ChatMessageMapperShardingTest {

    private static final String H2_URL =
            "jdbc:h2:mem:med_qa_d8;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;"
                    + "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

    private static final String SHARDING_URL = "jdbc:shardingsphere:classpath:sharding/med-sharding-d8.yaml";

    private static final int SHARD_COUNT = 16;

    private static DataSource shardingDs;

    /** Held open for the whole class so the in-memory H2 database (and its 16 shards) survive. */
    private static Connection rawH2;

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void setUp() throws Exception {
        DataSource rawH2Ds = new SimpleDriverDataSource(new org.h2.Driver(), H2_URL, "sa", "");
        shardingDs = new SimpleDriverDataSource(
                new org.apache.shardingsphere.driver.ShardingSphereDriver(), SHARDING_URL);

        // Flyway creates the 16 physical shards on the raw H2 schema (the same in-memory database the
        // ShardingSphere data source points at), validating the production DDL without MySQL.
        Flyway.configure().dataSource(rawH2Ds).locations("classpath:db/migration").load().migrate();

        rawH2 = rawH2Ds.getConnection();
        rawH2.setAutoCommit(true);

        Environment environment = new Environment("d8", new JdbcTransactionFactory(), shardingDs);
        Configuration configuration = new Configuration(environment);
        configuration.getTypeHandlerRegistry().register(RoleTypeTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(MetadataTypeHandler.class);
        try (InputStream xml = ChatMessageMapperShardingTest.class
                .getResourceAsStream("/mapper/ChatMessageMapper.xml")) {
            new XMLMapperBuilder(xml, configuration, "ChatMessageMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (rawH2 != null) {
            rawH2.close();
        }
    }

    private static ChatMessageDO sampleMessage(String sessionId, RoleType role) {
        return ChatMessageDO.builder()
                .messageId(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .tenantId("tenant-a")
                .deptId("dept-cardiology")
                .patientId("patient-001")
                .role(role)
                .content("患者主诉胸闷气短，持续两小时。")
                .tokenCount(24)
                .masked(false)
                .createdAt(1_735_000_000_000L + System.nanoTime() % 1000)
                .metadata(new LinkedHashMap<>(Map.of("source", "web", "channel", "app")))
                .build();
    }

    private static void assertRoutedToSingleShard(String sessionId, String messageId) throws Exception {
        int expected = Crc32ShardingAlgorithm.shardIndex(sessionId, SHARD_COUNT);
        for (int i = 0; i < SHARD_COUNT; i++) {
            long count = countInPhysical(i, messageId);
            if (i == expected) {
                assertEquals(1, count, "row must exist exactly once in med_message_" + i);
            } else {
                assertEquals(0, count, "row must NOT exist in med_message_" + i);
            }
        }
    }

    private static long countInPhysical(int shard, String messageId) throws Exception {
        try (PreparedStatement ps = rawH2.prepareStatement(
                "SELECT COUNT(*) FROM med_message_" + shard + " WHERE message_id = ?")) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    @Test
    @DisplayName("flyway migration creates all 16 physical shards on the H2 schema")
    void flywayCreatesSixteenShards() throws Exception {
        try (PreparedStatement ps = rawH2.prepareStatement(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME LIKE 'med_message_%'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(SHARD_COUNT, rs.getLong(1), "exactly 16 med_message_* tables expected");
            }
        }
    }

    @Test
    @DisplayName("insert through the logical table lands in exactly the crc32-selected physical shard")
    void insertIsRoutedToSingleCrc32Shard() throws Exception {
        ChatMessageDO message = sampleMessage("sess-route-" + UUID.randomUUID(), RoleType.PATIENT);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            ChatMessageMapper mapper = session.getMapper(ChatMessageMapper.class);
            assertEquals(1, mapper.insert(message));
        }
        assertRoutedToSingleShard(message.getSessionId(), message.getMessageId());
    }

    @Test
    @DisplayName("selectById round-trips every column including role and metadata json")
    void selectByIdRoundTripsAllFields() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source", "web");
        metadata.put("note", "patient said \"pain\"");
        ChatMessageDO message = ChatMessageDO.builder()
                .messageId(UUID.randomUUID().toString())
                .sessionId("sess-read-" + UUID.randomUUID())
                .tenantId("tenant-b")
                .deptId("dept-neurology")
                .patientId("patient-002")
                .role(RoleType.ASSISTANT)
                .content("建议尽快做一次心电图检查。")
                .tokenCount(42)
                .masked(true)
                .createdAt(1_735_000_123_456L)
                .metadata(metadata)
                .build();

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            ChatMessageMapper mapper = session.getMapper(ChatMessageMapper.class);
            mapper.insert(message);
        }

        ChatMessageDO loaded;
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            loaded = session.getMapper(ChatMessageMapper.class).selectById(message.getMessageId());
        }
        assertNotNull(loaded);
        assertEquals(message.getMessageId(), loaded.getMessageId());
        assertEquals(message.getSessionId(), loaded.getSessionId());
        assertEquals(message.getTenantId(), loaded.getTenantId());
        assertEquals(message.getDeptId(), loaded.getDeptId());
        assertEquals(message.getPatientId(), loaded.getPatientId());
        assertEquals(RoleType.ASSISTANT, loaded.getRole());
        assertEquals("建议尽快做一次心电图检查。", loaded.getContent());
        assertEquals(42, loaded.getTokenCount());
        assertTrue(loaded.isMasked());
        assertEquals(1_735_000_123_456L, loaded.getCreatedAt());
        assertEquals(metadata, loaded.getMetadata());
    }

    @Test
    @DisplayName("selectById for an unknown id returns null")
    void selectByIdUnknownReturnsNull() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            ChatMessageMapper mapper = session.getMapper(ChatMessageMapper.class);
            assertNull(mapper.selectById("missing-" + UUID.randomUUID()));
        }
    }

    @Test
    @DisplayName("selectBySessionId returns every message of a session via the shard key")
    void selectBySessionIdReturnsSessionMessages() throws Exception {
        String sessionId = "sess-list-" + UUID.randomUUID();
        ChatMessageDO first = sampleMessage(sessionId, RoleType.PATIENT);
        ChatMessageDO second = sampleMessage(sessionId, RoleType.ASSISTANT);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            ChatMessageMapper mapper = session.getMapper(ChatMessageMapper.class);
            mapper.insert(first);
            mapper.insert(second);
        }
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            List<ChatMessageDO> messages = session.getMapper(ChatMessageMapper.class)
                    .selectBySessionId(sessionId);
            assertEquals(2, messages.size());
            assertTrue(messages.stream().anyMatch(m -> m.getMessageId().equals(first.getMessageId())));
            assertTrue(messages.stream().anyMatch(m -> m.getMessageId().equals(second.getMessageId())));
        }
        // both rows of the same session must share the same shard
        assertRoutedToSingleShard(sessionId, first.getMessageId());
        assertRoutedToSingleShard(sessionId, second.getMessageId());
    }

    @Test
    @DisplayName("selectBySessionIdOrderByCreatedAtAsc replays messages oldest-first")
    void selectBySessionOrderedByCreatedAt() {
        String sessionId = "sess-order-" + UUID.randomUUID();
        long base = 1_735_000_000_000L;
        ChatMessageDO older = ChatMessageDO.builder()
                .messageId(UUID.randomUUID().toString()).sessionId(sessionId)
                .tenantId("t").deptId("d").patientId("p").role(RoleType.PATIENT)
                .content("old").tokenCount(1).masked(false).createdAt(base).metadata(new LinkedHashMap<>()).build();
        ChatMessageDO newer = ChatMessageDO.builder()
                .messageId(UUID.randomUUID().toString()).sessionId(sessionId)
                .tenantId("t").deptId("d").patientId("p").role(RoleType.ASSISTANT)
                .content("new").tokenCount(2).masked(false).createdAt(base + 5000).metadata(new LinkedHashMap<>()).build();
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            ChatMessageMapper mapper = session.getMapper(ChatMessageMapper.class);
            mapper.insert(newer);
            mapper.insert(older);
        }
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            List<ChatMessageDO> messages = session.getMapper(ChatMessageMapper.class)
                    .selectBySessionIdOrderByCreatedAtAsc(sessionId);
            assertEquals(2, messages.size());
            assertEquals(older.getMessageId(), messages.get(0).getMessageId());
            assertEquals(newer.getMessageId(), messages.get(1).getMessageId());
        }
    }

    @Test
    @DisplayName("selectBySessionId for an unknown session returns an empty list")
    void selectBySessionIdUnknownReturnsEmpty() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            ChatMessageMapper mapper = session.getMapper(ChatMessageMapper.class);
            assertTrue(mapper.selectBySessionId("sess-absent-" + UUID.randomUUID()).isEmpty());
        }
    }

    @Test
    @DisplayName("updateMasked flips the masking flag on the correct row")
    void updateMaskedTogglesFlag() {
        ChatMessageDO message = sampleMessage("sess-update-" + UUID.randomUUID(), RoleType.DOCTOR);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            ChatMessageMapper mapper = session.getMapper(ChatMessageMapper.class);
            mapper.insert(message);
            assertEquals(1, mapper.updateMasked(message.getMessageId(), true));
        }
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            ChatMessageDO loaded = session.getMapper(ChatMessageMapper.class).selectById(message.getMessageId());
            assertNotNull(loaded);
            assertTrue(loaded.isMasked());
        }
    }

    @Test
    @DisplayName("deleteBySessionId removes every row of a session from its shard")
    void deleteBySessionIdRemovesShardRows() throws Exception {
        String sessionId = "sess-delete-" + UUID.randomUUID();
        ChatMessageDO first = sampleMessage(sessionId, RoleType.PATIENT);
        ChatMessageDO second = sampleMessage(sessionId, RoleType.SYSTEM);
        int shard = Crc32ShardingAlgorithm.shardIndex(sessionId, SHARD_COUNT);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            ChatMessageMapper mapper = session.getMapper(ChatMessageMapper.class);
            mapper.insert(first);
            mapper.insert(second);
        }
        assertEquals(2, countInPhysical(shard, first.getMessageId()) + countInPhysical(shard, second.getMessageId()));

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            assertEquals(2, session.getMapper(ChatMessageMapper.class).deleteBySessionId(sessionId));
        }
        assertEquals(0, countInPhysical(shard, first.getMessageId()));
        assertEquals(0, countInPhysical(shard, second.getMessageId()));
    }

    @Test
    @DisplayName("deleteById removes only the targeted row")
    void deleteByIdRemovesSingleRow() {
        ChatMessageDO message = sampleMessage("sess-delone-" + UUID.randomUUID(), RoleType.PATIENT);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            ChatMessageMapper mapper = session.getMapper(ChatMessageMapper.class);
            mapper.insert(message);
            assertEquals(0, mapper.deleteById("missing-" + UUID.randomUUID()));
            assertEquals(1, mapper.deleteById(message.getMessageId()));
            assertEquals(0, mapper.deleteById(message.getMessageId()));
        }
    }
}
