package com.med.qa.config;

import com.med.qa.security.DeptScopeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Objects;

/**
 * Registers the declarative authorization interceptor of D22 on the API surface.
 *
 * <p>The {@link DeptScopeInterceptor} is mapped on {@code /api/**} and excluded from
 * {@code /actuator/**} and {@code /error} so probes and container error dispatches never need a
 * department scope. Handlers without {@link com.med.qa.security.annotation.RequireDept} are a no-op inside
 * the interceptor, so the broad path pattern costs one annotation lookup per request and nothing more.</p>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** Path pattern the department-scope interceptor is mounted on. */
    static final String API_PATH_PATTERN = "/api/**";

    private final DeptScopeInterceptor deptScopeInterceptor;

    /**
     * Creates the MVC configuration.
     *
     * @param deptScopeInterceptor department-scope interceptor, must not be {@code null}
     * @throws NullPointerException if {@code deptScopeInterceptor} is {@code null}
     */
    public WebMvcConfig(DeptScopeInterceptor deptScopeInterceptor) {
        this.deptScopeInterceptor = Objects.requireNonNull(
                deptScopeInterceptor, "deptScopeInterceptor must not be null");
    }

    /**
     * Mounts the department-scope interceptor on the API paths.
     *
     * @param registry MVC interceptor registry, must not be {@code null}
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        Objects.requireNonNull(registry, "registry must not be null");
        registry.addInterceptor(deptScopeInterceptor)
                .addPathPatterns(API_PATH_PATTERN)
                .excludePathPatterns("/actuator/**", "/error");
    }
}
