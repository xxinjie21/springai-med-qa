/**
 * Production observability contributions built on Spring Boot Actuator.
 *
 * <p>The deployment stack (compose health check and the Kubernetes probes documented in
 * {@code docs/DEPLOYMENT.md}) only sees {@code /actuator/health} and {@code /actuator/info}.
 * The default Actuator health report covers the auto-configured infrastructure beans, but this
 * application talks to MySQL through the ShardingSphere driver and to Redis through a lazily
 * created Redisson client, so the two stores the consultation path actually depends on deserve
 * an explicit, first-class probe.</p>
 *
 * <p>Everything here relies on official Actuator extension points
 * ({@code AbstractHealthIndicator}, {@code InfoContributor}) -- no hand-rolled monitoring.</p>
 */
package com.med.qa.actuator;
