package com.med.qa.audit.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Metadata tests for the {@link MedAudit} annotation: it must survive to runtime, target methods only,
 * and default its optional attributes to empty strings.
 */
class MedAuditTest {

    @MedAudit(action = "X")
    void annotatedMethod() {
    }

    @Test
    @DisplayName("the annotation is runtime-retained and method-targeted")
    void retentionAndTarget() throws NoSuchMethodException {
        Method m = MedAuditTest.class.getDeclaredMethod("annotatedMethod");
        MedAudit annotation = m.getAnnotation(MedAudit.class);
        assertNotNull(annotation);
        assertEquals("X", annotation.action());

        Retention retention = annotation.annotationType().getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());

        ElementType[] targets = annotation.annotationType().getAnnotation(java.lang.annotation.Target.class).value();
        boolean targetsMethods = false;
        for (ElementType type : targets) {
            if (type == ElementType.METHOD) {
                targetsMethods = true;
            }
        }
        assertTrue(targetsMethods);
    }

    @Test
    @DisplayName("optional attributes default to empty strings")
    void optionalDefaults() throws NoSuchMethodException {
        Method m = MedAuditTest.class.getDeclaredMethod("annotatedMethod");
        MedAudit annotation = m.getAnnotation(MedAudit.class);
        assertEquals("", annotation.resourceType());
        assertEquals("", annotation.target());
        assertEquals("", annotation.description());
    }
}
