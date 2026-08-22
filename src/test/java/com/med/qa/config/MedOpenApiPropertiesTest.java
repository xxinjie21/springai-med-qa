package com.med.qa.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MedOpenApiProperties}: defaults, happy-path setters and fail-fast validation.
 */
class MedOpenApiPropertiesTest {

    @Test
    void defaultsAreSane() {
        MedOpenApiProperties props = new MedOpenApiProperties();
        assertTrue(props.isEnabled());
        assertEquals("Med-QA Consultation API", props.getTitle());
        assertEquals("1.0.0", props.getVersion());
        assertEquals("X-API-Key", props.getApiKeyHeader());
        assertEquals(List.of("/api/chat/**"), props.getChatGroupPaths());
        assertEquals(List.of("/api/sessions/**"), props.getSessionGroupPaths());
        assertEquals(List.of("/api/rag/**"), props.getRagGroupPaths());
    }

    @Test
    void settersStoreValues() {
        MedOpenApiProperties props = new MedOpenApiProperties();
        props.setTitle("My API");
        props.setVersion("2.3.4");
        props.setDescription("desc");
        props.setApiKeyHeader("X-TOKEN");
        props.setContactName("Team");
        props.setContactEmail("team@example.com");
        props.setContactUrl("https://example.com");
        props.setLicenseName("MIT");
        props.setLicenseUrl("https://opensource.org/licenses/MIT");

        assertEquals("My API", props.getTitle());
        assertEquals("2.3.4", props.getVersion());
        assertEquals("desc", props.getDescription());
        assertEquals("X-TOKEN", props.getApiKeyHeader());
        assertEquals("Team", props.getContactName());
        assertEquals("team@example.com", props.getContactEmail());
        assertEquals("https://example.com", props.getContactUrl());
        assertEquals("MIT", props.getLicenseName());
        assertEquals("https://opensource.org/licenses/MIT", props.getLicenseUrl());
    }

    @Test
    void blankTitleIsRejected() {
        MedOpenApiProperties props = new MedOpenApiProperties();
        assertThrows(IllegalArgumentException.class, () -> props.setTitle(" "));
    }

    @Test
    void blankVersionIsRejected() {
        MedOpenApiProperties props = new MedOpenApiProperties();
        assertThrows(IllegalArgumentException.class, () -> props.setVersion(""));
    }

    @Test
    void blankApiKeyHeaderIsRejected() {
        MedOpenApiProperties props = new MedOpenApiProperties();
        assertThrows(IllegalArgumentException.class, () -> props.setApiKeyHeader(null));
        assertThrows(IllegalArgumentException.class, () -> props.setApiKeyHeader(""));
    }

    @Test
    void nullDescriptionFallsBackToEmpty() {
        MedOpenApiProperties props = new MedOpenApiProperties();
        props.setDescription(null);
        assertEquals("", props.getDescription());
    }

    @Test
    void emptyGroupPathsFallsBackToDefault() {
        MedOpenApiProperties props = new MedOpenApiProperties();
        props.setChatGroupPaths(List.of());
        assertEquals(List.of("/api/chat/**"), props.getChatGroupPaths());

        props.setSessionGroupPaths(null);
        assertEquals(List.of("/api/sessions/**"), props.getSessionGroupPaths());

        props.setRagGroupPaths(List.of());
        assertEquals(List.of("/api/rag/**"), props.getRagGroupPaths());
    }

    @Test
    void customGroupPathsAreKept() {
        MedOpenApiProperties props = new MedOpenApiProperties();
        props.setChatGroupPaths(List.of("/api/chat/**", "/api/stream/**"));
        assertEquals(List.of("/api/chat/**", "/api/stream/**"), props.getChatGroupPaths());
        assertFalse(props.getChatGroupPaths().isEmpty());
    }
}
