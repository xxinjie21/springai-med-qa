package com.med.qa.mapper;

import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.enums.SessionStatus;
import com.med.qa.mapper.typehandler.SessionStatusTypeHandler;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end verification of the retention statements added to {@link ChatSessionMapper} (D47)
 * against the production DDL, through ShardingSphere-JDBC.
 *
 * <p>These three statements are the only ones in the mapper that are deliberately <em>not</em> scoped
 * to a tenant/department, and the only ones that decide whether a clinical record changes state
 * without a human asking. Offline unit tests can only prove the service hands the right arguments to a
 * mock; what has to be proven here is what the SQL really does — that {@code updated_at <= cutoff}
 * really is the predicate, that the status comparison uses the numeric spec code, and that the
 * archiving statement refuses a session that was refreshed after the candidate query. A wrong
 * comparison operator in this statement is the difference between "abandoned sessions are archived"
 * and "the job that archives live consultations".</p>
 *
 * <p>The 16 {@code med_message} shards, {@code med_session} and the D47 retention index are created by
 * Flyway against an in-memory H2 schema (MySQL compatibility mode); every statement then runs through
 * ShardingSphere's {@code SINGLE} rule. The schema is shared by the whole class, so each test empties
 * {@code med_session} first and can therefore assert exact row counts.</p>
 */
class ChatSessionMapperRetentionShardingTest {

    private static final String H2_URL =
            "jdbc:h2:mem:med_qa_d47;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;"
                    + "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

    private static final String SHARDING_URL = "jdbc:shardingsphere:classpath:sharding/med-sharding-d47.yaml";

    /** Cutoff shared by every test, so the assertions can be exact. */
    private static final long CUTOFF = 1_735_000_000_000L;

    private static final String TENANT = "tenant-retention";

    private static final String DEPT = "dept-cardiology";

