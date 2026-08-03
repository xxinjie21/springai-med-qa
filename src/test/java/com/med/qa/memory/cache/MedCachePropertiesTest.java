package com.med.qa.memory.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of the {@code med.cache.*} configuration binding and its guard rails.
 */
class MedCachePropertiesTest {

    private static MedCacheProperties bind(Map<String, Object> raw) {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(raw);
        return new Binder(source).bind("med.cache", MedCacheProperties.class).get();
    }

    @Test
    @DisplayName("defaults keep a bounded, expiring cache without any configuration")
    void defaultsAreBoundedAndExpiring() {
        MedCacheProperties properties = new MedCacheProperties();

        assertEquals(Duration.ofMinutes(30), properties.getTtl());
        assertEquals(200, properties.getMaxMessages());
        assertTrue(properties.isWindowBounded());
    }

    @Test
    @DisplayName("positive: values are bound from the med.cache namespace")
    void bindsFromConfigurationNamespace() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("med.cache.ttl", "45m");
        raw.put("med.cache.max-messages", "50");

        MedCacheProperties properties = bind(raw);

        assertEquals(Duration.ofMinutes(45), properties.getTtl());
        assertEquals(50, properties.getMaxMessages());
    }

    @Test
    @DisplayName("boundary: a zero window disables trimming instead of failing")
    void zeroWindowMeansUnbounded() {
        MedCacheProperties properties = new MedCacheProperties();

        properties.setMaxMessages(0);

        assertEquals(0, properties.getMaxMessages());
        assertFalse(properties.isWindowBounded());
    }

    @Test
    @DisplayName("boundary: a one message window is still a bounded window")
    void singleMessageWindowIsBounded() {
        MedCacheProperties properties = new MedCacheProperties();

        properties.setMaxMessages(1);

        assertTrue(properties.isWindowBounded());
    }

    @Test
    @DisplayName("exception: a null ttl is rejected")
    void rejectsNullTtl() {
        MedCacheProperties properties = new MedCacheProperties();

        assertThrows(IllegalArgumentException.class, () -> properties.setTtl(null));
    }

    @Test
    @DisplayName("exception: a zero or negative ttl would keep medical data forever")
    void rejectsNonPositiveTtl() {
        MedCacheProperties properties = new MedCacheProperties();

        assertThrows(IllegalArgumentException.class, () -> properties.setTtl(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> properties.setTtl(Duration.ofSeconds(-1)));
    }

    @Test
    @DisplayName("exception: a negative window is rejected")
    void rejectsNegativeWindow() {
        MedCacheProperties properties = new MedCacheProperties();

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> properties.setMaxMessages(-5));

        assertTrue(error.getMessage().contains("max-messages"));
    }

    @Test
    @DisplayName("exception: an invalid ttl fails the binding, so startup fails fast")
    void invalidTtlFailsBinding() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("med.cache.ttl", "0s");

        assertThrows(BindException.class, () -> bind(raw));
    }

    @Test
    @DisplayName("toString exposes the effective tuning for startup logs")
    void toStringExposesTuning() {
        MedCacheProperties properties = new MedCacheProperties();

        String text = properties.toString();

        assertTrue(text.contains("ttl="));
        assertTrue(text.contains("maxMessages=200"));
    }
}
