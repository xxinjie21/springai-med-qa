/**
 * Push-based alerting for the medical backend.
 *
 * <p>Spring Boot Actuator answers "am I healthy?" but only to someone who asks. A hospital backend
 * cannot rely on a human asking at the right moment, so this package adds the missing push side:</p>
 *
 * <ol>
 *   <li>{@link com.med.qa.alert.MedStorageAlertMonitor} polls the existing
 *       {@code MedStorageHealthIndicator} on a fixed schedule;</li>
 *   <li>{@link com.med.qa.alert.MedAlertNotifier} applies the operator policy from
 *       {@code med.alert.*} and suppresses duplicates through a Redisson TTL map;</li>
 *   <li>the {@link com.med.qa.alert.MedAlertSink} implementations deliver the surviving alerts to
 *       the log pipeline and to Micrometer, which is what the Prometheus rules in
 *       {@code deploy/prometheus/med-qa-alerts.yml} page on.</li>
 * </ol>
 *
 * <p>Everything is built on maintained components: Actuator for the probe, Redisson for the shared
 * suppression window, Micrometer for the meters. No polling loop, cache or metric registry is
 * hand-written. Alerts carry operational metadata only -- never question text, model answers or
 * retrieved documents.</p>
 */
package com.med.qa.alert;
