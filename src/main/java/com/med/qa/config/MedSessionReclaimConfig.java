package com.med.qa.config;

import com.med.qa.alert.MedAlertNotifier;
import com.med.qa.job.MedJobRunLedger;
import com.med.qa.mapper.ChatMessageMapper;
import com.med.qa.mapper.ChatSessionMapper;
import com.med.qa.mapper.SessionArchiveMapper;
import com.med.qa.memory.lock.SessionLockService;
import com.med.qa.service.MedSessionReclaimScheduler;
import com.med.qa.service.MedSessionReclaimService;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the orphaned-cold-copy reclaim and its scheduler.
 *
 * <p>The whole configuration is gated by {@code med.session.reclaim.enabled}, which defaults to
 * {@code false}: this is the second capability in the project that deletes clinical records, so its
 * presence has to be a deliberate deployment decision rather than a default. With the switch off, neither
 * the service nor the scheduler exists and no timer can delete a row.</p>
 *
 * <p>{@link EnableScheduling} is declared here as well as in {@code MedAlertConfig},
 * {@code MedSessionRetentionConfig}, {@code MedSessionArchiveConfig} and {@code MedSessionPurgeConfig} on
 * purpose: the capabilities are independent, and a deployment that switched alerting off
 * ({@code med.alert.enabled=false}) but left the reclaim on would otherwise have a {@code @Scheduled}
 * method nobody ever invokes - a job that looks configured and never runs. Declaring the annotation
 * several times is harmless: Spring imports the same {@code SchedulingConfiguration} once.</p>
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(MedSessionReclaimProperties.class)
@ConditionalOnProperty(prefix = MedSessionReclaimProperties.PREFIX, name = "enabled",
        havingValue = "true")
public class MedSessionReclaimConfig {

    /** Bean name of the orphaned-cold-copy reclaim. */
    public static final String SERVICE = "medSessionReclaimService";

    /** Bean name of the scheduled driver. */
    public static final String SCHEDULER = "medSessionReclaimScheduler";

    /**
     * Contributes the orphaned-cold-copy reclaim.
     *
     * <p>{@link Lazy} on the Redisson parameter matters for the same reason as everywhere else in this
     * project: creating the client opens a connection, and the context must be able to refresh without
     * Redis available. The proxy only resolves when a run actually tries to take the cluster mutex.</p>
     *
     * <p>The session lock is a plain mandatory collaborator, not a lazily resolved one: it is part of what
     * makes one candidate accountable, because an export running concurrently is exactly the thing that
     * turns an orphan back into evidence.</p>
     *
     * @param sessionMapper  MyBatis mapper over {@code med_session}
     * @param archiveMapper  MyBatis mapper over the cold archive tables
     * @param messageMapper  MyBatis mapper over the sharded {@code med_message} table
     * @param lockService    distributed session lock serializing one candidate against an export or a restore
     * @param redissonClient lazily resolved client used for the cluster-wide reclaim mutex
     * @param properties     the {@code med.session.reclaim.*} policy
     * @return the reclaim service
     */
    @Bean(SERVICE)
    public MedSessionReclaimService medSessionReclaimService(
            ChatSessionMapper sessionMapper,
            SessionArchiveMapper archiveMapper,
            ChatMessageMapper messageMapper,
            SessionLockService lockService,
            @Lazy RedissonClient redissonClient,
            MedSessionReclaimProperties properties) {
        return new MedSessionReclaimService(sessionMapper, archiveMapper, messageMapper, lockService,
                redissonClient, properties);
    }

    /**
     * Contributes the scheduled driver.
     *
     * <p>The notifier arrives as an {@link ObjectProvider} because {@code med.alert.enabled=false} removes
     * it; the scheduler then still reclaims and simply has nowhere to push.</p>
     *
     * <p>The ledger arrives as a required dependency, and the {@code check-interval} is handed over
     * explicitly. Both matter for the same reason: the {@code @Scheduled} placeholder carries a literal
     * fallback ({@code :12h}), so a drifted property key would leave the scheduler firing on the fallback
     * while the ledger published a different interval -- and the stall rule, which is derived from the
     * published value, would then be tuned to a schedule the job does not actually run on. Passing the
     * bound property in makes the two read the same source.</p>
     *
     * @param reclaimService the reclaim this scheduler drives
     * @param notifier       provider of the alert dispatcher
     * @param ledger         the run ledger the scheduler records into
     * @param properties     the {@code med.session.reclaim.*} policy, source of the check interval
     * @return the scheduler
     */
    @Bean(SCHEDULER)
    public MedSessionReclaimScheduler medSessionReclaimScheduler(
            MedSessionReclaimService reclaimService,
            ObjectProvider<MedAlertNotifier> notifier,
            MedJobRunLedger ledger,
            MedSessionReclaimProperties properties) {
        return new MedSessionReclaimScheduler(reclaimService, notifier, ledger,
                properties.getCheckInterval());
    }
}
