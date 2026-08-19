package com.med.qa.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.med.qa.audit.AuditTargetResolver;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AuditTargetResolver}: SpEL resolution by argument name and position, the
 * {@code #result} variable, blank/malformed expressions (which never break the audited call) and the
 * {@code null} method guard.
 */
class AuditTargetResolverTest {

    static class Sample {
        public String op(String sessionId, int n) {
            return sessionId;
        }
    }

    private final AuditTargetResolver resolver = new AuditTargetResolver();

    private Method method() throws NoSuchMethodException {
        return Sample.class.getMethod("op", String.class, int.class);
    }

    @Test
    @DisplayName("resolves by argument name and by position")
    void resolvesByNameAndPosition() throws Exception {
        Method m = method();
        Sample target = new Sample();
        assertEquals("s1", resolver.resolve("#sessionId", m, target, new Object[]{"s1", 2}, null));
        assertEquals("s1", resolver.resolve("#a0", m, target, new Object[]{"s1", 2}, null));
    }

    @Test
    @DisplayName("resolves the #result variable")
    void resolvesResult() throws Exception {
        Method m = method();
        assertEquals("RV", resolver.resolve("#result", m, new Sample(), new Object[]{"s1", 2}, "RV"));
    }

    @Test
    @DisplayName("blank and null expressions yield no target")
    void blankExpressionYieldsNull() throws Exception {
        Method m = method();
        assertNull(resolver.resolve("", m, new Sample(), new Object[]{"s1", 2}, null));
        assertNull(resolver.resolve("   ", m, new Sample(), new Object[]{"s1", 2}, null));
    }

    @Test
    @DisplayName("a malformed or unresolvable expression returns null instead of throwing")
    void malformedExpressionIsSafe() throws Exception {
        Method m = method();
        assertNull(resolver.resolve("#sessionId.nope()", m, new Sample(), new Object[]{"s1", 2}, null));
        assertNull(resolver.resolve("#missing", m, new Sample(), new Object[]{"s1", 2}, null));
    }

    @Test
    @DisplayName("a null method is rejected")
    void nullMethodRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("#x", null, new Sample(), new Object[]{}, null));
    }
}
