package com.med.qa.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cross-file contract tests for the per-profile configuration files.
 *
 * <h2>Why this class exists</h2>
 * <p>The 2026-09-25 review found a P0 that 1389 green tests had all missed:
 * {@code application-prod.yml} re-declared {@code management.endpoints.web.exposure.include} as
 * {@code health,info}, and Spring Boot <em>replaces</em> a list property rather than merging it with
 * the value in {@code application.yml}. Because both the {@code Dockerfile} and
 * {@code docker-compose.yml} activate the {@code prod} profile, {@code /actuator/prometheus} became a
 * 404 in production, Prometheus could no longer scrape {@code med_qa_alert_total}, and every rule in
 * {@code deploy/prometheus/med-qa-alerts.yml} became unreachable — the entire observability investment
 * silently reduced to zero.</p>
 *
 * <p>The defect survived because every existing test looked at <em>one</em> file at a time. The
 * guard that was supposed to cover it asserted a <em>prefix</em> ({@code "include: health,info"}),
 * which the correct value also satisfies, and no test in the repository referenced
 * {@code application-prod.yml} at all.</p>
 *
 * <h2>What is asserted here</h2>
 * <p>These tests do not grep the YAML. They load the base file and each profile file through Spring
 * Boot's own {@link YamlPropertySourceLoader}, overlay them in the same precedence order the
 * framework uses at startup, and bind the result with the same {@link Binder} — so what is compared
 * is the value the application would really see, not the text of one file. The contract is:
 * <strong>no profile may remove an endpoint the base configuration exposes.</strong> A profile may
 * add one; dropping one requires changing this contract deliberately.</p>
 */
class ApplicationProfileContractTest {

    /** The property whose override caused P0-1. */
    private static final String EXPOSURE_INCLUDE = "management.endpoints.web.exposure.include";

    /** Every actuator endpoint the base configuration exposes. */
    private static final List<String> BASE_ENDPOINTS = List.of("health", "info", "prometheus");

    /** The profiles whose files must be covered by this contract. */
    private static final Set<String> KNOWN_PROFILES = Set.of("dev", "prod");

    /** {@code src/main/resources}, home of the configuration files under test. */
    private static Path resources;

    /** Profile files discovered on disk, as {@code application-<profile>.yml}. */
    private static List<Path> profileFiles;

    @BeforeAll
    static void locateConfigurationFiles() throws IOException {
        resources = Path.of(System.getProperty("user.dir"), "src/main/resources");
        try (var stream = Files.list(resources)) {
            profileFiles = stream
                    .filter(path -> path.getFileName().toString().startsWith("application-"))
                    .filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .toList();
        }
    }

    // ------------------------------------------------------------------ the contract

    @Test
    @DisplayName("the base configuration exposes exactly the three endpoints the deployment needs")
    void baseConfigurationExposesTheDocumentedEndpoints() {
        // health/info back the container probe and the version matrix; prometheus is the scrape
        // target of deploy/prometheus/prometheus.yml. Nothing else (env, beans, heapdump) may appear.
        assertThat(exposureInclude()).isEqualTo(BASE_ENDPOINTS);
    }

    @Test
    @DisplayName("no profile removes an endpoint the base configuration exposes")
    void noProfileNarrowsTheBaseExposure() {
        assertThat(profileFiles).as("at least one profile file must exist").isNotEmpty();
        for (Path profileFile : profileFiles) {
            assertNothingRemoved(profileName(profileFile), BASE_ENDPOINTS, exposureInclude(profileFile));
        }
    }

    @Test
    @DisplayName("the production profile still exposes the Prometheus scrape target")
    void productionProfileExposesPrometheus() {
        // The profile the image actually runs: without prometheus here, every alert rule in
        // deploy/prometheus/med-qa-alerts.yml is dead code in production.
        List<String> prodEndpoints = exposureInclude(resources.resolve("application-prod.yml"));
        assertThat(prodEndpoints).contains("prometheus");
        assertThat(prodEndpoints).contains("health");
    }

    @Test
    @DisplayName("every profile file on disk is covered by the contract, so a new one cannot slip through")
    void everyProfileFileIsCovered() {
        // A new application-<profile>.yml must be reviewed against this contract rather than silently
        // inheriting it; naming the expected set makes that a deliberate act.
        Set<String> profiles = new TreeSet<>();
        profileFiles.forEach(file -> profiles.add(profileName(file)));
        assertThat(profiles).isEqualTo(new TreeSet<>(KNOWN_PROFILES));
    }

    @Test
    @DisplayName("the profile under contract is the one the deployment stack really activates")
    void theContractedProfileIsTheOneDeploymentActivates() throws IOException {
        // If compose or the image switched to another profile, the prod assertions above would pass
        // vacuously while production ran an unguarded profile.
        Path root = Path.of(System.getProperty("user.dir"));
        String dockerfile = Files.readString(root.resolve("Dockerfile"));
        String compose = Files.readString(root.resolve("docker-compose.yml"));

        assertThat(dockerfile).contains("SPRING_PROFILES_ACTIVE=\"prod\"");
        assertThat(compose).contains("SPRING_PROFILES_ACTIVE: prod");
        assertThat(KNOWN_PROFILES).contains("prod");
    }

