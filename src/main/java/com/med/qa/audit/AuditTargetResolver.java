package com.med.qa.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the {@code target} expression of {@link com.med.qa.audit.annotation.MedAudit} into the id
 * of the touched resource.
 *
 * <p>Evaluation is delegated to Spring's own expression engine ({@link SpelExpressionParser} plus
 * {@link MethodBasedEvaluationContext}), the very mechanism behind the key expressions of
 * {@code @Cacheable}. Arguments are therefore addressable by name ({@code #sessionId}) — thanks to
 * {@link DefaultParameterNameDiscoverer} — or by position ({@code #a0} / {@code #p0}), and the return
 * value is exposed as {@code #result}.</p>
 *
 * <h2>Never break the audited call</h2>
 * <p>A malformed or inapplicable expression returns {@code null} and logs a warning instead of
 * propagating: the business operation has already run, and failing it because its audit label could
 * not be computed would turn an observability defect into a clinical outage. The entry is still
 * written — with an empty target — so the operation itself never disappears from the trail.</p>
 *
 * <p>Parsed expressions are cached by expression string. The cache is naturally bounded by the number
 * of {@code @MedAudit} declarations in the code base, so it needs no eviction policy.</p>
 */
@Component
public class AuditTargetResolver {

    private static final Logger log = LoggerFactory.getLogger(AuditTargetResolver.class);

    /** Name under which the audited method's return value is exposed to the expression. */
    public static final String RESULT_VARIABLE = "result";

    private final ExpressionParser parser = new SpelExpressionParser();

    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    private final Map<String, Expression> expressionCache = new ConcurrentHashMap<>();

    /**
     * Evaluates a target expression against the audited invocation.
     *
     * @param expression SpEL expression from the annotation; blank means "no single target"
     * @param method     the audited method, used to discover parameter names
     * @param target     the invoked bean, exposed as the expression root object, may be {@code null}
     * @param args       the invocation arguments, may be {@code null} for a no-arg method
     * @param result     the value returned by the method, or {@code null} when it threw
     * @return the resolved resource id, or {@code null} when the expression is blank, evaluates to
     *         {@code null}/blank, or cannot be evaluated
     */
    @Nullable
    public String resolve(@Nullable String expression,
                          Method method,
                          @Nullable Object target,
                          @Nullable Object[] args,
                          @Nullable Object result) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        if (method == null) {
            throw new IllegalArgumentException("method must not be null");
        }
        try {
            Expression parsed = expressionCache.computeIfAbsent(expression.trim(), parser::parseExpression);
            EvaluationContext context = new MethodBasedEvaluationContext(
                    target, method, args == null ? new Object[0] : args, parameterNameDiscoverer);
            context.setVariable(RESULT_VARIABLE, result);
            Object value = parsed.getValue(context);
            if (value == null) {
                return null;
            }
            String text = String.valueOf(value);
            return text.isBlank() ? null : text;
        } catch (RuntimeException ex) {
            log.warn("audit target expression [{}] on {}#{} could not be evaluated: {}",
                    expression, method.getDeclaringClass().getSimpleName(), method.getName(),
                    ex.getMessage());
            return null;
        }
    }
}
