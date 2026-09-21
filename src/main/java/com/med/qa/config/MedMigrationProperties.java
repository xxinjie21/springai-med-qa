package com.med.qa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection coordinates of the <em>physical</em> MySQL server that Flyway migrates, bound from the
 * {@code med.storage.migration.*} configuration namespace.
 *
 * <pre>
 * med:
 *   storage:
 *     migration:
 *       host: 127.0.0.1       # MED_MYSQL_HOST
 *       port: 3306            # MED_MYSQL_PORT
 *       database: med_qa      # MED_MYSQL_DATABASE
 *       username: med_qa      # MED_MYSQL_USERNAME
 *       password: ****        # MED_MYSQL_PASSWORD
 *       url:                  # MED_MIGRATION_URL, optional full-URL override
 *       locations: classpath:db/migration
 * </pre>
 *
 * <p>These are deliberately the same {@code MED_MYSQL_*} variables the ShardingSphere sharding file
 * consumes, so a deployment configures its database once. What differs is the <em>target</em>: the
 * sharding data source is a proxy that models the logical {@code med_message} table, whereas the
 * migrations create the physical {@code med_message_0..15} tables the proxy hides. See
 * {@link MedFlywayConfig} for why that distinction is not cosmetic.</p>
 *
 * <p>Setters validate eagerly, so an operator typo fails the application context at startup rather
 * than silently pointing the migrations at the wrong database.</p>
 */
@ConfigurationProperties(prefix = MedMigrationProperties.PREFIX)
public class MedMigrationProperties {

    /** Configuration namespace of the migration target. */
    public static final String PREFIX = "med.storage.migration";

    /** Default MySQL port. */
    public static final int DEFAULT_PORT = 3306;

    /** Default schema name, matching the ShardingSphere {@code databaseName}. */
    public static final String DEFAULT_DATABASE = "med_qa";

    /** Default migration user, matching the compose stack's {@code med_qa} account. */
    public static final String DEFAULT_USERNAME = "med_qa";

    /** Default migration location; the three versioned migrations shipped with the application. */
    public static final String DEFAULT_LOCATIONS = "classpath:db/migration";

    /** Default (absent) URL override, meaning "build the URL from host, port and database". */
    public static final String DEFAULT_URL = "";

    /** Default connect timeout, in milliseconds. */
    public static final int DEFAULT_CONNECTION_TIMEOUT_MILLIS = 10_000;

    private String host = "127.0.0.1";

    private int port = DEFAULT_PORT;

    private String database = DEFAULT_DATABASE;

    private String username = DEFAULT_USERNAME;

    private String password = "";

    private String url = DEFAULT_URL;

    private String locations = DEFAULT_LOCATIONS;

    private int connectionTimeoutMillis = DEFAULT_CONNECTION_TIMEOUT_MILLIS;

    /**
     * Returns the host of the physical MySQL server holding the shard tables.
     *
     * @return a non-blank host, never {@code null}
     */
    public String getHost() {
        return host;
    }

