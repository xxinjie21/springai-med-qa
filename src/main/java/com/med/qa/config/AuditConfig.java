package com.med.qa.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring of the audit layer: binds {@link MedAuditProperties} so the {@code @MedAudit} aspect, the
 * audit service and their truncation limits are configured from {@code med.audit.*}.
 *
 * <p>No beans are declared here. The aspect and the service are component-scanned; this class exists
 * so the property binding lives with the other configuration classes instead of being attached to a
 * component as a side effect.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MedAuditProperties.class)
public class AuditConfig {
}
