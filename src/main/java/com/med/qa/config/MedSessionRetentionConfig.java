package com.med.qa.config;

import com.med.qa.alert.MedAlertNotifier;
import com.med.qa.mapper.ChatSessionMapper;
import com.med.qa.memory.cache.RedisMessageCache;
import com.med.qa.memory.lock.SessionLockService;
import com.med.qa.service.MedSessionRetentionScheduler;
import com.med.qa.service.MedSessionRetentionService;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the consultation retention sweep and its scheduler.
 *
 * <p>The whole configuration is gated by {@code med.session.retention.enabled}, which defaults to
 * {@code false}: the sweep changes the lifecycle state of records nobody asked about, so its presence
 * has to be a deliberate deployment decision rather than a default. With the switch off, neither the
 * service nor the scheduler exists and nothing touches {@code med_session} on a timer.</p>
 *
 * <p>{@link EnableScheduling} is declared here as well as in {@code MedAlertConfig} on purpose: the two
 * capabilities are independent, and a deployment that switched alerting off
 * ({@code med.alert.enabled=false}) but left retention on would otherwise have a {@code @Scheduled}
 * method nobody ever invokes — a job that looks configured and never runs. Declaring the annotation
 * twice is harmless: Spring imports the same {@code SchedulingConfiguration} once.</p>
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(MedSessionRetentionProperties.class)
@ConditionalOnProperty(prefix = MedSessionRetentionProperties.PREFIX, name = "enabled",
        havingValue = "true")
public class MedSessionRetentionConfig {

    /** Bean name of the retention sweep. */
    public static final String SERVICE = "medSessionRetentionService";

    /** Bean name of the scheduled driver. */
    public static final String SCHEDULER = "medSessionRetentionScheduler";

    /**
     * Contributes the retention sweep.
     *
     * <p>{@link Lazy} on the Redisson parameter matters for the same reason as everywhere else in this
     * project: creating the client opens a connection, and the context must be able to refresh without
     * Redis available. The proxy only resolves when a sweep actually tries to take the cluster
     * mutex.</p>
     *
     * @param sessionMapper  MyBatis mapper over {@code med_session}
     * @param lockService    the distributed session lock shared with the message-append path
     * @param cache          Redis window cache, evicted for every archived session
     * @param redissonClient lazily resolved client used for the cluster-wide sweep mutex
     * @param properties     the {@code med.session.retention.*} policy
     * @return the sweep
     */
    @Bean(SERVICE)
    public MedSessionRetentionService medSessionRetentionService(ChatSessionMapper sessionMapper,
                                                                SessionLockService lockService,
                                                                RedisMessageCache cache,
                                                                @Lazy RedissonClient redissonClient,
                                                                MedSessionRetentionProperties properties) {
        return new MedSessionRetentionService(sessionMapper, lockService, cache, redissonClient,
                properties);
    }

    /**
     * Contributes the scheduled driver.
     *
     * <p>The notifier arrives as an {@link ObjectProvider} because {@code med.alert.enabled=false}
     * removes it; the scheduler then still sweeps and simply has nowhere to push.</p>
     *
     * @param retentionService the sweep this scheduler drives
     * @param notifier         provider of the alert dispatcher
     * @return the scheduler
     */
    @Bean(SCHEDULER)
    public MedSessionRetentionScheduler medSessionRetentionScheduler(
            MedSessionRetentionService retentionService,
            ObjectProvider<MedAlertNotifier> notifier) {
        return new MedSessionRetentionScheduler(retentionService, notifier);
    }
}
