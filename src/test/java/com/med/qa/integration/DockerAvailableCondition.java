package com.med.qa.integration;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

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
 * is wrapped and any failure disables the class instead of failing the build.</p>
 */
public class DockerAvailableCondition implements ExecutionCondition {

    /** Reason reported when the class is disabled for lack of a Docker environment. */
    private static final String DISABLED_REASON =
            "Docker daemon not available - skipping Testcontainers integration test";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        boolean available;
        try {
            available = DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable probeFailure) {
            // A daemon that cannot be reached or cannot pull its support image is not a usable
            // Docker environment; report the class as disabled rather than failing the suite.
            return ConditionEvaluationResult.disabled(DISABLED_REASON + " (" + probeFailure.getClass().getSimpleName() + ")");
        }
        if (available) {
            return ConditionEvaluationResult.enabled("Docker available - integration test enabled");
        }
        return ConditionEvaluationResult.disabled(DISABLED_REASON);
    }
}
