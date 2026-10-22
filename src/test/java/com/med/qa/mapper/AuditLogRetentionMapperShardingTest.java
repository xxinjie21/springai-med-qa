package com.med.qa.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.med.qa.domain.entity.AuditLogDO;
import com.med.qa.domain.enums.AuditOutcome;
import com.med.qa.mapper.typehandler.AuditOutcomeTypeHandler;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * End-to-end verification of the two retention statements {@link AuditLogMapper} gained in D55,
 * against the production DDL, through ShardingSphere-JDBC.
 *
 * <p>The trim these statements serve is the only delete against {@code med_audit_log}, and an audit
 * row has no second copy anywhere - so what has to be proven here is not that the service hands the
 * right arguments to a mock but what the SQL really does:</p>
 * <ul>
 *   <li>migration V7 really created {@code idx_med_audit_log_retention}: the retention scan is the
 *       repository's second query without a tenant dimension, and without this index every run is a
 *       full table scan of the whole audit trail;</li>
 *   <li>the cutoff is an <em>exclusive</em> upper bound - a row stamped exactly at
 *       {@code now - retention-days} is still inside the window and must never be counted or
 *       deleted, otherwise the trim would quietly eat one extra day of the compliance record;</li>
 *   <li>the predicate spans every tenant and department, because that is the job's contract (a
 *       tenant-scoped delete would leave other hospitals' expired rows behind forever);</li>
 *   <li>{@code LIMIT} really bounds one statement, and a second statement resumes exactly where the
 *       first stopped, so an interrupted run converges instead of re-deleting or skipping rows.</li>
 * </ul>
 *
 * <p>All tables - including the D55 index - are created by the production migrations (V1..V7)
 * against an in-memory H2 schema (MySQL compatibility mode), and both statements run through
 * ShardingSphere (its {@code SINGLE} rule routes {@code med_audit_log} to the one data source, as in
 * production). The schema is shared by the class, so each test empties the audit table first and can
 * assert exact row counts.</p>
 */
class AuditLogRetentionMapperShardingTest {

    private static final String H2_URL =
            "jdbc:h2:mem:med_qa_d55;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;"
                    + "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

    private static final String SHARDING_URL = "jdbc:shardingsphere:classpath:sharding/med-sharding-d55.yaml";

