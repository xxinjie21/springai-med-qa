package com.med.qa.config;

import com.med.qa.actuator.MedComponentInfoContributor;
import com.med.qa.actuator.MedStorageHealthIndicator;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import javax.sql.DataSource;

/**
 * Registers the application's Actuator contributions.
 *
 * <p>Both beans are guarded by {@code @ConditionalOnBean}: some offline test slices run without a
 * Redis connection factory or a JDBC data source, and a hard dependency here would break contexts
 * that never ask for a health report in the first place. In production both stores are always
 * present, so the probe is contributed normally.</p>
 */
@Configuration
public class ActuatorObservabilityConfig {

    /** Bean name of the storage probe; it also becomes the health component key. */
    public static final String STORAGE_HEALTH_INDICATOR = "medStorageHealthIndicator";

    /** Bean name of the version matrix contributor. */
    public static final String COMPONENT_INFO_CONTRIBUTOR = "medComponentInfoContributor";

    /**
     * Contributes the MySQL + Redis probe behind {@code /actuator/health}.
     *
     * @param redisConnectionFactory Boot-managed Redis connection factory
     * @param dataSource ShardingSphere-managed MySQL data source
     * @return the storage health indicator
     */
    @Bean(STORAGE_HEALTH_INDICATOR)
    @ConditionalOnBean({RedisConnectionFactory.class, DataSource.class})
    public MedStorageHealthIndicator medStorageHealthIndicator(RedisConnectionFactory redisConnectionFactory,
                                                              DataSource dataSource) {
        return new MedStorageHealthIndicator(redisConnectionFactory, dataSource);
    }

    /**
     * Contributes the runtime version matrix behind {@code /actuator/info}.
     *
     * @return the component info contributor
     */
    @Bean(COMPONENT_INFO_CONTRIBUTOR)
    public InfoContributor medComponentInfoContributor() {
        return new MedComponentInfoContributor();
    }
}
