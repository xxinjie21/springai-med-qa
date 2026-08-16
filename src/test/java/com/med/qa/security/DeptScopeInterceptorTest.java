package com.med.qa.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.med.qa.config.MedSecurityProperties;
import com.med.qa.security.annotation.DeptIdSource;
import com.med.qa.security.annotation.RequireDept;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Unit tests of {@link DeptScopeInterceptor}: annotation discovery, the {@code 403} rejection envelope
 * and the configuration toggles.
 *
 * <p>The interceptor is driven directly with {@link MockHttpServletRequest} /
 * {@link MockHttpServletResponse} and a hand-built {@link HandlerMethod}, so the department-scope decision
 * is verified without a servlet container, a dispatcher or any middleware.</p>
 */
class DeptScopeInterceptorTest {

    /** Stand-in controller exposing every annotation placement the interceptor must understand. */
    @RequireDept(param = "deptId", source = DeptIdSource.HEADER, roles = MedRole.STAFF, required = false)
    static class StubController {

        @RequireDept(source = DeptIdSource.QUERY)
        void queryScoped() {
        }

        @RequireDept(source = DeptIdSource.PATH)
        void pathScoped() {
        }

        @RequireDept(required = false)
        void bodyScoped() {
        }

        @RequireDept(roles = MedRole.STAFF)
        void staffOnly() {
        }

        void inheritsClassLevel() {
        }
    }

    /** Controller without any authorization annotation: must be let through untouched. */
    static class OpenController {

        void open() {
        }
    }

    private MedSecurityProperties properties;

    private DeptScopeInterceptor interceptor;

