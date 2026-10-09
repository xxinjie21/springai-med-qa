package com.med.qa.config;

import com.med.qa.alert.MedAlertNotifier;
import com.med.qa.mapper.ChatMessageMapper;
import com.med.qa.mapper.SessionArchiveMapper;
import com.med.qa.memory.serde.ProtoMessageCodec;
import com.med.qa.service.MedSessionArchiveExportScheduler;
import com.med.qa.service.MedSessionArchiveExportService;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the cold transcript archive export and its scheduler.
 *
 * <p>The whole configuration is gated by {@code med.session.archive.enabled}, which defaults to
 * {@code false}: the export writes copies of clinical records nobody asked about, so its presence has
 * to be a deliberate deployment decision rather than a default. With the switch off, neither the
 * service nor the scheduler exists and no timer touches the database.</p>
 *
 * <p>{@link EnableScheduling} is declared here as well as in {@code MedAlertConfig} and
 * {@code MedSessionRetentionConfig} on purpose: the capabilities are independent, and a deployment that
 * switched alerting off ({@code med.alert.enabled=false}) but left the archive on would otherwise have
 * a {@code @Scheduled} method nobody ever invokes - a job that looks configured and never runs.
 * Declaring the annotation several times is harmless: Spring imports the same
 * {@code SchedulingConfiguration} once.</p>
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(MedSessionArchiveProperties.class)
@ConditionalOnProperty(prefix = MedSessionArchiveProperties.PREFIX, name = "enabled",
        havingValue = "true")
public class MedSessionArchiveConfig {

    /** Bean name of the cold archive export. */
    public static final String SERVICE = "medSessionArchiveExportService";

    /** Bean name of the scheduled driver. */
    public static final String SCHEDULER = "medSessionArchiveExportScheduler";

    /**
     * Contributes the cold archive export.
     *
     * <p>{@link Lazy} on the Redisson parameter matters for the same reason as everywhere else in this
     * project: creating the client opens a connection, and the context must be able to refresh without
     * Redis available. The proxy only resolves when a run actually tries to take the cluster
     * mutex.</p>
     *
     * @param messageMapper  MyBatis mapper over the sharded {@code med_message} table
     * @param archiveMapper  MyBatis mapper over the cold archive tables
     * @param codec          Protobuf codec of the unified storage spec
     * @param redissonClient lazily resolved client used for the cluster-wide export mutex
     * @param properties     the {@code med.session.archive.*} policy
     * @return the export service
     */
    @Bean(SERVICE)
    public MedSessionArchiveExportService medSessionArchiveExportService(
            ChatMessageMapper messageMapper,
            SessionArchiveMapper archiveMapper,
            ProtoMessageCodec codec,
            @Lazy RedissonClient redissonClient,
            MedSessionArchiveProperties properties) {
        return new MedSessionArchiveExportService(messageMapper, archiveMapper, codec, redissonClient,
                properties);
    }

    /**
     * Contributes the scheduled driver.
     *
     * <p>The notifier arrives as an {@link ObjectProvider} because {@code med.alert.enabled=false}
     * removes it; the scheduler then still exports and simply has nowhere to push.</p>
     *
     * @param exportService the export this scheduler drives
     * @param notifier      provider of the alert dispatcher
     * @return the scheduler
     */
    @Bean(SCHEDULER)
    public MedSessionArchiveExportScheduler medSessionArchiveExportScheduler(
            MedSessionArchiveExportService exportService,
            ObjectProvider<MedAlertNotifier> notifier) {
        return new MedSessionArchiveExportScheduler(exportService, notifier);
    }
}
