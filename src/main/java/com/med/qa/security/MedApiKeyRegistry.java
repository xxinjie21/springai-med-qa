package com.med.qa.security;

import com.med.qa.config.MedSecurityProperties;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves an opaque API key into the {@link MedPrincipal} it authenticates.
 *
 * <p>Built once at startup from {@link MedSecurityProperties}; the resulting key-to-principal map is
 * immutable so resolution is lock-free and thread-safe for the whole application lifetime. A missing,
 * blank or unknown key resolves to {@link Optional#empty()}, which the {@link ApiKeyAuthFilter} turns
 * into a {@code 401}. The registry never talks to a database or a cache — the key set is a fixed
 * deployment secret.</p>
 */
@Component
public class MedApiKeyRegistry {

    private final Map<String, MedPrincipal> principalsByKey;

    /**
     * Builds the immutable resolution map from the security configuration.
     *
     * @param properties API-key configuration, must not be {@code null}
     * @throws IllegalArgumentException if a configured key maps to an invalid principal (blank tenant
     *                                  or department, or a {@code null} role)
     */
    public MedApiKeyRegistry(MedSecurityProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        Map<String, MedPrincipal> map = new LinkedHashMap<>();
        if (properties.getKeys() != null) {
            for (Map.Entry<String, MedApiKey> entry : properties.getKeys().entrySet()) {
                MedApiKey key = entry.getValue();
                if (key == null) {
                    continue;
                }
                map.put(entry.getKey(), toPrincipal(key));
            }
        }
        this.principalsByKey = Collections.unmodifiableMap(map);
    }

    /**
     * Resolves an API key to its principal.
     *
     * @param apiKey raw key from the request header, may be {@code null} or blank
     * @return the authenticated principal, or {@link Optional#empty()} when the key is absent or unknown
     */
    public Optional<MedPrincipal> resolve(@Nullable String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(principalsByKey.get(apiKey));
    }

    /**
     * Reports whether any API key has been configured.
     *
     * @return {@code true} when at least one key is mounted
     */
    public boolean isConfigured() {
        return !principalsByKey.isEmpty();
    }

    private static MedPrincipal toPrincipal(MedApiKey key) {
        Assert.hasText(key.getTenantId(), "api key tenantId must not be blank");
        Assert.hasText(key.getDeptId(), "api key deptId must not be blank");
        Objects.requireNonNull(key.getRole(), "api key role must not be null");
        return new MedPrincipal(key.getTenantId(), key.getDeptId(), key.getRole(), key.getPatientId());
    }
}