    private MockHttpServletRequest request;

    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        properties = new MedSecurityProperties();
        interceptor = new DeptScopeInterceptor(properties, new DeptScopeGuard(), new DeptIdResolver());
        request = new MockHttpServletRequest("GET", "/api/sessions");
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        MedSecurityContext.clear();
    }

    private static HandlerMethod handler(String methodName) {
        return handler(new StubController(), StubController.class, methodName);
    }

    private static HandlerMethod handler(Object bean, Class<?> type, String methodName) {
        try {
            Method method = type.getDeclaredMethod(methodName);
            return new HandlerMethod(bean, method);
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("rejects null collaborators")
        void nullArguments() {
            assertThatThrownBy(() -> new DeptScopeInterceptor(null, new DeptScopeGuard(), new DeptIdResolver()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new DeptScopeInterceptor(properties, null, new DeptIdResolver()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new DeptScopeInterceptor(properties, new DeptScopeGuard(), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("pass through")
    class PassThrough {

        @Test
        @DisplayName("a non-handler-method target (static resource, error dispatch) is untouched")
        void notAHandlerMethod() throws IOException {
            assertThat(interceptor.preHandle(request, response, "some-resource-handler")).isTrue();
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(interceptor.findAnnotation(null)).isNull();
        }

        @Test
        @DisplayName("a handler without @RequireDept is untouched even when anonymous")
        void unannotatedHandler() throws IOException {
            HandlerMethod open = handler(new OpenController(), OpenController.class, "open");
            assertThat(interceptor.preHandle(request, response, open)).isTrue();
            assertThat(response.getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("the check is skipped when security is disabled altogether")
        void securityDisabled() throws IOException {
            properties.setEnabled(false);
            assertThat(interceptor.preHandle(request, response, handler("queryScoped"))).isTrue();
        }

        @Test
        @DisplayName("the check is skipped when only department scoping is disabled")
        void deptScopeDisabled() throws IOException {
            properties.setDeptScopeEnabled(false);
            assertThat(interceptor.preHandle(request, response, handler("queryScoped"))).isTrue();
        }
    }

    @Nested
    @DisplayName("authorized requests")
    class Authorized {

        @Test
        @DisplayName("a matching department in the query string is admitted")
        void matchingQueryDepartment() throws IOException {
            MedSecurityContext.setPrincipal(new MedPrincipal("hosp-1", "cardiology", MedRole.PATIENT, "pat-77"));
            request.setParameter("deptId", "cardiology");
            assertThat(interceptor.preHandle(request, response, handler("queryScoped"))).isTrue();
            assertThat(response.getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("a matching department in a URI template variable is admitted")
        void matchingPathDepartment() throws IOException {
            MedSecurityContext.setPrincipal(new MedPrincipal("hosp-1", "neurology", MedRole.STAFF, null));
            request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                    Map.of("deptId", "neurology"));
            assertThat(interceptor.preHandle(request, response, handler("pathScoped"))).isTrue();
        }

        @Test
        @DisplayName("a body-borne scope only needs authentication")
        void bodyScopedNeedsAuthOnly() throws IOException {
            MedSecurityContext.setPrincipal(new MedPrincipal("hosp-1", "cardiology", MedRole.PATIENT, "pat-77"));
            assertThat(interceptor.preHandle(request, response, handler("bodyScoped"))).isTrue();
        }

        @Test
        @DisplayName("a class-level declaration applies to an unannotated method of that controller")
        void classLevelApplies() throws IOException {
            MedSecurityContext.setPrincipal(new MedPrincipal("hosp-1", "cardiology", MedRole.STAFF, null));
            request.addHeader("deptId", "cardiology");
            assertThat(interceptor.preHandle(request, response, handler("inheritsClassLevel"))).isTrue();
        }
    }

    @Nested
    @DisplayName("refused requests write 403")
    class Refused {

        @Test
        @DisplayName("a cross-department call is refused with the unified error envelope")
        void crossDepartment() throws IOException {
            MedSecurityContext.setPrincipal(new MedPrincipal("hosp-1", "cardiology", MedRole.STAFF, null));
            request.setParameter("deptId", "neurology");

            boolean proceed = interceptor.preHandle(request, response, handler("queryScoped"));

            assertThat(proceed).isFalse();
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentType()).startsWith("application/json");
            assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
            assertThat(response.getContentAsString())
                    .contains("\"code\":40300")
                    .contains("department mismatch");
        }

        @Test
        @DisplayName("an anonymous call is refused, so the check fails closed")
        void anonymous() throws IOException {
            request.setParameter("deptId", "cardiology");
            assertThat(interceptor.preHandle(request, response, handler("queryScoped"))).isFalse();
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentAsString()).contains("authentication required");
        }

        @Test
        @DisplayName("a missing department id is refused when the handler requires one")
        void missingDepartment() throws IOException {
            MedSecurityContext.setPrincipal(new MedPrincipal("hosp-1", "cardiology", MedRole.STAFF, null));
            assertThat(interceptor.preHandle(request, response, handler("queryScoped"))).isFalse();
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentAsString()).contains("required but missing");
        }

        @Test
        @DisplayName("a patient is refused on a staff-only handler")
        void patientOnStaffOnlyHandler() throws IOException {
            MedSecurityContext.setPrincipal(new MedPrincipal("hosp-1", "cardiology", MedRole.PATIENT, "pat-77"));
            request.setParameter("deptId", "cardiology");
            assertThat(interceptor.preHandle(request, response, handler("staffOnly"))).isFalse();
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentAsString()).contains("PATIENT");
        }

        @Test
        @DisplayName("a method-level declaration overrides the relaxed class-level one")
        void methodOverridesClass() throws IOException {
            MedSecurityContext.setPrincipal(new MedPrincipal("hosp-1", "cardiology", MedRole.STAFF, null));
            // Class level reads the header and tolerates a missing id; the method demands a query param.
            request.addHeader("deptId", "cardiology");
            assertThat(interceptor.preHandle(request, response, handler("queryScoped"))).isFalse();
            assertThat(response.getStatus()).isEqualTo(403);
        }
    }
}
