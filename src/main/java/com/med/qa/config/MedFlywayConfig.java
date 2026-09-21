package com.med.qa.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Runs the Flyway schema migrations against the physical MySQL server, bypassing ShardingSphere.
 *
 * <p><strong>Why the migrations must not go through the sharding proxy.</strong> The three versioned
 * migrations create the <em>physical</em> shard tables {@code med_message_0..15} plus
 * {@code med_session} and {@code med_audit_log}. The ShardingSphere data source declared under
 * {@code spring.datasource} deliberately hides those tables behind the logical {@code med_message}
 * table, and it fails in two distinct ways when asked to create them (both reproduced against a real
 * MySQL 8.0 during D34):</p>
 * <ol>
 *   <li>Flyway first probes whether the schema exists by reading {@code information_schema} through
 *       the proxy, concludes {@code med_qa} is absent, issues {@code CREATE DATABASE med_qa} and dies
 *       with MySQL error 1007 {@code Can't create database 'med_qa'; database exists}.</li>
 *   <li>With schema creation switched off the very first statement still aborts with
 *       {@code IllegalStateException: Load actual table metadata 'med_message_0' failed} - the proxy
 *       tries to introspect a table that the statement is in the middle of creating.</li>
 * </ol>
 *
 * <p>Boot's {@code FlywayAutoConfiguration} can only ever migrate through the context's single
 * {@code DataSource}, which here is the proxy, so it is excluded in
 * {@code spring.autoconfigure.exclude} and replaced by the two beans below: a small connection pool
 * aimed at the physical server ({@link MedMigrationPool}) and a {@link Flyway} instance that migrates
 * on startup.</p>
 *
 * <p><strong>The pool is deliberately not a {@code DataSource} bean.</strong> A second
 * {@code DataSource} bean would make the type ambiguous and take
 * {@code MybatisAutoConfiguration} - {@code @ConditionalOnSingleCandidate(DataSource.class)} - with
 * it; D34 reproduced the resulting context failure. See {@link MedMigrationPool} for the full
 * account. Keeping exactly one {@code DataSource} candidate means MyBatis, the storage health probe
 * and every other auto-configuration keep talking to the sharding data source exactly as before.</p>
 *
 * <p>The configuration is gated on {@code spring.flyway.enabled} (default {@code true}) rather than
 * on a switch of its own, so there is one knob: the offline test suite already pins that property to
 * {@code false}, which keeps unit tests away from any database, while a real deployment leaves it
 * unset and migrates during context refresh. A failing migration aborts startup, exactly as Boot's
 * own initializer would.</p>
 */
@Configuration
@EnableConfigurationProperties(MedMigrationProperties.class)
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MedFlywayConfig {

    /** Name of the migration pool bean, kept stable so tests and operators can address it. */
    public static final String MIGRATION_POOL_BEAN = "medMigrationPool";

    /** Name of the Flyway bean, kept stable so tests and operators can address it. */
    public static final String FLYWAY_BEAN = "medFlyway";

    /**
     * Creates the connection pool the migrations run on.
     *
     * <p>The bean is a {@link MedMigrationPool}, not a {@code DataSource}: registering a
     * {@code DataSource} here would make the type ambiguous for MyBatis and break the context (see
     * the class Javadoc). The declared {@code destroyMethod} closes the pool when the context shuts
     * down, so no connections outlive the application.</p>
     *
     * @param properties the validated migration coordinates, must not be {@code null}
     * @return the dedicated migration pool, never {@code null}
     */
    @Bean(name = MIGRATION_POOL_BEAN, destroyMethod = "close")
    public MedMigrationPool medMigrationPool(MedMigrationProperties properties) {
        return new MedMigrationPool(properties);
    }

    /**
     * Creates the Flyway instance that applies the migrations during context refresh.
     *
     * <p>{@code initMethod = "migrate"} reproduces Boot's behaviour: the migrations run while the
     * context is still starting, so a schema problem is a startup failure and never a surprise on the
     * first request.</p>
     *
     * @param properties        the validated migration coordinates, must not be {@code null}
     * @param medMigrationPool  the dedicated migration pool, must not be {@code null}
     * @return a configured Flyway instance, never {@code null}
     */
    @Bean(name = FLYWAY_BEAN, initMethod = "migrate")
    public Flyway medFlyway(MedMigrationProperties properties, MedMigrationPool medMigrationPool) {
        return Flyway.configure()
                .dataSource(medMigrationPool.getDataSource())
                .locations(properties.getLocations())
                .load();
    }
}