    // ------------------------------------------------------------------ the guard itself

    @Test
    @DisplayName("boundary: the contract rejects a profile that narrows the exposure list")
    void narrowingOverrideIsRejected() {
        assertThatThrownBy(() -> assertNothingRemoved("prod", List.of("health", "info", "prometheus"),
                List.of("health", "info")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("prod")
                .hasMessageContaining("prometheus");
    }

    @Test
    @DisplayName("boundary: the contract accepts a profile that widens or reorders the exposure list")
    void wideningOverrideIsAccepted() {
        assertNothingRemoved("dev", List.of("health", "info"), List.of("prometheus", "health", "info", "metrics"));
    }

    @Test
    @DisplayName("boundary: an empty profile list is rejected, not treated as \"nothing to check\"")
    void emptyOverrideIsRejected() {
        assertThatThrownBy(() -> assertNothingRemoved("prod", List.of("health", "info", "prometheus"), List.of()))
                .isInstanceOf(AssertionError.class);
    }

    // ------------------------------------------------------------------ the resolver itself

    @Test
    @DisplayName("the resolver really lets a profile override the base value (precedence, not concatenation)")
    void resolverHonoursProfilePrecedence(@TempDir Path tempDir) throws IOException {
        // Without this, a broken harness would make every contract assertion above vacuous: if the
        // base value always won, a narrowing override would look harmless.
        Path base = Files.writeString(tempDir.resolve("application.yml"),
                "management:\n  endpoints:\n    web:\n      exposure:\n        include: health,info,prometheus\n");
        Path overlay = Files.writeString(tempDir.resolve("application-prod.yml"),
                "management:\n  endpoints:\n    web:\n      exposure:\n        include: health,info\n");

        assertThat(exposureInclude(base, List.of(overlay))).isEqualTo(List.of("health", "info"));
        assertThat(exposureInclude(base, List.of())).isEqualTo(List.of("health", "info", "prometheus"));
    }

    @Test
    @DisplayName("boundary: a missing property resolves to an empty list instead of throwing")
    void missingPropertyResolvesToEmptyList(@TempDir Path tempDir) throws IOException {
        Path base = Files.writeString(tempDir.resolve("application.yml"), "server:\n  port: 8080\n");
        assertThat(exposureInclude(base, List.of())).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Resolves the effective {@code include} list for the real base file with the given profile
     * files overlaid.
     *
     * @param overlays profile files to overlay, highest precedence last
     * @return the endpoints the application would actually expose
     */
    private static List<String> exposureInclude(Path... overlays) {
        return exposureInclude(resources.resolve("application.yml"), List.of(overlays));
    }

    /**
     * Resolves the effective {@code include} list the way Spring Boot does at startup: load the base
     * file first, then overlay the profile files so a profile value wins.
     *
     * @param baseFile the base configuration file
     * @param overlays profile files, in the order they should take precedence
     * @return the bound endpoint list, empty when the property is absent
     */
    private static List<String> exposureInclude(Path baseFile, List<Path> overlays) {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        // Drop the ambient JVM sources: a stray -D on the build machine must not decide this contract.
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);

        for (PropertySource<?> source : load(baseFile)) {
            sources.addLast(source);
        }
        for (Path overlay : overlays) {
            List<PropertySource<?>> loaded = load(overlay);
            for (int i = loaded.size() - 1; i >= 0; i--) {
                sources.addFirst(loaded.get(i));
            }
        }
        return Binder.get(environment)
                .bind(EXPOSURE_INCLUDE, Bindable.listOf(String.class))
                .orElse(List.of());
    }

    /** Loads one YAML file into property sources using Spring Boot's own loader. */
    private static List<PropertySource<?>> load(Path file) {
        try {
            return new YamlPropertySourceLoader()
                    .load(file.getFileName().toString(), new FileSystemResource(file));
        } catch (IOException ex) {
            throw new IllegalStateException("unable to load " + file, ex);
        }
    }

    /**
     * Asserts that a profile's effective endpoint list does not drop anything the base list exposes.
     *
     * @param profile      profile name, used in the failure message
     * @param base         endpoints the base configuration exposes
     * @param profileValue endpoints the profile effectively exposes
     * @throws AssertionError when the profile removes an endpoint
     */
    static void assertNothingRemoved(String profile, List<String> base, List<String> profileValue) {
        List<String> removed = new ArrayList<>(base);
        removed.removeAll(profileValue);
        assertThat(removed)
                .as("profile '%s' must not remove an endpoint the base configuration exposes "
                        + "(Spring Boot replaces a list property instead of merging it); "
                        + "removed: %s", profile, removed)
                .isEmpty();
    }

    /** {@code application-prod.yml} to {@code prod}. */
    private static String profileName(Path profileFile) {
        String name = profileFile.getFileName().toString();
        return name.substring("application-".length(), name.length() - ".yml".length());
    }
}
