package com.med.qa.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.enums.SessionStatus;
import com.med.qa.mapper.typehandler.SessionStatusTypeHandler;
import java.io.InputStream;
import java.sql.Connection;
import java.util.List;
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
 * End-to-end verification of the MyBatis {@link ChatSessionMapper} against the ShardingSphere-JDBC
 * {@code med_session} single table.
 *
 * <p>The 16 {@code med_message} shards and the {@code med_session} table are created by Flyway against
 * an in-memory H2 schema (MySQL compatibility mode). All session CRUD then runs through ShardingSphere
 * (its {@code SINGLE} rule routes {@code med_session} to the one data source), proving the production
 * DDL and the {@code SessionStatusTypeHandler} without any real middleware.</p>
 *
 * <p>The shared H2 schema persists across the whole class, so every test that asserts an absolute count
 * or a strict ordering uses its own tenant/department scope to stay isolated from the rows other tests
 * insert.</p>
 */
class ChatSessionMapperShardingTest {

    private static final String H2_URL =
            "jdbc:h2:mem:med_qa_d20;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;"
                    + "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

    private static final String SHARDING_URL = "jdbc:shardingsphere:classpath:sharding/med-sharding-d20.yaml";

    private static final String TENANT = "tenant-sess";

    private static final String DEPT = "dept-cardiology";

    private static final String PATIENT = "patient-001";

