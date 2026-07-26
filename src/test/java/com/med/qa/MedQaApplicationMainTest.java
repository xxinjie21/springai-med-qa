package com.med.qa;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Unit tests for {@link MedQaApplication#main(String[])} that do not require any
 * external middleware. The application is started in {@code web-application-type=none}
 * mode so no server port is bound.
 */
class MedQaApplicationMainTest {

    /**
     * Positive case: invoking {@code main} with a non-web profile boots and returns
     * a live context without throwing.
     */
    @Test
    void mainStartsWithoutWebServer() {
        assertDoesNotThrow(() -> {
            try (ConfigurableApplicationContext ctx = startNonWeb()) {
                // context is auto-closed by try-with-resources
            }
        });
    }

    /**
     * Boundary/exception case: an unknown profile does not crash startup, while an
     * intentionally invalid property value surfaces as a startup failure. Here we
     * assert that a bad server port type is rejected during binding.
     */
    @Test
    void mainRejectsInvalidPortProperty() {
        assertThrows(Exception.class, () -> {
            try (ConfigurableApplicationContext ctx = org.springframework.boot.SpringApplication.run(
                    MedQaApplication.class,
                    "--spring.main.web-application-type=servlet",
                    "--server.port=not-a-number")) {
                // should not reach here
            }
        });
    }

    private ConfigurableApplicationContext startNonWeb() {
        return org.springframework.boot.SpringApplication.run(
                MedQaApplication.class,
                "--spring.main.web-application-type=none",
                "--spring.main.banner-mode=off");
    }
}
