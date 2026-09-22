package com.med.qa.integration;

import java.util.Objects;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 execution condition that disables a test container-based integration test when no Docker
 * daemon is reachable.
 *
 * <p>The D30 integration suite (real MySQL + Redis Stack via Testcontainers) must never break the
 * offline unit-test run. When Docker is absent the whole class is reported as disabled (skipped), so
 * {@code mvn test} stays green on developer machines and CI runners without a container runtime. On
 * a host that does have Docker the condition is satisfied and the integration test executes its
 * real-middleware assertions.</p>
 *
 * <p>The condition is evaluated before any {@code @BeforeAll} lifecycle method, so the Testcontainers
 * images are never pulled and the middleware is never started when Docker is unavailable.</p>
 *
 * <p>Probing the daemon is itself best-effort: on a machine where Docker answers the ping but cannot
 * pull its support image (offline or restricted registry), Testcontainers raises instead of returning
 * {@code false}. A container probe that throws is indistinguishable, from the test suite's point of
 * view, from a machine without Docker -- both mean "cannot run real middleware here" -- so the probe
 * is normalised through {@link IntegrationTestRequirements#probeDockerAvailable} and any failure
 * disables the class instead of failing the build.</p>
 *
 * <p><strong>Skipping is not always acceptable (D35).</strong> A silent skip is how two
 * production-blocking defects survived every build until D34: the suite reported itself disabled and
 * nobody noticed. When {@code med.test.integration.required} (or
 * {@code MED_TEST_INTEGRATION_REQUIRED}) is set, a missing daemon must fail the build instead. This
 * condition therefore leaves the class <em>enabled</em> in that case, so the {@code @BeforeAll} guard
 * in the integration test raises a clear {@link IllegalStateException} naming the switch -- rather
 * than throwing from the condition itself, which JUnit reports as an opaque container error with no
 * usable message.</p>
 */
public class DockerAvailableCondition implements ExecutionCondition {

    /** Reason reported when the class is disabled for lack of a Docker environment. */
    private static final String DISABLED_REASON =
            "Docker daemon not available - skipping Testcontainers integration test";

    /** Reason reported when the class stays enabled only so the mandatory-Docker guard can fail it. */
    private static final String REQUIRED_REASON =
            "Docker daemon not available but " + IntegrationTestRequirements.REQUIRED_PROPERTY
                    + " is set - letting the integration test fail instead of skipping it";

    /** Collaborator that answers "is Docker reachable here?". */
    private final DockerAvailabilityProbe probe;

    /** Constructor used by JUnit, wired to the real Testcontainers probe. */
    public DockerAvailableCondition() {
        this(DockerAvailabilityProbe.testcontainers());
    }

    /**
     * Constructor used by the unit tests, with the daemon probe injected.
     *
     * @param probe the probe to consult, never {@code null}
     */
    DockerAvailableCondition(DockerAvailabilityProbe probe) {
        this.probe = Objects.requireNonNull(probe, "probe");
    }

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (IntegrationTestRequirements.probeDockerAvailable(probe)) {
            return ConditionEvaluationResult.enabled("Docker available - integration test enabled");
        }
        if (IntegrationTestRequirements.isDockerRequired()) {
            return ConditionEvaluationResult.enabled(REQUIRED_REASON);
        }
        return ConditionEvaluationResult.disabled(DISABLED_REASON);
    }
}
