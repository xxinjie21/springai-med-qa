package com.med.qa.mapper;

import com.med.qa.domain.entity.ArchivedMessageDO;
import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.entity.SessionArchiveManifestDO;
import com.med.qa.domain.enums.RoleType;
import com.med.qa.domain.enums.SessionStatus;
import com.med.qa.mapper.typehandler.MetadataTypeHandler;
import com.med.qa.mapper.typehandler.RoleTypeTypeHandler;
import com.med.qa.mapper.typehandler.SessionStatusTypeHandler;
import com.med.qa.memory.sharding.Crc32ShardingAlgorithm;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end verification of the purge statements added in D50 against the production DDL, through
 * ShardingSphere-JDBC.
 *
 * <p>These are the only statements in the project that decide whether a clinical record is
 * <em>deleted</em>, so what has to be proven here is not that the service hands the right arguments to a
 * mock but what the SQL really does:</p>
 * <ul>
 *   <li>the candidate query requires all four predicates — {@code status = ARCHIVED}, a manifest in
 *       {@code med_session_archive}, {@code purged_at IS NULL}, and {@code updated_at <= cutoff} — so a
 *       missing predicate would silently widen the set of sessions the job is allowed to erase;</li>
 *   <li>{@code markPurged} really is a compare-and-set that sets the marker, and refuses a session that
 *       is not archived or was already retired;</li>
 *   <li>{@code deleteBySessionId} removes exactly one session's rows, from exactly one shard, and leaves
 *       every other session's rows alone — the shard key is what makes that a single-shard delete.</li>
 * </ul>
 *
 * <p>The 16 {@code med_message} shards, {@code med_session} and the D48 cold tables are created by the
 * production migrations (V1..V6) against an in-memory H2 schema (MySQL compatibility mode); every
 * statement then runs through ShardingSphere. The schema is shared by the class, so each test empties the
 * tables first and can therefore assert exact row counts.</p>
 */
class SessionPurgeMapperShardingTest {

    private static final String H2_URL =
            "jdbc:h2:mem:med_qa_d50;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;"
                    + "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

    private static final String SHARDING_URL = "jdbc:shardingsphere:classpath:sharding/med-sharding-d50.yaml";

    /** Cutoff shared by every test, so the assertions can be exact. */
    private static final long CUTOFF = 1_735_000_000_000L;

    private static final String TENANT = "tenant-purge";

    private static final String DEPT = "dept-cardiology";

    private static final String DIGEST =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final int SHARD_COUNT = 16;

