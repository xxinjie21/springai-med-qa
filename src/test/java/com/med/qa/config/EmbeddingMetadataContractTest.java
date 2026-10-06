package com.med.qa.config;

import com.med.qa.rag.MedRagIndexProperties;
import com.med.qa.rag.MedVectorStoreProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cross-component contract test for the retrieval-metadata isolation rule (D45).
 *
 * <h2>Why this class exists</h2>
 * <p>The 2026-09-25 review found that {@code application.yml} declared
 * {@code spring.ai.openai.embedding.metadata-mode: EMBED} under a comment claiming the exact
 * opposite ("metadata tags stay out of the vector"). Verified at bytecode level against Spring AI
 * 1.0.0: {@code OpenAiEmbeddingModel.embed(Document)} formats the document through that mode, and
 * {@code DefaultContentFormatter} reads {@link MetadataMode#EMBED} as "every metadata key minus
 * {@code excludedEmbedMetadataKeys}"). So the isolation tags — {@code tenant_id}, {@code dept_id},
 * {@code patient_id} — were being spliced into the text that gets vectorized: similarity was
 * polluted by the tags and the isolation labels were written into the vector itself.</p>
 *
 * <p>Nothing caught it because every existing test looked at one component in isolation: the model
 * test asserted "the official properties are handed through" (true, and irrelevant), and the
 * vector-store tests asserted the TAG fields are declared (also true — filtering was fine, only the
 * embedded text was wrong). No test crossed the configuration file, the framework's formatter and
 * the index schema.</p>
 *
 * <h2>What is asserted here</h2>
 * <ol>
 *   <li>the <em>effective</em> configuration value is {@link MetadataMode#NONE} — bound through
 *       Spring Boot's own {@link YamlPropertySourceLoader} plus {@link Binder}, with every profile
 *       file overlaid in the framework's precedence order, so a profile can neither re-introduce
 *       {@code EMBED} nor can a stray {@code -D} decide the contract;</li>
 *   <li>the configured value equals {@link EmbeddingModelConfig#SAFE_METADATA_MODE}, the fallback
 *       used when the property is absent (Spring AI's own default is {@code EMBED}, so a deployment
 *       that clears the property must not silently inherit it);</li>
 *   <li>the tags are still indexed as RediSearch {@code TAG} fields and the D37 probe still looks
 *       for exactly those fields, so turning {@code EMBED} off does not cost scoped retrieval;</li>
 *   <li>the difference the setting makes is <em>demonstrated</em> against the real Spring AI
 *       formatter rather than described in a comment, and the guard is shown to reject {@code EMBED}
 *       and a missing value.</li>
 * </ol>
 */
class EmbeddingMetadataContractTest {

    /** The official property whose value caused P1-1. */
    private static final String METADATA_MODE_PROPERTY = "spring.ai.openai.embedding.metadata-mode";

    /** The isolation tags the unified storage specification is built on. */
    private static final List<String> ISOLATION_TAGS = List.of("tenant_id", "dept_id", "patient_id");

    /** {@code src/main/resources}, home of the configuration files under test. */
    private static Path resources;

    /** Profile files discovered on disk, as {@code application-<profile>.yml}. */
    private static List<Path> profileFiles;

    @BeforeAll
    static void locateConfigurationFiles() throws IOException {
        resources = Path.of(System.getProperty("user.dir"), "src/main/resources");
        try (var stream = Files.list(resources)) {
            profileFiles = stream
                    .filter(path -> path.getFileName().toString().startsWith("application-"))
                    .filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .toList();
        }
    }

    // ------------------------------------------------------------------ the contract

    @Test
    @DisplayName("the effective metadata mode embeds the document text alone")
    void effectiveMetadataModeEmbedsTextOnly() {
        assertMetadataModeIsMetadataFree(effectiveMetadataMode(baseFile(), List.of()),
                METADATA_MODE_PROPERTY);
    }

    @Test
    @DisplayName("no profile re-introduces metadata into the embedded text")
    void noProfileReintroducesEmbeddedMetadata() {
        // Same failure shape as P0-1: a profile file may override this property, and the profile the
        // image and compose really activate is prod. Overlaying each profile is the only way to see
        // what that deployment would embed.
        assertThat(profileFiles).as("at least one profile file must exist").isNotEmpty();
        for (Path profileFile : profileFiles) {
            assertMetadataModeIsMetadataFree(effectiveMetadataMode(baseFile(), List.of(profileFile)),
                    METADATA_MODE_PROPERTY + " with profile '" + profileName(profileFile) + "'");
        }
    }

    @Test
    @DisplayName("the configured value is the same mode the code falls back to")
    void configuredModeMatchesTheCodeFallback() {
        // Ties the two halves together: the YAML value and the fallback used when the property is
        // absent. Spring AI's own default for this field is EMBED, so an absent property is a
        // regression, not a neutral omission.
        assertThat(effectiveMetadataMode(baseFile(), List.of()))
                .isEqualTo(EmbeddingModelConfig.SAFE_METADATA_MODE)
                .isEqualTo(MetadataMode.NONE);
    }

    @Test
    @DisplayName("the isolation tags stay indexed as TAG fields, so NONE costs no filtering")
    void isolationTagsRemainIndexedAsTagFields() {
        StandardEnvironment environment = environment(baseFile(), profileFiles);

        MedVectorStoreProperties vectorStore =
                bind(environment, MedVectorStoreProperties.PREFIX, MedVectorStoreProperties.class);
        List<String> tagFields = vectorStore.getMetadataFields().stream()
                .filter(spec -> spec.getType() == MedVectorStoreProperties.MetadataFieldType.TAG)
                .map(MedVectorStoreProperties.MetadataFieldSpec::getName)
                .toList();
        assertThat(tagFields).containsExactlyElementsOf(ISOLATION_TAGS);

        // A bound value could come from the Java defaults, which would make the assertion above pass
        // vacuously if the YAML block were deleted; the raw sources prove the file declares it.
        for (int i = 0; i < ISOLATION_TAGS.size(); i++) {
            assertThat(environment.getProperty(
                    MedVectorStoreProperties.PREFIX + ".metadata-fields[" + i + "].name"))
                    .as("metadata-fields[%d].name must be declared in the YAML, not inherited "
                            + "from the Java default", i)
                    .isEqualTo(ISOLATION_TAGS.get(i));
            assertThat(environment.getProperty(
                    MedVectorStoreProperties.PREFIX + ".metadata-fields[" + i + "].type"))
                    .as("metadata-fields[%d].type", i)
                    .isEqualTo("TAG");
        }

        // The D37 probe compares the live index against this list, so it has to name the same fields
        // the index is built with - otherwise a rebuilt index is reported as drifted.
        MedRagIndexProperties index =
                bind(environment, MedRagIndexProperties.PREFIX, MedRagIndexProperties.class);
        assertThat(index.getExpectedTagFields()).containsExactlyElementsOf(ISOLATION_TAGS);
    }

    // ------------------------------------------------------------------ the demonstration

    @Test
    @DisplayName("NONE embeds the text alone while EMBED splices the tags into it")
    void metadataModeDecidesWhatEntersTheVector() {
        // The reason the value matters, demonstrated against the real Spring AI formatter instead of
        // asserted from documentation: this is what OpenAiEmbeddingModel.embed(Document) feeds to the
        // embedding endpoint. If a future Spring AI release changed what NONE means, the contract
        // would silently rot - this test fails instead.
        Document document = Document.builder()
                .text("stable angina pectoris: first-line management")
                .metadata(isolationMetadata())
                .build();

        String textOnly = document.getFormattedContent(MetadataMode.NONE);
        String withMetadata = document.getFormattedContent(MetadataMode.EMBED);

        // The official formatter separates sections with a newline, so NONE yields the document text
        // with no metadata section at all - not even the tag names.
        assertThat(textOnly.trim()).isEqualTo("stable angina pectoris: first-line management");
        assertThat(textOnly).doesNotContain("tenant_id", "dept_id", "patient_id");
        assertThat(textOnly).doesNotContain("hosp-a", "dept-cardio", "patient-1");
        // EMBED, by contrast, appends every metadata key: this is what the project used to send.
        assertThat(withMetadata).contains("tenant_id", "dept_id", "patient_id");
        assertThat(withMetadata).contains("hosp-a", "dept-cardio", "patient-1");
    }

    // ------------------------------------------------------------------ the guard itself

    @Test
    @DisplayName("boundary: the guard rejects EMBED, so it can never pass vacuously")
    void guardRejectsEmbeddedMetadata() {
        assertThatThrownBy(() -> assertMetadataModeIsMetadataFree(MetadataMode.EMBED, "synthetic"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("EMBED")
                .hasMessageContaining("synthetic");
    }

    @Test
    @DisplayName("boundary: a missing property is a failure, not a pass")
    void missingPropertyIsRejected() {
        // Fail closed: Spring AI's default is EMBED, so "not configured" must never read as "fine".
        assertThatThrownBy(() -> assertMetadataModeIsMetadataFree(null, "unconfigured"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("unconfigured");
    }

    @Test
    @DisplayName("the resolver really lets a profile override the base value (precedence, not concatenation)")
    void resolverHonoursProfilePrecedence(@TempDir Path tempDir) throws IOException {
        // Without this, a broken harness would make every contract assertion above vacuous: if the
        // base value always won, a profile that re-introduced EMBED would look harmless.
        Path base = Files.writeString(tempDir.resolve("application.yml"),
                "spring:\n  ai:\n    openai:\n      embedding:\n        metadata-mode: NONE\n");
        Path overlay = Files.writeString(tempDir.resolve("application-prod.yml"),
                "spring:\n  ai:\n    openai:\n      embedding:\n        metadata-mode: EMBED\n");

        assertThat(effectiveMetadataMode(base, List.of(overlay))).isEqualTo(MetadataMode.EMBED);
        assertThat(effectiveMetadataMode(base, List.of())).isEqualTo(MetadataMode.NONE);
    }

    @Test
    @DisplayName("boundary: a configuration without the property resolves to null instead of a default")
    void missingPropertyResolvesToNull(@TempDir Path tempDir) throws IOException {
        Path base = Files.writeString(tempDir.resolve("application.yml"), "server:\n  port: 8080\n");
        assertThat(effectiveMetadataMode(base, List.of())).isNull();
    }

    // ------------------------------------------------------------------ helpers

    /** The real base configuration file. */
    private static Path baseFile() {
        return resources.resolve("application.yml");
    }

    /**
     * Resolves the effective metadata mode for the real base file with the given profile files
     * overlaid.
     *
     * @param overlays profile files, highest precedence last
     * @return the mode the embedding model would actually be built with, {@code null} when unset
     */
    private static MetadataMode effectiveMetadataMode(Path baseFile, List<Path> overlays) {
        StandardEnvironment environment = environment(baseFile, overlays);
        return Binder.get(environment)
                .bind(METADATA_MODE_PROPERTY, MetadataMode.class)
                .orElse(null);
    }

    /**
     * Builds the property environment the way Spring Boot does at startup: load the base file first,
     * then overlay the profile files so a profile value wins.
     *
     * @param baseFile the base configuration file
     * @param overlays profile files, in the order they should take precedence
     * @return the environment holding the effective configuration
     */
    private static StandardEnvironment environment(Path baseFile, List<Path> overlays) {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        // Drop the ambient JVM sources: a stray -D on the build machine must not decide this contract.
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);

        for (PropertySource<?> source : load(baseFile)) {
            sources.addLast(source);
        }
        for (Path overlay : overlays) {
            List<PropertySource<?>> loaded = load(overlay);
            for (int i = loaded.size() - 1; i >= 0; i--) {
                sources.addFirst(loaded.get(i));
            }
        }
        return environment;
    }

    /** Loads one YAML file into property sources using Spring Boot's own loader. */
    private static List<PropertySource<?>> load(Path file) {
        try {
            return new YamlPropertySourceLoader()
                    .load(file.getFileName().toString(), new FileSystemResource(file));
        } catch (IOException ex) {
            throw new IllegalStateException("unable to load " + file, ex);
        }
    }

    /** Binds a configuration prefix, failing loudly instead of silently skipping the assertion. */
    private static <T> T bind(StandardEnvironment environment, String prefix, Class<T> type) {
        return Binder.get(environment).bind(prefix, type)
                .orElseThrow(() -> new IllegalStateException(
                        "no configuration bound from " + prefix + " - the guard would be vacuous"));
    }

    /** The isolation metadata a medical document carries into the index. */
    private static Map<String, Object> isolationMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tenant_id", "hosp-a");
        metadata.put("dept_id", "dept-cardio");
        metadata.put("patient_id", "patient-1");
        return metadata;
    }

    /**
     * Asserts that the effective metadata mode keeps every isolation tag out of the embedded text.
     *
     * @param actual  the mode the embedding model would be built with, {@code null} when unset
     * @param context what was resolved, used in the failure message
     * @throws AssertionError when the mode is {@code EMBED} or absent
     */
    static void assertMetadataModeIsMetadataFree(MetadataMode actual, String context) {
        assertThat(actual)
                .as("%s must embed the document text alone: EMBED splices every metadata key into "
                        + "the embedded text, which pollutes similarity and writes the "
                        + "tenant/dept/patient isolation tags into the vector. A missing value is "
                        + "worse still - Spring AI's own default is EMBED (D45).", context)
                .isNotNull()
                .isEqualTo(MetadataMode.NONE);
    }

    /** {@code application-prod.yml} to {@code prod}. */
    private static String profileName(Path profileFile) {
        String name = profileFile.getFileName().toString();
        return name.substring("application-".length(), name.length() - ".yml".length());
    }
}
