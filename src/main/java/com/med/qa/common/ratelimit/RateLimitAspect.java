package com.med.qa.common.ratelimit;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.common.ratelimit.annotation.RateLimit;
import com.med.qa.config.MedRateLimitProperties;
import com.med.qa.security.MedPrincipal;
import com.med.qa.security.MedSecurityContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Around advice enforcing the {@link RateLimit} annotation through Redisson's distributed
 * {@link org.redisson.api.RRateLimiter}.
 *
 * <h2>Key composition</h2>
 * <p>The bucket key is {@code {prefix}{dimension}:{interface}} where {@code dimension} isolates the
 * caller (principal id, or a SpEL expression from {@link RateLimit#key()}) and {@code interface} is
 * {@code ClassName#methodName}. Each endpoint therefore keeps an independent, per-caller bucket &mdash; a
 * single patient hammering {@code /api/chat/stream} is throttled without affecting anyone else.</p>
 *
 * <h2>Failure semantics</h2>
 * <p>An exhausted bucket yields {@link ErrorCode#RATE_LIMITED}; a Redis outage yields
 * {@link ErrorCode#STORAGE_ERROR} (fail closed, like the session lock). When {@code med.rate-limit.enabled}
 * is {@code false} the advice is a pure pass-through, so the offline test suite and local development
 * never touch Redis. The aspect orders itself outside the audit aspect (lower order), so a rejected call
 * is rejected cheaply, before the audit timer starts.</p>
 */
@Aspect
@Component
public class RateLimitAspect implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAspect.class);

    private final RateLimitService rateLimitService;

    private final MedRateLimitProperties properties;

    private final ExpressionParser parser = new SpelExpressionParser();

    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    private final Map<String, Expression> expressionCache = new ConcurrentHashMap<>();

    /**
     * Creates the rate-limit aspect.
     *
     * @param rateLimitService facade over the Redisson limiter, must not be {@code null}
     * @param properties       master switch, advice order and fallback limits, must not be {@code null}
     * @throws IllegalArgumentException if either argument is {@code null}
     */
    public RateLimitAspect(RateLimitService rateLimitService, MedRateLimitProperties properties) {
        if (rateLimitService == null) {
            throw new IllegalArgumentException("rateLimitService must not be null");
        }
        if (properties == null) {
            throw new IllegalArgumentException("properties must not be null");
        }
        this.rateLimitService = rateLimitService;
        this.properties = properties;
    }

    /**
     * Returns the advice order, keeping the aspect on the outside of the advice chain.
     *
     * @return the configured order value
     */
    @Override
    public int getOrder() {
        return properties.getOrder();
    }

    /**
     * Runs the rate-limited method, acquiring a permit first.
     *
     * @param joinPoint the intercepted invocation, must not be {@code null}
     * @return whatever the limited method returned
     * @throws Throwable the original exception of the limited method, or {@link BizException} with
     *                   {@link ErrorCode#RATE_LIMITED} when the bucket is exhausted, or
     *                   {@link ErrorCode#STORAGE_ERROR} when Redis is unreachable
     */
    @Around("@annotation(com.med.qa.common.ratelimit.annotation.RateLimit)")
    public Object limit(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = resolveMethod(joinPoint);
        RateLimit annotation = method == null
                ? null
                : AnnotatedElementUtils.findMergedAnnotation(method, RateLimit.class);
        if (annotation == null || !properties.isEnabled()) {
            return joinPoint.proceed();
        }

        int rate = annotation.rate() > 0 ? annotation.rate() : properties.getDefaultRate();
        int duration = annotation.durationSeconds() > 0
                ? annotation.durationSeconds() : properties.getDefaultDurationSeconds();
        String key = buildKey(annotation, method, joinPoint);

        boolean allowed = rateLimitService.tryAcquire(key, rate, duration);
        if (!allowed) {
            throw new BizException(ErrorCode.RATE_LIMITED,
                    "rate limit exceeded for " + key);
        }
        return joinPoint.proceed();
    }

    private String buildKey(RateLimit annotation, Method method, ProceedingJoinPoint joinPoint) {
        String dimension = resolveDimension(annotation, method, joinPoint);
        String iface = method.getDeclaringClass().getSimpleName() + "#" + method.getName();
        return properties.getKeyPrefix() + dimension + ":" + iface;
    }

    /**
     * Resolves the caller dimension for the bucket key.
     *
     * <p>When {@link RateLimit#key()} is set it is evaluated as a SpEL expression against the method
     * arguments (and {@code #result}); a blank or failed result falls back to the authenticated principal,
     * then to {@code anonymous}. The fallback chain is deliberate: a misconfigured expression must never
     * collapse every caller into one silently-shared bucket nor leak a raw error into the key.</p>
     */
    private String resolveDimension(RateLimit annotation, Method method, ProceedingJoinPoint joinPoint) {
        String expression = annotation.key();
        if (expression != null && !expression.isBlank()) {
            String resolved = evaluate(expression.trim(), method, joinPoint);
            if (resolved != null && !resolved.isBlank()) {
                return resolved;
            }
            log.warn("rate-limit key expression [{}] on {}#{} resolved to blank; falling back to principal",
                    expression, method.getDeclaringClass().getSimpleName(), method.getName());
        }
        return principalDimension();
    }

    @Nullable
    private String evaluate(String expression, Method method, ProceedingJoinPoint joinPoint) {
        try {
            Expression parsed = expressionCache.computeIfAbsent(expression, parser::parseExpression);
            EvaluationContext context = new MethodBasedEvaluationContext(
                    joinPoint.getTarget(), method, joinPoint.getArgs(), parameterNameDiscoverer);
            Object value = parsed.getValue(context);
            return value == null ? null : String.valueOf(value);
        } catch (RuntimeException ex) {
            log.warn("rate-limit key expression [{}] on {}#{} could not be evaluated: {}",
                    expression, method.getDeclaringClass().getSimpleName(), method.getName(),
                    ex.getMessage());
            return null;
        }
    }

    private String principalDimension() {
        MedPrincipal principal = MedSecurityContext.getPrincipal();
        if (principal == null) {
            return "anonymous";
        }
        if (principal.isPatient() && principal.getPatientId() != null) {
            return "patient:" + principal.getPatientId();
        }
        return "tenant:" + principal.getTenantId();
    }

    @Nullable
    private static Method resolveMethod(ProceedingJoinPoint joinPoint) {
        if (!(joinPoint.getSignature() instanceof MethodSignature signature)) {
            return null;
        }
        Object target = joinPoint.getTarget();
        return target == null
                ? signature.getMethod()
                : AopUtils.getMostSpecificMethod(signature.getMethod(), target.getClass());
    }
}
