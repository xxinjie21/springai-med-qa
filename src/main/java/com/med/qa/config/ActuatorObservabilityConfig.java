package com.med.qa.config;

import com.med.qa.actuator.MedComponentInfoContributor;
import com.med.qa.actuator.MedStorageHealthIndicator;
import com.med.qa.actuator.MedVectorIndexHealthIndicator;
import com.med.qa.rag.MedRagIndexProperties;
import com.med.qa.rag.MedVectorIndexProbe;
import com.med.qa.rag.MedVectorStoreProperties;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import javax.sql.DataSource;

import redis.clients.jedis.JedisPooled;

/**
 * Registers the application's Actuator contributions.
 *
 * <p>The storage probe is guarded by {@code @ConditionalOnBean}: some offline test slices run without
 * a Redis connection factory or a JDBC data source, and a hard dependency here would break contexts
 * that never ask for a health report in the first place. In production both stores are always
 * present, so the probe is contributed normally.</p>
 *
 * <p>The vector-index probe is guarded by {@code med.rag.index.enabled} instead. It needs no
 * {@code @ConditionalOnBean} because {@code config.VectorStoreConfig} declares the Jedis client
 * unconditionally (lazily), so the only reason for the component to be absent is that the operator
 * turned it off — which is exactly what a deployment without Redis Stack has to do.</p>
 */
@Configuration
public class ActuatorObservabilityConfig {

    /** Bean name of the storage probe; it also becomes the health component key. */
    public static final String STORAGE_HEALTH_INDICATOR = "medStorageHealthIndicator";

    /** Bean name of the vector-index probe; it also becomes the health component key. */
    public static final String VECTOR_INDEX_HEALTH_INDICATOR = "medVectorIndexHealthIndicator";

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
     * Contributes the RAG vector-index probe behind {@code /actuator/health}.
     *
     * <p>Answers a question the storage probe cannot: reachable Redis does not imply a usable
     * RediSearch index. A missing index or a drifted TAG schema both degrade retrieval without ever
     * failing a request, so they are reported as an explicit {@code DOWN} component.</p>
     *
     * <p>{@link Lazy} on the Jedis parameter matters: {@code VectorStoreConfig} declares the client
     * lazily because creating it opens a connection pool, and a plain injection would force that
     * during context refresh — breaking the invariant that the context boots without middleware. The
     * probe is only constructed with a lazy proxy and resolves it on its first command.</p>
     *
     * @param jedis           lazily resolved Jedis client dedicated to the vector index
     * @param storeProperties index topology, source of the probed index name
     * @param indexProperties expected TAG fields and the monitoring switches
     * @return the vector-index health indicator
     */
    @Bean(VECTOR_INDEX_HEALTH_INDICATOR)
    @ConditionalOnProperty(prefix = MedRagIndexProperties.PREFIX, name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public MedVectorIndexHealthIndicator medVectorIndexHealthIndicator(@Lazy JedisPooled jedis,
                                                                      MedVectorStoreProperties storeProperties,
                                                                      MedRagIndexProperties indexProperties) {
        return new MedVectorIndexHealthIndicator(
                new MedVectorIndexProbe(jedis, storeProperties), indexProperties);
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
