package com.med.qa.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unit tests of {@link MedSecurityProperties}: defaults, toggles and validation.
 */
class MedSecurityPropertiesTest {

    @Test
    @DisplayName("ships with safe defaults: enabled, auth required, dept scope on, X-API-Key header, empty keys")
    void defaults() {
        MedSecurityProperties properties = new MedSecurityProperties();
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.isRequireAuth()).isTrue();
        assertThat(properties.isDeptScopeEnabled()).isTrue();
        assertThat(properties.getHeaderName()).isEqualTo("X-API-Key");
        assertThat(properties.getKeys()).isEmpty();
    }

    @Test
    @DisplayName("toggles can be flipped")
    void toggles() {
        MedSecurityProperties properties = new MedSecurityProperties();
        properties.setEnabled(false);
        properties.setRequireAuth(false);
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isRequireAuth()).isFalse();
    }

    @Test
    @DisplayName("department-scope enforcement toggles independently of api-key authentication")
    void deptScopeToggleIsIndependent() {
        MedSecurityProperties properties = new MedSecurityProperties();
        properties.setDeptScopeEnabled(false);
        assertThat(properties.isDeptScopeEnabled()).isFalse();
        assertThat(properties.isEnabled()).isTrue();

        properties.setDeptScopeEnabled(true);
        assertThat(properties.isDeptScopeEnabled()).isTrue();
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        private final MedSecurityProperties properties = new MedSecurityProperties();

        @Test
        @DisplayName("a blank header name is rejected")
        void blankHeader() {
            assertThatThrownBy(() -> properties.setHeaderName("  "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a null keys map is normalised to an empty map, not stored as null")
        void nullKeysNormalised() {
            properties.setKeys(null);
            assertThat(properties.getKeys()).isEmpty();
        }

        @Test
        @DisplayName("a populated keys map is retained")
        void retainsKeys() {
            Map<String, com.med.qa.security.MedApiKey> keys = new LinkedHashMap<>();
            keys.put("k", new com.med.qa.security.MedApiKey());
            properties.setKeys(keys);
            assertThat(properties.getKeys()).containsKey("k");
        }
    }
}
