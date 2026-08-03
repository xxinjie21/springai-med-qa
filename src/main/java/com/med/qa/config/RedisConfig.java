package com.med.qa.config;

import com.med.qa.memory.cache.MedCacheProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * Spring Data Redis wiring for the conversation memory cache.
 *
 * <p>The connection factory itself (Lettuce, pooling, timeouts, credentials) is left to Spring
 * Boot's official {@code spring.data.redis.*} auto-configuration — this class only contributes the
 * template used by the memory layer.</p>
 *
 * <p>Values are stored as raw Protobuf bytes, therefore the value serializer must be a pass-through
 * byte-array serializer. Using the default JDK serializer here would wrap the payload in Java
 * serialization framing and break byte compatibility with the heterogeneous Python middleware that
 * reads the same keys.</p>
 */
@Configuration
@EnableConfigurationProperties(MedCacheProperties.class)
public class RedisConfig {

    /** Bean name of the Protobuf-friendly template, referenced by the cache component. */
    public static final String MESSAGE_REDIS_TEMPLATE = "medMessageRedisTemplate";

    /**
     * Builds the {@code String} keyed, {@code byte[]} valued template backing
     * {@link com.med.qa.memory.cache.RedisMessageCache}.
     *
     * @param connectionFactory the Boot-managed Redis connection factory, must not be {@code null}
     * @return an initialized template with string keys and raw byte-array values
     * @throws IllegalStateException if {@code connectionFactory} is {@code null}
     */
    @Bean(MESSAGE_REDIS_TEMPLATE)
    public RedisTemplate<String, byte[]> medMessageRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        RedisSerializer<String> keySerializer = RedisSerializer.string();
        RedisSerializer<byte[]> valueSerializer = RedisSerializer.byteArray();
        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        template.setEnableDefaultSerializer(false);
        template.afterPropertiesSet();
        return template;
    }
}
