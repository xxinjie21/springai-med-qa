package com.med.qa.ci;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guard tests for {@code .dockerignore}, the companion of the D27 multi-stage build.
 *
 * <p>Two failure modes are pinned here. First, leaking local state: {@code target/}, IDE metadata,
 * logs, {@code .env} files and the local {@code .workbuddy/} workspace must never be uploaded into
 * the build context or baked into an image layer. Second, over-excluding: the builder stage still
 * needs {@code pom.xml}, {@code mvnw}, the {@code .mvn/wrapper} jar and {@code src/}, so an
 * over-eager pattern would break the image build in a way no other test would catch.</p>
 */
class DockerIgnoreConfigTest {

    /** Effective ignore patterns: comments and blank lines stripped. */
    private static List<String> patterns;

    @BeforeAll
    static void readDockerIgnore() throws IOException {
        patterns = Files.readAllLines(dockerIgnorePath()).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }

    private static Path dockerIgnorePath() {
        return Path.of(System.getProperty("user.dir"), ".dockerignore");
    }

    @Test
    @DisplayName(".dockerignore exists at the project root and declares patterns")
    void dockerIgnoreExists() {
        assertThat(Files.exists(dockerIgnorePath())).isTrue();
        assertThat(patterns).isNotEmpty();
    }

    @Test
    @DisplayName("local build output and VCS metadata stay out of the build context")
    void excludesBuildOutputAndVcs() {
        assertThat(patterns).as("the builder stage compiles from source itself").contains("target/");
        assertThat(patterns).anyMatch(pattern -> pattern.startsWith(".git"));
    }

    @Test
    @DisplayName("local workspace data, logs and secrets never enter an image layer")
    void excludesWorkspaceLogsAndSecrets() {
        assertThat(patterns).contains(".workbuddy/");
        assertThat(patterns).anyMatch(pattern -> pattern.equals("*.log") || pattern.equals("logs/"));
        assertThat(patterns).contains(".env");
        assertThat(patterns).anyMatch(pattern -> pattern.endsWith(".local"));
        assertThat(patterns).contains("application-local.yml");
    }

    @Test
    @DisplayName("boundary: nothing the builder stage needs is excluded")
    void doesNotExcludeBuildInputs() {
        // Exact-match guard: excluding any of these would break `COPY mvnw pom.xml ./` or `COPY src/`.
        assertThat(patterns).doesNotContain("pom.xml", "mvnw", "src/", "src", ".mvn/", ".mvn", "*");

        // The wrapper jar must survive: only the wrapper's scratch dirs may be ignored.
        assertThat(patterns).noneMatch(pattern -> pattern.equals(".mvn/wrapper/")
                || pattern.equals(".mvn/wrapper/maven-wrapper.jar"));
        assertThat(patterns).allMatch(pattern -> !pattern.startsWith(".mvn/")
                || pattern.startsWith(".mvn/."));
    }

    @Test
    @DisplayName("boundary: reading a non-existent ignore file throws")
    void missingIgnoreFileThrows() {
        Path missing = Path.of(System.getProperty("user.dir"), ".no-such-dockerignore");

        assertThat(Files.exists(missing)).isFalse();
        assertThatThrownBy(() -> Files.readAllLines(missing)).isInstanceOf(IOException.class);
    }
}
