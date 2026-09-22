package com.med.qa.integration;

/**
 * Container image coordinates used by the Testcontainers integration suite.
 *
 * <p>These strings appear in two places that cannot see each other: the integration tests that start
 * the containers, and the CI workflow that pre-pulls them into a cached {@code docker save} archive.
 * Keeping them here gives the workflow guard test ({@code com.med.qa.ci.CiIntegrationStageConfigTest})
 * a single value to compare against, so a version bump that only lands in one of the two places fails
 * the build instead of quietly warming the cache with the wrong image.</p>
 */
public final class MedIntegrationImages {

    /** MySQL image the storage / migration integration tests boot. */
    public static final String MYSQL = "mysql:8.0.36";

    /** Redis Stack image backing the Redisson lock and the message cache in the integration tests. */
    public static final String REDIS_STACK = "redis/redis-stack:7.4.0-v3";

    private MedIntegrationImages() {
    }
}
