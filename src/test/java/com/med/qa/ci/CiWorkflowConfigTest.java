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
 * Guard tests for the GitHub Actions CI workflow definition.
 *
 * <p>These tests parse {@code .github/workflows/ci.yml} and assert the
 * contract of the D5 iteration: triggered on main push/PR, builds with
 * JDK 17 (temurin), runs {@code mvnw verify} and uploads the JaCoCo
 * coverage report as an artifact. Any breaking edit to the pipeline
 * fails the build locally before it ever reaches GitHub.</p>
 */
class CiWorkflowConfigTest {

    private static Map<String, Object> workflow;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void loadWorkflow() throws IOException {
        Path file = workflowPath();
        try (InputStream in = Files.newInputStream(file)) {
            workflow = new Yaml().load(in);
        }
    }

    private static Path workflowPath() {
        // Maven surefire runs with working directory = project basedir.
        return Path.of(System.getProperty("user.dir"), ".github", "workflows", "ci.yml");
    }

    @Test
    @DisplayName("workflow file exists and parses as a non-empty YAML mapping")
    void workflowFileParses() {
        assertThat(Files.exists(workflowPath())).isTrue();
        assertThat(workflow).isNotNull().isNotEmpty();
        assertThat(workflow.get("name")).isEqualTo("CI");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("triggers on push and pull_request targeting main")
    void triggersOnMainPushAndPullRequest() {
        // SnakeYAML parses the bare key `on:` as boolean key TRUE (YAML 1.1).
        Object onSection = workflow.containsKey("on") ? workflow.get("on") : workflow.get(Boolean.TRUE);
        assertThat(onSection).as("on: trigger section").isInstanceOf(Map.class);
        Map<String, Object> on = (Map<String, Object>) onSection;

        Map<String, Object> push = (Map<String, Object>) on.get("push");
        Map<String, Object> pr = (Map<String, Object>) on.get("pull_request");
        assertThat((List<String>) push.get("branches")).containsExactly("main");
        assertThat((List<String>) pr.get("branches")).containsExactly("main");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("verify job runs mvnw verify on temurin JDK 17 and uploads jacoco artifact")
    void verifyJobContract() {
        Map<String, Object> jobs = (Map<String, Object>) workflow.get("jobs");
        assertThat(jobs).containsKey("verify");
        Map<String, Object> verify = (Map<String, Object>) jobs.get("verify");
        assertThat(verify.get("runs-on")).isEqualTo("ubuntu-latest");

        List<Map<String, Object>> steps = (List<Map<String, Object>>) verify.get("steps");
        assertThat(steps).isNotEmpty();

        // JDK setup step: temurin 17 with maven cache.
        Map<String, Object> javaWith = steps.stream()
                .filter(s -> String.valueOf(s.get("uses")).startsWith("actions/setup-java"))
                .map(s -> (Map<String, Object>) s.get("with"))
                .findFirst().orElseThrow();
        assertThat(javaWith.get("distribution")).isEqualTo("temurin");
        assertThat(String.valueOf(javaWith.get("java-version"))).isEqualTo("17");
        assertThat(javaWith.get("cache")).isEqualTo("maven");

        // Build step must call the maven wrapper with the verify phase.
        String buildRun = steps.stream()
                .map(s -> String.valueOf(s.get("run")))
                .filter(r -> r.contains("./mvnw"))
                .findFirst().orElseThrow();
        assertThat(buildRun).contains("verify");

        // Coverage upload step must target the jacoco site directory.
        Map<String, Object> uploadWith = steps.stream()
                .filter(s -> String.valueOf(s.get("uses")).startsWith("actions/upload-artifact"))
                .map(s -> (Map<String, Object>) s.get("with"))
                .filter(w -> "jacoco-coverage-report".equals(w.get("name")))
                .findFirst().orElseThrow();
        assertThat(String.valueOf(uploadWith.get("path"))).contains("target/site/jacoco");
    }

    @Test
    @DisplayName("workflow restricts token permissions to read-only contents")
    void permissionsAreReadOnly() {
        @SuppressWarnings("unchecked")
        Map<String, Object> permissions = (Map<String, Object>) workflow.get("permissions");
        assertThat(permissions).containsEntry("contents", "read");
    }

    @Test
    @DisplayName("boundary: loading a missing workflow file throws IOException")
    void missingWorkflowFileThrows() {
        Path missing = Path.of(System.getProperty("user.dir"), ".github", "workflows", "no-such.yml");
        assertThatThrownBy(() -> Files.newInputStream(missing)).isInstanceOf(IOException.class);
    }
}
