package com.med.qa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

import java.util.List;

/**
 * Configuration of the generated OpenAPI document (D26).
 *
 * <p>These values drive {@link OpenApiConfig}: the document metadata (title, version, description,
 * optional contact and license), the API-key security scheme name (mirrored from
 * {@code med.security.header-name} so the "Authorize" button in Swagger UI matches the header the
 * {@code ApiKeyAuthFilter} actually reads), and the three controller groups exposed as separate
 * Swagger UI dropdowns. Every setter validates its input so a malformed configuration fails fast at
 * context startup rather than producing a silent, empty document.</p>
 */
@ConfigurationProperties(prefix = "med.openapi")
public class MedOpenApiProperties {

    /** Whether the hand-written metadata below is applied (springdoc itself is toggled separately). */
    private boolean enabled = true;

    /** Human-readable document title shown in Swagger UI. */
    private String title = "Med-QA Consultation API";

    /** Free-form document description. */
    private String description =
            "Hospital-grade AI consultation backend: streaming chat, session lifecycle, and a "
                    + "tag-scoped medical RAG corpus. Every endpoint requires an API key in the "
                    + "configured header.";

    /** Document / API version shown in Swagger UI. */
    private String version = "1.0.0";

    private String contactName;
    private String contactEmail;
    private String contactUrl;

    private String licenseName = "Apache 2.0";
    private String licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0";

    /** Header the {@code ApiKeyAuthFilter} reads; the OpenAPI security scheme uses the same name. */
    private String apiKeyHeader = "X-API-Key";

    private List<String> chatGroupPaths = defaultChatPaths();
    private List<String> sessionGroupPaths = defaultSessionPaths();
    private List<String> ragGroupPaths = defaultRagPaths();

    private static List<String> defaultChatPaths() {
        return List.of("/api/chat/**");
    }

    private static List<String> defaultSessionPaths() {
        return List.of("/api/sessions/**");
    }

    private static List<String> defaultRagPaths() {
        return List.of("/api/rag/**");
    }

    /**
     * Creates the properties with their safe defaults.
     */
    public MedOpenApiProperties() {
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        Assert.hasText(title, "med.openapi.title must not be blank");
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        // A null description is tolerated (treated as empty) so a missing value does not crash boot.
        this.description = (description == null) ? "" : description;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        Assert.hasText(version, "med.openapi.version must not be blank");
        this.version = version;
    }

    public String getContactName() {
        return contactName;
    }

    public void setContactName(String contactName) {
        this.contactName = contactName;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public void setContactEmail(String contactEmail) {
        this.contactEmail = contactEmail;
    }

    public String getContactUrl() {
        return contactUrl;
    }

    public void setContactUrl(String contactUrl) {
        this.contactUrl = contactUrl;
    }

    public String getLicenseName() {
        return licenseName;
    }

    public void setLicenseName(String licenseName) {
        this.licenseName = licenseName;
    }

    public String getLicenseUrl() {
        return licenseUrl;
    }

    public void setLicenseUrl(String licenseUrl) {
        this.licenseUrl = licenseUrl;
    }

    public String getApiKeyHeader() {
        return apiKeyHeader;
    }

    public void setApiKeyHeader(String apiKeyHeader) {
        Assert.hasText(apiKeyHeader, "med.openapi.api-key-header must not be blank");
        this.apiKeyHeader = apiKeyHeader;
    }

    public List<String> getChatGroupPaths() {
        return chatGroupPaths;
    }

    public void setChatGroupPaths(List<String> paths) {
        this.chatGroupPaths = (paths == null || paths.isEmpty()) ? defaultChatPaths() : paths;
    }

    public List<String> getSessionGroupPaths() {
        return sessionGroupPaths;
    }

    public void setSessionGroupPaths(List<String> paths) {
        this.sessionGroupPaths = (paths == null || paths.isEmpty()) ? defaultSessionPaths() : paths;
    }

    public List<String> getRagGroupPaths() {
        return ragGroupPaths;
    }

    public void setRagGroupPaths(List<String> paths) {
        this.ragGroupPaths = (paths == null || paths.isEmpty()) ? defaultRagPaths() : paths;
    }
}
