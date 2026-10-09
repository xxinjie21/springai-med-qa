package com.med.qa.mapper;

import com.med.qa.domain.entity.ArchivedMessageDO;
import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.entity.SessionArchiveManifestDO;
import com.med.qa.domain.enums.RoleType;
import com.med.qa.domain.enums.SessionStatus;
import com.med.qa.mapper.typehandler.RoleTypeTypeHandler;
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
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end verification of the cold archive statements added in D48 against the production DDL,
 * through ShardingSphere-JDBC.
 *
 * <p>Offline unit tests can prove the export service hands the right arguments to a mock. What has to be
 * proven here is what the SQL really does, because every one of these statements fails quietly if it is
 * wrong:</p>
 * <ul>
 *   <li>the candidate query really excludes sessions that already have a manifest ({@code NOT EXISTS}),
 *       and really orders by {@code updated_at} - a wrong predicate makes the job either re-export
 *       certified sessions forever or walk past the backlog;</li>
 *   <li>the archive insert is really idempotent on {@code (session_id, message_id)} - the whole retry
 *       story depends on a duplicate being a no-op rather than a second row;</li>
 *   <li>the manifest insert is really a compare-and-set on {@code session_id};</li>
 *   <li>the stored payload survives the round trip byte for byte, including bytes that are not valid
 *       UTF-8 - a digest computed over a mangled payload would never match again;</li>
 *   <li>both transcript reads are ordered by {@code (created_at, message_id)}, so two reads of an
 *       unchanged transcript hash the same;</li>
 *   <li>the archive tables are reachable through the {@code SINGLE} rule and stay one table, not 16.</li>
 * </ul>
 *
 * <p>The schema is created by the production migrations (V1..V5) against an in-memory H2 schema (MySQL
 * compatibility mode) and shared by the class, so each test empties the tables first and can assert exact
 * row counts.</p>
 */
class SessionArchiveMapperShardingTest {

    private static final String H2_URL =
            "jdbc:h2:mem:med_qa_d48;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;"
                    + "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

    private static final String SHARDING_URL =
            "jdbc:shardingsphere:classpath:sharding/med-sharding-d48.yaml";

    private static final String TENANT = "tenant-archive";

    private static final String DEPT = "dept-cardiology";

