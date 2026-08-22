package com.med.qa.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;
import org.springdoc.core.models.GroupedOpenApi;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link OpenApiConfig}: the document metadata, the API-key security scheme, the global
 * security requirement, and the three controller groups. Exercises the public factory methods directly
 * so no Spring context (or middleware) is required.
 */
class OpenApiConfigTest {

    private static MedOpenApiProperties sampleProps() {
        MedOpenApiProperties props = new MedOpenApiProperties();
        props.setTitle("Test API");
        props.setVersion("9.9.9");
        props.setDescription("Test description");
        props.setApiKeyHeader("X-TEST-KEY");
        props.setContactName("Docs");
        props.setContactEmail("docs@example.com");
        props.setLicenseName("Apache 2.0");
        props.setLicenseUrl("https://www.apache.org/licenses/LICENSE-2.0");
        return props;
    }

    @Test
    void openApiCarriesMetadata() {
        OpenApiConfig config = new OpenApiConfig();
        OpenAPI api = config.medOpenApi(sampleProps());

        Info info = api.getInfo();
        assertEquals("Test API", info.getTitle());
        assertEquals("9.9.9", info.getVersion());
        assertEquals("Test description", info.getDescription());
        assertNotNull(info.getContact());
        assertEquals("Docs", info.getContact().getName());
        assertEquals("docs@example.com", info.getContact().getEmail());
        assertNotNull(info.getLicense());
        assertEquals("Apache 2.0", info.getLicense().getName());
    }

    @Test
    void openApiDeclaresApiKeySecurityScheme() {
        OpenApiConfig config = new OpenApiConfig();
        OpenAPI api = config.medOpenApi(sampleProps());

        Components components = api.getComponents();
        assertNotNull(components);
        SecurityScheme scheme = components.getSecuritySchemes().get(OpenApiConfig.API_KEY_SCHEME);
        assertNotNull(scheme, "expected the apiKeyAuth security scheme");
        assertEquals(SecurityScheme.Type.APIKEY, scheme.getType());
        assertEquals(SecurityScheme.In.HEADER, scheme.getIn());
        assertEquals("X-TEST-KEY", scheme.getName());
    }

    @Test
    void openApiAppliesGlobalSecurityRequirement() {
        OpenApiConfig config = new OpenApiConfig();
        OpenAPI api = config.medOpenApi(sampleProps());

        assertNotNull(api.getSecurity());
        assertEquals(1, api.getSecurity().size());
        SecurityRequirement requirement = api.getSecurity().get(0);
        assertTrue(requirement.containsKey(OpenApiConfig.API_KEY_SCHEME));
    }

    @Test
    void chatGroupMatchesConfiguredPaths() {
        OpenApiConfig config = new OpenApiConfig();
        MedOpenApiProperties props = sampleProps();
        props.setChatGroupPaths(List.of("/api/chat/**", "/api/stream/**"));

        GroupedOpenApi group = config.chatApiGroup(props);
        assertEquals("chat", group.getGroup());
        assertEquals(props.getChatGroupPaths(), group.getPathsToMatch());
    }

    @Test
    void sessionGroupMatchesConfiguredPaths() {
        OpenApiConfig config = new OpenApiConfig();
        GroupedOpenApi group = config.sessionApiGroup(sampleProps());
        assertEquals("session", group.getGroup());
        assertEquals(sampleProps().getSessionGroupPaths(), group.getPathsToMatch());
    }

    @Test
    void ragGroupMatchesConfiguredPaths() {
        OpenApiConfig config = new OpenApiConfig();
        GroupedOpenApi group = config.ragApiGroup(sampleProps());
        assertEquals("rag", group.getGroup());
        assertEquals(sampleProps().getRagGroupPaths(), group.getPathsToMatch());
    }

    @Test
    void emptyGroupPathsFallBackToDefault() {
        OpenApiConfig config = new OpenApiConfig();
        MedOpenApiProperties props = sampleProps();
        props.setChatGroupPaths(List.of()); // setter restores the default

        GroupedOpenApi group = config.chatApiGroup(props);
        assertEquals(List.of("/api/chat/**"), group.getPathsToMatch());
    }
}
