package com.med.qa;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Integration-style smoke test verifying that the Spring application context
 * bootstraps successfully with the current (D1) configuration.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "spring.flyway.enabled=false")
class MedQaApplicationTests {

    /**
     * Positive case: the full application context loads without throwing.
     * A failure here indicates a broken bean wiring or configuration.
     */
    @Test
    void contextLoads() {
        // context startup itself is the assertion; JUnit fails if it throws
    }
}
