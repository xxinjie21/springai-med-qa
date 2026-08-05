package com.med.qa.config;

import com.med.qa.memory.lock.MedLockProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RedissonConfigTest {

    private static RedisProperties redisProperties() {
        RedisProperties properties = new RedisProperties();
        properties.setHost("redis.hospital.internal");
        properties.setPort(6380);
        properties.setDatabase(3);
        properties.setClientName("med-qa");
        properties.setTimeout(Duration.ofSeconds(3));
        properties.setConnectTimeout(Duration.ofSeconds(2));
        return properties;
    }

    @Test
    @DisplayName("spring.data.redis settings are mapped onto a single server configuration")
    void mapsSpringPropertiesToSingleServer() {
        Config config = RedissonConfig.buildConfig(redisProperties(), new MedLockProperties());

        SingleServerConfig server = config.useSingleServer();
        assertThat(server.getAddress()).isEqualTo("redis://redis.hospital.internal:6380");
        assertThat(server.getDatabase()).isEqualTo(3);
        assertThat(server.getClientName()).isEqualTo("med-qa");
        assertThat(server.getTimeout()).isEqualTo(3000);
        assertThat(server.getConnectTimeout()).isEqualTo(2000);
    }

    @Test
    @DisplayName("the med.lock watchdog timeout drives the redisson lease renewal period")
    void appliesWatchdogTimeout() {
        MedLockProperties lockProperties = new MedLockProperties();
        lockProperties.setWatchdogTimeout(Duration.ofSeconds(45));

        Config config = RedissonConfig.buildConfig(redisProperties(), lockProperties);

        assertThat(config.getLockWatchdogTimeout()).isEqualTo(45_000L);
    }

    @Test
    @DisplayName("credentials are only forwarded when actually configured")
    void forwardsCredentialsWhenPresent() {
        RedisProperties properties = redisProperties();
        properties.setUsername("med-app");
        properties.setPassword("s3cret");

        SingleServerConfig server =
                RedissonConfig.buildConfig(properties, new MedLockProperties()).useSingleServer();

        assertThat(server.getUsername()).isEqualTo("med-app");
        assertThat(server.getPassword()).isEqualTo("s3cret");
    }

    @Test
    @DisplayName("an empty password stays unset so local dev does not send a bogus AUTH")
    void skipsBlankCredentials() {
        RedisProperties properties = redisProperties();
        properties.setPassword("");

        SingleServerConfig server =
                RedissonConfig.buildConfig(properties, new MedLockProperties()).useSingleServer();

        assertThat(server.getPassword()).isNull();
        assertThat(server.getUsername()).isNull();
    }

    @Test
    @DisplayName("tls switches the address scheme to rediss://")
    void usesTlsSchemeWhenSslEnabled() {
        RedisProperties properties = redisProperties();
        properties.getSsl().setEnabled(true);

        SingleServerConfig server =
                RedissonConfig.buildConfig(properties, new MedLockProperties()).useSingleServer();

        assertThat(server.getAddress()).startsWith(RedissonConfig.SSL_SCHEME);
    }

    @Test
    @DisplayName("null or zero timeouts keep the redisson defaults instead of disabling them")
    void keepsDefaultsForMissingTimeouts() {
        RedisProperties properties = redisProperties();
        properties.setTimeout(null);
        properties.setConnectTimeout(Duration.ZERO);

        SingleServerConfig server =
                RedissonConfig.buildConfig(properties, new MedLockProperties()).useSingleServer();

        assertThat(server.getTimeout()).isPositive();
        assertThat(server.getConnectTimeout()).isPositive();
    }

    @Test
    @DisplayName("a blank host is rejected rather than silently pointing at localhost")
    void rejectsBlankHost() {
        RedisProperties properties = redisProperties();
        properties.setHost("  ");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> RedissonConfig.buildConfig(properties, new MedLockProperties()))
                .withMessageContaining("spring.data.redis.host");
    }

    @Test
    @DisplayName("null arguments are rejected as programming errors")
    void rejectsNullArguments() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RedissonConfig.buildConfig(null, new MedLockProperties()))
                .withMessageContaining("redisProperties");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RedissonConfig.buildConfig(redisProperties(), null))
                .withMessageContaining("lockProperties");
    }

    @Test
    @DisplayName("the client bean stays lazy so the context boots without a reachable redis")
    void clientBeanIsLazyAndShutsDown() throws NoSuchMethodException {
        Method beanMethod = RedissonConfig.class
                .getMethod("redissonClient", RedisProperties.class, MedLockProperties.class);

        assertThat(beanMethod.getAnnotation(Lazy.class)).isNotNull();
        Bean bean = beanMethod.getAnnotation(Bean.class);
        assertThat(bean).isNotNull();
        assertThat(bean.destroyMethod()).isEqualTo("shutdown");
        assertThat(bean.name()).containsExactly(RedissonConfig.REDISSON_CLIENT);
    }
}
