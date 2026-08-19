package com.med.qa.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.med.qa.domain.entity.AuditLogDO;
import com.med.qa.domain.enums.AuditOutcome;
import com.med.qa.mapper.typehandler.AuditOutcomeTypeHandler;
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
 * End-to-end verification of the MyBatis {@link AuditLogMapper} against the ShardingSphere-JDBC
 * {@code med_audit_log} single table.
 *
 * <p>All audit tables are created by Flyway against an in-memory H2 schema (MySQL compatibility mode),
 * and the mapper runs through ShardingSphere (its {@code SINGLE} rule routes {@code med_audit_log} to
 * the one data source), proving the production DDL and the {@code AuditOutcomeTypeHandler} without any
 * real middleware. The shared H2 schema persists across the whole class, so every test that asserts an
 * absolute count uses its own tenant/department scope to stay isolated from rows other tests insert.</p>
 */
class AuditLogMapperShardingTest {

    private static final String H2_URL =
            "jdbc:h2:mem:med_qa_d23;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;"
                    + "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

    private static final String SHARDING_URL = "jdbc:shardingsphere:classpath:sharding/med-sharding-d23.yaml";

    /** Held open for the whole class so the in-memory H2 schema survives across tests. */
    private static Connection rawH2;

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void setUp() throws Exception {
        DataSource rawH2Ds = new SimpleDriverDataSource(new org.h2.Driver(), H2_URL, "sa", "");
        DataSource shardingDs = new SimpleDriverDataSource(
                new org.apache.shardingsphere.driver.ShardingSphereDriver(), SHARDING_URL);

        // Flyway runs against the raw H2 schema (the same in-memory database the ShardingSphere data
        // source points at), validating the production V1 + V2 + V3 DDL without MySQL.
        Flyway.configure().dataSource(rawH2Ds).locations("classpath:db/migration").load().migrate();

        rawH2 = rawH2Ds.getConnection();
        rawH2.setAutoCommit(true);

        Environment environment = new Environment("d23", new JdbcTransactionFactory(), shardingDs);
        Configuration configuration = new Configuration(environment);
        configuration.getTypeHandlerRegistry().register(AuditOutcomeTypeHandler.class);
        try (InputStream xml = AuditLogMapperShardingTest.class
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

    private static AuditLogDO sample(String tenant, String dept, String operator, String action,
                                     AuditOutcome outcome, long createdAt) {
        AuditLogDO row = new AuditLogDO();
        row.setAuditId("audit-" + UUID.randomUUID());
        row.setTenantId(tenant);
        row.setDeptId(dept);
        row.setOperatorId(operator);
        row.setOperatorRole("PATIENT");
        row.setAction(action);
        row.setResourceType("SESSION");
        row.setResourceId("res-" + createdAt);
        row.setOutcome(outcome);
        row.setErrorCode(outcome == AuditOutcome.FAILURE ? 40400 : null);
        row.setLatencyMillis(createdAt % 100);
        row.setMessage("m" + createdAt);
        row.setCreatedAt(createdAt);
        return row;
    }

    @Test
    @DisplayName("flyway migration creates the med_audit_log table on the H2 schema")
    void flywayCreatesAuditTable() throws Exception {
        try (var ps = rawH2.prepareStatement(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'med_audit_log'")) {
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getLong(1), "med_audit_log table must exist");
            }
        }
    }

    @Test
    @DisplayName("insert through the logical table round-trips with the outcome type handler")
    void insertAndSelectByIdRoundTrips() {
        AuditLogDO row = sample("t1", "d1", "o1", "SESSION_VIEW", AuditOutcome.SUCCESS, 1_735_000_000_000L);
        try (SqlSession sql = sqlSessionFactory.openSession(true)) {
            assertEquals(1, sql.getMapper(AuditLogMapper.class).insert(row));
        }
        try (SqlSession sql = sqlSessionFactory.openSession(true)) {
            AuditLogMapper mapper = sql.getMapper(AuditLogMapper.class);
            AuditLogDO loaded = mapper.selectById(row.getAuditId());
            assertNotNull(loaded);
            assertEquals(row.getAuditId(), loaded.getAuditId());
            assertEquals("t1", loaded.getTenantId());
            assertEquals("SESSION_VIEW", loaded.getAction());
            assertEquals(AuditOutcome.SUCCESS, loaded.getOutcome());
            assertEquals(1_735_000_000_000L, loaded.getCreatedAt());
        }
    }

    @Test
    @DisplayName("a FAILURE outcome is persisted and read back through the type handler")
    void failureOutcomeRoundTrips() {
        AuditLogDO row = sample("t1", "d1", "o1", "SESSION_CLOSE", AuditOutcome.FAILURE, 1_735_000_000_001L);
        try (SqlSession sql = sqlSessionFactory.openSession(true)) {
            sql.getMapper(AuditLogMapper.class).insert(row);
        }
        try (SqlSession sql = sqlSessionFactory.openSession(true)) {
            AuditLogDO loaded = sql.getMapper(AuditLogMapper.class).selectById(row.getAuditId());
            assertNotNull(loaded);
            assertEquals(AuditOutcome.FAILURE, loaded.getOutcome());
            assertEquals(40400, loaded.getErrorCode());
        }
    }

    @Test
    @DisplayName("selectById for an unknown id returns null")
    void selectByIdUnknownReturnsNull() {
        try (SqlSession sql = sqlSessionFactory.openSession(true)) {
            assertNull(sql.getMapper(AuditLogMapper.class)
                    .selectById("missing-" + UUID.randomUUID()));
        }
    }

    @Test
    @DisplayName("selectPage returns entries newest-first, honouring offset/limit and operator/outcome filters")
    void selectPageOrdersAndFilters() {
        String tenant = "t-page";
        String dept = "d-page";
        for (long ts = 1; ts <= 5; ts++) {
            AuditOutcome outcome = ts % 2 == 0 ? AuditOutcome.FAILURE : AuditOutcome.SUCCESS;
            AuditLogDO row = sample(tenant, dept, "op-" + (ts % 2 == 0 ? "B" : "A"), "SESSION_VIEW",
                    outcome, 1_700_000_000_000L + ts);
            try (SqlSession sql = sqlSessionFactory.openSession(true)) {
                sql.getMapper(AuditLogMapper.class).insert(row);
            }
        }
        try (SqlSession sql = sqlSessionFactory.openSession(true)) {
            AuditLogMapper mapper = sql.getMapper(AuditLogMapper.class);
            List<AuditLogDO> firstPage = mapper.selectPage(tenant, dept, null, null, null, 0, 2);
            assertEquals(2, firstPage.size());
            assertEquals(1_700_000_000_005L, firstPage.get(0).getCreatedAt());

            List<AuditLogDO> secondPage = mapper.selectPage(tenant, dept, null, null, null, 2, 2);
            assertEquals(2, secondPage.size());
            assertEquals(1_700_000_000_003L, secondPage.get(0).getCreatedAt());

            List<AuditLogDO> operatorA = mapper.selectPage(tenant, dept, "op-A", null, null, 0, 10);
            assertEquals(3, operatorA.size());

            List<AuditLogDO> failures = mapper.selectPage(tenant, dept, null, null, AuditOutcome.FAILURE, 0, 10);
            assertEquals(2, failures.size());
            for (AuditLogDO failure : failures) {
                assertEquals(AuditOutcome.FAILURE, failure.getOutcome());
            }
        }
    }

    @Test
    @DisplayName("countByCondition narrows by operator and outcome")
    void countByConditionFilters() {
        String tenant = "t-count";
        String dept = "d-count";
        AuditLogDO success = sample(tenant, dept, "opA", "SESSION_VIEW", AuditOutcome.SUCCESS, 1_700_000_001_000L);
        AuditLogDO failure = sample(tenant, dept, "opB", "SESSION_VIEW", AuditOutcome.FAILURE, 1_700_000_002_000L);
        try (SqlSession sql = sqlSessionFactory.openSession(true)) {
            AuditLogMapper mapper = sql.getMapper(AuditLogMapper.class);
            mapper.insert(success);
            mapper.insert(failure);
        }
        try (SqlSession sql = sqlSessionFactory.openSession(true)) {
            AuditLogMapper mapper = sql.getMapper(AuditLogMapper.class);
            assertEquals(2, mapper.countByCondition(tenant, dept, null, null, null));
            assertEquals(1, mapper.countByCondition(tenant, dept, "opA", null, null));
            assertEquals(1, mapper.countByCondition(tenant, dept, null, null, AuditOutcome.FAILURE));
            assertEquals(0, mapper.countByCondition(tenant, dept, "opA", null, AuditOutcome.FAILURE));
        }
    }
}
