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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end verification of the reclaim statements added in D52 against the production DDL, through
 * ShardingSphere-JDBC.
 *
 * <p>The job these statements serve deletes clinical data, so what has to be proven here is not that the
 * service hands the right arguments to a mock but what the SQL really does:</p>
 * <ul>
 *   <li>the candidate query requires all three predicates — the session must not be cold, must have no
 *       manifest, and must still have cold rows — so a missing predicate would silently widen the set of
 *       copies the job is allowed to erase;</li>
 *   <li>the "not cold" predicate is written as {@code status < :archivedStatus}, which is only equivalent
 *       to "not archived" while {@link SessionStatus#ARCHIVED} keeps the highest code. That equivalence is
 *       asserted here, because the index-range reasoning in the mapper's javadoc depends on it and an enum
 *       change would otherwise silently turn the query into something else;</li>
 *   <li>the cold rows are the completion signal: once they are deleted the session leaves the candidate set
 *       on its own, which is why this job needs no marker column — a claim that only holds if the
 *       {@code EXISTS (med_message_archive)} predicate is really there;</li>
 *   <li>{@code deleteColdCopy} removes exactly one session's cold rows, leaves every other session's rows
 *       alone, never touches the manifest table, and never touches the sharded {@code med_message}
 *       tables.</li>
 * </ul>
 *
 * <p>The 16 {@code med_message} shards, {@code med_session} and the D48 cold tables are created by the
 * production migrations (V1..V6) against an in-memory H2 schema (MySQL compatibility mode); every
 * statement then runs through ShardingSphere. The schema is shared by the class, so each test empties the
 * tables first and can therefore assert exact row counts.</p>
 */
class SessionReclaimMapperShardingTest {

    private static final String H2_URL =
            "jdbc:h2:mem:med_qa_d52;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;"
                    + "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

    private static final String SHARDING_URL = "jdbc:shardingsphere:classpath:sharding/med-sharding-d52.yaml";

    private static final long TOUCHED_AT = 1_735_000_000_000L;

    private static final String TENANT = "tenant-reclaim";

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
        // source points at - so V1..V6 are the production scripts.
        Flyway.configure().dataSource(rawH2Ds).locations("classpath:db/migration").load().migrate();

        rawH2 = rawH2Ds.getConnection();
        rawH2.setAutoCommit(true);

        Environment environment = new Environment("d52", new JdbcTransactionFactory(), shardingDs);
        Configuration configuration = new Configuration(environment);
        configuration.getTypeHandlerRegistry().register(SessionStatusTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(RoleTypeTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(MetadataTypeHandler.class);
        for (String xml : List.of("/mapper/ChatSessionMapper.xml", "/mapper/ChatMessageMapper.xml",
                "/mapper/SessionArchiveMapper.xml")) {
            try (InputStream stream = SessionReclaimMapperShardingTest.class.getResourceAsStream(xml)) {
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

    // ------------------------------------------------------------------ the predicate's assumption

    @Test
    @DisplayName("ARCHIVED keeps the highest status code, which is what makes `< ARCHIVED` mean `not archived`")
    void archivedIsTheHighestStatusCode() {
        int archived = SessionStatus.ARCHIVED.getCode();
        for (SessionStatus status : SessionStatus.values()) {
            assertTrue(status.getCode() <= archived,
                    "the candidate query expresses 'not archived' as a strict upper bound so that it is a "
                            + "single index range; " + status + " must not sit above ARCHIVED");
        }
        assertEquals(Arrays.stream(SessionStatus.values())
                        .filter(status -> status != SessionStatus.ARCHIVED)
                        .count(),
                Arrays.stream(SessionStatus.values())
                        .filter(status -> status.getCode() < archived)
                        .count(),
                "every non-archived status has to fall below ARCHIVED, or the bound would exclude one");
    }

    // ------------------------------------------------------------------ the candidate query

    @Test
    @DisplayName("the candidate query requires a live session, no manifest and a cold copy still present")
    void candidateQueryRequiresEveryPredicate() {
        // The orphan this job exists for: restored (so ACTIVE), certification retired, cold rows left over.
        insert(session("orphan", SessionStatus.ACTIVE));
        insertArchived("orphan", "m-1");
        // A closed session can be an orphan too: a restored session may be closed afterwards.
        insert(session("closed-orphan", SessionStatus.CLOSED));
        insertArchived("closed-orphan", "m-1");
        // Cold again: the export is about to certify those very rows, so they are not an orphan.
        insert(session("cold-again", SessionStatus.ARCHIVED));
        insertArchived("cold-again", "m-1");
        // Certified: the copy is evidence, and evidence is never reclaimed.
        insert(session("certified", SessionStatus.ACTIVE));
        insertArchived("certified", "m-1");
        insertManifest("certified");
        // Nothing to reclaim: no cold rows at all.
        insert(session("no-cold-copy", SessionStatus.ACTIVE));

        List<ChatSessionDO> candidates = inArchive(mapper ->
                mapper.selectReclaimableOrphans(SessionStatus.ARCHIVED, 10));

        assertEquals(List.of("closed-orphan", "orphan"),
                candidates.stream().map(ChatSessionDO::getSessionId).toList(),
                "a cold copy may only be reclaimed when the session is live, uncertified and still has one");
    }

    @Test
    @DisplayName("the cold rows are the completion signal: deleting them removes the candidate by itself")
    void deletingTheColdRowsRemovesTheCandidate() {
        insert(session("orphan", SessionStatus.ACTIVE));
        insertArchived("orphan", "m-1");
        assertEquals(1, inArchive(mapper ->
                mapper.selectReclaimableOrphans(SessionStatus.ARCHIVED, 10)).size());

        inArchive(mapper -> mapper.deleteColdCopy("orphan"));

        assertTrue(inArchive(mapper -> mapper.selectReclaimableOrphans(SessionStatus.ARCHIVED, 10)).isEmpty(),
                "without this the job would re-fetch the same reclaimed session on every run forever - "
                        + "this job needs no marker column precisely because the predicate observes the delete");
        long remaining = inArchive(mapper -> mapper.countReclaimableOrphans(SessionStatus.ARCHIVED));
        assertEquals(0L, remaining);
    }

    @Test
    @DisplayName("the candidate query is ordered oldest first and honours its limit")
    void candidateQueryOrdersAndLimits() {
        insert(session("oldest", SessionStatus.ACTIVE, TOUCHED_AT - 3_000));
        insertArchived("oldest", "m-1");
        insert(session("middle", SessionStatus.ACTIVE, TOUCHED_AT - 2_000));
        insertArchived("middle", "m-1");
        insert(session("newest", SessionStatus.CLOSED, TOUCHED_AT - 1_000));
        insertArchived("newest", "m-1");

        List<ChatSessionDO> first = inArchive(mapper ->
                mapper.selectReclaimableOrphans(SessionStatus.ARCHIVED, 2));

        assertEquals(List.of("oldest", "middle"), first.stream().map(ChatSessionDO::getSessionId).toList(),
                "a backlog must drain from the copies that have been orphaned the longest");
    }

    @Test
    @DisplayName("the count uses the same predicate as the selection")
    void countMatchesTheSelection() {
        insert(session("orphan-1", SessionStatus.ACTIVE));
        insertArchived("orphan-1", "m-1");
        insert(session("orphan-2", SessionStatus.CLOSED));
        insertArchived("orphan-2", "m-1");
        insert(session("cold", SessionStatus.ARCHIVED));
        insertArchived("cold", "m-1");
        insert(session("certified", SessionStatus.ACTIVE));
        insertArchived("certified", "m-1");
        insertManifest("certified");

        long count = inArchive(mapper -> mapper.countReclaimableOrphans(SessionStatus.ARCHIVED));

        assertEquals(2L, count);
    }

    // ------------------------------------------------------------------ the delete

    @Test
    @DisplayName("the delete removes exactly one session's cold rows and no other session's")
    void deleteRemovesOnlyThatSessionsRows() {
        insertArchived("victim", "m-1");
        insertArchived("victim", "m-2");
        insertArchived("survivor", "m-1");

        int deleted = inArchive(mapper -> mapper.deleteColdCopy("victim"));

        assertEquals(2, deleted);
        assertEquals(0, countArchivedRows("victim"));
        assertEquals(1, countArchivedRows("survivor"), "another consultation must not lose a cold row");
        int again = inArchive(mapper -> mapper.deleteColdCopy("victim"));
        assertEquals(0, again, "a second delete is a no-op, so an interrupted run converges");
    }

    @Test
    @DisplayName("the delete touches neither the manifest table nor the sharded hot tables")
    void deleteLeavesTheEvidenceAndTheLiveTiersAlone() {
        String sessionId = "session-both-tiers";
        insert(session(sessionId, SessionStatus.ACTIVE));
        insertManifest(sessionId);
        insertArchived(sessionId, "m-1");
        insertMessage(sessionId);

        inArchive(mapper -> mapper.deleteColdCopy(sessionId));

        assertEquals(1, countManifestRows(sessionId),
                "the certification is not this job's to remove - only a restore retires it");
        assertEquals(1, countRowsInShard(sessionId),
                "the live transcript is the record of truth and must keep every row");
        assertEquals(0, countArchivedRows(sessionId));
    }

    // ------------------------------------------------------------------ the cold-store aggregates (D54)

    @Test
    @DisplayName("the row count covers the whole cold table and only the cold table")
    void countsEveryColdRowAndNothingElse() {
        insertArchived("a", "m-1");
        insertArchived("a", "m-2");
        insertArchived("b", "m-1");
        // A hot shard row and a session row are not the cold store: counting them would make the
        // published size a number no operator can reason about.
        insert(session("live", SessionStatus.ACTIVE));
        insertMessage("live");

        long coldRows = inArchive(mapper -> mapper.countArchivedMessages());
        assertEquals(3L, coldRows);
    }

    @Test
    @DisplayName("the row count shrinks when the reclaim retires a copy, which is what the rule watches")
    void countFollowsTheReclaim() {
        insertArchived("a", "m-1");
        insertArchived("a", "m-2");
        insertArchived("b", "m-1");

        inArchive(mapper -> mapper.deleteColdCopy("a"));

        long coldRows = inArchive(mapper -> mapper.countArchivedMessages());
        assertEquals(1L, coldRows);
    }

    @Test
    @DisplayName("the oldest row's creation instant is the minimum, and an empty store reports zero")
    void oldestColdRowIsTheMinimum() {
        // Empty first: COALESCE is what keeps "no rows at all" from turning into "age = now" downstream.
        long empty = inArchive(mapper -> mapper.oldestArchivedMessageCreatedAt());
        assertEquals(0L, empty);

        inArchive(mapper -> mapper.insertIfAbsent(new ArchivedMessageDO("a", "m-1", TENANT, DEPT,
                "patient-1", TOUCHED_AT + 1_000, TOUCHED_AT, new byte[] {1})));
        inArchive(mapper -> mapper.insertIfAbsent(new ArchivedMessageDO("a", "m-2", TENANT, DEPT,
                "patient-1", TOUCHED_AT - 1_000, TOUCHED_AT, new byte[] {2})));

        long oldest = inArchive(mapper -> mapper.oldestArchivedMessageCreatedAt());
        assertEquals(TOUCHED_AT - 1_000, oldest);
    }

    // ------------------------------------------------------------------ helpers

    private static void insert(ChatSessionDO session) {
        inSession(mapper -> mapper.insert(session));
    }

    private static void insertManifest(String sessionId) {
        inArchive(mapper -> mapper.insertManifestIfAbsent(new SessionArchiveManifestDO(sessionId, TENANT,
                DEPT, "patient-1", 1, DIGEST, TOUCHED_AT - 10_000)));
    }

    private static void insertMessage(String sessionId) {
        inMessages(mapper -> mapper.insert(ChatMessageDO.builder()
                .messageId(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .tenantId(TENANT)
                .deptId(DEPT)
                .patientId("patient-1")
                .role(RoleType.PATIENT)
                .content("会诊正文")
                .tokenCount(8)
                .masked(false)
                .createdAt(TOUCHED_AT - 1_000)
                .metadata(new LinkedHashMap<>(Map.of("source", "web")))
                .build()));
    }

    private static void insertArchived(String sessionId, String messageId) {
        inArchive(mapper -> mapper.insertIfAbsent(new ArchivedMessageDO(sessionId, messageId, TENANT, DEPT,
                "patient-1", TOUCHED_AT - 1_000, TOUCHED_AT - 500, new byte[] {1, 2, 3})));
    }

    private static ChatSessionDO session(String sessionId, SessionStatus status) {
        return session(sessionId, status, TOUCHED_AT);
    }

    private static ChatSessionDO session(String sessionId, SessionStatus status, long updatedAt) {
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId(sessionId);
        session.setTenantId(TENANT);
        session.setDeptId(DEPT);
        session.setPatientId("patient-1");
        session.setTitle("reclaim " + sessionId);
        session.setStatus(status);
        session.setCreatedAt(updatedAt);
        session.setUpdatedAt(updatedAt);
        return session;
    }

    private static int countRowsInShard(String sessionId) {
        int shard = Crc32ShardingAlgorithm.shardIndex(sessionId, SHARD_COUNT);
        return count("SELECT COUNT(*) FROM med_message_" + shard + " WHERE session_id = '" + sessionId + "'");
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
