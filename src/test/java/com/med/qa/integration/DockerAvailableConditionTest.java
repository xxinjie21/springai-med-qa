package com.med.qa.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Unit tests for {@link DockerAvailableCondition}, the JUnit gate in front of the Testcontainers
 * suite.
 *
 * <p>The daemon probe is injected through {@link DockerAvailabilityProbe} and mocked with Mockito, so
 * the three decisions the gate can make are pinned without a Docker daemon being involved: enable the
 * class when Docker is reachable, disable it when the suite is allowed to be skipped, and keep it
 * enabled when Docker is mandatory so the integration test's own {@code @BeforeAll} guard raises the
 * loud failure (D35).</p>
 *
 * <p>That last case is the whole point of the iteration: the previous behaviour - disabling the class
 * unconditionally whenever the probe answered {@code false} - is how the suite skipped itself
 * silently while the build stayed green.</p>
 */
class DockerAvailableConditionTest {

    private final ExtensionContext context = mock(ExtensionContext.class);

    @Test
    @DisplayName("Docker reachable enables the integration class")
    void dockerAvailableEnablesTheClass() {
        DockerAvailabilityProbe probe = mock(DockerAvailabilityProbe.class);
        when(probe.isDockerAvailable()).thenReturn(true);

        ConditionEvaluationResult result = new DockerAvailableCondition(probe).evaluateExecutionCondition(context);

        assertThat(result.isDisabled()).isFalse();
        assertThat(result.getReason().orElseThrow()).contains("Docker available");
    }

    @Test
    @DisplayName("Docker unreachable and the switch unset disables the class, keeping the offline suite green")
    void dockerUnavailableWithoutTheSwitchDisablesTheClass() {
        DockerAvailabilityProbe probe = mock(DockerAvailabilityProbe.class);
        when(probe.isDockerAvailable()).thenReturn(false);

        String previous = System.getProperty(IntegrationTestRequirements.REQUIRED_PROPERTY);
        System.clearProperty(IntegrationTestRequirements.REQUIRED_PROPERTY);
        try {
            ConditionEvaluationResult result =
                    new DockerAvailableCondition(probe).evaluateExecutionCondition(context);

            assertThat(result.isDisabled())
                    .as("with the mandatory switch unset the suite must still be skippable")
                    .isTrue();
            assertThat(result.getReason().orElseThrow()).contains("skipping Testcontainers integration test");
        } finally {
            restore(previous);
        }
    }

    @Test
    @DisplayName("Docker unreachable but the switch set keeps the class enabled so the failure is loud")
    void dockerUnavailableWithTheSwitchKeepsTheClassEnabled() {
        DockerAvailabilityProbe probe = mock(DockerAvailabilityProbe.class);
        when(probe.isDockerAvailable()).thenReturn(false);

        String previous = System.getProperty(IntegrationTestRequirements.REQUIRED_PROPERTY);
        System.setProperty(IntegrationTestRequirements.REQUIRED_PROPERTY, "true");
        try {
            ConditionEvaluationResult result =
                    new DockerAvailableCondition(probe).evaluateExecutionCondition(context);

            assertThat(result.isDisabled())
                    .as("a mandatory Docker run must never be reported as skipped")
                    .isFalse();
            assertThat(result.getReason().orElseThrow())
                    .contains(IntegrationTestRequirements.REQUIRED_PROPERTY);
        } finally {
            restore(previous);
        }
    }

    @Test
    @DisplayName("boundary: a probe that throws is treated as Docker unavailable")
    void throwingProbeIsTreatedAsUnavailable() {
        DockerAvailabilityProbe probe = mock(DockerAvailabilityProbe.class);
        when(probe.isDockerAvailable()).thenThrow(new RuntimeException("ryuk image cannot be pulled"));

        String previous = System.getProperty(IntegrationTestRequirements.REQUIRED_PROPERTY);
        System.clearProperty(IntegrationTestRequirements.REQUIRED_PROPERTY);
        try {
            ConditionEvaluationResult result =
                    new DockerAvailableCondition(probe).evaluateExecutionCondition(context);

            assertThat(result.isDisabled()).isTrue();
        } finally {
            restore(previous);
        }
    }

    @Test
    @DisplayName("end to end: mandatory Docker plus a missing daemon enables the class, whose guard then fails loudly")
    void mandatoryDockerAndMissingDaemonComposeIntoALoudFailure() {
        DockerAvailabilityProbe probe = mock(DockerAvailabilityProbe.class);
        when(probe.isDockerAvailable()).thenReturn(false);

        String previous = System.getProperty(IntegrationTestRequirements.REQUIRED_PROPERTY);
        System.setProperty(IntegrationTestRequirements.REQUIRED_PROPERTY, "true");
        try {
            // Step 1: the gate must not report a skip.
            assertThat(new DockerAvailableCondition(probe).evaluateExecutionCondition(context).isDisabled())
                    .as("a mandatory Docker run must never be reported as skipped")
                    .isFalse();

            // Step 2: the guard every integration test calls first in @BeforeAll turns that into a
            // failure naming the switch, which is the message an operator sees on a broken runner.
            assertThatThrownBy(() -> IntegrationTestRequirements.verifyDockerAvailableForCurrentRun(probe))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(IntegrationTestRequirements.REQUIRED_PROPERTY);
        } finally {
            restore(previous);
        }
    }

    @Test
    @DisplayName("boundary: the default constructor wires the real Testcontainers probe")
    void defaultConstructorIsUsable() {
        // JUnit instantiates the condition reflectively, so the no-argument constructor has to exist
        // and must not need a Docker daemon just to be constructed.
        assertThat(new DockerAvailableCondition()).isNotNull();
    }

    private static void restore(String previous) {
        if (previous == null) {
            System.clearProperty(IntegrationTestRequirements.REQUIRED_PROPERTY);
        } else {
            System.setProperty(IntegrationTestRequirements.REQUIRED_PROPERTY, previous);
        }
    }
}
