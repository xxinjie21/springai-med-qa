package com.med.qa.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes every dispatched alert to the application log.
 *
 * <p>This is the sink that works when nothing else does: it needs no network, no collector and no
 * credentials, so it stays useful precisely in the situation an alert is about -- a store that went
 * away. The line is emitted in a flat {@code key=value} shape so a log pipeline can index the
 * severity, code and component fields without a parser per environment.</p>
 *
 * <p>The log level follows the severity ({@code ERROR} for critical, {@code WARN} for warning,
 * {@code INFO} for informational), which lets an operator route by level alone.</p>
 */
public class LoggingMedAlertSink implements MedAlertSink {

    private static final Logger log = LoggerFactory.getLogger(LoggingMedAlertSink.class);

    /**
     * Logs the alert at the level matching its severity.
     *
     * @param alert the alert to log, never {@code null}
     */
    @Override
    public void publish(MedAlert alert) {
        switch (alert.severity()) {
            case CRITICAL -> log.error("med-alert severity={} code={} component={} occurredAt={} message={} details={}",
                    alert.severity(), alert.code(), alert.component(), alert.occurredAt(),
                    alert.message(), alert.details());
            case WARNING -> log.warn("med-alert severity={} code={} component={} occurredAt={} message={} details={}",
                    alert.severity(), alert.code(), alert.component(), alert.occurredAt(),
                    alert.message(), alert.details());
            case INFO -> log.info("med-alert severity={} code={} component={} occurredAt={} message={} details={}",
                    alert.severity(), alert.code(), alert.component(), alert.occurredAt(),
                    alert.message(), alert.details());
        }
    }
}
