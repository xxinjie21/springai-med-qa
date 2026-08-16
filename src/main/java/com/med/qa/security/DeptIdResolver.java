package com.med.qa.security;

import com.med.qa.security.annotation.DeptIdSource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads the requested department id out of the request envelope for {@link DeptScopeInterceptor}.
 *
 * <h2>Why only the envelope</h2>
 * <p>An interceptor runs before Spring MVC resolves handler arguments, so reading the body here would
 * consume the input stream and break every {@code @RequestBody} binding downstream. The resolver
 * therefore looks at the query string, the URI template variables published by
 * {@link HandlerMapping#URI_TEMPLATE_VARIABLES_ATTRIBUTE} and the request headers — all of which are
 * re-readable — and returns an empty {@link Optional} when none of them carries the id.</p>
 */
@Component
public class DeptIdResolver {

    /**
     * Resolves the department id for the given lookup strategy.
     *
     * @param request the current request, must not be {@code null}
     * @param param   name of the parameter / template variable / header, must not be blank
     * @param source  lookup strategy, must not be {@code null}
     * @return the department id, or {@link Optional#empty()} when the request does not carry one
     * @throws IllegalArgumentException when {@code param} is blank
     * @throws NullPointerException     when {@code request} or {@code source} is {@code null}
     */
    public Optional<String> resolve(HttpServletRequest request, String param, DeptIdSource source) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(source, "source must not be null");
        if (!StringUtils.hasText(param)) {
            throw new IllegalArgumentException("param must not be blank");
        }
        return switch (source) {
            case QUERY -> fromQuery(request, param);
            case PATH -> fromPath(request, param);
            case HEADER -> fromHeader(request, param);
            case AUTO -> fromQuery(request, param)
                    .or(() -> fromPath(request, param))
                    .or(() -> fromHeader(request, param));
        };
    }

    private static Optional<String> fromQuery(HttpServletRequest request, String param) {
        return normalize(request.getParameter(param));
    }

    private static Optional<String> fromHeader(HttpServletRequest request, String param) {
        return normalize(request.getHeader(param));
    }

    @SuppressWarnings("unchecked")
    private static Optional<String> fromPath(HttpServletRequest request, String param) {
        Object attribute = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(attribute instanceof Map<?, ?> raw)) {
            return Optional.empty();
        }
        Map<String, String> variables = (Map<String, String>) raw;
        return normalize(variables.get(param));
    }

    private static Optional<String> normalize(String value) {
        return StringUtils.hasText(value) ? Optional.of(value.trim()) : Optional.empty();
    }
}
