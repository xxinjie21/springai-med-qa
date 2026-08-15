package com.med.qa.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.med.qa.config.MedSecurityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Unit tests of {@link MedApiKeyRegistry}: key resolution, unknown keys and configuration errors.
 */
class MedApiKeyRegistryTest {

    private static MedSecurityProperties propsWith(Map<String, MedApiKey> keys) {
        MedSecurityProperties properties = new MedSecurityProperties();
        properties.setKeys(keys);
        return properties;
    }

    private static MedApiKey patientKey(String tenant, String dept, String patient) {
        MedApiKey key = new MedApiKey();
        key.setTenantId(tenant);
        key.setDeptId(dept);
        key.setRole(MedRole.PATIENT);
        key.setPatientId(patient);
        return key;
    }

    private static MedApiKey staffKey(String tenant, String dept) {
        MedApiKey key = new MedApiKey();
        key.setTenantId(tenant);
        key.setDeptId(dept);
        key.setRole(MedRole.STAFF);
        return key;
    }

    @Test
    @DisplayName("resolves a configured patient key to its principal")
    void resolvesPatientKey() {
        MedApiKeyRegistry registry = new MedApiKeyRegistry(propsWith(Map.of(
                "secret", patientKey("hosp-1", "card", "pat-77"))));

        Optional<MedPrincipal> principal = registry.resolve("secret");

        assertThat(registry.isConfigured()).isTrue();
        assertThat(principal).isPresent();
        assertThat(principal.get().getRole()).isEqualTo(MedRole.PATIENT);
        assertThat(principal.get().getPatientId()).isEqualTo("pat-77");
        assertThat(principal.get().getTenantId()).isEqualTo("hosp-1");
    }

    @Test
    @DisplayName("resolves a configured staff key without a patient id")
    void resolvesStaffKey() {
        MedApiKeyRegistry registry = new MedApiKeyRegistry(propsWith(Map.of(
                "staff-secret", staffKey("hosp-1", "card"))));

        MedPrincipal principal = registry.resolve("staff-secret").orElseThrow();
        assertThat(principal.isStaff()).isTrue();
        assertThat(principal.getPatientId()).isNull();
    }

    @Nested
    @DisplayName("rejection")
    class Rejection {

        private final MedApiKeyRegistry registry = new MedApiKeyRegistry(propsWith(Map.of(
                "secret", patientKey("hosp-1", "card", "pat-77"))));

        @Test
        @DisplayName("an unknown key resolves to empty")
        void unknownKey() {
            assertThat(registry.resolve("wrong")).isEmpty();
        }

        @Test
        @DisplayName("a null key resolves to empty")
        void nullKey() {
            assertThat(registry.resolve(null)).isEmpty();
        }

        @Test
        @DisplayName("a blank key resolves to empty")
        void blankKey() {
            assertThat(registry.resolve("   ")).isEmpty();
        }
    }

    @Test
    @DisplayName("an empty key map yields an unconfigured registry")
    void emptyMap() {
        MedApiKeyRegistry registry = new MedApiKeyRegistry(new MedSecurityProperties());
        assertThat(registry.isConfigured()).isFalse();
        assertThat(registry.resolve("anything")).isEmpty();
    }

    @Test
    @DisplayName("a null keys map is treated as empty, not an error")
    void nullKeysMap() {
        MedSecurityProperties properties = new MedSecurityProperties();
        properties.setKeys(null);
        MedApiKeyRegistry registry = new MedApiKeyRegistry(properties);
        assertThat(registry.isConfigured()).isFalse();
    }

    @Test
    @DisplayName("keys are matched exactly, including case")
    void caseSensitive() {
        MedApiKeyRegistry registry = new MedApiKeyRegistry(propsWith(Map.of(
                "Secret", staffKey("hosp-1", "card"))));
        assertThat(registry.resolve("secret")).isEmpty();
        assertThat(registry.resolve("Secret")).isPresent();
    }

    @Test
    @DisplayName("a key mapping to a principal with a blank tenant fails fast")
    void rejectsInvalidPrincipal() {
        MedApiKey bad = new MedApiKey();
        bad.setTenantId("  ");
        bad.setDeptId("card");
        bad.setRole(MedRole.STAFF);
        Map<String, MedApiKey> keys = new LinkedHashMap<>();
        keys.put("bad", bad);

        assertThatThrownBy(() -> new MedApiKeyRegistry(propsWith(keys)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
