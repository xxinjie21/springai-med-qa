package com.med.qa.security;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.config.MedSecurityProperties;
import com.med.qa.security.annotation.RequireDept;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Enforcement point of {@link RequireDept}: rejects a cross-department call with {@code 403} before the
 * handler runs (D22).
 *
 * <h2>Position in the chain</h2>
 * <p>{@link ApiKeyAuthFilter} (a servlet filter) authenticates and publishes the {@link MedPrincipal};
 * this interceptor runs later, once Spring MVC has picked the {@link HandlerMethod}, so it can read the
 * annotation and the URI template variables. Rejecting here — rather than inside the controller — means an
 * unauthorized department never reaches MySQL, Redis, the vector index or the LLM.</p>
 *
 * <h2>Response shape</h2>
 * <p>A rejection is written directly as {@code 403} plus the unified {@code ApiResult} JSON envelope,
 * mirroring the {@code 401} written by the authentication filter. It is deliberately not delegated to
 * {@code GlobalExceptionHandler}: that advice maps {@link BizException} onto HTTP {@code 200} with a
 * business code, which is right for handler failures but wrong for a pre-dispatch authorization refusal,
 * and it does not reliably apply to async (SSE) handlers.</p>
 *
 * <h2>Toggles</h2>
 * <p>The check is skipped when {@link MedSecurityProperties#isEnabled()} or
 * {@link MedSecurityProperties#isDeptScopeEnabled()} is off, so offline tests and local development boot
 * without mounting API keys.</p>
 */
@Component
public class DeptScopeInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(DeptScopeInterceptor.class);

    private final MedSecurityProperties properties;

    private final DeptScopeGuard guard;

    private final DeptIdResolver resolver;

    /**
     * Creates the interceptor.
     *
     * @param properties security configuration, must not be {@code null}
     * @param guard      department-scope guard, must not be {@code null}
     * @param resolver   department-id resolver, must not be {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public DeptScopeInterceptor(MedSecurityProperties properties,
                                DeptScopeGuard guard,
                                DeptIdResolver resolver) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.guard = Objects.requireNonNull(guard, "guard must not be null");
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
    }

    /**
     * Verifies the department scope of an annotated handler.
     *
     * @param request  current request, must not be {@code null}
     * @param response current response, must not be {@code null}
     * @param handler  the resolved handler; anything other than a {@link HandlerMethod} (static resources,
     *                 error dispatches) is let through untouched
     * @return {@code true} to continue the chain, {@code false} after a {@code 403} has been written
     * @throws IOException when the rejection body cannot be written
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        RequireDept annotation = findAnnotation(handler);
        if (annotation == null) {
            return true;
        }
        if (!properties.isEnabled() || !properties.isDeptScopeEnabled()) {
            return true;
        }
        MedPrincipal principal = MedSecurityContext.getPrincipal();
        Optional<String> requestedDeptId = resolver.resolve(request, annotation.param(), annotation.source());
        List<MedRole> allowedRoles = Arrays.asList(annotation.roles());
        try {
            guard.assertDeptAllowed(principal, requestedDeptId.orElse(null), allowedRoles,
                    annotation.required());
            return true;
        } catch (BizException ex) {
            log.warn("department scope denied: path={}, requestedDept={}, principalDept={}, reason={}",
                    request.getRequestURI(), requestedDeptId.orElse("<none>"),
                    principal == null ? "<anonymous>" : principal.getDeptId(), ex.getMessage());
            writeForbidden(response, ex.getMessage());
            return false;
        }
    }

    /**
     * Resolves the effective {@link RequireDept} of a handler: a method-level declaration wins over the
     * controller-class declaration.
     *
     * @param handler the resolved handler, may be any object
     * @return the annotation, or {@code null} when the handler is not department-scoped
     */
    @Nullable
    RequireDept findAnnotation(@Nullable Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return null;
        }
        RequireDept onMethod = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getMethod(), RequireDept.class);
        if (onMethod != null) {
            return onMethod;
        }
        return AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), RequireDept.class);
    }

    private static void writeForbidden(HttpServletResponse response, @Nullable String message)
            throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        String detail = (message == null || message.isBlank())
                ? ErrorCode.FORBIDDEN.getMessage()
                : message;
        response.getWriter().write("{\"code\":" + ErrorCode.FORBIDDEN.getCode()
                + ",\"message\":\"" + escape(detail) + "\",\"data\":null}");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
