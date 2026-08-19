package com.med.qa.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.med.qa.audit.annotation.MedAudit;
import com.med.qa.common.exception.BizException;
import com.med.qa.domain.enums.AuditOutcome;
import com.med.qa.service.AuditService;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.config.MedAuditProperties;
import com.med.qa.security.MedPrincipal;
import com.med.qa.security.MedRole;
import com.med.qa.security.MedSecurityContext;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link AuditAspect}: the around advice records SUCCESS/FAILURE with the resolved
 * principal and target, never changes the business outcome (rethrows the original, swallows an audit
 * write failure), and is bypassed when disabled.
 */
class AuditAspectTest {

    static class AuditedTarget {
        @MedAudit(action = "TEST_ACTION", resourceType = "TEST_RES", target = "#id", description = "did it")
        public String run(String id) {
            return "ok:" + id;
        }
    }

    private final AuditService auditService = mock(AuditService.class);

    private final AuditTargetResolver targetResolver = mock(AuditTargetResolver.class);

    private static Method auditedMethod() throws NoSuchMethodException {
        return AuditedTarget.class.getMethod("run", String.class);
    }

    private ProceedingJoinPoint mockJoinPoint(Method method, Object target, Object[] args,
                                              Object result, Throwable failure) throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        MethodSignature sig = mock(MethodSignature.class);
        when(pjp.getSignature()).thenReturn(sig);
        when(sig.getMethod()).thenReturn(method);
        when(pjp.getTarget()).thenReturn(target);
        when(pjp.getArgs()).thenReturn(args);
        if (failure != null) {
            when(pjp.proceed()).thenThrow(failure);
        } else {
            when(pjp.proceed()).thenReturn(result);
        }
        return pjp;
    }

    @AfterEach
    void clearContext() {
        MedSecurityContext.clear();
    }

    @Test
    @DisplayName("a successful call records SUCCESS with the principal and resolved target")
    void recordsSuccess() throws Throwable {
        Method method = auditedMethod();
        ProceedingJoinPoint pjp = mockJoinPoint(method, new AuditedTarget(),
                new Object[]{"sess-9"}, "ok:sess-9", null);
        MedSecurityContext.setPrincipal(new MedPrincipal("t1", "d1", MedRole.PATIENT, "p1"));
        AtomicLong nanos = new AtomicLong(1_000L);
        AuditAspect aspect = new AuditAspect(auditService, targetResolver,
                new MedAuditProperties(), () -> nanos.getAndAdd(5_000_000L));
        when(targetResolver.resolve(eq("#id"), any(), any(), any(), any())).thenReturn("sess-9");

        Object result = aspect.audit(pjp);

        assertEquals("ok:sess-9", result);
        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditService).record(captor.capture());
        AuditRecord rec = captor.getValue();
        assertEquals("TEST_ACTION", rec.action());
        assertEquals("TEST_RES", rec.resourceType());
        assertEquals("sess-9", rec.resourceId());
        assertEquals(AuditOutcome.SUCCESS, rec.outcome());
        assertEquals("t1", rec.tenantId());
        assertEquals("p1", rec.operatorId());
        assertEquals(MedRole.PATIENT, rec.operatorRole());
        assertEquals(5L, rec.latencyMillis());
    }

    @Test
    @DisplayName("a throwing call records FAILURE with the classified error code and rethrows the original")
    void recordsFailureAndRethrows() throws Throwable {
        Method method = auditedMethod();
        BizException biz = new BizException(ErrorCode.NOT_FOUND, "nope");
        ProceedingJoinPoint pjp = mockJoinPoint(method, new AuditedTarget(),
                new Object[]{"sess-9"}, null, biz);
        MedSecurityContext.setPrincipal(new MedPrincipal("t1", "d1", MedRole.STAFF, null));
        AuditAspect aspect = new AuditAspect(auditService, targetResolver,
                new MedAuditProperties(), () -> 0L);
        when(targetResolver.resolve(any(), any(), any(), any(), any())).thenReturn(null);

        BizException thrown = assertThrows(BizException.class, () -> aspect.audit(pjp));
        assertSame(biz, thrown);

        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditService).record(captor.capture());
        AuditRecord rec = captor.getValue();
        assertEquals(AuditOutcome.FAILURE, rec.outcome());
        assertEquals(ErrorCode.NOT_FOUND.getCode(), rec.errorCode());
        assertEquals(0L, rec.latencyMillis());
    }

    @Test
    @DisplayName("when disabled the advice proceeds but never records")
    void disabledSkipsRecording() throws Throwable {
        Method method = auditedMethod();
        ProceedingJoinPoint pjp = mockJoinPoint(method, new AuditedTarget(),
                new Object[]{"x"}, "ok:x", null);
        MedAuditProperties properties = new MedAuditProperties();
        properties.setEnabled(false);
        AuditAspect aspect = new AuditAspect(auditService, targetResolver, properties, () -> 0L);

        assertEquals("ok:x", aspect.audit(pjp));
        verify(auditService, never()).record(any());
    }

    @Test
    @DisplayName("a failed audit write is swallowed and the business result is still returned")
    void auditWriteFailureIsSwallowed() throws Throwable {
        Method method = auditedMethod();
        ProceedingJoinPoint pjp = mockJoinPoint(method, new AuditedTarget(),
                new Object[]{"x"}, "ok:x", null);
        MedSecurityContext.setPrincipal(new MedPrincipal("t1", "d1", MedRole.PATIENT, "p1"));
        AuditAspect aspect = new AuditAspect(auditService, targetResolver,
                new MedAuditProperties(), () -> 0L);
        doThrow(new RuntimeException("audit boom")).when(auditService).record(any());
        when(targetResolver.resolve(any(), any(), any(), any(), any())).thenReturn("x");

        assertEquals("ok:x", aspect.audit(pjp));
    }

    @Test
    @DisplayName("the aspect reports the configured advice order")
    void orderMatchesProperties() {
        MedAuditProperties properties = new MedAuditProperties();
        properties.setOrder(42);
        AuditAspect aspect = new AuditAspect(auditService, targetResolver, properties, () -> 0L);
        assertEquals(42, aspect.getOrder());
    }
}