    /**
     * Sets the physical MySQL host.
     *
     * @param host a non-blank host name or address
     * @throws IllegalArgumentException if {@code host} is {@code null} or blank, which would build a
     *                                  JDBC URL that resolves to nothing and fail the migration with
     *                                  a confusing driver error
     */
    public void setHost(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException(
                    PREFIX + ".host must not be blank but was '" + host + "'");
        }
        this.host = host;
    }

    /**
     * Returns the physical MySQL port.
     *
     * @return a port in the valid TCP range
     */
    public int getPort() {
        return port;
    }

    /**
     * Sets the physical MySQL port.
     *
     * @param port a port between 1 and 65535
     * @throws IllegalArgumentException if {@code port} is outside the valid TCP range
     */
    public void setPort(int port) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException(
                    PREFIX + ".port must be within 1..65535 but was " + port);
        }
        this.port = port;
    }

    /**
     * Returns the schema the migrations are applied to.
     *
     * @return a non-blank schema name, never {@code null}
     */
    public String getDatabase() {
        return database;
    }

    /**
     * Sets the schema the migrations are applied to.
     *
     * @param database a non-blank schema name
     * @throws IllegalArgumentException if {@code database} is {@code null} or blank
     */
    public void setDatabase(String database) {
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException(
                    PREFIX + ".database must not be blank but was '" + database + "'");
        }
        this.database = database;
    }

    /**
     * Returns the migration user.
     *
     * @return a non-blank user name, never {@code null}
     */
    public String getUsername() {
        return username;
    }

    /**
     * Sets the migration user.
     *
     * @param username a non-blank user name
     * @throws IllegalArgumentException if {@code username} is {@code null} or blank
     */
    public void setUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException(
                    PREFIX + ".username must not be blank but was '" + username + "'");
        }
        this.username = username;
    }

    /**
     * Returns the migration password.
     *
     * @return the password, never {@code null}; may be empty when the server accepts an empty
     *         password, which is common in local development
     */
    public String getPassword() {
        return password;
    }

    /**
     * Sets the migration password.
     *
     * @param password the password, may be empty but never {@code null}
     * @throws IllegalArgumentException if {@code password} is {@code null}
     */
    public void setPassword(String password) {
        if (password == null) {
            throw new IllegalArgumentException(PREFIX + ".password must not be null");
        }
        this.password = password;
    }

    /**
     * Returns the full-URL override, or an empty string when the URL is assembled from
     * {@code host}, {@code port} and {@code database}.
     *
     * @return the override, never {@code null}; empty means "not configured"
     */
    public String getUrl() {
        return url;
    }

    /**
     * Sets a complete JDBC URL that replaces the one assembled from {@code host} / {@code port} /
     * {@code database}.
     *
     * <p>This is the escape hatch for targets the three coordinates cannot express - a socket, a
     * connection proxy, or an in-memory database in a test. When it is blank (the default, and what
     * {@code MED_MIGRATION_URL} resolves to when unset) the URL is assembled from the coordinates
     * instead.</p>
     *
     * @param url the JDBC URL, or {@code null}/blank to fall back to the assembled URL
     */
    public void setUrl(String url) {
        this.url = (url == null) ? DEFAULT_URL : url.trim();
    }

    /**
     * Returns the Flyway migration locations.
     *
     * @return a non-blank comma-separated list of locations, never {@code null}
     */
    public String getLocations() {
        return locations;
    }

    /**
     * Sets the Flyway migration locations.
     *
     * @param locations a non-blank comma-separated list of locations
     * @throws IllegalArgumentException if {@code locations} is {@code null} or blank; Flyway would
     *                                  then scan nothing and silently apply zero migrations
     */
    public void setLocations(String locations) {
        if (locations == null || locations.isBlank()) {
            throw new IllegalArgumentException(
                    PREFIX + ".locations must not be blank but was '" + locations + "'");
        }
        this.locations = locations;
    }

    /**
     * Returns how long the migration pool waits for a connection before failing startup.
     *
     * @return a positive timeout in milliseconds
     */
    public int getConnectionTimeoutMillis() {
        return connectionTimeoutMillis;
    }

    /**
     * Sets the connection timeout of the migration pool.
     *
     * @param connectionTimeoutMillis a positive timeout in milliseconds
     * @throws IllegalArgumentException if the timeout is not positive; a zero or negative value would
     *                                  make Hikari wait forever on an unreachable database and turn a
     *                                  misconfiguration into a hung deployment
     */
    public void setConnectionTimeoutMillis(int connectionTimeoutMillis) {
        if (connectionTimeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    PREFIX + ".connection-timeout-millis must be positive but was " + connectionTimeoutMillis);
        }
        this.connectionTimeoutMillis = connectionTimeoutMillis;
    }

    /**
     * Builds the JDBC URL of the physical MySQL server, or returns the explicit override when one is
     * configured.
     *
     * <p>{@code characterEncoding} is set to {@code UTF-8}, a <em>Java</em> charset name: the MySQL
     * server charset name {@code utf8mb4} makes Connector/J throw
     * {@code UnsupportedEncodingException} before the connection is opened. The driver negotiates
     * utf8mb4 on the wire against the utf8mb4 server the compose stack starts.</p>
     *
     * @return the JDBC URL, never {@code null}
     */
    public String getJdbcUrl() {
        if (!url.isBlank()) {
            return url;
        }
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=UTF-8&useSSL=false"
                + "&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
                + "&rewriteBatchedStatements=true";
    }

    /**
     * Returns whether an explicit URL override is in effect.
     *
     * @return {@code true} when {@code url} is non-blank
     */
    public boolean isUrlOverridden() {
        return !url.isBlank();
    }

    /**
     * Returns the name given to the migration connection pool, so it is distinguishable from the
     * application pool in logs and connection listings.
     *
     * @return a stable pool name, never {@code null}
     */
    public String getPoolName() {
        return "med-qa-migration-pool";
    }

    @Override
    public String toString() {
        // The password is deliberately omitted so a startup log can never leak the database secret.
        // The URL override is reported as a flag rather than verbatim for the same reason: an
        // operator may embed credentials in it.
        return "MedMigrationProperties{host=" + host
                + ", port=" + port
                + ", database=" + database
                + ", username=" + username
                + ", urlOverridden=" + isUrlOverridden()
                + ", locations=" + locations
                + ", connectionTimeoutMillis=" + connectionTimeoutMillis + '}';
    }
}
