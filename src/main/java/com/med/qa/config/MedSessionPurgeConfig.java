package com.med.qa.config;

import com.med.qa.alert.MedAlertNotifier;
import com.med.qa.job.MedJobRunLedger;
import com.med.qa.mapper.ChatMessageMapper;
import com.med.qa.mapper.ChatSessionMapper;
import com.med.qa.mapper.SessionArchiveMapper;
import com.med.qa.memory.lock.SessionLockService;
import com.med.qa.memory.serde.ProtoMessageCodec;
import com.med.qa.service.MedSessionPurgeScheduler;
import com.med.qa.service.MedSessionPurgeService;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the hot-shard purge and its scheduler.
 *
 * <p>The whole configuration is gated by {@code med.session.purge.enabled}, which defaults to
 * {@code false}: this is the only capability in the project that deletes clinical records, so its
 * presence has to be a deliberate deployment decision rather than a default. With the switch off,
 * neither the service nor the scheduler exists and no timer can delete a row.</p>
 *
 * <p>{@link EnableScheduling} is declared here as well as in {@code MedAlertConfig},
 * {@code MedSessionRetentionConfig} and {@code MedSessionArchiveConfig} on purpose: the capabilities are
 * independent, and a deployment that switched alerting off ({@code med.alert.enabled=false}) but left the
 * purge on would otherwise have a {@code @Scheduled} method nobody ever invokes - a job that looks
 * configured and never runs. Declaring the annotation several times is harmless: Spring imports the same
 * {@code SchedulingConfiguration} once.</p>
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(MedSessionPurgeProperties.class)
@ConditionalOnProperty(prefix = MedSessionPurgeProperties.PREFIX, name = "enabled",
        havingValue = "true")
public class MedSessionPurgeConfig {

    /** Bean name of the hot-shard purge. */
    public static final String SERVICE = "medSessionPurgeService";

    /** Bean name of the scheduled driver. */
    public static final String SCHEDULER = "medSessionPurgeScheduler";

    /**
     * Contributes the hot-shard purge.
     *
     * <p>{@link Lazy} on the Redisson parameter matters for the same reason as everywhere else in this
     * project: creating the client opens a connection, and the context must be able to refresh without
     * Redis available. The proxy only resolves when a run actually tries to take the cluster mutex.</p>
     *
     * <p>The session lock is a plain mandatory collaborator, not a lazily resolved one: since D51 it is
     * part of what makes one candidate accountable, because restoring a session is the one write that can
     * move it out of {@code ARCHIVED} underneath a candidate this run already selected.</p>
     *
     * @param sessionMapper  MyBatis mapper over {@code med_session}
     * @param archiveMapper  MyBatis mapper over the cold archive tables
     * @param messageMapper  MyBatis mapper over the sharded {@code med_message} table
     * @param codec          Protobuf codec of the unified storage spec
     * @param lockService    distributed session lock serializing one candidate against a restore
     * @param redissonClient lazily resolved client used for the cluster-wide purge mutex
     * @param properties     the {@code med.session.purge.*} policy
     * @return the purge service
     */
    @Bean(SERVICE)
    public MedSessionPurgeService medSessionPurgeService(
            ChatSessionMapper sessionMapper,
            SessionArchiveMapper archiveMapper,
            ChatMessageMapper messageMapper,
            ProtoMessageCodec codec,
            SessionLockService lockService,
            @Lazy RedissonClient redissonClient,
            MedSessionPurgeProperties properties) {
        return new MedSessionPurgeService(sessionMapper, archiveMapper, messageMapper, codec,
                lockService, redissonClient, properties);
    }

    /**
     * Contributes the scheduled driver.
     *
     * <p>The notifier arrives as an {@link ObjectProvider} because {@code med.alert.enabled=false} removes
     * it; the scheduler then still purges and simply has nowhere to push.</p>
     *
     * <p>The ledger arrives as a required dependency, and the {@code check-interval} is handed over
     * explicitly. Both matter for the same reason: the {@code @Scheduled} placeholder carries a literal
     * fallback ({@code :12h}), so a drifted property key would leave the scheduler firing on the fallback
     * while the ledger published a different interval -- and the stall rule, which is derived from the
     * published value, would then be tuned to a schedule the job does not actually run on. Passing the
     * bound property in makes the two read the same source.</p>
     *
     * @param purgeService the purge this scheduler drives
     * @param notifier     provider of the alert dispatcher
     * @param ledger       the run ledger the scheduler records into
     * @param properties   the {@code med.session.purge.*} policy, source of the check interval
     * @return the scheduler
     */
    @Bean(SCHEDULER)
    public MedSessionPurgeScheduler medSessionPurgeScheduler(
            MedSessionPurgeService purgeService,
            ObjectProvider<MedAlertNotifier> notifier,
            MedJobRunLedger ledger,
            MedSessionPurgeProperties properties) {
        return new MedSessionPurgeScheduler(purgeService, notifier, ledger,
                properties.getCheckInterval());
    }
}