    private static Connection rawH2;

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void setUp() throws Exception {
        DataSource rawH2Ds = new SimpleDriverDataSource(new org.h2.Driver(), H2_URL, "sa", "");
        DataSource shardingDs = new SimpleDriverDataSource(
                new org.apache.shardingsphere.driver.ShardingSphereDriver(), SHARDING_URL);

        // Flyway runs against the raw H2 schema - the same in-memory database the ShardingSphere data
        // source points at - so V1..V6 (including the D50 purge marker) are the production scripts.
        Flyway.configure().dataSource(rawH2Ds).locations("classpath:db/migration").load().migrate();

        rawH2 = rawH2Ds.getConnection();
        rawH2.setAutoCommit(true);

        Environment environment = new Environment("d50", new JdbcTransactionFactory(), shardingDs);
        Configuration configuration = new Configuration(environment);
        configuration.getTypeHandlerRegistry().register(SessionStatusTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(RoleTypeTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(MetadataTypeHandler.class);
        for (String xml : List.of("/mapper/ChatSessionMapper.xml", "/mapper/ChatMessageMapper.xml",
                "/mapper/SessionArchiveMapper.xml")) {
            try (InputStream stream = SessionPurgeMapperShardingTest.class.getResourceAsStream(xml)) {
                new XMLMapperBuilder(stream, configuration, xml, configuration.getSqlFragments()).parse();
            }
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
    void emptyTheTables() throws Exception {
        try (Statement statement = rawH2.createStatement()) {
            statement.execute("DELETE FROM med_session");
            statement.execute("DELETE FROM med_session_archive");
            statement.execute("DELETE FROM med_message_archive");
            for (int shard = 0; shard < SHARD_COUNT; shard++) {
                statement.execute("DELETE FROM med_message_" + shard);
            }
        }
    }

    @Test
    @DisplayName("flyway V6 adds the purge marker to med_session")
    void flywayAddsThePurgeMarker() throws Exception {
        try (var ps = rawH2.prepareStatement(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'med_session' AND COLUMN_NAME = 'purged_at'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getLong(1),
                        "without this column the purge job cannot tell a retired session from a candidate");
            }
        }
    }

    @Test
    @DisplayName("a fresh session carries no purge marker")
    void freshSessionHasNoMarker() {
        insert(session("fresh", SessionStatus.ARCHIVED, CUTOFF - 1_000));

        assertNull(inSession(mapper -> mapper.selectById("fresh")).getPurgedAt());
    }

    @Test
    @DisplayName("the candidate query requires a manifest, an archived status and the cooling-off window")
    void candidateQueryRequiresEveryPredicate() {
        insert(session("eligible", SessionStatus.ARCHIVED, CUTOFF - 3_000));
        insertManifest("eligible");
        insert(session("no-manifest", SessionStatus.ARCHIVED, CUTOFF - 3_000));
        insert(session("too-recent", SessionStatus.ARCHIVED, CUTOFF + 1));
        insertManifest("too-recent");
        insert(session("active", SessionStatus.ACTIVE, CUTOFF - 3_000));
        insertManifest("active");
        insert(session("closed", SessionStatus.CLOSED, CUTOFF - 3_000));
        insertManifest("closed");

        List<ChatSessionDO> candidates = inArchive(mapper ->
                mapper.selectPurgeableArchivedSessions(SessionStatus.ARCHIVED, CUTOFF, 10));

        assertEquals(List.of("eligible"), candidates.stream().map(ChatSessionDO::getSessionId).toList(),
                "a session may only lose its hot rows when it is archived, certified and past the "
                        + "cooling-off window");
    }

    @Test
    @DisplayName("the boundary is inclusive: a session exactly at the cutoff is eligible")
    void candidateQueryAcceptsTheBoundary() {
        insert(session("boundary", SessionStatus.ARCHIVED, CUTOFF));
        insertManifest("boundary");

        List<ChatSessionDO> candidates = inArchive(mapper ->
                mapper.selectPurgeableArchivedSessions(SessionStatus.ARCHIVED, CUTOFF, 10));

        assertEquals(List.of("boundary"), candidates.stream().map(ChatSessionDO::getSessionId).toList());
    }

    @Test
    @DisplayName("a retired session leaves the candidate set, so a rerun does not see it again")
    void purgedSessionsLeaveTheCandidateSet() {
        insert(session("retired", SessionStatus.ARCHIVED, CUTOFF - 5_000));
        insertManifest("retired");

        int marked = inSession(mapper -> mapper.markPurged("retired", SessionStatus.ARCHIVED, CUTOFF));

        assertEquals(1, marked);
        assertTrue(inArchive(mapper ->
                        mapper.selectPurgeableArchivedSessions(SessionStatus.ARCHIVED, CUTOFF, 10)).isEmpty(),
                "without this the oldest certified sessions would be re-fetched on every run");
        long remaining = inArchive(mapper ->
                mapper.countPurgeableArchivedSessions(SessionStatus.ARCHIVED, CUTOFF));
        assertEquals(0L, remaining);
    }

    @Test
    @DisplayName("the candidate query is ordered oldest first and honours its limit")
    void candidateQueryOrdersAndLimits() {
        insert(session("oldest", SessionStatus.ARCHIVED, CUTOFF - 3_000));
        insertManifest("oldest");
        insert(session("middle", SessionStatus.ARCHIVED, CUTOFF - 2_000));
        insertManifest("middle");
        insert(session("newest", SessionStatus.ARCHIVED, CUTOFF - 1_000));
        insertManifest("newest");

        List<ChatSessionDO> first = inArchive(mapper ->
                mapper.selectPurgeableArchivedSessions(SessionStatus.ARCHIVED, CUTOFF, 2));

        assertEquals(List.of("oldest", "middle"), first.stream().map(ChatSessionDO::getSessionId).toList(),
                "a backlog must drain from the sessions that have been cold the longest");
    }

    @Test
    @DisplayName("the count uses the same predicate as the selection")
    void countMatchesTheSelection() {
        insert(session("eligible-1", SessionStatus.ARCHIVED, CUTOFF - 3_000));
        insertManifest("eligible-1");
        insert(session("eligible-2", SessionStatus.ARCHIVED, CUTOFF));
        insertManifest("eligible-2");
        insert(session("no-manifest", SessionStatus.ARCHIVED, CUTOFF - 3_000));
        insert(session("too-recent", SessionStatus.ARCHIVED, CUTOFF + 1));
        insertManifest("too-recent");

        long count = inArchive(mapper ->
                mapper.countPurgeableArchivedSessions(SessionStatus.ARCHIVED, CUTOFF));

        assertEquals(2L, count);
    }

    @Test
    @DisplayName("markPurged is a compare-and-set: the second call changes nothing")
    void markPurgedIsACompareAndSet() {
        insert(session("retire-me", SessionStatus.ARCHIVED, CUTOFF - 5_000));

        int first = inSession(mapper -> mapper.markPurged("retire-me", SessionStatus.ARCHIVED, CUTOFF));
        int second = inSession(mapper ->
                mapper.markPurged("retire-me", SessionStatus.ARCHIVED, CUTOFF + 1));

        assertEquals(1, first, "the first call retires the session");
        assertEquals(0, second, "a second replica must not be able to claim the same session");
        assertEquals(CUTOFF, inSession(mapper -> mapper.selectById("retire-me")).getPurgedAt(),
                "the marker keeps the first timestamp, it is not overwritten");
    }

    @Test
    @DisplayName("markPurged refuses a session that is no longer archived")
    void markPurgedRefusesANonArchivedSession() {
        insert(session("active", SessionStatus.ACTIVE, CUTOFF - 5_000));

        int rows = inSession(mapper -> mapper.markPurged("active", SessionStatus.ARCHIVED, CUTOFF));

        assertEquals(0, rows);
        assertNull(inSession(mapper -> mapper.selectById("active")).getPurgedAt());
    }

    @Test
    @DisplayName("the delete removes exactly one session's rows from exactly one shard")
    void deleteRemovesOnlyThatSessionsRows() {
        String victim = "session-victim";
        String survivor = "session-survivor";
        insertMessage(victim, RoleType.PATIENT);
        insertMessage(victim, RoleType.ASSISTANT);
        insertMessage(survivor, RoleType.PATIENT);

        int deleted = inMessages(mapper -> mapper.deleteBySessionId(victim));

        assertEquals(2, deleted, "the session_id shard key makes this a single-shard delete");
        assertEquals(0, countRowsInShard(victim));
        assertEquals(1, countRowsInShard(survivor), "another consultation must not lose a single row");
        int again = inMessages(mapper -> mapper.deleteBySessionId(victim));
        assertEquals(0, again, "a second delete is a no-op, so an interrupted run converges");
    }

    @Test
    @DisplayName("the cold tables are untouched by the purge statements")
    void coldTablesAreNotTouched() {
        String sessionId = "session-cold-intact";
        insert(session(sessionId, SessionStatus.ARCHIVED, CUTOFF - 5_000));
        insertManifest(sessionId);
        insertMessage(sessionId, RoleType.PATIENT);
        insertArchived(sessionId, "message-1");

        inMessages(mapper -> mapper.deleteBySessionId(sessionId));
        inSession(mapper -> mapper.markPurged(sessionId, SessionStatus.ARCHIVED, CUTOFF));

        assertEquals(1, countArchivedRows(sessionId), "the cold copy is the evidence and must survive");
        assertEquals(1, countManifestRows(sessionId), "the manifest is the certification and must survive");
    }

    // ------------------------------------------------------------------ helpers

    private static void insert(ChatSessionDO session) {
        inSession(mapper -> mapper.insert(session));
    }

    private static void insertManifest(String sessionId) {
        inArchive(mapper -> mapper.insertManifestIfAbsent(new SessionArchiveManifestDO(sessionId, TENANT,
                DEPT, "patient-1", 1, DIGEST, CUTOFF - 10_000)));
    }

    private static void insertMessage(String sessionId, RoleType role) {
        inMessages(mapper -> mapper.insert(ChatMessageDO.builder()
                .messageId(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .tenantId(TENANT)
                .deptId(DEPT)
                .patientId("patient-1")
                .role(role)
                .content("会诊正文")
                .tokenCount(8)
                .masked(false)
                .createdAt(CUTOFF - 1_000)
                .metadata(new LinkedHashMap<>(Map.of("source", "web")))
                .build()));
    }

    private static void insertArchived(String sessionId, String messageId) {
        inArchive(mapper -> mapper.insertIfAbsent(new ArchivedMessageDO(sessionId, messageId, TENANT, DEPT,
                "patient-1", CUTOFF - 1_000, CUTOFF - 500, new byte[] {1, 2, 3})));
    }

    private static ChatSessionDO session(String sessionId, SessionStatus status, long updatedAt) {
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId(sessionId);
        session.setTenantId(TENANT);
        session.setDeptId(DEPT);
        session.setPatientId("patient-1");
        session.setTitle("purge " + sessionId);
        session.setStatus(status);
        session.setCreatedAt(updatedAt);
        session.setUpdatedAt(updatedAt);
        return session;
    }

    private static int countRowsInShard(String sessionId) {
        int shard = Crc32ShardingAlgorithm.shardIndex(sessionId, SHARD_COUNT);
        try (Statement statement = rawH2.createStatement()) {
            try (ResultSet rs = statement.executeQuery(
                    "SELECT COUNT(*) FROM med_message_" + shard + " WHERE session_id = '" + sessionId + "'")) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("failed to count the shard rows of " + sessionId, ex);
        }
    }

    private static int countArchivedRows(String sessionId) {
        return count("SELECT COUNT(*) FROM med_message_archive WHERE session_id = '" + sessionId + "'");
    }

    private static int countManifestRows(String sessionId) {
        return count("SELECT COUNT(*) FROM med_session_archive WHERE session_id = '" + sessionId + "'");
    }

    private static int count(String sql) {
        try (Statement statement = rawH2.createStatement()) {
            try (ResultSet rs = statement.executeQuery(sql)) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("failed to run " + sql, ex);
        }
    }

    private static <T> T inSession(java.util.function.Function<ChatSessionMapper, T> action) {
        try (SqlSession sql = sqlSessionFactory.openSession(true)) {
            return action.apply(sql.getMapper(ChatSessionMapper.class));
        }
    }

    private static <T> T inArchive(java.util.function.Function<SessionArchiveMapper, T> action) {
        try (SqlSession sql = sqlSessionFactory.openSession(true)) {
            return action.apply(sql.getMapper(SessionArchiveMapper.class));
        }
    }

    private static <T> T inMessages(java.util.function.Function<ChatMessageMapper, T> action) {
        try (SqlSession sql = sqlSessionFactory.openSession(true)) {
            return action.apply(sql.getMapper(ChatMessageMapper.class));
        }
    }
}
