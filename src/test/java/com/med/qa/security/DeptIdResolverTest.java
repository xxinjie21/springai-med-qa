package com.med.qa.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.med.qa.security.annotation.DeptIdSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.Optional;

/**
 * Unit tests of {@link DeptIdResolver}: each lookup source, the AUTO precedence chain and the
 * "nothing found" contract.
 *
 * <p>Driven with {@link MockHttpServletRequest} — no servlet container and no middleware.</p>
 */
class DeptIdResolverTest {

    private DeptIdResolver resolver;

    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        resolver = new DeptIdResolver();
        request = new MockHttpServletRequest("GET", "/api/sessions");
    }

    @Nested
    @DisplayName("single sources")
    class SingleSources {

        @Test
        @DisplayName("reads the department id from a query parameter")
        void fromQuery() {
            request.setParameter("deptId", "cardiology");
            assertThat(resolver.resolve(request, "deptId", DeptIdSource.QUERY))
                    .contains("cardiology");
        }

        @Test
        @DisplayName("reads the department id from a URI template variable")
        void fromPath() {
            request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                    Map.of("deptId", "neurology"));
            assertThat(resolver.resolve(request, "deptId", DeptIdSource.PATH))
                    .contains("neurology");
        }

        @Test
        @DisplayName("reads the department id from a header")
        void fromHeader() {
            request.addHeader("X-Dept-Id", "oncology");
            assertThat(resolver.resolve(request, "X-Dept-Id", DeptIdSource.HEADER))
                    .contains("oncology");
        }

        @Test
        @DisplayName("returns empty when the requested source carries nothing")
        void missingValue() {
            request.addHeader("deptId", "cardiology");
            assertThat(resolver.resolve(request, "deptId", DeptIdSource.QUERY)).isEmpty();
            assertThat(resolver.resolve(request, "deptId", DeptIdSource.PATH)).isEmpty();
        }

        @Test
        @DisplayName("treats a blank value as absent and trims a padded one")
        void blankAndPadded() {
            request.setParameter("deptId", "   ");
            assertThat(resolver.resolve(request, "deptId", DeptIdSource.QUERY)).isEmpty();

            MockHttpServletRequest padded = new MockHttpServletRequest();
            padded.setParameter("deptId", "  cardiology  ");
            assertThat(resolver.resolve(padded, "deptId", DeptIdSource.QUERY)).contains("cardiology");
        }
    }

    @Nested
    @DisplayName("AUTO precedence")
    class Auto {

        @Test
        @DisplayName("prefers the query parameter over path and header")
        void queryWins() {
            request.setParameter("deptId", "from-query");
            request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                    Map.of("deptId", "from-path"));
            request.addHeader("deptId", "from-header");
            assertThat(resolver.resolve(request, "deptId", DeptIdSource.AUTO)).contains("from-query");
        }

        @Test
        @DisplayName("falls back to the path variable, then to the header")
        void fallbackChain() {
            request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                    Map.of("deptId", "from-path"));
            request.addHeader("deptId", "from-header");
            assertThat(resolver.resolve(request, "deptId", DeptIdSource.AUTO)).contains("from-path");

            MockHttpServletRequest headerOnly = new MockHttpServletRequest();
            headerOnly.addHeader("deptId", "from-header");
            assertThat(resolver.resolve(headerOnly, "deptId", DeptIdSource.AUTO)).contains("from-header");
        }

        @Test
        @DisplayName("returns empty when the request carries no department id at all")
        void nothingAnywhere() {
            assertThat(resolver.resolve(request, "deptId", DeptIdSource.AUTO))
                    .isEqualTo(Optional.empty());
        }

        @Test
        @DisplayName("ignores a URI-template attribute that is not a map")
        void malformedTemplateAttribute() {
            request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, "not-a-map");
            assertThat(resolver.resolve(request, "deptId", DeptIdSource.PATH)).isEmpty();
        }
    }

    @Nested
    @DisplayName("argument validation")
    class Validation {

        @Test
        @DisplayName("rejects a blank parameter name")
        void blankParam() {
            assertThatThrownBy(() -> resolver.resolve(request, " ", DeptIdSource.AUTO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("param");
        }

        @Test
        @DisplayName("rejects a null request or source")
        void nullArguments() {
            assertThatThrownBy(() -> resolver.resolve(null, "deptId", DeptIdSource.AUTO))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> resolver.resolve(request, "deptId", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
