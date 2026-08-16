package com.med.qa.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.med.qa.security.DeptIdResolver;
import com.med.qa.security.DeptScopeGuard;
import com.med.qa.security.DeptScopeInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

import java.util.List;

/**
 * Unit tests of {@link WebMvcConfig}: the department-scope interceptor must be mounted on the API paths
 * and kept off the probe/error paths.
 *
 * <p>The real {@link InterceptorRegistry} is used through a subclass that exposes its protected
 * accumulator, so the assertions describe what Spring MVC would actually register — no application context
 * is started.</p>
 */
class WebMvcConfigTest {

    /** Registry subclass exposing the protected accumulator for assertions. */
    private static final class CapturingRegistry extends InterceptorRegistry {

        List<Object> captured() {
            return getInterceptors();
        }
    }

    private DeptScopeInterceptor interceptor;

    private WebMvcConfig config;

    @BeforeEach
    void setUp() {
        interceptor = new DeptScopeInterceptor(
                new MedSecurityProperties(), new DeptScopeGuard(), new DeptIdResolver());
        config = new WebMvcConfig(interceptor);
    }

    @Test
    @DisplayName("registers the department-scope interceptor on /api/** only")
    void registersOnApiPaths() {
        CapturingRegistry registry = new CapturingRegistry();

        config.addInterceptors(registry);

        assertThat(registry.captured()).hasSize(1);
        assertThat(registry.captured().get(0)).isInstanceOf(MappedInterceptor.class);
        MappedInterceptor mapped = (MappedInterceptor) registry.captured().get(0);
        assertThat(mapped.getInterceptor()).isSameAs(interceptor);
        assertThat(mapped.getIncludePathPatterns()).containsExactly(WebMvcConfig.API_PATH_PATTERN);
    }

    @Test
    @DisplayName("excludes actuator probes and the container error dispatch")
    void excludesProbes() {
        CapturingRegistry registry = new CapturingRegistry();

        config.addInterceptors(registry);

        MappedInterceptor mapped = (MappedInterceptor) registry.captured().get(0);
        assertThat(mapped.getExcludePathPatterns()).containsExactlyInAnyOrder("/actuator/**", "/error");
    }

    @Test
    @DisplayName("rejects a null interceptor or a null registry")
    void rejectsNulls() {
        assertThatThrownBy(() -> new WebMvcConfig(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("deptScopeInterceptor");
        assertThatThrownBy(() -> config.addInterceptors(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("registry");
    }
}
