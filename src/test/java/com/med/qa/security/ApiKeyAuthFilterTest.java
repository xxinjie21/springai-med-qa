package com.med.qa.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.med.qa.config.MedSecurityProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unit tests of {@link ApiKeyAuthFilter} driving {@link MockHttpServletRequest} through the filter.
 *
 * <p>The filter performs no IO of its own: the registry is built from an in-memory configuration, so the
 * whole authentication path is exercised without Redis or MySQL.</p>
 */
class ApiKeyAuthFilterTest {

    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach
    void tearDown() {
        MedSecurityContext.clear();
    }

    private static MedSecurityProperties properties(boolean enabled, boolean requireAuth) {
        MedSecurityProperties properties = new MedSecurityProperties();
        properties.setEnabled(enabled);
        properties.setRequireAuth(requireAuth);
        properties.setHeaderName("X-API-Key");
        MedApiKey key = new MedApiKey();
        key.setTenantId("hosp-1");
        key.setDeptId("cardiology");
        key.setRole(MedRole.PATIENT);
        key.setPatientId("pat-77");
        Map<String, MedApiKey> keys = new LinkedHashMap<>();
        keys.put("secret", key);
        properties.setKeys(keys);
        return properties;
    }

    @Nested
    @DisplayName("when the filter is disabled")
    class Disabled {

        @Test
        @DisplayName("every request passes through untouched, even without a key")
        void passesThrough() throws Exception {
            ApiKeyAuthFilter filter = new ApiKeyAuthFilter(properties(false, true), new MedApiKeyRegistry(properties(false, true)));
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sessions/x");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, chain);

            verify(chain).doFilter(any(), any());
            assertThat(response.getStatus()).isNotEqualTo(401);
        }
    }

    @Nested
    @DisplayName("when the filter is enabled and requires auth")
    class EnabledRequireAuth {

        private final MedSecurityProperties props = properties(true, true);
        private final ApiKeyAuthFilter filter = new ApiKeyAuthFilter(props, new MedApiKeyRegistry(props));

        @Test
        @DisplayName("a valid key binds the principal and continues the chain")
        void validKey() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sessions/x");
            request.addHeader("X-API-Key", "secret");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, chain);

            verify(chain).doFilter(any(), any());
            assertThat(response.getStatus()).isNotEqualTo(401);
            // context is cleared after the chain returns
            assertThat(MedSecurityContext.getPrincipal()).isNull();
        }

        @Test
        @DisplayName("a missing key is rejected with 401 and the chain is not run")
        void missingKey() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sessions/x");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).contains("40100");
            verify(chain, never()).doFilter(any(), any());
        }

        @Test
        @DisplayName("an unknown key is rejected with 401")
        void unknownKey() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sessions/x");
            request.addHeader("X-API-Key", "nope");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            verify(chain, never()).doFilter(any(), any());
        }

        @Test
        @DisplayName("management endpoints under /actuator are skipped")
        void actuatorSkipped() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
            request.setServletPath("/actuator/health");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, chain);

            verify(chain).doFilter(any(), any());
            assertThat(response.getStatus()).isNotEqualTo(401);
        }
    }

    @Nested
    @DisplayName("when auth is not required")
    class AnonymousAllowed {

        private final MedSecurityProperties props = properties(true, false);
        private final ApiKeyAuthFilter filter = new ApiKeyAuthFilter(props, new MedApiKeyRegistry(props));

        @Test
        @DisplayName("a request without a key is passed through as anonymous")
        void anonymousPasses() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sessions/x");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, chain);

            verify(chain).doFilter(any(), any());
            assertThat(response.getStatus()).isNotEqualTo(401);
            assertThat(MedSecurityContext.getPrincipal()).isNull();
        }

        @Test
        @DisplayName("a valid key still binds the principal")
        void validKeyStillBinds() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sessions/x");
            request.addHeader("X-API-Key", "secret");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, chain);

            verify(chain).doFilter(any(), any());
            assertThat(MedSecurityContext.getPrincipal()).isNull();
        }
    }
}
