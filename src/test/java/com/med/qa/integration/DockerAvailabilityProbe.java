package com.med.qa.integration;

import org.testcontainers.DockerClientFactory;

/**
 * Seam over the Testcontainers Docker daemon probe.
 *
 * <p>The integration gate ({@link DockerAvailableCondition}, {@link IntegrationTestRequirements}) has
 * to ask "is a Docker daemon reachable here?". Asking Testcontainers directly is a static call into
 * {@link DockerClientFactory}, which makes the gate impossible to exercise in a unit test: the answer
 * would be whatever the developer machine happens to report. This interface turns that call into a
 * collaborator that can be replaced by a stub, so the gate's decisions (enable / disable / fail
 * loudly) are pinned by deterministic tests instead of by the ambient environment.</p>
 *
 * <p>Production code always uses {@link #testcontainers()}; the abstraction exists purely so the
 * gate can be tested.</p>
 */
@FunctionalInterface
public interface DockerAvailabilityProbe {

    /**
     * Reports whether a usable Docker daemon is reachable.
     *
     * <p>Implementations are allowed to throw: Testcontainers raises rather than returning
     * {@code false} when a daemon answers but cannot pull its support image. Callers must go through
     * {@link IntegrationTestRequirements#probeDockerAvailable(DockerAvailabilityProbe)}, which
     * normalises a throwing probe to {@code false}.</p>
     *
     * @return {@code true} when the daemon is reachable and usable
     */
    boolean isDockerAvailable();

    /**
     * The real probe, delegating to Testcontainers.
     *
     * @return a probe backed by {@link DockerClientFactory#isDockerAvailable()}
     */
    static DockerAvailabilityProbe testcontainers() {
        return () -> DockerClientFactory.instance().isDockerAvailable();
    }
}
