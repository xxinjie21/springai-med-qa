package com.med.qa.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IntegrationTestRequirements}, the policy that decides whether the
 * Testcontainers suite may skip itself.
 *
 * <p>This policy is the D35 answer to a silent failure: the integration suite used to disable itself
 * whenever the Docker probe answered {@code false}, and the build stayed green while the
 * real-middleware assertions never ran. The rules that turn that skip into a hard failure on CI are
 * pinned here, with every external input injected, so the tests are deterministic on a machine with
 * or without Docker.</p>
 */
class IntegrationTestRequirementsTest {

    // ---------------------------------------------------------------------------------------------
    // Switch resolution
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the switch names are the ones the CI workflow and the docs agree on")
    void switchNamesAreStable() {
        assertThat(IntegrationTestRequirements.REQUIRED_PROPERTY).isEqualTo("med.test.integration.required");
        assertThat(IntegrationTestRequirements.REQUIRED_ENV_VARIABLE).isEqualTo("MED_TEST_INTEGRATION_REQUIRED");
    }

    @Test
    @DisplayName("the system property alone turns the requirement on")
    void propertyAloneEnablesTheRequirement() {
        assertThat(IntegrationTestRequirements.isDockerRequired("true", null)).isTrue();
    }

    @Test
    @DisplayName("the environment variable alone turns the requirement on")
    void environmentVariableAloneEnablesTheRequirement() {
        assertThat(IntegrationTestRequirements.isDockerRequired(null, "true")).isTrue();
    }

    @Test
    @DisplayName("when both sources are set the system property wins, so a local opt-out is possible")
    void propertyOutranksTheEnvironmentVariable() {
        assertThat(IntegrationTestRequirements.isDockerRequired("false", "true")).isFalse();
        assertThat(IntegrationTestRequirements.isDockerRequired("true", "false")).isTrue();
    }

    @Test
    @DisplayName("a blank property falls through to the environment variable instead of shadowing it")
    void blankPropertyFallsThrough() {
        assertThat(IntegrationTestRequirements.isDockerRequired("   ", "true")).isTrue();
        assertThat(IntegrationTestRequirements.isDockerRequired("", "1")).isTrue();
    }

    @Test
    @DisplayName("boundary: neither source set means the suite may skip itself")
    void neitherSourceSetMeansOptional() {
        assertThat(IntegrationTestRequirements.isDockerRequired(null, null)).isFalse();
        assertThat(IntegrationTestRequirements.isDockerRequired("false", "0")).isFalse();
    }

    @Test
    @DisplayName("truthy spellings are recognised case-insensitively")
    void truthySpellings() {
        for (String raw : new String[] {"true", "TRUE", "True", " true ", "1", "yes", "YES", "on", "ON"}) {
            assertThat(IntegrationTestRequirements.isTruthy(raw))
                    .as("%s must be truthy", raw).isTrue();
        }
        for (String raw : new String[] {"false", "0", "no", "off", "banana", "", "   "}) {
            assertThat(IntegrationTestRequirements.isTruthy(raw))
                    .as("%s must not be truthy", raw).isFalse();
        }
    }

    @Test
    @DisplayName("boundary: a null raw value is not truthy")
    void nullIsNotTruthy() {
        assertThat(IntegrationTestRequirements.isTruthy(null)).isFalse();
    }

    @Test
    @DisplayName("the global lookup reads exactly the two documented sources")
    void globalLookupUsesTheDocumentedSources() {
        assertThat(IntegrationTestRequirements.isDockerRequired())
                .isEqualTo(IntegrationTestRequirements.isDockerRequired(
                        System.getProperty(IntegrationTestRequirements.REQUIRED_PROPERTY),
                        System.getenv(IntegrationTestRequirements.REQUIRED_ENV_VARIABLE)));
    }

    // ---------------------------------------------------------------------------------------------
    // Probe normalisation
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a probe that answers true is reported as available")
    void probeAnswersTrue() {
        assertThat(IntegrationTestRequirements.probeDockerAvailable(() -> true)).isTrue();
    }

    @Test
    @DisplayName("a probe that answers false is reported as unavailable")
    void probeAnswersFalse() {
        assertThat(IntegrationTestRequirements.probeDockerAvailable(() -> false)).isFalse();
    }

    @Test
    @DisplayName("boundary: a probe that throws is normalised to unavailable instead of propagating")
    void throwingProbeIsNormalised() {
        DockerAvailabilityProbe exploding = mock(DockerAvailabilityProbe.class);
        when(exploding.isDockerAvailable()).thenThrow(new IllegalStateException("cannot pull the ryuk image"));

        assertThat(IntegrationTestRequirements.probeDockerAvailable(exploding)).isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // The gate itself
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("verifyDockerAvailable")
    class VerifyDockerAvailable {

        @Test
        @DisplayName("required and available passes silently")
        void requiredAndAvailablePasses() {
            assertThatCode(() -> IntegrationTestRequirements.verifyDockerAvailable(true, true))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("required and unavailable fails, and the message names the switch an operator must flip")
        void requiredAndUnavailableFails() {
            assertThatThrownBy(() -> IntegrationTestRequirements.verifyDockerAvailable(true, false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(IntegrationTestRequirements.REQUIRED_PROPERTY)
                    .hasMessageContaining(IntegrationTestRequirements.REQUIRED_ENV_VARIABLE);
        }

        @Test
        @DisplayName("boundary: optional and unavailable is allowed, which keeps the offline suite green")
        void optionalAndUnavailablePasses() {
            assertThatCode(() -> IntegrationTestRequirements.verifyDockerAvailable(false, false))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("the current-run convenience really throws when the mandatory switch is set and Docker is absent")
    void currentRunConvenienceHonoursTheSwitch() {
        String previous = System.getProperty(IntegrationTestRequirements.REQUIRED_PROPERTY);
        try {
            System.setProperty(IntegrationTestRequirements.REQUIRED_PROPERTY, "true");
            assertThatThrownBy(() -> IntegrationTestRequirements
                    .verifyDockerAvailableForCurrentRun(() -> false))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            if (previous == null) {
                System.clearProperty(IntegrationTestRequirements.REQUIRED_PROPERTY);
            } else {
                System.setProperty(IntegrationTestRequirements.REQUIRED_PROPERTY, previous);
            }
        }
        // With the property gone, the outcome must match whatever the ambient environment says, so
        // this assertion holds both on a developer machine (optional) and on a runner that exports
        // MED_TEST_INTEGRATION_REQUIRED globally.
        if (IntegrationTestRequirements.isDockerRequired()) {
            assertThatThrownBy(() -> IntegrationTestRequirements.verifyDockerAvailableForCurrentRun(() -> false))
                    .isInstanceOf(IllegalStateException.class);
        } else {
            assertThatCode(() -> IntegrationTestRequirements.verifyDockerAvailableForCurrentRun(() -> false))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("the image coordinates are the ones the integration tests boot and the workflow caches")
    void imageCoordinatesAreTheExpectedGaTags() {
        assertThat(MedIntegrationImages.MYSQL).isEqualTo("mysql:8.0.36");
        assertThat(MedIntegrationImages.REDIS_STACK).isEqualTo("redis/redis-stack:7.4.0-v3");
    }
}
