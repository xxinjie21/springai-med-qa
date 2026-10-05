package com.med.qa.alert;

import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Central dispatcher for medical alerts: applies the operator's policy, suppresses duplicates and
 * fans the surviving alerts out to every {@link MedAlertSink}.
 *
 * <h2>Policy</h2>
 * <p>An alert is dropped when the chain is disabled, when its code is muted, or when its severity
 * falls below {@code med.alert.minimum-severity}. Those are deliberate operator decisions and are
 * logged at {@code DEBUG} so they stay observable without filling the log.</p>
 *
 * <h2>Deduplication</h2>
 * <p>Suppression uses Redisson's {@link RMapCache}, i.e. a Redis hash with a per-entry TTL, rather
 * than a hand-rolled cache: the map is shared by every replica, so three pods probing the same MySQL
 * node still produce one page, and the TTL is enforced by Redis itself so a restarted pod does not
 * lose the window. The key is {@link MedAlert#fingerprint()}, so a new component failing is a new
 * alert even while the first one is still suppressed.</p>
 *
 * <p>The fingerprint is written <strong>after</strong> the alert has been accepted by at least one
 * sink, never before. The order is the whole point: {@code putIfAbsent}-first marks the alert as
 * delivered while the delivery may still fail, so an outage of every sink (a full log volume, an
 * unreachable metrics collector) would silently discard that alert for the entire cooldown window --
 * exactly the situation in which the alert matters most. With the write moved after the dispatch, a
 * failed delivery leaves no fingerprint behind and the next scheduled pass raises the alert again.</p>
 *
 * <p>The check-then-write pair is not atomic, so two replicas can both dispatch the same alert. That
 * is the intended trade: a duplicate page is strictly better than a lost one, and the write still
 * uses {@code putIfAbsent} so the cooldown window itself is never shortened by a later replica.</p>
 *
 * <h2>Fail-open</h2>
 * <p>If the deduplication store is unreachable the alert is dispatched anyway. Losing an alert
 * because the suppression bookkeeping is down would be strictly worse than sending a duplicate --
 * and an unreachable Redis is itself one of the conditions this chain reports.</p>
 */
public class MedAlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(MedAlertNotifier.class);

    /** Suffix appended to {@code med.alert.key-prefix} to form the deduplication map name. */
    public static final String DEDUPLICATION_SUFFIX = "dedupe";

    private final MedAlertProperties properties;

    private final List<MedAlertSink> sinks;

    private final RedissonClient redissonClient;

    /**
     * Creates the notifier.
     *
     * @param properties     the {@code med.alert.*} policy, must not be {@code null}
     * @param sinks          every sink to fan out to; {@code null} is treated as "no sink"
     * @param redissonClient the deduplication store, or {@code null} to dispatch without suppression
     * @throws IllegalArgumentException if {@code properties} is {@code null}
     */
    public MedAlertNotifier(MedAlertProperties properties, List<MedAlertSink> sinks,
                            RedissonClient redissonClient) {
        if (properties == null) {
            throw new IllegalArgumentException("properties must not be null");
        }
        this.properties = properties;
        this.sinks = sinks == null ? List.of() : List.copyOf(sinks);
        this.redissonClient = redissonClient;
    }

    /**
     * Builds an alert from its parts and dispatches it.
     *
     * @param severity  urgency of the alert, must not be {@code null}
     * @param code      stable identifier, must not be blank
     * @param component the failing part, must not be blank
     * @param message   one-line description, must not be blank
     * @return {@code true} when the alert reached at least one sink
     * @throws IllegalArgumentException if any argument is {@code null} or blank
     */
    public boolean raise(MedAlertSeverity severity, String code, String component, String message) {
        return raise(MedAlert.of(severity, code, component, message));
    }

    /**
     * Applies the policy, dispatches the alert to every sink and only then records the delivery.
     *
     * <p>Sink failures are isolated: one broken sink is logged and the remaining sinks still receive
     * the alert. The return value therefore means "delivered somewhere", not "delivered everywhere".
     * The cooldown fingerprint is written only when this returns {@code true}, so a pass in which
     * every sink failed stays re-raisable instead of being suppressed for the whole window.</p>
     *
     * @param alert the alert to dispatch, must not be {@code null}
     * @return {@code true} when the alert reached at least one sink
     * @throws IllegalArgumentException if {@code alert} is {@code null}
     */
    public boolean raise(MedAlert alert) {
        if (alert == null) {
            throw new IllegalArgumentException("alert must not be null");
        }
        if (!properties.isEnabled()) {
            log.debug("alerting disabled, dropping {}", alert.fingerprint());
            return false;
        }
        if (properties.isMuted(alert.code())) {
            log.debug("alert code {} is muted, dropping {}", alert.code(), alert.fingerprint());
            return false;
        }
        if (!properties.isReportable(alert.severity())) {
            log.debug("severity {} is below the configured floor {}, dropping {}",
                    alert.severity(), properties.getMinimumSeverity(), alert.fingerprint());
            return false;
        }
        if (isSuppressed(alert)) {
            log.debug("alert {} is inside its cooldown window, suppressing", alert.fingerprint());
            return false;
        }
        boolean delivered = dispatch(alert);
        if (delivered) {
            rememberDelivered(alert);
        }
        return delivered;
    }

    /**
     * Tells whether the alert fingerprint was already dispatched inside the cooldown window.
     *
     * <p>Read-only on purpose. The previous implementation used the return value of
     * {@code putIfAbsent} to answer the same question, which conflated "was it delivered before"
     * with "claim it now" and made a failed delivery indistinguishable from a delivered one. Redisson
     * evaluates the entry TTL inside {@code containsKey}, so an expired fingerprint correctly reports
     * as absent.</p>
     *
     * @param alert the alert to test
     * @return {@code true} when the alert must be suppressed
     */
    private boolean isSuppressed(MedAlert alert) {
        if (!properties.isDeduplicationEnabled() || redissonClient == null) {
            return false;
        }
        try {
            RMapCache<String, Long> dedupeMap =
                    redissonClient.getMapCache(properties.getKeyPrefix() + DEDUPLICATION_SUFFIX);
            return dedupeMap != null && dedupeMap.containsKey(alert.fingerprint());
        } catch (RuntimeException ex) {
            // Fail open: a duplicate page is strictly better than a lost one.
            log.warn("alert deduplication store unavailable, dispatching without suppression: {}",
                    ex.getMessage());
            return false;
        }
    }

    /**
     * Records that the alert was delivered, so the next identical alert is suppressed.
     *
     * <p>Called only after {@link #dispatch(MedAlert)} accepted the alert somewhere. The write uses
     * {@code putIfAbsent} so that a replica which lost the race does not extend or shorten the window
     * the winner established. A store failure here is logged and swallowed: the alert has already
     * reached a sink, so the only consequence is a possible repeat, which is the safe direction.</p>
     *
     * @param alert the alert that reached at least one sink
     */
    private void rememberDelivered(MedAlert alert) {
        if (!properties.isDeduplicationEnabled() || redissonClient == null) {
            return;
        }
        try {
            RMapCache<String, Long> dedupeMap =
                    redissonClient.getMapCache(properties.getKeyPrefix() + DEDUPLICATION_SUFFIX);
            if (dedupeMap == null) {
                return;
            }
            dedupeMap.putIfAbsent(alert.fingerprint(), alert.occurredAt().toEpochMilli(),
                    properties.getCooldown().toMillis(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException ex) {
            log.warn("could not record the delivered alert {}, a repeat is possible: {}",
                    alert.fingerprint(), ex.getMessage());
        }
    }

    /**
     * Sends the alert to every configured sink, isolating per-sink failures.
     *
     * @param alert the alert to dispatch
     * @return {@code true} when at least one sink accepted the alert
     */
    private boolean dispatch(MedAlert alert) {
        int delivered = 0;
        for (MedAlertSink sink : sinks) {
            try {
                sink.publish(alert);
                delivered++;
            } catch (RuntimeException ex) {
                log.warn("alert sink {} failed for {}: {}", sink.getClass().getSimpleName(),
                        alert.fingerprint(), ex.getMessage());
            }
        }
        if (delivered == 0) {
            log.warn("no alert sink accepted {}; check the alerting configuration", alert.fingerprint());
        }
        return delivered > 0;
    }

    /**
     * Returns how many sinks this notifier fans out to.
     *
     * @return the sink count, never negative
     */
    public int sinkCount() {
        return sinks.size();
    }
}
