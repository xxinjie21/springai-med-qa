package com.med.qa.ci;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guard tests for the D27 multi-stage {@code Dockerfile}.
 *
 * <p>The image must compile with the pinned Maven Wrapper inside a JDK stage and then ship the
 * application on a slim JRE, using Spring Boot layered-jar extraction so Docker can cache the
 * slow-changing dependency layers separately from the volatile application layer.</p>
 *
 * <p>Building the image needs a Docker daemon, which unit tests must not require. These tests
 * therefore parse the Dockerfile text and pin its structural properties, so a regression -- a
 * collapsed single-stage build, a fat JDK runtime, a missing layer copy, running as root, or the
 * pre-3.2 launcher class -- fails {@code mvn test} instead of only showing up in production.</p>
 */
class DockerfileConfigTest {

    /** Raw file lines, kept so ordering assertions can reason about the real layout. */
    private static List<String> lines;

    /** The whole file as one string, used for "appears before" cache-ordering checks. */
    private static String content;

    @BeforeAll
    static void readDockerfile() throws IOException {
        lines = Files.readAllLines(dockerfilePath());
        content = String.join("\n", lines);
    }

    private static Path dockerfilePath() {
        // Maven surefire runs with working directory = project basedir.
        return Path.of(System.getProperty("user.dir"), "Dockerfile");
    }

    /** Effective instruction lines: comments and blank lines stripped, each trimmed. */
    private static List<String> instructions() {
        return lines.stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }

    /** All instruction lines starting with the given Dockerfile keyword (case-insensitive). */
    private static List<String> directive(String keyword) {
        String prefix = keyword.toUpperCase(Locale.ROOT) + " ";
        return instructions().stream()
                .filter(line -> line.toUpperCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }

    @Test
    @DisplayName("the Dockerfile exists at the project root and contains instructions")
    void dockerfileExists() {
        assertThat(Files.exists(dockerfilePath())).isTrue();
        assertThat(instructions()).isNotEmpty();
    }

    @Test
    @DisplayName("it is a multi-stage build: a JDK builder stage plus a slim JRE runtime stage")
    void multiStageBuild() {
        List<String> froms = directive("FROM");
        assertThat(froms).as("expected exactly two build stages").hasSize(2);

        String builder = froms.get(0).toLowerCase(Locale.ROOT);
        String runtime = froms.get(1).toLowerCase(Locale.ROOT);

        // The builder compiles, so it needs the full JDK and must be aliased for --from copies.
        assertThat(builder).contains("-jdk");
        assertThat(builder).contains(" as builder");

        // The runtime only runs: no compiler, no Maven, no build tooling in the shipped image.
        assertThat(runtime).contains("-jre");
        assertThat(runtime).as("shipping a JDK bloats the image by ~200MB").doesNotContain("-jdk");
    }

    @Test
    @DisplayName("dependencies are warmed in their own layer before the sources are copied")
    void dependencyLayerIsCachedBeforeSources() {
        int pomCopy = content.indexOf("pom.xml");
        int goOffline = content.indexOf("dependency:go-offline");
        int srcCopy = content.indexOf("COPY src/");

        assertThat(pomCopy).as("pom.xml must be copied into the builder").isNotNegative();
        assertThat(goOffline).as("dependency:go-offline warms the cached layer").isNotNegative();
        assertThat(srcCopy).as("application sources must be copied").isNotNegative();

        // Cache ordering is the whole point: sources last, otherwise every edit re-resolves deps.
        assertThat(srcCopy).as("sources must be copied after the dependency warm-up").isGreaterThan(goOffline);
    }

    @Test
    @DisplayName("the build uses the pinned Maven Wrapper rather than a system maven")
    void buildsWithMavenWrapper() {
        assertThat(content).contains("./mvnw");
        assertThat(content).as("a system maven would drift from the pinned wrapper version")
                .doesNotContain("RUN mvn ");
    }

    @Test
    @DisplayName("the layered jar is exploded with the Spring Boot 3.x tools jarmode")
    void extractsLayeredJar() {
        assertThat(content).contains("-Djarmode=tools");
        assertThat(content).contains("extract");
        assertThat(content).as("without --layers the extraction is not layer-aware").contains("--layers");
        // layertools is the deprecated Boot 2.x jarmode; it must not creep back in.
        assertThat(content).doesNotContain("-Djarmode=layertools");
    }

    @Test
    @DisplayName("all four Spring Boot layers are copied slow-changing -> fast-changing")
    void copiesLayersInCacheOrder() {
        int dependencies = content.indexOf("extracted/dependencies/");
        int loader = content.indexOf("extracted/spring-boot-loader/");
        int snapshots = content.indexOf("extracted/snapshot-dependencies/");
        int application = content.indexOf("extracted/application/");

        assertThat(dependencies).as("the dependencies layer must be copied").isNotNegative();
        assertThat(loader).isGreaterThan(dependencies);
        assertThat(snapshots).isGreaterThan(loader);
        assertThat(application).as("the volatile application layer must be copied last")
                .isGreaterThan(snapshots);
    }

    @Test
    @DisplayName("the runtime stage drops privileges to a dedicated non-root user")
    void runsAsNonRoot() {
        List<String> users = directive("USER");
        assertThat(users).as("a USER directive is required").isNotEmpty();

        String user = users.get(users.size() - 1).substring("USER ".length()).trim();
        assertThat(user).isNotEmpty();
        assertThat(user).as("a medical backend must not run as root").isNotEqualTo("root");

        // The account has to be created before it can be switched to.
        assertThat(content).contains("useradd");
        assertThat(content).contains("groupadd");
    }

    @Test
    @DisplayName("the entrypoint boots via the Spring Boot 3.x JarLauncher and publishes the http port")
    void entrypointUsesJarLauncher() {
        assertThat(directive("ENTRYPOINT")).isNotEmpty();
        // Boot 3.2 moved the loader into the .launch subpackage; the old FQCN fails at runtime.
        assertThat(content).contains("org.springframework.boot.loader.launch.JarLauncher");
        assertThat(directive("EXPOSE")).anyMatch(line -> line.contains("8080"));
    }

    @Test
    @DisplayName("container-aware JVM ergonomics are set so cgroup memory limits are honoured")
    void containerAwareJvmOptions() {
        assertThat(content).contains("MaxRAMPercentage");
        assertThat(content).contains("ExitOnOutOfMemoryError");
        assertThat(directive("ENV")).anyMatch(line -> line.contains("JAVA_OPTS"));
    }

    @Test
    @DisplayName("no credentials are baked into image layers")
    void noSecretsInImage() {
        String lower = content.toLowerCase(Locale.ROOT);
        assertThat(lower).doesNotContain("api-key=");
        assertThat(lower).doesNotContain("password=");
        assertThat(lower).doesNotContain("med_security_keys");
    }

    @Test
    @DisplayName("boundary: unsupported directives are absent and a missing Dockerfile read throws")
    void boundaryUnknownDirectiveAndMissingFile() {
        assertThat(directive("ONBUILD")).isEmpty();
        assertThat(directive("MAINTAINER")).as("MAINTAINER is deprecated in favour of LABEL").isEmpty();

        assertThatThrownBy(() -> Files.readAllLines(
                Path.of(System.getProperty("user.dir"), "No-Such-Dockerfile")))
                .isInstanceOf(IOException.class);
    }
}
