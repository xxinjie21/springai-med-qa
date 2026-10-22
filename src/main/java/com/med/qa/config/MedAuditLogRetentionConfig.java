package com.med.qa.config;

import com.med.qa.alert.MedAlertNotifier;
import com.med.qa.job.MedJobRunLedger;
import com.med.qa.mapper.AuditLogMapper;
import com.med.qa.service.MedAuditLogRetentionScheduler;
import com.med.qa.service.MedAuditLogRetentionService;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the audit-log retention trim and its scheduler.
 *
 * <p>The whole configuration is gated by {@code med.audit-log.retention.enabled}, which defaults to
 * {@code false}: this capability deletes audit rows, and an audit row has no second copy, so its
 * presence has to be a deliberate deployment decision rather than a default. With the switch off,
 * neither the service nor the scheduler exists and no timer can delete a row.</p>
 *
 * <p>{@link EnableScheduling} is declared here as well as in {@code MedAlertConfig},
 * {@code MedSessionRetentionConfig}, {@code MedSessionArchiveConfig}, {@code MedSessionPurgeConfig}
 * and {@code MedSessionReclaimConfig} on purpose: the capabilities are independent, and a deployment
 * that switched alerting off ({@code med.alert.enabled=false}) but left the trim on would otherwise
 * have a {@code @Scheduled} method nobody ever invokes - a job that looks configured and never runs.
 * Declaring the annotation several times is harmless: Spring imports the same
 * {@code SchedulingConfiguration} once.</p>
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(MedAuditLogRetentionProperties.class)
@ConditionalOnProperty(prefix = MedAuditLogRetentionProperties.PREFIX, name = "enabled",
        havingValue = "true")
public class MedAuditLogRetentionConfig {

    /** Bean name of the audit-log retention trim. */
    public static final String SERVICE = "medAuditLogRetentionService";

    /** Bean name of the scheduled driver. */
    public static final String SCHEDULER = "medAuditLogRetentionScheduler";

    /**
     * Contributes the audit-log retention trim.
     *
     * <p>{@link Lazy} on the Redisson parameter matters for the same reason as everywhere else in
     * this project: creating the client opens a connection, and the context must be able to refresh
     * without Redis available. The proxy only resolves when a run actually tries to take the cluster
     * mutex.</p>
     *
     * @param auditLogMapper MyBatis mapper over {@code med_audit_log}
     * @param redissonClient lazily resolved client used for the cluster-wide trim mutex
     * @param properties     the {@code med.audit-log.retention.*} policy
     * @return the trim service
     */
    @Bean(SERVICE)
    public MedAuditLogRetentionService medAuditLogRetentionService(
            AuditLogMapper auditLogMapper,
            @Lazy RedissonClient redissonClient,
            MedAuditLogRetentionProperties properties) {
        return new MedAuditLogRetentionService(auditLogMapper, redissonClient, properties);
    }

    /**
     * Contributes the scheduled driver.
     *
     * <p>The notifier arrives as an {@link ObjectProvider} because {@code med.alert.enabled=false}
     * removes it; the scheduler then still trims and simply has nowhere to push.</p>
     *
     * <p>The ledger arrives as a required dependency, and the {@code check-interval} is handed over
     * explicitly. Both matter for the same reason as in the four session configs: the
     * {@code @Scheduled} placeholder carries a literal fallback ({@code :24h}), so a drifted
     * property key would leave the scheduler firing on the fallback while the ledger published a
     * different interval -- and the stall rule, which is derived from the published value, would
     * then be tuned to a schedule the job does not actually run on. Passing the bound property in
     * makes the two read the same source.</p>
     *
     * @param retentionService the trim this scheduler drives
     * @param notifier         provider of the alert dispatcher
     * @param ledger           the run ledger the scheduler records into
     * @param properties       the {@code med.audit-log.retention.*} policy, source of the check
     *                         interval
     * @return the scheduler
     */
    @Bean(SCHEDULER)
    public MedAuditLogRetentionScheduler medAuditLogRetentionScheduler(
            MedAuditLogRetentionService retentionService,
            ObjectProvider<MedAlertNotifier> notifier,
            MedJobRunLedger ledger,
            MedAuditLogRetentionProperties properties) {
        return new MedAuditLogRetentionScheduler(retentionService, notifier, ledger,
                properties.getCheckInterval());
    }
}
