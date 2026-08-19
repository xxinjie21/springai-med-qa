package com.med.qa.audit;

import com.med.qa.audit.annotation.MedAudit;
import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.config.MedAuditProperties;
import com.med.qa.security.MedPrincipal;
import com.med.qa.security.MedSecurityContext;
import com.med.qa.service.AuditService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Around advice writing one audit entry per {@link MedAudit}-annotated invocation.
 *
 * <h2>What is recorded</h2>
 * <p>The acting principal resolved by {@code ApiKeyAuthFilter} into {@link MedSecurityContext} (who),
 * the annotation's action and resource type (what), the evaluated target expression (on which
 * object), the measured wall-clock latency (how expensive) and the outcome — {@code SUCCESS}, or
 * {@code FAILURE} plus the business error code of the thrown {@link BizException}. Both paths are
 * recorded: in a hospital deployment the rejected attempt is usually the interesting one, so an
 * exception must not cost its audit entry.</p>
 *
 * <h2>Why the audit never changes the business outcome</h2>
 * <p>The advice re-throws the original exception untouched and swallows failures of the audit write
 * itself (logging them at {@code ERROR}). Converting a completed medical operation into a {@code 500}
 * because its evidence row could not be inserted would harm the patient in front of the clinician;
 * conversely, letting an audit failure mask the real business exception would destroy the diagnosis.
 * The trade-off is explicit and one-directional: the trail may lose an entry, the caller never loses
 * their result.</p>
 *
 * <h2>Latency measurement</h2>
 * <p>Duration comes from a monotonic nanosecond source, not from the wall clock, so an NTP step or a
 * daylight-saving jump cannot produce a negative or wildly inflated figure. The source is injectable
 * for deterministic tests. The aspect orders itself with a low value
 * ({@link MedAuditProperties#getOrder()}), keeping it on the outside of the advice chain so the
 * measurement covers the whole call rather than a fragment of it.</p>
 */
@Aspect
@Component
public class AuditAspect implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    private final AuditService auditService;

    private final AuditTargetResolver targetResolver;

    private final MedAuditProperties properties;

    private final LongSupplier nanoTimeSource;

    /**
     * Creates the aspect used by the application context.
     *
     * @param auditService   audit persistence service, must not be {@code null}
     * @param targetResolver resolver of the annotation's target expression, must not be {@code null}
     * @param properties     master switch, advice order and truncation limits, must not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    @Autowired
    public AuditAspect(AuditService auditService,
                       AuditTargetResolver targetResolver,
                       MedAuditProperties properties) {
        this(auditService, targetResolver, properties, System::nanoTime);
    }

    /**
     * Creates the aspect with an explicit monotonic time source, so measured latencies are
     * deterministic in tests.
     *
     * @param auditService    audit persistence service, must not be {@code null}
     * @param targetResolver  resolver of the annotation's target expression, must not be {@code null}
     * @param properties      master switch, advice order and truncation limits, must not be {@code null}
     * @param nanoTimeSource  monotonic nanosecond source, must not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public AuditAspect(AuditService auditService,
                       AuditTargetResolver targetResolver,
                       MedAuditProperties properties,
                       LongSupplier nanoTimeSource) {
        if (auditService == null) {
            throw new IllegalArgumentException("auditService must not be null");
        }
        if (targetResolver == null) {
            throw new IllegalArgumentException("targetResolver must not be null");
        }
        if (properties == null) {
            throw new IllegalArgumentException("properties must not be null");
        }
        if (nanoTimeSource == null) {
            throw new IllegalArgumentException("nanoTimeSource must not be null");
        }
        this.auditService = auditService;
        this.targetResolver = targetResolver;
        this.properties = properties;
        this.nanoTimeSource = nanoTimeSource;
    }

    /**
     * Runs the audited method and appends its audit entry.
     *
     * @param joinPoint the intercepted invocation, must not be {@code null}
     * @return whatever the audited method returned
     * @throws Throwable the original exception of the audited method, re-thrown unchanged
     */
    @Around("@annotation(com.med.qa.audit.annotation.MedAudit)")
    public Object audit(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = resolveMethod(joinPoint);
        MedAudit annotation = method == null
                ? null
                : AnnotatedElementUtils.findMergedAnnotation(method, MedAudit.class);
        if (annotation == null || !properties.isEnabled()) {
            return joinPoint.proceed();
        }

        long startNanos = nanoTimeSource.getAsLong();
        Object result = null;
        Throwable failure = null;
        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable ex) {
            failure = ex;
            throw ex;
        } finally {
            long latencyMillis = elapsedMillis(startNanos);
            write(annotation, method, joinPoint, result, failure, latencyMillis);
        }
    }

    /**
     * Returns the advice order, keeping the aspect outside the rest of the chain.
     *
     * @return the configured order value
     */
    @Override
    public int getOrder() {
        return properties.getOrder();
    }

    private void write(MedAudit annotation,
                       Method method,
                       ProceedingJoinPoint joinPoint,
                       @Nullable Object result,
                       @Nullable Throwable failure,
                       long latencyMillis) {
        try {
            auditService.record(buildRecord(annotation, method, joinPoint, result, failure, latencyMillis));
        } catch (RuntimeException ex) {
            // The audited operation already happened. Losing its entry is a compliance incident worth
            // an ERROR line, but rethrowing here would either fail a successful medical operation or
            // replace the caller's real exception with a storage one.
            log.error("failed to record audit entry for action {} on {}#{}: {}",
                    annotation.action(), method.getDeclaringClass().getSimpleName(), method.getName(),
                    ex.getMessage(), ex);
        }
    }

    private AuditRecord buildRecord(MedAudit annotation,
                                    Method method,
                                    ProceedingJoinPoint joinPoint,
                                    @Nullable Object result,
                                    @Nullable Throwable failure,
                                    long latencyMillis) {
        MedPrincipal principal = MedSecurityContext.getPrincipal();
        String resourceId = targetResolver.resolve(
                annotation.target(), method, joinPoint.getTarget(), joinPoint.getArgs(), result);
        AuditRecord.Builder builder = AuditRecord.builder(annotation.action())
                .principal(principal)
                .resource(emptyToNull(annotation.resourceType()), resourceId)
                .latencyMillis(latencyMillis);
        if (failure == null) {
            builder.success(emptyToNull(annotation.description()));
        } else {
            builder.failure(classifyErrorCode(failure), describeFailure(failure));
        }
        return builder.build();
    }

    /**
     * Maps a thrown exception to the business code that is stored with a failed entry: the declared
     * code of a {@link BizException}, or {@link ErrorCode#INTERNAL_ERROR} for anything unexpected.
     */
    private static Integer classifyErrorCode(Throwable failure) {
        if (failure instanceof BizException bizException) {
            return bizException.getErrorCode().getCode();
        }
        return ErrorCode.INTERNAL_ERROR.getCode();
    }

    /**
     * Builds the failure summary. The exception type is always included because a bare message such as
     * "null" or "0" tells a reviewer nothing about what went wrong.
     */
    private static String describeFailure(Throwable failure) {
        String message = failure.getMessage();
        String type = failure.getClass().getSimpleName();
        return message == null || message.isBlank() ? type : type + ": " + message;
    }

    private long elapsedMillis(long startNanos) {
        long elapsed = nanoTimeSource.getAsLong() - startNanos;
        return elapsed <= 0 ? 0L : TimeUnit.NANOSECONDS.toMillis(elapsed);
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

    @Nullable
    private static String emptyToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
