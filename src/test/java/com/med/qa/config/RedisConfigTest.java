package com.med.qa.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests of the Redis template wiring. The serializer contract matters beyond style: a JDK
 * serialized value would break byte compatibility with the Python middleware reading the same keys.
 */
@ExtendWith(MockitoExtension.class)
class RedisConfigTest {

    @Mock
    private RedisConnectionFactory connectionFactory;

    private final RedisConfig config = new RedisConfig();

    @Test
    @DisplayName("positive: the template is initialized with the given connection factory")
    void buildsInitializedTemplate() {
        RedisTemplate<String, byte[]> template = config.medMessageRedisTemplate(connectionFactory);

        assertNotNull(template);
        assertSame(connectionFactory, template.getConnectionFactory());
    }

    @Test
    @DisplayName("positive: keys are plain strings so med:chat:* keys stay human readable")
    void usesStringKeySerializer() {
        RedisTemplate<String, byte[]> template = config.medMessageRedisTemplate(connectionFactory);

        RedisSerializer<?> keySerializer = template.getKeySerializer();

        assertInstanceOf(StringRedisSerializer.class, keySerializer);
        assertArrayEquals("med:chat:t1:d1:s1".getBytes(StandardCharsets.UTF_8),
                template.getStringSerializer().serialize("med:chat:t1:d1:s1"));
    }

    @Test
    @DisplayName("positive: values pass through untouched, preserving raw protobuf bytes")
    void usesPassThroughValueSerializer() {
        RedisTemplate<String, byte[]> template = config.medMessageRedisTemplate(connectionFactory);

        @SuppressWarnings("unchecked")
        RedisSerializer<byte[]> valueSerializer = (RedisSerializer<byte[]>) template.getValueSerializer();
        byte[] payload = {0x0A, 0x04, 0x74, 0x65};

        assertArrayEquals(payload, valueSerializer.serialize(payload));
        assertArrayEquals(payload, valueSerializer.deserialize(payload));
    }

    @Test
    @DisplayName("positive: hash serializers mirror the top level ones")
    void hashSerializersMirrorTopLevel() {
        RedisTemplate<String, byte[]> template = config.medMessageRedisTemplate(connectionFactory);

        assertInstanceOf(StringRedisSerializer.class, template.getHashKeySerializer());
        assertEquals(template.getValueSerializer().getClass(), template.getHashValueSerializer().getClass());
    }

    @Test
    @DisplayName("exception: a missing connection factory fails fast at context startup")
    void rejectsMissingConnectionFactory() {
        assertThrows(IllegalStateException.class, () -> config.medMessageRedisTemplate(null));
    }

    @Test
    @DisplayName("the bean name constant matches the qualifier used by the cache component")
    void exposesStableBeanName() {
        assertEquals("medMessageRedisTemplate", RedisConfig.MESSAGE_REDIS_TEMPLATE);
    }
}
