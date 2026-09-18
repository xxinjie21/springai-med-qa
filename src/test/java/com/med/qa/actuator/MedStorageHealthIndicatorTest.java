package com.med.qa.actuator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MedStorageHealthIndicator}.
 *
 * <p>Redis and MySQL are pure Mockito doubles: a health probe must never require a live store, it
 * only has to classify the outcome correctly. Every test drives the public
 * {@link MedStorageHealthIndicator#health()} entry point so the Actuator contract (status plus
 * per-component details) is what is actually asserted.</p>
 */
class MedStorageHealthIndicatorTest {

    private final RedisConnectionFactory redisConnectionFactory = mock(RedisConnectionFactory.class);
    private final DataSource dataSource = mock(DataSource.class);

    /** Fresh indicator over the mock stores. */
    private MedStorageHealthIndicator indicator() {
        return new MedStorageHealthIndicator(redisConnectionFactory, dataSource);
    }

    /** Wires a Redis connection whose {@code PING} returns {@code reply}. */
    private void givenRedisPing(String reply) {
        RedisConnection connection = mock(RedisConnection.class);
        when(connection.ping()).thenReturn(reply);
        when(redisConnectionFactory.getConnection()).thenReturn(connection);
    }

    /** Wires a MySQL connection whose validation statement succeeds. */
    private void givenMySqlAvailable() throws SQLException {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenReturn(true);
        when(dataSource.getConnection()).thenReturn(connection);
    }

    @Test
    @DisplayName("both stores reachable: status UP with an available detail per component")
    void reportsUpWhenBothStoresAnswer() throws Exception {
        givenRedisPing(MedStorageHealthIndicator.EXPECTED_PING_REPLY);
        givenMySqlAvailable();

        Health health = indicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry(MedStorageHealthIndicator.REDIS_COMPONENT, "available")
                .containsEntry(MedStorageHealthIndicator.MYSQL_COMPONENT, "available");
    }

    @Test
    @DisplayName("ping reply is matched case insensitively")
    void acceptsLowerCasePingReply() throws Exception {
        givenRedisPing("pong");
        givenMySqlAvailable();

        assertThat(indicator().health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("unexpected ping reply marks the whole probe down")
    void reportsDownOnUnexpectedPingReply() throws Exception {
        givenRedisPing("+OK");
        givenMySqlAvailable();

        Health health = indicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry(MedStorageHealthIndicator.REDIS_COMPONENT,
                "unexpected ping reply");
    }

    @Test
    @DisplayName("redis failure is reported per component and never propagates")
    void reportsDownWhenRedisIsUnreachable() throws Exception {
        when(redisConnectionFactory.getConnection()).thenThrow(new IllegalStateException("connection refused"));
        givenMySqlAvailable();

        Health health = indicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(String.valueOf(health.getDetails().get(MedStorageHealthIndicator.REDIS_COMPONENT)))
                .startsWith("unavailable: ")
                .contains("connection refused");
    }

    @Test
    @DisplayName("null ping reply is treated as an unavailable store, not as a success")
    void reportsDownWhenRedisReplyIsNull() throws Exception {
        givenRedisPing(null);
        givenMySqlAvailable();

        assertThat(indicator().health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("mysql failure is reported per component and never propagates")
    void reportsDownWhenMySqlIsUnreachable() throws Exception {
        givenRedisPing(MedStorageHealthIndicator.EXPECTED_PING_REPLY);
        when(dataSource.getConnection()).thenThrow(new SQLException("access denied"));

        Health health = indicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(String.valueOf(health.getDetails().get(MedStorageHealthIndicator.MYSQL_COMPONENT)))
                .startsWith("unavailable: ")
                .contains("access denied");
    }

    @Test
    @DisplayName("a validation query returning no result set marks mysql down")
    void reportsDownWhenValidationQueryReturnsNothing() throws Exception {
        givenRedisPing(MedStorageHealthIndicator.EXPECTED_PING_REPLY);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenReturn(false);
        when(dataSource.getConnection()).thenReturn(connection);

        Health health = indicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry(MedStorageHealthIndicator.MYSQL_COMPONENT,
                "validation query returned no result");
    }

    @Test
    @DisplayName("the validation query only ever runs the documented SELECT 1")
    void runsOnlyTheDocumentedValidationQuery() throws Exception {
        givenRedisPing(MedStorageHealthIndicator.EXPECTED_PING_REPLY);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenReturn(true);
        when(dataSource.getConnection()).thenReturn(connection);

        indicator().health();

        org.mockito.Mockito.verify(statement).execute(MedStorageHealthIndicator.VALIDATION_QUERY);
    }

    @Test
    @DisplayName("a null redis connection factory is a programming error")
    void rejectsNullRedisConnectionFactory() {
        assertThatThrownBy(() -> new MedStorageHealthIndicator(null, dataSource))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redisConnectionFactory");
    }

    @Test
    @DisplayName("a null data source is a programming error")
    void rejectsNullDataSource() {
        assertThatThrownBy(() -> new MedStorageHealthIndicator(redisConnectionFactory, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dataSource");
    }
}
