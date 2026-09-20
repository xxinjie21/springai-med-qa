package com.med.qa.alert;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One immutable alert raised by the medical backend.
 *
 * <p>An alert carries only operational facts: a severity, a stable machine-readable {@code code},
 * the affected {@code component}, a human-readable {@code message} and free-form {@code details}.
 * Clinical content must never travel through this type -- alerts are written to log aggregators and
 * paging systems that are outside the patient-data boundary.</p>
 *
 * <p>{@link #fingerprint()} identifies "the same problem happening again". The notifier uses it as
 * the deduplication key, so a storage outage that lasts an hour produces one page instead of sixty,
 * while an outage of a <em>different</em> component still pages immediately.</p>
 *
 * @param severity   urgency of the alert, never {@code null}
 * @param code       stable identifier such as {@code storage-down}, never blank
 * @param component  the failing part such as {@code mysql} or {@code redis}, never blank
 * @param message    one-line operator-facing description, never blank
 * @param occurredAt the moment the condition was observed, never {@code null}
 * @param details    extra context; an empty map is used when nothing is available
 */
public record MedAlert(MedAlertSeverity severity,
                       String code,
                       String component,
                       String message,
                       Instant occurredAt,
                       Map<String, Object> details) {

    /**
     * Validates the fields and takes a defensive, unmodifiable copy of {@code details}.
     *
     * @throws IllegalArgumentException if any required field is {@code null} or blank
     */
    public MedAlert {
        requireText(severity, "severity");
        code = requireText(code, "code");
        component = requireText(component, "component");
        message = requireText(message, "message");
        requireText(occurredAt, "occurredAt");
        details = details == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    /**
     * Builds an alert stamped with the current instant and no extra details.
     *
     * @param severity  urgency of the alert, must not be {@code null}
     * @param code      stable identifier, must not be blank
     * @param component the failing part, must not be blank
     * @param message   one-line description, must not be blank
     * @return a fully populated alert, never {@code null}
     */
    public static MedAlert of(MedAlertSeverity severity, String code, String component, String message) {
        return new MedAlert(severity, code, component, message, Instant.now(), Map.of());
    }

    /**
     * Builds an alert stamped with the current instant and the given details.
     *
     * @param severity  urgency of the alert, must not be {@code null}
     * @param code      stable identifier, must not be blank
     * @param component the failing part, must not be blank
     * @param message   one-line description, must not be blank
     * @param details   extra context, may be {@code null} for "none"
     * @return a fully populated alert, never {@code null}
     */
    public static MedAlert of(MedAlertSeverity severity, String code, String component, String message,
                              Map<String, Object> details) {
        return new MedAlert(severity, code, component, message, Instant.now(), details);
    }

    /**
     * Returns the deduplication key of this alert.
     *
     * <p>The key is {@code code + ':' + component}, so severity changes and message rewording do not
     * open a new suppression window.</p>
     *
     * @return a stable, non-blank key
     */
    public String fingerprint() {
        return code + ':' + component;
    }

    private static <T> T requireText(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        if (value instanceof CharSequence text && text.toString().isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return Objects.requireNonNull(value);
    }
}
