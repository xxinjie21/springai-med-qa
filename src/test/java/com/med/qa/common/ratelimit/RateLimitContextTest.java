package com.med.qa.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.med.qa.MedQaApplication;
import com.med.qa.config.MedRateLimitProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Offline Spring wiring of the rate-limit layer: the aspect and the service facade are beans, the
 * {@code med.rate-limit.*} properties bind, and disabling the limiter lets the full context boot
 * without Redis (the Redisson client is {@code @Lazy}).
 */
@SpringBootTest(classes = MedQaApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "med.rate-limit.enabled=false"
})
class RateLimitContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("binds rate-limit beans and properties offline")
    void wiresRateLimitBeansOffline() {
        assertThat(context.getBean(RateLimitAspect.class)).isNotNull();
        assertThat(context.getBean(RateLimitService.class)).isNotNull();
        MedRateLimitProperties props = context.getBean(MedRateLimitProperties.class);
        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getKeyPrefix()).isEqualTo("med:ratelimit:");
    }
}