    private static Connection rawH2;

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void setUp() throws Exception {
        DataSource rawH2Ds = new SimpleDriverDataSource(new org.h2.Driver(), H2_URL, "sa", "");
        DataSource shardingDs = new SimpleDriverDataSource(
                new org.apache.shardingsphere.driver.ShardingSphereDriver(), SHARDING_URL);

        // Flyway runs against the raw H2 schema - the same in-memory database the ShardingSphere data
        // source points at - so V1..V4 (including the D47 retention index) are the production scripts.
        Flyway.configure().dataSource(rawH2Ds).locations("classpath:db/migration").load().migrate();

        rawH2 = rawH2Ds.getConnection();
        rawH2.setAutoCommit(true);

        Environment environment = new Environment("d47", new JdbcTransactionFactory(), shardingDs);
        Configuration configuration = new Configuration(environment);
        configuration.getTypeHandlerRegistry().register(SessionStatusTypeHandler.class);
        try (InputStream xml = ChatSessionMapperRetentionShardingTest.class
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

    @BeforeEach
    void emptySessionTable() throws Exception {
        try (Statement statement = rawH2.createStatement()) {
            statement.execute("DELETE FROM med_session");
        }
    }

    @Test
    @DisplayName("flyway V4 creates the retention index on (status, updated_at)")
    void flywayCreatesTheRetentionIndex() throws Exception {
        try (var ps = rawH2.prepareStatement(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES "
                        + "WHERE TABLE_NAME = 'med_session' AND INDEX_NAME = 'idx_med_session_retention'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getLong(1),
                        "without this index the cross-tenant sweep is a full table scan on every run");
            }
        }
    }

    @Test
    @DisplayName("the candidate query returns stale ACTIVE sessions only, oldest first")
    void selectStaleSessionsFiltersAndOrders() {
        insert(session("stale-oldest", SessionStatus.ACTIVE, CUTOFF - 3_000));
        insert(session("stale-newer", SessionStatus.ACTIVE, CUTOFF - 1_000));
        insert(session("boundary", SessionStatus.ACTIVE, CUTOFF));
        insert(session("fresh", SessionStatus.ACTIVE, CUTOFF + 1));
        insert(session("archived", SessionStatus.ARCHIVED, CUTOFF - 3_000));
        insert(session("closed", SessionStatus.CLOSED, CUTOFF - 3_000));

        List<ChatSessionDO> stale = inSession(mapper ->
                mapper.selectStaleSessions(SessionStatus.ACTIVE, CUTOFF, 10));

        assertEquals(List.of("stale-oldest", "stale-newer", "boundary"),
                stale.stream().map(ChatSessionDO::getSessionId).toList(),
                "only ACTIVE rows at or before the cutoff are candidates, and the longest idle comes first");
    }

    @Test
    @DisplayName("the candidate query honours its limit and still starts from the longest idle")
    void selectStaleSessionsHonoursTheLimit() {
        insert(session("stale-oldest", SessionStatus.ACTIVE, CUTOFF - 3_000));
        insert(session("stale-newer", SessionStatus.ACTIVE, CUTOFF - 1_000));

        List<ChatSessionDO> stale = inSession(mapper ->
                mapper.selectStaleSessions(SessionStatus.ACTIVE, CUTOFF, 1));

        assertEquals(List.of("stale-oldest"), stale.stream().map(ChatSessionDO::getSessionId).toList());
    }

    @Test
    @DisplayName("the count uses the same predicate as the selection")
    void countMatchesTheSelection() {
        insert(session("stale-1", SessionStatus.ACTIVE, CUTOFF - 3_000));
        insert(session("stale-2", SessionStatus.ACTIVE, CUTOFF));
        insert(session("fresh", SessionStatus.ACTIVE, CUTOFF + 1));
        insert(session("closed", SessionStatus.CLOSED, CUTOFF - 3_000));

        long count = inSession(mapper -> mapper.countStaleSessions(SessionStatus.ACTIVE, CUTOFF));

        assertEquals(2, count);
    }

    @Test
    @DisplayName("the sweep sees every tenant and department, because it has no request scope")
    void sweepSpansTenants() {
        insert(session("tenant-a-session", TENANT, DEPT, SessionStatus.ACTIVE, CUTOFF - 1_000));
        insert(session("tenant-b-session", "tenant-other", "dept-neurology", SessionStatus.ACTIVE,
                CUTOFF - 1_000));

        List<ChatSessionDO> stale = inSession(mapper ->
                mapper.selectStaleSessions(SessionStatus.ACTIVE, CUTOFF, 10));

        assertEquals(2, stale.size(),
                "a retention job has no principal to scope by; an abandoned session is abandoned everywhere");
    }

    @Test
    @DisplayName("archiving a stale session applies the transition and stamps updated_at")
    void updateStatusIfStaleArchivesAStaleSession() {
        insert(session("stale", SessionStatus.ACTIVE, CUTOFF - 5_000));

        int rows = inSession(mapper -> mapper.updateStatusIfStale("stale", SessionStatus.ARCHIVED,
                SessionStatus.ACTIVE, CUTOFF, CUTOFF + 42));

        assertEquals(1, rows);
        ChatSessionDO stored = inSession(mapper -> mapper.selectById("stale"));
        assertEquals(SessionStatus.ARCHIVED, stored.getStatus());
        assertEquals(CUTOFF + 42, stored.getUpdatedAt());
    }

    @Test
    @DisplayName("a session refreshed after the candidate query is refused, not archived")
    void updateStatusIfStaleRefusesARefreshedSession() {
        // The whole point of the D47 primitive: the staleness predicate travels inside the UPDATE, so a
        // patient who came back between the candidate query and the archive is safe. A plain
        // `status = ACTIVE` compare-and-set would archive this consultation.
        insert(session("refreshed", SessionStatus.ACTIVE, CUTOFF + 1));

        int rows = inSession(mapper -> mapper.updateStatusIfStale("refreshed", SessionStatus.ARCHIVED,
                SessionStatus.ACTIVE, CUTOFF, CUTOFF + 42));

        assertEquals(0, rows, "a session updated after the cutoff must never be archived by the sweep");
        assertEquals(SessionStatus.ACTIVE, inSession(mapper -> mapper.selectById("refreshed")).getStatus());
    }

    @Test
    @DisplayName("the boundary is inclusive: a session exactly at the cutoff is archivable")
    void updateStatusIfStaleAcceptsTheBoundary() {
        insert(session("boundary", SessionStatus.ACTIVE, CUTOFF));

        int rows = inSession(mapper -> mapper.updateStatusIfStale("boundary", SessionStatus.ARCHIVED,
                SessionStatus.ACTIVE, CUTOFF, CUTOFF + 42));

        assertEquals(1, rows, "the selection and the archive must agree on <=, or candidates never archive");
    }

    @Test
    @DisplayName("a session that is no longer ACTIVE is refused even when it is stale")
    void updateStatusIfStaleRefusesANonActiveSession() {
        insert(session("closed", SessionStatus.CLOSED, CUTOFF - 5_000));

        int rows = inSession(mapper -> mapper.updateStatusIfStale("closed", SessionStatus.ARCHIVED,
                SessionStatus.ACTIVE, CUTOFF, CUTOFF + 42));

        assertEquals(0, rows);
        assertEquals(SessionStatus.CLOSED, inSession(mapper -> mapper.selectById("closed")).getStatus());
    }

    @Test
    @DisplayName("an archived session leaves the candidate set, so a rerun does not see it again")
    void archivedSessionsLeaveTheCandidateSet() {
        insert(session("stale", SessionStatus.ACTIVE, CUTOFF - 5_000));

        inSession(mapper -> mapper.updateStatusIfStale("stale", SessionStatus.ARCHIVED,
                SessionStatus.ACTIVE, CUTOFF, CUTOFF + 42));

        assertTrue(inSession(mapper -> mapper.selectStaleSessions(SessionStatus.ACTIVE, CUTOFF, 10)).isEmpty());
        long remaining = inSession(mapper -> mapper.countStaleSessions(SessionStatus.ACTIVE, CUTOFF));
        assertEquals(0L, remaining);
    }

    // ------------------------------------------------------------------ helpers

    private static void insert(ChatSessionDO session) {
        inSession(mapper -> mapper.insert(session));
    }

    private static ChatSessionDO session(String sessionId, SessionStatus status, long updatedAt) {
        return session(sessionId, TENANT, DEPT, status, updatedAt);
    }

    private static ChatSessionDO session(String sessionId, String tenantId, String deptId,
                                         SessionStatus status, long updatedAt) {
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId(sessionId);
        session.setTenantId(tenantId);
        session.setDeptId(deptId);
        session.setPatientId("patient-" + sessionId);
        session.setStatus(status);
        session.setCreatedAt(updatedAt);
        session.setUpdatedAt(updatedAt);
        return session;
    }

    private static <T> T inSession(java.util.function.Function<ChatSessionMapper, T> action) {
        try (SqlSession sql = sqlSessionFactory.openSession(true)) {
            return action.apply(sql.getMapper(ChatSessionMapper.class));
        }
    }
}
