package com.med.qa.config;

import com.med.qa.memory.lock.MedLockProperties;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * Redisson client wiring, backing the distributed session lock (and, from D25 on, the annotation
 * driven rate limiter).
 *
 * <h2>Why the client is declared here instead of relying on the starter's auto-configuration</h2>
 * <p>{@code RedissonAutoConfigurationV2} also contributes a {@code RedissonConnectionFactory}, which
 * would take over Spring Data Redis from Lettuce and — being an eagerly instantiated singleton —
 * would open a Redis connection during context refresh. That breaks the project invariant that the
 * application context boots (and the whole unit test suite runs) without any middleware. The
 * auto-configuration is therefore excluded in {@code application.yml} and the client is declared
 * here as a {@link Lazy} bean, so the first actual lock acquisition triggers the connection.</p>
 *
 * <p>All connection settings are still read from Spring Boot's official {@code spring.data.redis.*}
 * namespace, so Redisson and the Lettuce-based message cache always target the same instance.</p>
 */
@Configuration
@EnableConfigurationProperties({MedLockProperties.class, RedisProperties.class})
public class RedissonConfig {

    /** Bean name of the shared Redisson client. */
    public static final String REDISSON_CLIENT = "redissonClient";

    /** Address scheme used for plain TCP connections. */
    public static final String PLAIN_SCHEME = "redis://";

    /** Address scheme used when {@code spring.data.redis.ssl.enabled} is set. */
    public static final String SSL_SCHEME = "rediss://";

    /**
     * Creates the shared Redisson client.
     *
     * <p>Declared {@link Lazy}: {@code Redisson.create} connects eagerly and would fail a
     * middleware-less startup. Injection points must therefore also be annotated {@code @Lazy} so
     * Spring hands them a proxy instead of forcing instantiation.</p>
     *
     * @param redisProperties Boot's {@code spring.data.redis.*} settings, must not be {@code null}
     * @param lockProperties  {@code med.lock.*} settings supplying the watchdog period, must not be
     *                        {@code null}
     * @return a connected Redisson client, never {@code null}
     * @throws org.redisson.client.RedisConnectionException if Redis is unreachable on first use
     */
    @Bean(name = REDISSON_CLIENT, destroyMethod = "shutdown")
    @Lazy
    public RedissonClient redissonClient(RedisProperties redisProperties,
                                         MedLockProperties lockProperties) {
        return Redisson.create(buildConfig(redisProperties, lockProperties));
    }

    /**
     * Translates the Spring Boot Redis settings into a Redisson single-server configuration.
     *
     * <p>Exposed as a static method so the mapping can be asserted in unit tests without opening a
     * connection.</p>
     *
     * @param redisProperties Boot's {@code spring.data.redis.*} settings, must not be {@code null}
     * @param lockProperties  {@code med.lock.*} settings supplying the watchdog period, must not be
     *                        {@code null}
     * @return a Redisson configuration pointing at the configured instance, never {@code null}
     * @throws IllegalArgumentException if either argument is {@code null} or the configured host is
     *                                  blank, which would silently fall back to localhost in
     *                                  production
     */
    public static Config buildConfig(RedisProperties redisProperties, MedLockProperties lockProperties) {
        if (redisProperties == null) {
            throw new IllegalArgumentException("redisProperties must not be null");
        }
        if (lockProperties == null) {
            throw new IllegalArgumentException("lockProperties must not be null");
        }
        if (!StringUtils.hasText(redisProperties.getHost())) {
            throw new IllegalArgumentException("spring.data.redis.host must not be blank");
        }

        Config config = new Config();
        config.setLockWatchdogTimeout(lockProperties.getWatchdogTimeout().toMillis());

        String scheme = redisProperties.getSsl() != null && redisProperties.getSsl().isEnabled()
                ? SSL_SCHEME
                : PLAIN_SCHEME;
        SingleServerConfig server = config.useSingleServer()
                .setAddress(scheme + redisProperties.getHost() + ":" + redisProperties.getPort())
                .setDatabase(redisProperties.getDatabase());

        if (StringUtils.hasText(redisProperties.getUsername())) {
            server.setUsername(redisProperties.getUsername());
        }
        if (StringUtils.hasText(redisProperties.getPassword())) {
            server.setPassword(redisProperties.getPassword());
        }
        if (StringUtils.hasText(redisProperties.getClientName())) {
            server.setClientName(redisProperties.getClientName());
        }
        applyIfPositive(redisProperties.getTimeout(), server::setTimeout);
        applyIfPositive(redisProperties.getConnectTimeout(), server::setConnectTimeout);
        return config;
    }

    private static void applyIfPositive(Duration duration, java.util.function.IntConsumer setter) {
        if (duration != null && !duration.isZero() && !duration.isNegative()) {
            setter.accept((int) duration.toMillis());
        }
    }
}