    /** Held open for the whole class so the in-memory H2 schema survives across tests. */
    private static Connection rawH2;

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void setUp() throws Exception {
        DataSource rawH2Ds = new SimpleDriverDataSource(new org.h2.Driver(), H2_URL, "sa", "");
        DataSource shardingDs = new SimpleDriverDataSource(
                new org.apache.shardingsphere.driver.ShardingSphereDriver(), SHARDING_URL);

        // Flyway runs against the raw H2 schema (the same in-memory database the ShardingSphere data
        // source points at), so V1..V7 are the production scripts - V7 included.
        Flyway.configure().dataSource(rawH2Ds).locations("classpath:db/migration").load().migrate();

        rawH2 = rawH2Ds.getConnection();
        rawH2.setAutoCommit(true);

        Environment environment = new Environment("d55", new JdbcTransactionFactory(), shardingDs);
        Configuration configuration = new Configuration(environment);
        configuration.getTypeHandlerRegistry().register(AuditOutcomeTypeHandler.class);
        try (InputStream xml = AuditLogRetentionMapperShardingTest.class
                .getResourceAsStream("/mapper/AuditLogMapper.xml")) {
            new XMLMapperBuilder(xml, configuration, "AuditLogMapper.xml",
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
    void emptyTheAuditTable() throws Exception {
        try (Statement statement = rawH2.createStatement()) {
            statement.execute("DELETE FROM med_audit_log");
        }
    }

    // ------------------------------------------------------------------ the V7 index

    @Test
    @DisplayName("flyway V7 creates the retention index on med_audit_log(created_at)")
    void flywayCreatesTheRetentionIndex() throws Exception {
        // The trim is the second query in the repository without a tenant dimension (the first is the
        // D47 session sweep, served by V4). All three pre-existing composite indexes lead with
        // tenant_id, so without this index every retention run is a full table scan of the whole
        // audit trail - exactly the unbounded work an unattended background job must not do.
        try (var ps = rawH2.prepareStatement(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES "
                        + "WHERE TABLE_NAME = 'med_audit_log' AND INDEX_NAME = 'idx_med_audit_log_retention'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getLong(1),
                        "V7 must create idx_med_audit_log_retention, or every trim scans the whole trail");
            }
        }
    }

    // ------------------------------------------------------------------ the count

    @Test
    @DisplayName("the count spans every tenant but treats the cutoff as an exclusive upper bound")
    void countCountsOnlyRowsStrictlyBeforeTheCutoff() {
        long cutoff = 1_735_000_000_000L;
        // Two tenants, two departments: the trim is deliberately cross-tenant, and a predicate that
        // quietly scoped itself would leave the other hospital's expired rows behind forever.
        insert("tenant-a", "dept-a", cutoff - 2_000);
        insert("tenant-a", "dept-a", cutoff - 1);
        insert("tenant-b", "dept-b", cutoff - 1_000);
        // Boundary: stamped exactly at the cutoff, i.e. still inside the retention window.
        insert("tenant-a", "dept-a", cutoff);
        // Fresh: well inside the window.
        insert("tenant-b", "dept-b", cutoff + 5_000);

        long expired = inAudit(mapper -> mapper.countAuditLogsCreatedBefore(cutoff));

        assertEquals(3L, expired,
                "the cutoff is exclusive: a row stamped exactly at now - retention-days is still "
                        + "inside the window, and one extra day of trimmed record is silent data loss");
    }

    @Test
    @DisplayName("the count of an empty trail is zero, not a failure")
    void countOfAnEmptyTrailIsZero() {
        long expired = inAudit(mapper -> mapper.countAuditLogsCreatedBefore(1_735_000_000_000L));
        assertEquals(0L, expired);
    }

    // ------------------------------------------------------------------ the delete

    @Test
    @DisplayName("the delete removes at most LIMIT rows, only expired ones, and resumes on the next call")
    void deleteBoundsOneStatementAndDrainsAcrossStatements() {
        long cutoff = 1_735_000_000_000L;
        for (long ts = cutoff - 5_000; ts <= cutoff - 1_000; ts += 1_000) {
            insert("tenant-a", "dept-a", ts);
        }
        insert("tenant-b", "dept-b", cutoff);      // boundary: inside the window
        insert("tenant-b", "dept-b", cutoff + 1);  // fresh

        int first = inAudit(mapper -> mapper.deleteAuditLogsCreatedBefore(cutoff, 3));
        assertEquals(3, first, "one statement must never delete more than its LIMIT");
        long afterFirst = inAudit(mapper -> mapper.countAuditLogsCreatedBefore(cutoff));
        assertEquals(2L, afterFirst,
                "the two remaining expired rows are exactly what the next statement picks up");

        int second = inAudit(mapper -> mapper.deleteAuditLogsCreatedBefore(cutoff, 3));
        assertEquals(2, second, "the second statement drains the rest, and reports fewer than LIMIT");
        long afterSecond = inAudit(mapper -> mapper.countAuditLogsCreatedBefore(cutoff));
        assertEquals(0L, afterSecond);

        int third = inAudit(mapper -> mapper.deleteAuditLogsCreatedBefore(cutoff, 3));
        assertEquals(0, third, "a drained backlog deletes nothing, so an interrupted run converges");

        assertEquals(2, countAuditRows(),
                "the row at the cutoff and the fresh row must survive every statement");
    }

    @Test
    @DisplayName("a delete whose cutoff is in the future of every row removes the whole trail")
    void deleteWithALateCutoffRemovesEverything() {
        insert("tenant-a", "dept-a", 1_700_000_000_000L);
        insert("tenant-b", "dept-b", 1_700_000_000_001L);

        int deleted = inAudit(mapper -> mapper.deleteAuditLogsCreatedBefore(1_735_000_000_000L, 500));

        assertEquals(2, deleted);
        assertEquals(0, countAuditRows());
    }

    // ------------------------------------------------------------------ helpers

    private static void insert(String tenant, String dept, long createdAt) {
        AuditLogDO row = new AuditLogDO();
        row.setAuditId("audit-" + UUID.randomUUID());
        row.setTenantId(tenant);
        row.setDeptId(dept);
        row.setOperatorId("operator-1");
        row.setOperatorRole("PATIENT");
        row.setAction("SESSION_VIEW");
        row.setResourceType("SESSION");
        row.setResourceId("res-" + createdAt);
        row.setOutcome(AuditOutcome.SUCCESS);
        row.setLatencyMillis(1);
        row.setMessage("m" + createdAt);
        row.setCreatedAt(createdAt);
        inAudit(mapper -> mapper.insert(row));
    }

    private static int countAuditRows() {
        try (Statement statement = rawH2.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM med_audit_log")) {
            assertTrue(rs.next());
            return rs.getInt(1);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to count the audit rows", ex);
        }
    }

    private static <T> T inAudit(java.util.function.Function<AuditLogMapper, T> action) {
        try (SqlSession sql = sqlSessionFactory.openSession(true)) {
            return action.apply(sql.getMapper(AuditLogMapper.class));
        }
    }
}
