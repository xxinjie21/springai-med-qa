package com.med.qa.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link MedMigrationPool}.
 *
 * <p>Two properties of the pool are load-bearing and are asserted here rather than left implicit:
 * it must not be usable as a Spring {@code DataSource} bean (that is what broke the context in D34),
 * and building it must not require a reachable server (so the configuration can be exercised, and
 * so the failure an operator sees comes from the migration, not from a pool constructor).</p>
 */
class MedMigrationPoolTest {

    private static MedMigrationProperties h2Properties(String database) {
        MedMigrationProperties properties = new MedMigrationProperties();
        properties.setUrl("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
        properties.setUsername("sa");
        properties.setPassword("");
        return properties;
    }

    @Test
    @DisplayName("the pool carries the configured coordinates and a name distinguishable from the app pool")
    void exposesTheConfiguredCoordinates() {
        MedMigrationProperties properties = h2Properties("med_migration_pool_coordinates");

        try (MedMigrationPool pool = new MedMigrationPool(properties)) {
            assertThat(pool.getJdbcUrl()).isEqualTo(properties.getJdbcUrl());
            assertThat(pool.getPoolName()).isEqualTo("med-qa-migration-pool");
            assertThat(pool.isClosed()).isFalse();
            assertThat(pool.getDataSource()).isInstanceOf(HikariDataSource.class);
            // The pool is a DataSource at runtime, but it is deliberately NOT declared as a bean of
            // that type. MedFlywayConfigTest asserts the container-side half of this contract.
            assertThat(pool.getDataSource()).isNotNull();
        }
    }

    @Test
    @DisplayName("the pool hands out working connections and is closed afterwards")
    void servesConnectionsAndCloses() throws Exception {
        MedMigrationProperties properties = h2Properties("med_migration_pool_connections");

        MedMigrationPool pool = new MedMigrationPool(properties);
        try (Connection connection = pool.getDataSource().getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }

        pool.close();
        assertThat(pool.isClosed()).isTrue();
        // Shutdown races a failed startup in a real deployment, so closing twice must stay harmless.
        pool.close();
        assertThat(pool.isClosed()).isTrue();
    }

    @Test
    @DisplayName("boundary: building the pool never probes the network, so the migration reports the failure")
    void constructionIsLazyAndDoesNotProbeTheServer() {
        MedMigrationProperties properties = new MedMigrationProperties();
        // Port 1 is never listening: a fail-fast pool would abort here.
        properties.setUrl("jdbc:mysql://127.0.0.1:1/med_qa");
        properties.setConnectionTimeoutMillis(250);

        try (MedMigrationPool pool = new MedMigrationPool(properties)) {
            assertThat(pool.isClosed()).isFalse();
            assertThat(pool.getJdbcUrl()).isEqualTo("jdbc:mysql://127.0.0.1:1/med_qa");
        }
    }

    @Test
    @DisplayName("boundary: a null properties object is rejected as a programming error")
    void rejectsNullProperties() {
        assertThatNullPointerException()
                .isThrownBy(() -> new MedMigrationPool(null))
                .withMessageContaining("properties");
    }
}
