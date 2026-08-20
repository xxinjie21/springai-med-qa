package com.med.qa.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring of the rate-limit layer: binds {@link MedRateLimitProperties} so the {@code @RateLimit} aspect,
 * the service facade and their guard rails are configured from {@code med.rate-limit.*}.
 *
 * <p>No beans are declared here. The aspect and the service are component-scanned; this class exists so
 * the property binding lives with the other configuration classes instead of being attached to a
 * component as a side effect.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MedRateLimitProperties.class)
public class RateLimitConfig {
}