    private static final String DIGEST =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static Connection rawH2;

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void setUp() throws Exception {
        DataSource rawH2Ds = new SimpleDriverDataSource(new org.h2.Driver(), H2_URL, "sa", "");
        DataSource shardingDs = new SimpleDriverDataSource(
                new org.apache.shardingsphere.driver.ShardingSphereDriver(), SHARDING_URL);

        // Flyway runs against the raw H2 schema - the same in-memory database the ShardingSphere data
        // source points at - so V1..V5 (including the D48 cold tables) are the production scripts.
        Flyway.configure().dataSource(rawH2Ds).locations("classpath:db/migration").load().migrate();

        rawH2 = rawH2Ds.getConnection();
        rawH2.setAutoCommit(true);

        Environment environment = new Environment("d48", new JdbcTransactionFactory(), shardingDs);
        Configuration configuration = new Configuration(environment);
        configuration.getTypeHandlerRegistry().register(SessionStatusTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(RoleTypeTypeHandler.class);
        for (String xml : List.of("/mapper/ChatSessionMapper.xml", "/mapper/ChatMessageMapper.xml",
                "/mapper/SessionArchiveMapper.xml")) {
            try (InputStream stream = SessionArchiveMapperShardingTest.class.getResourceAsStream(xml)) {
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
            statement.execute("DELETE FROM med_message_archive");
            statement.execute("DELETE FROM med_session_archive");
            for (int shard = 0; shard < 16; shard++) {
                statement.execute("DELETE FROM med_message_" + shard);
            }
        }
    }

    // ------------------------------------------------------------------ schema

    @Test
    @DisplayName("flyway V5 creates both cold tables and the archive read index")
    void flywayCreatesTheColdTables() throws Exception {
        assertThat(tableExists("med_message_archive")).isTrue();
        assertThat(tableExists("med_session_archive")).isTrue();
        assertThat(indexExists("med_message_archive", "idx_med_message_archive_session"))
                .as("without this index a session's transcript is not a contiguous range read")
                .isTrue();
        assertThat(indexExists("med_session_archive", "idx_med_session_archive_exported")).isTrue();
    }

    @Test
    @DisplayName("the cold tier stays one table per entity, unlike the 16 message shards")
    void theColdTierIsNotSharded() throws Exception {
        // The archive's whole reason to exist: one contiguous range read per session instead of a
        // scatter-gather across 16 physical tables. The pattern also matches med_message_archive, so the
        // assertion is spelled out instead of hidden behind a count.
        List<String> messageTables = tablesLike("med_message\\_%");

        assertThat(messageTables)
                .contains("med_message_0", "med_message_15", "med_message_archive")
                .as("16 shards plus the single cold table, and nothing else")
                .hasSize(17);
        assertThat(messageTables).doesNotContain("med_message_archive_0", "med_message_archive_15");
        assertThat(tablesLike("med_session_archive%")).containsExactly("med_session_archive");
    }

    // ------------------------------------------------------------------ candidate query

    @Test
    @DisplayName("the candidate query returns unexported archived sessions only, oldest first")
    void selectUnexportedArchivedSessionsFiltersAndOrders() {
        insertSession(session("archived-old", SessionStatus.ARCHIVED, 1_000L));
        insertSession(session("archived-new", SessionStatus.ARCHIVED, 2_000L));
        insertSession(session("closed", SessionStatus.CLOSED, 1_000L));
        insertSession(session("active", SessionStatus.ACTIVE, 1_000L));
        insertSession(session("already-exported", SessionStatus.ARCHIVED, 500L));
        certify(manifest("already-exported"));

        List<ChatSessionDO> candidates = candidates(SessionStatus.ARCHIVED, 10);

        assertThat(candidates).extracting(ChatSessionDO::getSessionId)
                .as("a session that already has a manifest must never come back, whatever its age")
                .containsExactly("archived-old", "archived-new");
        assertThat(candidates.get(0).getTenantId()).isEqualTo(TENANT);
        assertThat(candidates.get(0).getPatientId()).isEqualTo("patient-archived-old");
        assertThat(candidates.get(0).getStatus()).isEqualTo(SessionStatus.ARCHIVED);
    }

    @Test
    @DisplayName("the candidate query honours its limit from the oldest end")
    void selectUnexportedArchivedSessionsHonoursTheLimit() {
        insertSession(session("archived-old", SessionStatus.ARCHIVED, 1_000L));
        insertSession(session("archived-new", SessionStatus.ARCHIVED, 2_000L));

        List<ChatSessionDO> candidates = candidates(SessionStatus.ARCHIVED, 1);

        assertThat(candidates).extracting(ChatSessionDO::getSessionId).containsExactly("archived-old");
    }

    @Test
    @DisplayName("the backlog count uses the same predicate as the selection")
    void countMatchesTheSelection() {
        insertSession(session("archived-1", SessionStatus.ARCHIVED, 1_000L));
        insertSession(session("archived-2", SessionStatus.ARCHIVED, 2_000L));
        insertSession(session("active", SessionStatus.ACTIVE, 1_000L));
        insertSession(session("exported", SessionStatus.ARCHIVED, 3_000L));
        certify(manifest("exported"));

        assertThat(pendingCount()).isEqualTo(2L);
        assertThat(candidates(SessionStatus.ARCHIVED, 10)).hasSize(2);
    }

    @Test
    @DisplayName("the status parameter carries the numeric spec code, so ARCHIVED is 2 on the wire")
    void theStatusParameterUsesTheNumericSpecCode() {
        insertSession(session("archived", SessionStatus.ARCHIVED, 1_000L));
        insertSession(session("closed", SessionStatus.CLOSED, 1_000L));

        assertThat(candidates(SessionStatus.ARCHIVED, 10)).extracting(ChatSessionDO::getSessionId)
                .containsExactly("archived");
        assertThat(candidates(SessionStatus.CLOSED, 10)).extracting(ChatSessionDO::getSessionId)
                .containsExactly("closed");
    }

    // ------------------------------------------------------------------ cold copy

    @Test
    @DisplayName("copying the same message twice writes one row and reports the second as a no-op")
    void insertIfAbsentIsIdempotent() {
        ArchivedMessageDO row = archiveRow("session-1", "msg-1", 1_000L, bytes("payload"));

        int first = archive(row);
        int second = archive(row);

        assertThat(first).as("the first copy reports that it wrote a row").isEqualTo(1);
        assertThat(second)
                .as("a retry must be a no-op, otherwise every re-export doubles the cold store")
                .isZero();
        assertThat(coldRows("session-1")).hasSize(1);
    }

    @Test
    @DisplayName("the payload survives the round trip byte for byte, including non-UTF-8 bytes")
    void payloadSurvivesTheRoundTrip() {
        byte[] binary = new byte[256];
        for (int i = 0; i < binary.length; i++) {
            binary[i] = (byte) i;
        }
        archive(archiveRow("session-1", "msg-binary", 1_000L, binary));

        List<ArchivedMessageDO> stored = coldRows("session-1");

        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getPayload())
                .as("a mangled payload would make every later checksum verification fail")
                .isEqualTo(binary);
        assertThat(stored.get(0).getArchivedAt()).isEqualTo(9_000L);
        assertThat(stored.get(0).getTenantId()).isEqualTo(TENANT);
        assertThat(stored.get(0).getPatientId()).isEqualTo("patient-session-1");
    }

    @Test
    @DisplayName("the transcript comes back in a total order, so two reads hash the same")
    void selectBySessionIdReturnsATotalOrder() {
        // Same created_at on purpose: without the message_id tie-break the two reads could disagree and
        // the export would report a mismatch on an unchanged transcript.
        archive(archiveRow("session-1", "msg-b", 1_000L, bytes("b")));
        archive(archiveRow("session-1", "msg-a", 1_000L, bytes("a")));
        archive(archiveRow("session-1", "msg-c", 2_000L, bytes("c")));
        archive(archiveRow("session-2", "msg-z", 500L, bytes("z")));

        List<String> first = coldRows("session-1").stream()
                .map(ArchivedMessageDO::getMessageId).toList();
        List<String> second = coldRows("session-1").stream()
                .map(ArchivedMessageDO::getMessageId).toList();

        assertThat(first).containsExactly("msg-a", "msg-b", "msg-c");
        assertThat(second).isEqualTo(first);
        assertThat(coldRows("session-2")).extracting(ArchivedMessageDO::getMessageId)
                .containsExactly("msg-z");
    }

    @Test
    @DisplayName("the export never deletes: the hot transcript is still in the shard after a copy")
    void theHotTranscriptIsLeftInPlace() {
        insertSession(session("session-1", SessionStatus.ARCHIVED, 1_000L));
        insertMessage(message("msg-1", "session-1", 1_000L));
        archive(archiveRow("session-1", "msg-1", 1_000L, bytes("payload")));

        assertThat(coldRows("session-1")).hasSize(1);
        assertThat(hotTranscript("session-1")).hasSize(1);
    }

    @Test
    @DisplayName("the hot transcript read is ordered by (created_at, message_id) too")
    void transcriptReadIsOrderedDeterministically() {
        insertSession(session("session-1", SessionStatus.ARCHIVED, 1_000L));
        insertMessage(message("msg-b", "session-1", 1_000L));
        insertMessage(message("msg-a", "session-1", 1_000L));
        insertMessage(message("msg-c", "session-1", 2_000L));

        assertThat(hotTranscript("session-1")).extracting(ChatMessageDO::getMessageId)
                .containsExactly("msg-a", "msg-b", "msg-c");
    }

    // ------------------------------------------------------------------ manifest

    @Test
    @DisplayName("the manifest insert is a compare-and-set on session_id and round-trips its values")
    void manifestInsertIsACompareAndSet() {
        int first = certify(manifest("session-1"));
        int second = certify(manifest("session-1"));

        assertThat(first).isEqualTo(1);
        assertThat(second)
                .as("a second certification of the same session must be reported, not silently applied")
                .isZero();
        SessionArchiveManifestDO stored = manifestOf("session-1");
        assertThat(stored).isNotNull();
        assertThat(stored.getMessageCount()).isEqualTo(2);
        assertThat(stored.getPayloadChecksum()).isEqualTo(DIGEST);
        assertThat(stored.getExportedAt()).isEqualTo(9_000L);
        assertThat(stored.getDeptId()).isEqualTo(DEPT);
    }

    @Test
    @DisplayName("an exported session leaves the candidate set, so a rerun does not see it again")
    void exportedSessionsLeaveTheCandidateSet() {
        insertSession(session("session-1", SessionStatus.ARCHIVED, 1_000L));

        certify(manifest("session-1"));

        assertThat(candidates(SessionStatus.ARCHIVED, 10)).isEmpty();
        assertThat(pendingCount()).isZero();
        assertThat(manifestOf("session-1")).isNotNull();
    }

    @Test
    @DisplayName("a session that was never exported has no manifest")
    void missingManifestIsNull() {
        assertThat(manifestOf("session-unknown")).isNull();
    }

    // ------------------------------------------------------------------ typed helpers

    private static List<ChatSessionDO> candidates(SessionStatus status, int limit) {
        return inSession(mapper -> mapper.selectUnexportedArchivedSessions(status, limit));
    }

    private static long pendingCount() {
        return inSession(mapper -> mapper.countUnexportedArchivedSessions(SessionStatus.ARCHIVED));
    }

    private static int archive(ArchivedMessageDO row) {
        return inSession(mapper -> mapper.insertIfAbsent(row));
    }

    private static List<ArchivedMessageDO> coldRows(String sessionId) {
        return inSession(mapper -> mapper.selectBySessionId(sessionId));
    }

    private static int certify(SessionArchiveManifestDO manifest) {
        return inSession(mapper -> mapper.insertManifestIfAbsent(manifest));
    }

    private static SessionArchiveManifestDO manifestOf(String sessionId) {
        return inSession(mapper -> mapper.selectManifest(sessionId));
    }

    private static List<ChatMessageDO> hotTranscript(String sessionId) {
        return inSessionChat(mapper -> mapper.selectTranscriptBySessionId(sessionId));
    }

    private static void insertSession(ChatSessionDO session) {
        try (SqlSession sql = sqlSessionFactory.openSession(true)) {
            sql.getMapper(ChatSessionMapper.class).insert(session);
        }
    }

    private static void insertMessage(ChatMessageDO message) {
        try (SqlSession sql = sqlSessionFactory.openSession(true)) {
            sql.getMapper(ChatMessageMapper.class).insert(message);
        }
    }

    private static <T> T inSession(Function<SessionArchiveMapper, T> action) {
        try (SqlSession sql = sqlSessionFactory.openSession(true)) {
            return action.apply(sql.getMapper(SessionArchiveMapper.class));
        }
    }

    private static <T> T inSessionChat(Function<ChatMessageMapper, T> action) {
        try (SqlSession sql = sqlSessionFactory.openSession(true)) {
            return action.apply(sql.getMapper(ChatMessageMapper.class));
        }
    }

    // ------------------------------------------------------------------ raw-schema helpers

    private boolean tableExists(String table) throws Exception {
        return count("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = '" + table + "'")
                == 1;
    }

    private boolean indexExists(String table, String index) throws Exception {
        return count("SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_NAME = '" + table
                + "' AND INDEX_NAME = '" + index + "'") == 1;
    }

    private List<String> tablesLike(String pattern) throws Exception {
        try (var ps = rawH2.prepareStatement("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE TABLE_NAME LIKE ? ESCAPE '\\' ORDER BY TABLE_NAME")) {
            ps.setString(1, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> names = new ArrayList<>();
                while (rs.next()) {
                    names.add(rs.getString(1).toLowerCase(Locale.ROOT));
                }
                return names;
            }
        }
    }

    private int count(String sql) throws Exception {
        try (Statement statement = rawH2.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static ChatSessionDO session(String sessionId, SessionStatus status, long updatedAt) {
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId(sessionId);
        session.setTenantId(TENANT);
        session.setDeptId(DEPT);
        session.setPatientId("patient-" + sessionId);
        session.setStatus(status);
        session.setCreatedAt(updatedAt);
        session.setUpdatedAt(updatedAt);
        return session;
    }

    private static ChatMessageDO message(String messageId, String sessionId, long createdAt) {
        return ChatMessageDO.builder()
                .messageId(messageId)
                .sessionId(sessionId)
                .tenantId(TENANT)
                .deptId(DEPT)
                .patientId("patient-" + sessionId)
                .role(RoleType.PATIENT)
                .content("content of " + messageId)
                .createdAt(createdAt)
                .build();
    }

    private static ArchivedMessageDO archiveRow(String sessionId, String messageId, long createdAt,
                                                byte[] payload) {
        return new ArchivedMessageDO(sessionId, messageId, TENANT, DEPT, "patient-" + sessionId,
                createdAt, 9_000L, payload);
    }

    private static SessionArchiveManifestDO manifest(String sessionId) {
        return new SessionArchiveManifestDO(sessionId, TENANT, DEPT, "patient-" + sessionId, 2, DIGEST,
                9_000L);
    }
}
