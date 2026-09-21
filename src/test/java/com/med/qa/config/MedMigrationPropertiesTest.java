package com.med.qa.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link MedMigrationProperties}, the connection coordinates the schema migrations run
 * with.
 *
 * <p>The properties are the only place where a typo can silently point the migrations at the wrong
 * database, so every setter is asserted twice: once for the value it stores and once for the
 * deployment mistake it refuses. No database is touched here - the URL is only assembled.</p>
 */
class MedMigrationPropertiesTest {

    @Test
    @DisplayName("defaults target the same MySQL the sharding rules consume, with a Java charset name")
    void defaultsTargetTheShardingDatabase() {
        MedMigrationProperties properties = new MedMigrationProperties();

        assertThat(properties.getHost()).isEqualTo("127.0.0.1");
        assertThat(properties.getPort()).isEqualTo(3306);
        assertThat(properties.getDatabase()).isEqualTo("med_qa");
        assertThat(properties.getUsername()).isEqualTo("med_qa");
        assertThat(properties.getLocations()).isEqualTo("classpath:db/migration");
        assertThat(properties.isUrlOverridden()).isFalse();

        String url = properties.getJdbcUrl();
        assertThat(url).startsWith("jdbc:mysql://127.0.0.1:3306/med_qa?");
        // characterEncoding takes a JAVA charset name; the MySQL server name utf8mb4 makes
        // Connector/J throw UnsupportedEncodingException before the connection is opened.
        assertThat(url).contains("characterEncoding=UTF-8");
        assertThat(url).doesNotContain("utf8mb4");
        assertThat(url).contains("allowPublicKeyRetrieval=true");
    }

    @Test
    @DisplayName("configured coordinates are reflected in the assembled JDBC URL")
    void configuredCoordinatesReachTheUrl() {
        MedMigrationProperties properties = new MedMigrationProperties();
        properties.setHost("mysql.hospital.internal");
        properties.setPort(3307);
        properties.setDatabase("med_qa_prod");
        properties.setUsername("med-migrator");
        properties.setPassword("s3cret");
        properties.setLocations("classpath:db/migration,classpath:db/migration-hotfix");

        assertThat(properties.getJdbcUrl())
                .startsWith("jdbc:mysql://mysql.hospital.internal:3307/med_qa_prod?");
        assertThat(properties.getLocations()).contains("migration-hotfix");
        assertThat(properties.getPoolName()).isEqualTo("med-qa-migration-pool");
    }

    @Test
    @DisplayName("an explicit URL wins over host, port and database")
    void explicitUrlOverridesTheAssembledOne() {
        MedMigrationProperties properties = new MedMigrationProperties();
        properties.setHost("ignored.internal");
        properties.setUrl("  jdbc:h2:mem:override;DB_CLOSE_DELAY=-1  ");

        assertThat(properties.isUrlOverridden()).isTrue();
        assertThat(properties.getJdbcUrl()).isEqualTo("jdbc:h2:mem:override;DB_CLOSE_DELAY=-1");
    }

    @Test
    @DisplayName("boundary: a blank or null URL falls back to the assembled MySQL URL")
    void blankUrlFallsBackToTheAssembledUrl() {
        MedMigrationProperties properties = new MedMigrationProperties();

        properties.setUrl("");
        assertThat(properties.isUrlOverridden()).isFalse();
        properties.setUrl("   ");
        assertThat(properties.isUrlOverridden()).isFalse();
        properties.setUrl(null);
        assertThat(properties.isUrlOverridden()).isFalse();
        assertThat(properties.getJdbcUrl()).startsWith("jdbc:mysql://127.0.0.1:3306/med_qa?");
    }

    @Test
    @DisplayName("boundary: a blank host is rejected instead of building a URL that resolves nowhere")
    void rejectsBlankHost() {
        MedMigrationProperties properties = new MedMigrationProperties();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setHost("  "))
                .withMessageContaining("med.storage.migration.host");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setHost(null))
                .withMessageContaining("med.storage.migration.host");
    }

    @Test
    @DisplayName("boundary: ports outside the TCP range are rejected")
    void rejectsImpossiblePorts() {
        MedMigrationProperties properties = new MedMigrationProperties();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setPort(0))
                .withMessageContaining("med.storage.migration.port");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setPort(65_536))
                .withMessageContaining("med.storage.migration.port");
    }

    @Test
    @DisplayName("boundary: blank schema, user and locations are rejected")
    void rejectsBlankRequiredText() {
        MedMigrationProperties properties = new MedMigrationProperties();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setDatabase(""))
                .withMessageContaining("med.storage.migration.database");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setUsername(null))
                .withMessageContaining("med.storage.migration.username");
        // Blank locations would let Flyway scan nothing and apply zero migrations silently.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setLocations(" "))
                .withMessageContaining("med.storage.migration.locations");
    }

    @Test
    @DisplayName("boundary: a null password is rejected while an empty one is accepted for local dev")
    void passwordMayBeEmptyButNotNull() {
        MedMigrationProperties properties = new MedMigrationProperties();

        properties.setPassword("");
        assertThat(properties.getPassword()).isEmpty();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setPassword(null))
                .withMessageContaining("med.storage.migration.password");
    }

    @Test
    @DisplayName("boundary: a non-positive connection timeout is rejected, a hung deployment is worse")
    void rejectsNonPositiveConnectionTimeout() {
        MedMigrationProperties properties = new MedMigrationProperties();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setConnectionTimeoutMillis(0))
                .withMessageContaining("med.storage.migration.connection-timeout-millis");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setConnectionTimeoutMillis(-1))
                .withMessageContaining("med.storage.migration.connection-timeout-millis");

        properties.setConnectionTimeoutMillis(2_500);
        assertThat(properties.getConnectionTimeoutMillis()).isEqualTo(2_500);
    }

    @Test
    @DisplayName("toString never leaks the password, and reports a URL override only as a flag")
    void toStringOmitsSecrets() {
        MedMigrationProperties properties = new MedMigrationProperties();
        properties.setPassword("top-secret");
        properties.setUrl("jdbc:mysql://user:top-secret@mysql.internal:3306/med_qa");

        String rendered = properties.toString();

        assertThat(rendered).doesNotContain("top-secret");
        assertThat(rendered).contains("urlOverridden=true");
        // The override is reported as a flag, never verbatim: an operator may embed credentials in it.
        assertThat(rendered).doesNotContain("mysql.internal");
    }
}
