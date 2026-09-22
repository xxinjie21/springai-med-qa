package com.med.qa.integration;

import java.util.Locale;

/**
 * Policy that decides whether the Testcontainers integration suite may be skipped.
 *
 * <p>Until D34 the suite could disable itself silently: {@link DockerAvailableCondition} asked
 * Testcontainers whether Docker was reachable, Testcontainers answered {@code false} on a Docker
 * Engine that rejected its hard-coded API fallback, and the whole class was reported as
 * <em>skipped</em>. The build stayed green while the real-middleware assertions never ran, which is
 * exactly how two production-blocking defects (a missing {@code flyway-mysql} module and migrations
 * driven through the ShardingSphere proxy) survived every build.</p>
 *
 * <p>A skip is only acceptable when it is a deliberate local convenience. On a CI runner that is
 * expected to provide Docker, the same condition must fail the build instead. That distinction is
 * expressed by a single switch:</p>
 *
 * <ul>
 *   <li>JVM system property {@value #REQUIRED_PROPERTY} (highest precedence, so a single
 *       {@code -Dmed.test.integration.required=true} on the Maven command line is enough), or</li>
 *   <li>environment variable {@value #REQUIRED_ENV_VARIABLE} (how the workflow sets it).</li>
 * </ul>
 *
 * <p>Truthy values are {@code true}, {@code 1}, {@code yes} and {@code on}, case-insensitive. When
 * the property is present but not truthy it wins over the environment variable, so a developer can
 * force {@code -Dmed.test.integration.required=false} to opt out on a machine whose environment
 * carries the flag.</p>
 */
public final class IntegrationTestRequirements {

    /** System property that makes a missing Docker daemon a build failure. */
    public static final String REQUIRED_PROPERTY = "med.test.integration.required";

    /** Environment variable form of {@link #REQUIRED_PROPERTY}, used by the CI workflow. */
    public static final String REQUIRED_ENV_VARIABLE = "MED_TEST_INTEGRATION_REQUIRED";

    private IntegrationTestRequirements() {
    }

    /**
     * Resolves whether Docker is mandatory for this run from the ambient JVM and process
     * environment.
     *
     * @return {@code true} when a missing daemon must fail the build instead of skipping the suite
     */
    public static boolean isDockerRequired() {
        return isDockerRequired(
                System.getProperty(REQUIRED_PROPERTY),
                System.getenv(REQUIRED_ENV_VARIABLE));
    }

    /**
     * Precedence-aware variant of {@link #isDockerRequired()}, with both sources injected so the
     * resolution rules can be tested without mutating the JVM or the process environment.
     *
     * @param propertyValue      value of {@value #REQUIRED_PROPERTY}, or {@code null} when unset
     * @param environmentValue   value of {@value #REQUIRED_ENV_VARIABLE}, or {@code null} when unset
     * @return {@code true} when Docker is mandatory
     */
    public static boolean isDockerRequired(String propertyValue, String environmentValue) {
        if (propertyValue != null && !propertyValue.isBlank()) {
            return isTruthy(propertyValue);
        }
        return isTruthy(environmentValue);
    }

    /**
     * Interprets a raw configuration value as a boolean switch.
     *
     * @param raw raw value, may be {@code null} or blank
     * @return {@code true} for {@code true} / {@code 1} / {@code yes} / {@code on} (case-insensitive)
     */
    public static boolean isTruthy(String raw) {
        if (raw == null) {
            return false;
        }
        String normalised = raw.trim().toLowerCase(Locale.ROOT);
        return "true".equals(normalised)
                || "1".equals(normalised)
                || "yes".equals(normalised)
                || "on".equals(normalised);
    }

    /**
     * Probes the daemon and normalises every failure to {@code false}.
     *
     * <p>A probe that throws is indistinguishable, from the suite's point of view, from a machine
     * without Docker: both mean "real middleware cannot run here". Normalising here keeps the
     * decision logic in one place instead of repeating the {@code try}/{@code catch} in every
     * integration test.</p>
     *
     * @param probe the probe to consult, never {@code null}
     * @return {@code true} only when the probe answered {@code true} without throwing
     */
    public static boolean probeDockerAvailable(DockerAvailabilityProbe probe) {
        try {
            return probe.isDockerAvailable();
        } catch (Throwable probeFailure) {
            return false;
        }
    }

    /**
     * Fails loudly when Docker is mandatory for this run but unavailable.
     *
     * @param dockerRequired  whether a missing daemon must fail the build
     * @param dockerAvailable whether the daemon is reachable
     * @throws IllegalStateException when {@code dockerRequired} is {@code true} and
     *                               {@code dockerAvailable} is {@code false}
     */
    public static void verifyDockerAvailable(boolean dockerRequired, boolean dockerAvailable) {
        if (dockerRequired && !dockerAvailable) {
            throw new IllegalStateException(
                    "Docker is required for the Testcontainers integration suite but no usable daemon is"
                            + " reachable. Set " + REQUIRED_PROPERTY + "=true (or "
                            + REQUIRED_ENV_VARIABLE + "=true) only on a runner that provides Docker, or"
                            + " unset it to skip the suite on a developer machine.");
        }
    }

    /**
     * Convenience for the integration tests' {@code @BeforeAll}: probes the daemon, then enforces
     * the policy. A no-op when the suite is allowed to be skipped, so the same call site works both
     * locally and on CI.
     *
     * @param probe the probe to consult, never {@code null}
     * @throws IllegalStateException when Docker is mandatory but unavailable
     */
    public static void verifyDockerAvailableForCurrentRun(DockerAvailabilityProbe probe) {
        verifyDockerAvailable(isDockerRequired(), probeDockerAvailable(probe));
    }
}
