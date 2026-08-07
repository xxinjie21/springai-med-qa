package com.med.qa.ci;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guard tests for the local development stack definition.
 *
 * <p>The vector store requires RediSearch and RedisJSON, which plain {@code redis:7} does not ship.
 * These tests parse {@code docker-compose.yml} and pin that the declared node is a Redis Stack
 * image exposing the Redis port, so a downgrade to a vanilla Redis image fails the build instead of
 * failing at the first {@code FT.CREATE}.</p>
 */
class DockerComposeConfigTest {

    private static Map<String, Object> compose;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void loadCompose() throws IOException {
        try (InputStream in = Files.newInputStream(composePath())) {
            compose = (Map<String, Object>) new Yaml().load(in);
        }
    }

    private static Path composePath() {
        // Maven surefire runs with working directory = project basedir.
        return Path.of(System.getProperty("user.dir"), "docker-compose.yml");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> redisStackService() {
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        return (Map<String, Object>) services.get("redis-stack");
    }

    @Test
    @DisplayName("the compose file exists at the project root and parses as YAML")
    void composeFileParses() {
        assertThat(Files.exists(composePath())).isTrue();
        assertThat(compose).isNotNull().containsKey("services");
    }

    @Test
    @DisplayName("a redis-stack service is declared with a RediSearch capable image")
    void redisStackServiceUsesStackImage() {
        Map<String, Object> service = redisStackService();

        assertThat(service).as("services.redis-stack must be declared").isNotNull();
        assertThat((String) service.get("image"))
                .as("plain redis images have no RediSearch module")
                .startsWith("redis/redis-stack:");
    }

    @Test
    @DisplayName("the redis port is published so the app, Redisson and the index share one node")
    @SuppressWarnings("unchecked")
    void redisPortIsPublished() {
        List<String> ports = (List<String>) redisStackService().get("ports");

        assertThat(ports).isNotEmpty();
        assertThat(ports).anyMatch(mapping -> mapping.endsWith(":6379"));
    }

    @Test
    @DisplayName("a health check is declared so dependent services can wait for readiness")
    @SuppressWarnings("unchecked")
    void healthCheckIsDeclared() {
        Map<String, Object> healthcheck = (Map<String, Object>) redisStackService().get("healthcheck");

        assertThat(healthcheck).isNotNull();
        assertThat((List<String>) healthcheck.get("test")).contains("redis-cli");
        assertThat(healthcheck).containsKeys("interval", "retries");
    }

    @Test
    @DisplayName("data is kept in a named volume so the index survives a container restart")
    @SuppressWarnings("unchecked")
    void dataVolumeIsDeclared() {
        List<String> volumes = (List<String>) redisStackService().get("volumes");
        Map<String, Object> declared = (Map<String, Object>) compose.get("volumes");

        assertThat(volumes).anyMatch(mapping -> mapping.endsWith(":/data"));
        assertThat(declared).containsKey("redis-stack-data");
    }

    @Test
    @DisplayName("an unknown service is absent, guarding against accidental stack edits")
    void unknownServiceIsAbsent() {
        @SuppressWarnings("unchecked")
        Map<String, Object> services = (Map<String, Object>) compose.get("services");

        assertThat(services).doesNotContainKey("redis");
        assertThatThrownBy(() -> {
            Object missing = services.get("does-not-exist");
            missing.toString();
        }).isInstanceOf(NullPointerException.class);
    }
}
