package com.med.qa.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.common.ratelimit.annotation.RateLimit;
import com.med.qa.config.MedRateLimitProperties;
import com.med.qa.security.MedPrincipal;
import com.med.qa.security.MedRole;
import com.med.qa.security.MedSecurityContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;

/**
 * Unit tests of {@link RateLimitAspect}: pass-through when disabled, proceed-on-allow, rejection on
 * denial, bucket-key composition by principal and by SpEL, and the configured advice order. The limiter
 * is a mock, so no Redis is contacted.
 */
class RateLimitAspectTest {

    /** Target carrying {@link RateLimit}-annotated methods exercised by the tests. */
    static class TestTarget {
        @RateLimit(rate = 5, durationSeconds = 1)
        public String rated(String patientId) {
            return "ok";
        }

        @RateLimit(rate = 5, durationSeconds = 1, key = "#patientId")
        public String keyed(String patientId) {
            return "ok";
        }

        @RateLimit(rate = 5, durationSeconds = 1, key = "#missing")
        public String brokenKey(String patientId) {
            return "ok";
        }
    }

    private final RateLimitService rateLimitService = mock(RateLimitService.class);

    @AfterEach
    void clearContext() {
        MedSecurityContext.clear();
    }

    private ProceedingJoinPoint joinPointFor(Method method, Object target, Object[] args) throws Throwable {
        ProceedingJoinPoint jp = mock(ProceedingJoinPoint.class);
        MethodSignature sig = mock(MethodSignature.class);
        when(jp.getSignature()).thenReturn(sig);
        when(sig.getMethod()).thenReturn(method);
        when(jp.getTarget()).thenReturn(target);
        when(jp.getArgs()).thenReturn(args);
        when(jp.proceed()).thenReturn("ok");
        return jp;
    }

    private RateLimitAspect aspect(boolean enabled) {
        MedRateLimitProperties props = new MedRateLimitProperties();
        props.setEnabled(enabled);
        return new RateLimitAspect(rateLimitService, props);
    }

    @Test
    @DisplayName("passes through unchanged when the limiter is disabled")
    void disabled() throws Throwable {
        Method m = TestTarget.class.getMethod("rated", String.class);
        ProceedingJoinPoint jp = joinPointFor(m, new TestTarget(), new Object[]{"p1"});
        Object result = aspect(false).limit(jp);
        assertThat(result).isEqualTo("ok");
        verify(rateLimitService, never()).tryAcquire(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("proceeds and acquires a permit when one is granted")
    void allowed() throws Throwable {
        Method m = TestTarget.class.getMethod("rated", String.class);
        ProceedingJoinPoint jp = joinPointFor(m, new TestTarget(), new Object[]{"p1"});
        when(rateLimitService.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
        Object result = aspect(true).limit(jp);
        assertThat(result).isEqualTo("ok");
        verify(rateLimitService).tryAcquire(anyString(), eq(5), eq(1));
    }

    @Test
    @DisplayName("throws RATE_LIMITED and never proceeds when the bucket is exhausted")
    void denied() throws Throwable {
        Method m = TestTarget.class.getMethod("rated", String.class);
        ProceedingJoinPoint jp = joinPointFor(m, new TestTarget(), new Object[]{"p1"});
        when(rateLimitService.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(false);
        BizException ex = assertThrows(BizException.class, () -> aspect(true).limit(jp));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
        verify(jp, never()).proceed();
    }

    @Test
    @DisplayName("buckets by the authenticated patient principal when no key expression is set")
    void principalPatient() throws Throwable {
        Method m = TestTarget.class.getMethod("rated", String.class);
        MedSecurityContext.setPrincipal(new MedPrincipal("t1", "d1", MedRole.PATIENT, "pat-1"));
        ProceedingJoinPoint jp = joinPointFor(m, new TestTarget(), new Object[]{"pat-1"});
        when(rateLimitService.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
        aspect(true).limit(jp);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(rateLimitService).tryAcquire(key.capture(), eq(5), eq(1));
        assertThat(key.getValue()).contains("patient:pat-1").contains("TestTarget#rated");
    }

    @Test
    @DisplayName("buckets by the SpEL key expression when provided")
    void spelKey() throws Throwable {
        Method m = TestTarget.class.getMethod("keyed", String.class);
        MedSecurityContext.setPrincipal(null);
        ProceedingJoinPoint jp = joinPointFor(m, new TestTarget(), new Object[]{"pat-x"});
        when(rateLimitService.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
        aspect(true).limit(jp);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(rateLimitService).tryAcquire(key.capture(), eq(5), eq(1));
        assertThat(key.getValue()).contains("pat-x").contains("TestTarget#keyed");
    }

    @Test
    @DisplayName("falls back to anonymous when the SpEL key cannot be resolved")
    void brokenSpelFallback() throws Throwable {
        Method m = TestTarget.class.getMethod("brokenKey", String.class);
        MedSecurityContext.setPrincipal(null);
        ProceedingJoinPoint jp = joinPointFor(m, new TestTarget(), new Object[]{"pat-x"});
        when(rateLimitService.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
        aspect(true).limit(jp);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(rateLimitService).tryAcquire(key.capture(), eq(5), eq(1));
        assertThat(key.getValue()).contains("anonymous").contains("TestTarget#brokenKey");
    }

    @Test
    @DisplayName("returns the configured advice order")
    void order() {
        MedRateLimitProperties props = new MedRateLimitProperties();
        props.setOrder(7);
        assertThat(new RateLimitAspect(rateLimitService, props).getOrder()).isEqualTo(7);
    }
}
