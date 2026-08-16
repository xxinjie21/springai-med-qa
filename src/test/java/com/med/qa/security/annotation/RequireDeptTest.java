package com.med.qa.security.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import com.med.qa.security.MedRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Unit tests of the {@link RequireDept} annotation contract: defaults, overrides and meta-annotations.
 *
 * <p>The defaults matter as much as the behaviour: a handler that simply writes {@code @RequireDept}
 * must get the strict variant (department id mandatory, read from the request envelope, every
 * authenticated role admitted), because a lax default would silently open endpoints.</p>
 */
class RequireDeptTest {

    @RequireDept
    private static class DefaultsHolder {

        @RequireDept
        void defaults() {
        }
    }

    @RequireDept(param = "department", source = DeptIdSource.PATH, roles = MedRole.STAFF, required = false)
    private static class OverriddenHolder {

        @RequireDept(param = "department", source = DeptIdSource.HEADER, roles = {MedRole.STAFF, MedRole.PATIENT})
        void overridden() {
        }
    }

    @Test
    @DisplayName("defaults are strict: deptId param, AUTO source, no role restriction, id required")
    void defaults() throws NoSuchMethodException {
        Method method = DefaultsHolder.class.getDeclaredMethod("defaults");
        RequireDept annotation = method.getAnnotation(RequireDept.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.param()).isEqualTo("deptId");
        assertThat(annotation.source()).isEqualTo(DeptIdSource.AUTO);
        assertThat(annotation.roles()).isEmpty();
        assertThat(annotation.required()).isTrue();
    }

    @Test
    @DisplayName("every attribute can be overridden on a method")
    void overriddenOnMethod() throws NoSuchMethodException {
        Method method = OverriddenHolder.class.getDeclaredMethod("overridden");
        RequireDept annotation = method.getAnnotation(RequireDept.class);
        assertThat(annotation.param()).isEqualTo("department");
        assertThat(annotation.source()).isEqualTo(DeptIdSource.HEADER);
        assertThat(Arrays.asList(annotation.roles()))
                .containsExactly(MedRole.STAFF, MedRole.PATIENT);
        assertThat(annotation.required()).isTrue();
    }

    @Test
    @DisplayName("can be declared on a controller class with relaxed requirements")
    void declaredOnType() {
        RequireDept annotation = OverriddenHolder.class.getAnnotation(RequireDept.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.source()).isEqualTo(DeptIdSource.PATH);
        assertThat(annotation.required()).isFalse();
        assertThat(annotation.roles()).containsExactly(MedRole.STAFF);
    }

    @Test
    @DisplayName("is retained at runtime and targets both types and methods")
    void metaAnnotations() {
        Retention retention = RequireDept.class.getAnnotation(Retention.class);
        Target target = RequireDept.class.getAnnotation(Target.class);
        assertThat(retention).isNotNull();
        assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(target).isNotNull();
        assertThat(target.value()).containsExactlyInAnyOrder(ElementType.TYPE, ElementType.METHOD);
    }

    @Test
    @DisplayName("source enum covers query, path, header and the auto fallback")
    void sourceValues() {
        assertThat(DeptIdSource.values())
                .containsExactly(DeptIdSource.AUTO, DeptIdSource.QUERY,
                        DeptIdSource.PATH, DeptIdSource.HEADER);
        assertThat(DeptIdSource.valueOf("QUERY")).isEqualTo(DeptIdSource.QUERY);
    }
}
