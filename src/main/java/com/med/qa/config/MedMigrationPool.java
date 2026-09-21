package com.med.qa.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * The dedicated connection pool the schema migrations run on, pointed at the <em>physical</em> MySQL
 * server instead of the ShardingSphere data source.
 *
 * <p><strong>Why this is a plain holder and not a {@code DataSource} bean.</strong> Wrapping the pool
 * in a type that is not a {@code DataSource} is a correctness requirement, not a style choice. Adding
 * a second {@code DataSource} bean to the context makes {@code DataSource} ambiguous, and
 * {@code MybatisAutoConfiguration} - guarded by {@code @ConditionalOnSingleCandidate(DataSource.class)}
 * and whose {@code sqlSessionFactory} takes a {@code DataSource} parameter - then fails to build
 * {@code SqlSessionTemplate}. The D34 integration run reproduced the consequence: every mapper bean
 * died with {@code Cannot resolve reference to bean 'sqlSessionTemplate'} while the context was
 * starting, and the real root cause (a database that could not be reached) was buried several
 * {@code Caused by} levels down. Keeping the pool out of the {@code DataSource} type space leaves the
 * ShardingSphere proxy as the single candidate, so MyBatis, the storage health probe and the rest of
 * the application keep resolving exactly the data source they always did. The regression guard for
 * this lives in {@code MedFlywayConfigTest} and {@code MedProductionStartupIntegrationTest}.</p>
 *
 * <p>The pool is intentionally tiny: migrations are a one-shot startup activity, so two connections
 * cover Flyway's needs while the connection budget stays with the application pool.</p>
 *
 * <p>Connections are opened lazily. {@code initializationFailTimeout} is negative, so constructing
 * the pool never touches the network: the migration that follows is what must abort startup, and it
 * reports the failure against the database it was pointed at. A fail-fast pool would instead surface
 * as {@code Failed to initialize pool} while the Flyway bean is being created, which is a much harder
 * failure for an operator to read - and it would make the configuration impossible to exercise
 * without a live server.</p>
 *
 * <p>The holder implements {@link AutoCloseable} so the pool has a Spring-managed lifecycle: the
 * owning bean is declared with {@code destroyMethod = "close"}.</p>
 */
public final class MedMigrationPool implements AutoCloseable {

    /**
     * Upper bound of the migration pool. Migrations run once during context refresh and Flyway never
     * needs more than a couple of connections at a time, so a larger pool would only take connections
     * away from the application.
     */
    public static final int MAXIMUM_POOL_SIZE = 2;

    /**
     * Hikari's "do not probe the server while building the pool" value. A negative timeout bypasses
     * the initial connection attempt entirely; the first real {@code getConnection()} - Flyway's -
     * then reports the failure against the target database.
     */
    public static final int LAZY_INITIALIZATION = -1;

    private final HikariDataSource dataSource;

    /**
     * Builds the pool from validated migration coordinates.
     *
     * @param properties the validated migration coordinates, must not be {@code null}
     * @throws NullPointerException  if {@code properties} is {@code null}
     * @throws IllegalStateException if Hikari rejects the configuration (for example an unparseable
     *                               JDBC URL), which is a deployment error and must fail startup
     */
    public MedMigrationPool(MedMigrationProperties properties) {
        Objects.requireNonNull(properties, "properties");
        HikariConfig config = new HikariConfig();
        config.setPoolName(properties.getPoolName());
        config.setJdbcUrl(properties.getJdbcUrl());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setMaximumPoolSize(MAXIMUM_POOL_SIZE);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(properties.getConnectionTimeoutMillis());
        config.setInitializationFailTimeout(LAZY_INITIALIZATION);
        this.dataSource = new HikariDataSource(config);
    }

    /**
     * Returns the pool, so Flyway can be pointed at it.
     *
     * @return the underlying pool, never {@code null}; returned as {@link DataSource} on purpose, so
     *         no caller can start treating the pool as a Spring bean
     */
    public DataSource getDataSource() {
        return dataSource;
    }

    /**
     * Returns the JDBC URL the migrations will connect to.
     *
     * @return the effective JDBC URL, never {@code null}
     */
    public String getJdbcUrl() {
        return dataSource.getJdbcUrl();
    }

    /**
     * Returns the pool name, which distinguishes the migration connections from the application pool
     * in logs and in the server's connection list.
     *
     * @return a stable pool name, never {@code null}
     */
    public String getPoolName() {
        return dataSource.getPoolName();
    }

    /**
     * Returns whether the pool has already been closed.
     *
     * @return {@code true} once {@link #close()} has run
     */
    public boolean isClosed() {
        return dataSource.isClosed();
    }

    /**
     * Closes the pool and every idle connection it holds. Called by the Spring container on shutdown.
     *
     * <p>Closing is idempotent: Hikari ignores repeated calls, so a shutdown that races a failed
     * startup cannot throw on the way out.</p>
     */
    @Override
    public void close() {
        dataSource.close();
    }
}
