package com.med.qa.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Tests of {@link MedFlywayConfig}: the wiring that runs the schema migrations against the physical
 * MySQL server while the context is still starting.
 *
 * <p>Everything here runs offline. The enabled path is exercised against an in-memory H2 database
 * through the {@code med.storage.migration.url} override, with a test-only migration location
 * ({@code classpath:db/migration-h2}) because the production migrations are MySQL DDL. That makes the
 * real production wiring - a dedicated pool plus a Flyway bean whose {@code migrate()} runs during
 * context refresh - verifiable without any middleware, which is exactly the gap that let two
 * production-blocking defects ship before D34.</p>
 *
 * <p>The most important assertion in this class is
 * {@link #theMigrationPoolIsNotADataSourceBean()}: the migration pool must never be registered as a
 * {@code DataSource}, or {@code MybatisAutoConfiguration}
 * ({@code @ConditionalOnSingleCandidate(DataSource.class)}) loses its single candidate and every
 * mapper in the application fails to wire.</p>
 */
class MedFlywayConfigTest {

    private static final String H2_URL = "jdbc:h2:mem:med_flyway_config;DB_CLOSE_DELAY=-1";

    private static final String H2_LOCATIONS = "classpath:db/migration-h2";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(MedFlywayConfig.class);

    @Test
    @DisplayName("with spring.flyway.enabled=false nothing is registered and no database is touched")
    void disabledSwitchRemovesTheWholeMigrationPath() {
        runner.withPropertyValues("spring.flyway.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.containsBean(MedFlywayConfig.MIGRATION_POOL_BEAN)).isFalse();
                    assertThat(context.containsBean(MedFlywayConfig.FLYWAY_BEAN)).isFalse();
                    assertThat(context.getBeanNamesForType(Flyway.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(MedMigrationPool.class)).isEmpty();
                });
    }

    @Test
    @DisplayName("the migration runs during context refresh and the pool is closed with the context")
    void enabledSwitchMigratesOnRefresh() {
        MedMigrationPool[] captured = new MedMigrationPool[1];

        runner.withPropertyValues(
                        "med.storage.migration.url=" + H2_URL,
                        "med.storage.migration.username=sa",
                        "med.storage.migration.password=",
                        "med.storage.migration.locations=" + H2_LOCATIONS)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.containsBean(MedFlywayConfig.MIGRATION_POOL_BEAN)).isTrue();
                    assertThat(context.containsBean(MedFlywayConfig.FLYWAY_BEAN)).isTrue();

                    Flyway flyway = context.getBean(Flyway.class);
                    MigrationInfo[] applied = flyway.info().applied();
                    assertThat(applied).as("the versioned migration must have been applied").hasSize(1);
                    assertThat(applied[0].getVersion().getVersion()).isEqualTo("1");

                    MedMigrationPool pool = context.getBean(MedMigrationPool.class);
                    captured[0] = pool;
                    assertThat(pool.getJdbcUrl()).isEqualTo(H2_URL);
                    assertThat(probeRowCount(pool.getDataSource()))
                            .as("the DDL the migration executed must really exist")
                            .isZero();
                });

        assertThat(captured[0].isClosed())
                .as("the container must close the migration pool on shutdown")
                .isTrue();
    }

    @Test
    @DisplayName("the migration pool is not a DataSource bean, so MyBatis keeps a single candidate")
    void theMigrationPoolIsNotADataSourceBean() {
        runner.withPropertyValues(
                        "med.storage.migration.url=" + H2_URL,
                        "med.storage.migration.username=sa",
                        "med.storage.migration.password=",
                        "med.storage.migration.locations=" + H2_LOCATIONS)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // This context deliberately declares no ShardingSphere data source, so the only
                    // correct answer is "no DataSource bean at all". Were the pool registered as one,
                    // a full application context would end up with two candidates and MyBatis would
                    // stop producing SqlSessionTemplate.
                    assertThat(context.getBeanNamesForType(DataSource.class))
                            .as("the migration pool must not enter the DataSource type space")
                            .isEmpty();
                    assertThat(context.getBean(MedMigrationPool.class).getDataSource())
                            .as("the pool is still a DataSource at runtime - it just is not a bean")
                            .isInstanceOf(DataSource.class);
                });
    }

    @Test
    @DisplayName("boundary: an unreachable target aborts startup in the migration, not in a bean clash")
    void unreachableTargetAbortsStartupInsideTheMigration() {
        runner.withPropertyValues(
                        // Port 1 is never listening, so the connection is refused immediately.
                        "med.storage.migration.url=jdbc:mysql://127.0.0.1:1/med_qa",
                        "med.storage.migration.username=med_qa",
                        "med.storage.migration.password=med_qa",
                        "med.storage.migration.connection-timeout-millis=250")
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable failure = context.getStartupFailure();

                    assertThat(messageChain(failure))
                            .as("the failure must be attributed to the migration bean and its own pool")
                            .anyMatch(message -> message.contains("medFlyway"))
                            .anyMatch(message -> message.contains("med-qa-migration-pool"));
                    assertThat(causeChain(failure))
                            .as("a genuine connection attempt must have been made")
                            .anyMatch(SQLException.class::isInstance);
                    // The defect this configuration exists to avoid: a second DataSource bean makes
                    // the type ambiguous and the context dies on bean resolution instead.
                    assertThat(messageChain(failure))
                            .as("startup must not fail on a bean-resolution problem")
                            .noneMatch(message -> message.contains("NoUniqueBeanDefinitionException")
                                    || message.contains("NoSuchBeanDefinitionException"));
                });
    }

    @Test
    @DisplayName("application.yml excludes Boot's Flyway auto-configuration and targets the physical server")
    void applicationYmlExcludesBootFlywayAutoConfiguration() throws IOException {
        String applicationYml = Files.readString(
                Path.of(System.getProperty("user.dir"), "src/main/resources/application.yml"));

        // Boot's FlywayAutoConfiguration can only migrate through the context's single DataSource,
        // which here is the ShardingSphere proxy - the one target the DDL cannot be applied through.
        assertThat(applicationYml)
                .contains("- org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration");
        for (String key : List.of("host", "port", "database", "username", "password", "url", "locations")) {
            assertThat(applicationYml)
                    .as("med.storage.migration.%s must be configurable", key)
                    .contains("      " + key + ":");
        }
    }

    private static long probeRowCount(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM med_migration_probe")) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static List<String> messageChain(Throwable failure) {
        List<String> messages = new ArrayList<>();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                messages.add(current.getMessage());
            }
        }
        return messages;
    }

    private static List<Throwable> causeChain(Throwable failure) {
        List<Throwable> causes = new ArrayList<>();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            causes.add(current);
        }
        return causes;
    }
}
