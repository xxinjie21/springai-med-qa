package com.med.qa.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Security wiring for D21: API-key authentication and patient-session ownership enforcement.
 *
 * <p>Registers {@link MedSecurityProperties} as a managed bean so the {@link
 * com.med.qa.security.ApiKeyAuthFilter} and {@link com.med.qa.security.MedApiKeyRegistry} can be injected
 * with it. The actual filter and guard are declared in the {@code security} package; this class only
 * binds configuration and will host the department-scope authorization of later iterations.</p>
 */
@Configuration
@EnableConfigurationProperties(MedSecurityProperties.class)
public class SecurityConfig {
}
