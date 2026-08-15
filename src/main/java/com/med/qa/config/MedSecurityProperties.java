package com.med.qa.config;

import com.med.qa.security.MedApiKey;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration of API-key authentication and patient-session ownership enforcement (D21).
 *
 * <p>The hospital deployment mounts a small map of opaque API keys to the principals they stand for;
 * the keys themselves are environment secrets and are never committed. Two switches govern behaviour:
 * {@code enabled} removes the filter from the servlet chain entirely (offline tests, local dev), and
 * {@code requireAuth} decides whether a request with no key is rejected ({@code true}) or treated as an
 * anonymous, unauthenticated call ({@code false}). The service-layer {@link
 * com.med.qa.security.PatientAccessGuard} still rejects any ownership-sensitive operation when no
 * principal is present, so {@code requireAuth=false} only relaxes the filter, not the guard.</p>
 */
@ConfigurationProperties(prefix = "med.security")
public class MedSecurityProperties {

    /** Whether the API-key authentication filter is registered in the servlet chain. */
    private boolean enabled = true;

    /** Whether a request without a key is rejected with {@code 401} instead of being let through. */
    private boolean requireAuth = true;

    /** Request header carrying the API key. */
    private String headerName = "X-API-Key";

    /** Opaque API key string to the principal it authenticates. Empty when no keys are mounted. */
    private Map<String, MedApiKey> keys = new LinkedHashMap<>();

    /**
     * Creates the properties with their safe defaults.
     */
    public MedSecurityProperties() {
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRequireAuth() {
        return requireAuth;
    }

    public void setRequireAuth(boolean requireAuth) {
        this.requireAuth = requireAuth;
    }

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        Assert.hasText(headerName, "headerName must not be blank");
        this.headerName = headerName;
    }

    public Map<String, MedApiKey> getKeys() {
        return keys;
    }

    public void setKeys(Map<String, MedApiKey> keys) {
        this.keys = (keys == null) ? new LinkedHashMap<>() : keys;
    }
}
