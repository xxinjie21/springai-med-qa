package com.med.qa.actuator;

import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Health probe for the two stores the consultation path cannot work without.
 *
 * <p>MySQL is reached through the ShardingSphere-JDBC driver, which keeps the 16
 * {@code med_message_*} shards behind a single logical data source; a successful
 * {@code SELECT 1} proves both the pool and the shard routing metadata are usable. Redis backs
 * the conversation cache, the distributed session lock and the rate limiter.</p>
 *
 * <p>The indicator is intentionally shallow: it verifies reachability only and never issues
 * business queries, so it stays cheap enough to be polled every few seconds by a container
 * health check. Any failure is reported as {@code DOWN} with a per-component reason instead of
 * propagating, which is exactly what a liveness probe needs.</p>
 */
public class MedStorageHealthIndicator extends AbstractHealthIndicator {

    /** Detail key carrying the Redis probe outcome. */
    public static final String REDIS_COMPONENT = "redis";

    /** Detail key carrying the MySQL probe outcome. */
    public static final String MYSQL_COMPONENT = "mysql";

    /** Expected reply of the Redis {@code PING} command. */
    public static final String EXPECTED_PING_REPLY = "PONG";

    /** Trivial statement used to validate a pooled MySQL connection. */
    public static final String VALIDATION_QUERY = "SELECT 1";

    /**
     * Detail value recorded for a component that answered its probe.
     *
     * <p>Public because {@code com.med.qa.alert.MedStorageAlertMonitor} reads the health report back
     * and must distinguish "the component answered" from "the component is down"; comparing against
     * a copied literal would let the two sides drift apart silently.</p>
     */
    public static final String AVAILABLE = "available";

    private final RedisConnectionFactory redisConnectionFactory;
    private final DataSource dataSource;

    /**
     * Creates the indicator over the application's managed connections.
     *
     * @param redisConnectionFactory Boot-managed Redis connection factory, must not be {@code null}
     * @param dataSource ShardingSphere-managed MySQL data source, must not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public MedStorageHealthIndicator(RedisConnectionFactory redisConnectionFactory, DataSource dataSource) {
        if (redisConnectionFactory == null) {
            throw new IllegalArgumentException("redisConnectionFactory must not be null");
        }
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource must not be null");
        }
        this.redisConnectionFactory = redisConnectionFactory;
        this.dataSource = dataSource;
    }

    /**
     * Probes both stores and aggregates the outcome into a single health status.
     *
     * @param builder the Actuator builder collecting status and details
     */
    @Override
    protected void doHealthCheck(Health.Builder builder) {
        boolean redisAvailable = probeRedis(builder);
        boolean mysqlAvailable = probeMySql(builder);
        if (redisAvailable && mysqlAvailable) {
            builder.up();
        } else {
            builder.down();
        }
    }

    /**
     * Pings Redis and records the outcome under {@link #REDIS_COMPONENT}.
     *
     * @param builder the health builder receiving the detail entry
     * @return {@code true} when Redis answered {@code PONG}
     */
    private boolean probeRedis(Health.Builder builder) {
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            String reply = connection.ping();
            if (EXPECTED_PING_REPLY.equalsIgnoreCase(reply)) {
                builder.withDetail(REDIS_COMPONENT, AVAILABLE);
                return true;
            }
            builder.withDetail(REDIS_COMPONENT, "unexpected ping reply");
            return false;
        } catch (RuntimeException ex) {
            builder.withDetail(REDIS_COMPONENT, "unavailable: " + ex.getMessage());
            return false;
        }
    }

    /**
     * Executes a trivial statement through the ShardingSphere data source and records the outcome
     * under {@link #MYSQL_COMPONENT}.
     *
     * @param builder the health builder receiving the detail entry
     * @return {@code true} when a pooled connection could run {@code SELECT 1}
     */
    private boolean probeMySql(Health.Builder builder) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            boolean executed = statement.execute(VALIDATION_QUERY);
            if (executed) {
                builder.withDetail(MYSQL_COMPONENT, AVAILABLE);
                return true;
            }
            builder.withDetail(MYSQL_COMPONENT, "validation query returned no result");
            return false;
        } catch (SQLException | RuntimeException ex) {
            builder.withDetail(MYSQL_COMPONENT, "unavailable: " + ex.getMessage());
            return false;
        }
    }
}
