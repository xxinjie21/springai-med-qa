package com.med.qa.security;

import com.med.qa.config.MedSecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Servlet filter authenticating requests by an API key and binding the resolved
 * {@link MedPrincipal} to the {@link MedSecurityContext}.
 *
 * <h2>Behaviour</h2>
 * <ul>
 *   <li>The filter is a no-op when {@link MedSecurityProperties#isEnabled() disabled}, so offline tests
 *       and local dev never hit a {@code 401}.</li>
 *   <li>A request with no key is either rejected with {@code 401} (when {@code requireAuth} is on) or
 *       passed through as anonymous (when off).</li>
 *   <li>A request with an unknown/blank key is rejected with {@code 401}.</li>
 *   <li>A request with a valid key has its principal bound for the rest of the chain and cleared
 *       afterwards, so the value never leaks into a subsequent request on a pooled thread.</li>
 * </ul>
 *
 * <p>The principal is resolved entirely by {@link MedApiKeyRegistry}; this filter performs no IO of its
 * own and therefore boots offline (no Redis, no MySQL). Management endpoints under {@code /actuator} are
 * excluded so health checks never require a key.</p>
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    private final MedSecurityProperties properties;

    private final MedApiKeyRegistry registry;

    /**
     * Creates the filter.
     *
     * @param properties security configuration, must not be {@code null}
     * @param registry    API-key resolver, must not be {@code null}
     */
    public ApiKeyAuthFilter(MedSecurityProperties properties, MedApiKeyRegistry registry) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    @Override
    public int getOrder() {
        // Run early, before the controllers and the streaming endpoints.
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return true;
        }
        String path = request.getServletPath();
        return path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String key = request.getHeader(properties.getHeaderName());
        if (!StringUtils.hasText(key)) {
            if (properties.isRequireAuth()) {
                writeUnauthorized(response);
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }
        Optional<MedPrincipal> principal = registry.resolve(key);
        if (principal.isEmpty()) {
            writeUnauthorized(response);
            return;
        }
        MedSecurityContext.setPrincipal(principal.get());
        try {
            filterChain.doFilter(request, response);
        } finally {
            MedSecurityContext.clear();
        }
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":40100,\"message\":\"unauthorized\",\"data\":null}");
    }

    /**
     * Exposes the bound principal for tests that drive the filter directly.
     *
     * @return the principal set by the most recent authenticated request, or {@code null}
     */
    @Nullable
    MedPrincipal currentPrincipalForTest() {
        return MedSecurityContext.getPrincipal();
    }
}
