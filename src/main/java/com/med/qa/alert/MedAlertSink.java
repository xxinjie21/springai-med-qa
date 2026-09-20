package com.med.qa.alert;

/**
 * Destination of a dispatched {@link MedAlert}.
 *
 * <p>The notifier fans an alert out to every sink on the context, so the same event reaches the log
 * pipeline (for forensics) and the metrics pipeline (for paging rules) without either knowing about
 * the other. Implementations must be cheap, must not block on a remote system for long, and must
 * never let a delivery failure escape: a broken sink is reported by the notifier and the remaining
 * sinks still receive the alert.</p>
 *
 * <p>Alerts carry operational metadata only. A sink must never be used to ship question text,
 * model answers or retrieved documents out of the patient-data boundary.</p>
 */
public interface MedAlertSink {

    /**
     * Delivers one alert.
     *
     * @param alert the alert to deliver, never {@code null}
     */
    void publish(MedAlert alert);
}
