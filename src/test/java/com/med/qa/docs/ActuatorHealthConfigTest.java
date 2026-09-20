package com.med.qa.docs;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Guard tests for the production health endpoint the deployment stack depends on.
 *
 * <p>The compose stack, the Kubernetes probes in the handbook and every uptime check go through
 * {@code /actuator/health}. The {@code management} block in {@code application.yml} only documents
 * intent -- without the Actuator starter on the classpath the endpoint simply does not exist and
 * the container is marked unhealthy even though the application is fine. These tests tie the three
 * sides together: dependency present, endpoint exposed, probe pointing at it.</p>
 */
class ActuatorHealthConfigTest {

    /** Full {@code pom.xml} text. */
    private static String pom;

    /** Full {@code application.yml} text. */
    private static String applicationYml;

    /** Full {@code docker-compose.yml} text. */
    private static String compose;

    @BeforeAll
    static void readConfiguration() throws IOException {
        Path root = Path.of(System.getProperty("user.dir"));
        pom = Files.readString(root.resolve("pom.xml"));
        applicationYml = Files.readString(root.resolve("src/main/resources/application.yml"));
        compose = Files.readString(root.resolve("docker-compose.yml"));
    }

    @Test
    @DisplayName("the Actuator starter is a declared dependency, managed by the Spring Boot BOM")
    void actuatorStarterIsDeclared() {
        assertThat(pom).contains("spring-boot-starter-actuator");
    }

    @Test
    @DisplayName("the health endpoint infrastructure is really on the test classpath")
    void healthEndpointIsOnClasspath() {
        assertThatNoException().isThrownBy(
                () -> Class.forName("org.springframework.boot.actuate.health.HealthEndpoint"));
    }

    @Test
    @DisplayName("health, info and prometheus are exposed, and health never leaks component details")
    void managementEndpointsAreNarrowlyExposed() {
        assertThat(applicationYml).contains("exposure:");
        // prometheus joined the exposure list in D33: it is the scrape target of the alerting stack.
        // Anything beyond these three (env, beans, heapdump, threaddump...) must stay unexposed.
        assertThat(applicationYml).contains("include: health,info,prometheus");
        assertThat(applicationYml).contains("show-details: never");
    }

    @Test
    @DisplayName("the compose health check probes the documented health endpoint")
    void composeProbesTheHealthEndpoint() {
        assertThat(compose).contains("/actuator/health");
        assertThat(compose).contains("condition: service_healthy");
    }
}