    /** Held open for the whole class so the in-memory H2 schema survives across tests. */
    private static Connection rawH2;

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void setUp() throws Exception {
        DataSource rawH2Ds = new SimpleDriverDataSource(new org.h2.Driver(), H2_URL, "sa", "");
        DataSource shardingDs = new SimpleDriverDataSource(
                new org.apache.shardingsphere.driver.ShardingSphereDriver(), SHARDING_URL);

        // Flyway runs against the raw H2 schema (the same in-memory database the ShardingSphere data
        // source points at), validating the production V1 + V2 DDL without MySQL.
        Flyway.configure().dataSource(rawH2Ds).locations("classpath:db/migration").load().migrate();

        rawH2 = rawH2Ds.getConnection();
        rawH2.setAutoCommit(true);

        Environment environment = new Environment("d20", new JdbcTransactionFactory(), shardingDs);
        Configuration configuration = new Configuration(environment);
        configuration.getTypeHandlerRegistry().register(SessionStatusTypeHandler.class);
        try (InputStream xml = ChatSessionMapperShardingTest.class
                .getResourceAsStream("/mapper/ChatSessionMapper.xml")) {
            new XMLMapperBuilder(xml, configuration, "ChatSessionMapper.xml",
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

    private static ChatSessionDO sampleSession(String tenant, String dept, long createdAt) {
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId("sess-" + UUID.randomUUID());
        session.setTenantId(tenant);
        session.setDeptId(dept);
        session.setPatientId(PATIENT);
        session.setTitle("主诉胸闷");
        session.setStatus(SessionStatus.ACTIVE);
        session.setCreatedAt(createdAt);
        session.setUpdatedAt(createdAt);
        return session;
    }

    @Test
    @DisplayName("flyway migration creates the med_session table on the H2 schema")
    void flywayCreatesSessionTable() throws Exception {
        try (var ps = rawH2.prepareStatement(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'med_session'")) {
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getLong(1), "med_session table must exist");
            }
        }
    }

    @Test
    @DisplayName("insert through the logical table is persisted and read back with its status code")
    void insertAndSelectByIdRoundTrips() {
        ChatSessionDO session = sampleSession(TENANT, DEPT, 1_735_000_000_000L);
        try (SqlSession sql = sqlSessionFactory.openSession(true)) {
            assertEquals(1, sql.getMapper(ChatSessionMapper.class).insert(session));
        }
        try (SqlSession sql = sqlSessionFactory.openSession(true)) {
            ChatSessionMapper mapper = sql.getMapper(ChatSessionMapper.class);
            ChatSessionDO loaded = mapper.selectById(session.getSessionId());
            assertNotNull(loaded);
            assertEquals(session.getSessionId(), loaded.getSessionId());
            assertEquals(TENANT, loaded.getTenantId());
            assertEquals(DEPT, loaded.getDeptId());
            assertEquals(PATIENT, loaded.getPatientId());
            assertEquals("主诉胸闷", loaded.getTitle());
            assertEquals(SessionStatus.ACTIVE, loaded.getStatus());
            assertEquals(1_735_000_000_000L, loaded.getCreatedAt());
        }
    }

    @Test
    @DisplayName("selectById for an unknown id returns null")
    void selectByIdUnknownReturnsNull() {
        try (SqlSession sql = sqlSessionFactory.openSession(true)) {
            assertNull(sql.getMapper(ChatSessionMapper.class)
                    .selectById("missing-" + UUID.randomUUID()));
        }
    }

    @Test
    @DisplayName("updateStatus is a compare-and-set: it applies only while the expected status holds")
    void updateStatusIsCompareAndSet() {
        ChatSessionDO session = sampleSession(TENANT, DEPT, 1_735_000_000_111L);
        try (SqlSession sql = sqlSessionFactory.openSession(true)) {
            sql.getMapper(ChatSessionMapper.class).insert(session);
        }
        try (SqlSession sql = sqlSessionFactory.openSession(true)) {
            ChatSessionMapper mapper = sql.getMapper(ChatSessionMapper.class);
            // ACTIVE -> CLOSED with expected ACTIVE: applies (1 row).
            assertEquals(1, mapper.updateStatus(
                    session.getSessionId(), SessionStatus.CLOSED, SessionStatus.ACTIVE, 1_735_000_000_222L));
            // ACTIVE -> ARCHIVED with expected ACTIVE again: row is now CLOSED, so no row matches (0).
            assertEquals(0, mapper.updateStatus(
                    session.getSessionId(), SessionStatus.ARCHIVED, SessionStatus.ACTIVE, 1_735_000_000_333L));
            assertEquals(SessionStatus.CLOSED, mapper.selectById(session.getSessionId()).getStatus());
        }
    }

    @Test
    @DisplayName("selectPage returns sessions newest-first and honours the offset/limit window")
    void selectPageOrdersAndPaginates() {
        String tenant = "tenant-page";
        String dept = "dept-page";
        for (long ts = 1; ts <= 5; ts++) {
            ChatSessionDO session = sampleSession(tenant, dept, 1_700_000_000_000L + ts);
            try (SqlSession sql = sqlSessionFactory.openSession(true)) {
                sql.getMapper(ChatSessionMapper.class).insert(session);
            }
        }
        try (SqlSession sql = sqlSessionFactory.openSession(true)) {
            ChatSessionMapper mapper = sql.getMapper(ChatSessionMapper.class);
            List<ChatSessionDO> firstPage = mapper.selectPage(tenant, dept, null, null, 0, 2);
            assertEquals(2, firstPage.size());
            // newest first: the last inserted (ts=5) must lead.
            assertEquals(1_700_000_000_005L, firstPage.get(0).getCreatedAt());

            List<ChatSessionDO> secondPage = mapper.selectPage(tenant, dept, null, null, 2, 2);
            assertEquals(2, secondPage.size());
            assertEquals(1_700_000_000_003L, secondPage.get(0).getCreatedAt());
        }
    }

    @Test
    @DisplayName("countByCondition narrows by patient and by status")
    void countByConditionFilters() {
        String tenant = "tenant-count";
        String dept = "dept-count";
        ChatSessionDO active = sampleSession(tenant, dept, 1_700_000_001_000L);
        active.setPatientId("patient-A");
        ChatSessionDO closed = sampleSession(tenant, dept, 1_700_000_002_000L);
        closed.setPatientId("patient-B");
        closed.setStatus(SessionStatus.CLOSED);
        closed.setUpdatedAt(1_700_000_002_000L);
        try (SqlSession sql = sqlSessionFactory.openSession(true)) {
            ChatSessionMapper mapper = sql.getMapper(ChatSessionMapper.class);
            mapper.insert(active);
            mapper.insert(closed);
        }
        try (SqlSession sql = sqlSessionFactory.openSession(true)) {
            ChatSessionMapper mapper = sql.getMapper(ChatSessionMapper.class);
            assertEquals(2, mapper.countByCondition(tenant, dept, null, null));
            assertEquals(1, mapper.countByCondition(tenant, dept, "patient-A", null));
            assertEquals(1, mapper.countByCondition(tenant, dept, null, SessionStatus.CLOSED));
            assertEquals(0, mapper.countByCondition(tenant, dept, "patient-A", SessionStatus.CLOSED));
        }
    }
}
