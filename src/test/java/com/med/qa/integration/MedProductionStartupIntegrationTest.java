package com.med.qa.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.med.qa.MedQaApplication;
import com.med.qa.config.MedFlywayConfig;
import com.med.qa.config.MedMigrationPool;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Boots the <em>real</em> application context with schema migration enabled against a genuine MySQL
 * instance, and asserts that the shard schema actually appears.
 *
 * <p>This closes the one hole the rest of the suite leaves open. Every Spring-context test in this
 * repository pins {@code spring.flyway.enabled=false} so the suite stays offline, which means the
 * production startup path - running the migrations during context refresh - had never been executed
 * by any test. The D34 integration run showed what that hid: with only {@code flyway-core} on the
 * classpath Flyway cannot speak to MySQL at all ({@code Unsupported Database: MySQL 8.0}), and
 * driving the migrations through the ShardingSphere proxy fails as well (MySQL error 1007, then
 * {@code Load actual table metadata 'med_message_0' failed}). Either one stops the service from
 * starting in production, and neither is visible to a text-level assertion or to a container boot
 * smoke test that only looks for the startup banner.</p>
 *
 * <p>The context is started through {@link SpringApplicationBuilder} rather than
 * {@code @SpringBootTest} so the migration switch can be flipped per test, and the assertions are
 * made the way an operator would: by querying {@code information_schema} on the physical server.</p>
 *
 * <p><strong>Coordinates are passed as command-line arguments on purpose.</strong> The first version
 * of this test used {@code SpringApplicationBuilder.properties(...)}, which installs <em>default</em>
 * properties - the lowest-precedence source in the environment. {@code application.yml} already
 * declares {@code med.storage.migration.host: ${MED_MYSQL_HOST:127.0.0.1}} and friends, so the
 * defaults lost and the migration ran against whatever MySQL happened to listen on the host's port
 * 3306. The failure was an {@code Access denied} for the {@code med_qa} account, which says nothing
 * about the actual defect. Command-line arguments outrank every file-based source, and
 * {@link #migratesThePhysicalSchemaAndKeepsOneDataSource()} asserts the effective JDBC URL so a
 * future regression is reported as "the migration went to the wrong server" instead of as a
 * confusing authentication error.</p>
 */
@ExtendWith(DockerAvailableCondition.class)
class MedProductionStartupIntegrationTest {

    private static final int SHARD_COUNT = 16;

    /** Schema the enabled case migrates into; the container creates it up front. */
    private static final String MIGRATED_SCHEMA = "med_qa";

    /**
     * A schema that is never created. The disabled case points here on purpose: if the migration bean
     * were still active it would either create the schema (Flyway's default {@code createSchemas}) or
     * fail the context on the missing privileges, so an empty result proves the migrations really did
     * not run. Pointing at a schema nobody else touches also keeps the two tests independent of
     * execution order.
     */
    private static final String UNMIGRATED_SCHEMA = "med_qa_unmigrated";

    private static MySQLContainer<?> mysql;

    @BeforeAll
    static void startMysql() {
        // D35: a missing daemon fails loudly when Docker is declared mandatory for the run (CI),
        // instead of the suite silently skipping itself - see IntegrationTestRequirements.
        IntegrationTestRequirements.verifyDockerAvailableForCurrentRun(DockerAvailabilityProbe.testcontainers());

        mysql = new MySQLContainer<>(DockerImageName.parse(MedIntegrationImages.MYSQL))
                .withDatabaseName(MIGRATED_SCHEMA)
                .withUsername("med_qa")
                .withPassword("med_qa");
        mysql.start();
    }

    @AfterAll
    static void stopMysql() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    @DisplayName("the context starts with migrations enabled and creates the 16 shards plus both SINGLE tables")
    void migratesThePhysicalSchemaAndKeepsOneDataSource() throws Exception {
        try (ConfigurableApplicationContext context = startContext(true, MIGRATED_SCHEMA)) {
            MedMigrationPool pool = context.getBean(MedMigrationPool.class);
            assertThat(context.getBean(Flyway.class)).isNotNull();

            // The guard that turns "the migration silently went somewhere else" into a clear failure.
            assertThat(pool.getJdbcUrl())
                    .as("the migration must target the Testcontainers instance, never an ambient MySQL")
                    .contains(":" + mysql.getMappedPort(MySQLContainer.MYSQL_PORT) + "/" + MIGRATED_SCHEMA);

            // The extra pool must stay out of the DataSource type space, or MyBatis loses its single
            // candidate and every mapper fails to wire (reproduced in D34).
            assertThat(context.getBeanNamesForType(DataSource.class))
                    .as("only the ShardingSphere data source may be a DataSource bean")
                    .hasSize(1);
            assertThat(context.getBean(DataSource.class))
                    .as("the application must still resolve the ShardingSphere data source")
                    .isNotSameAs(pool.getDataSource());

            List<String> tables = existingTables(MIGRATED_SCHEMA);
            assertThat(tables).as("the SINGLE rule tables come from migration V2 and V3")
                    .contains("med_session", "med_audit_log");
            for (int shard = 0; shard < SHARD_COUNT; shard++) {
                assertThat(tables)
                        .as("migration V1 must create physical shard med_message_%d", shard)
                        .contains("med_message_" + shard);
            }
            assertThat(tables).contains("flyway_schema_history");
            assertThat(appliedMigrationCount()).as("V1, V2 and V3 must all be applied").isEqualTo(3);
        }
    }

    @Test
    @DisplayName("boundary: with migrations disabled the context still starts and leaves its schema untouched")
    void migrationsCanBeDisabled() throws Exception {
        try (ConfigurableApplicationContext context = startContext(false, UNMIGRATED_SCHEMA)) {
            assertThat(context.containsBean(MedFlywayConfig.FLYWAY_BEAN)).isFalse();
            assertThat(context.containsBean(MedFlywayConfig.MIGRATION_POOL_BEAN)).isFalse();
            // No migration ran against this schema, so it must still be empty.
            assertThat(existingTables(UNMIGRATED_SCHEMA)).isEmpty();
        }
    }

    /**
     * Starts the application with the migration switch and coordinates supplied as command-line
     * arguments, so they outrank {@code application.yml} (see the class Javadoc).
     */
    private ConfigurableApplicationContext startContext(boolean flywayEnabled, String schema) {
        return new SpringApplicationBuilder(MedQaApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.flyway.enabled=" + flywayEnabled,
                        "--med.storage.migration.host=" + mysql.getHost(),
                        "--med.storage.migration.port=" + mysql.getMappedPort(MySQLContainer.MYSQL_PORT),
                        "--med.storage.migration.database=" + schema,
                        "--med.storage.migration.username=" + mysql.getUsername(),
                        "--med.storage.migration.password=" + mysql.getPassword());
    }

    /** Lists the tables of a schema, querying the physical server directly (no proxy involved). */
    private static List<String> existingTables(String schema) throws Exception {
        try (Connection connection = rawConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT table_name FROM information_schema.tables WHERE table_schema = ?")) {
            statement.setString(1, schema);
            try (ResultSet rs = statement.executeQuery()) {
                List<String> tables = new ArrayList<>();
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
                return tables;
            }
        }
    }

    private static int appliedMigrationCount() throws Exception {
        try (Connection connection = rawConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM " + MIGRATED_SCHEMA + ".flyway_schema_history WHERE success = 1");
             ResultSet rs = statement.executeQuery()) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    /**
     * Opens a connection to the server itself, without selecting a schema, so a schema that does not
     * exist can still be inspected.
     */
    private static Connection rawConnection() throws Exception {
        // characterEncoding must be a JAVA charset name; `utf8mb4` is a MySQL server charset and
        // Connector/J rejects it before the connection is ever opened.
        String url = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(MySQLContainer.MYSQL_PORT)
                + "/?useUnicode=true&characterEncoding=UTF-8&useSSL=false"
                + "&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        return DriverManager.getConnection(url, mysql.getUsername(), mysql.getPassword());
    }
}
