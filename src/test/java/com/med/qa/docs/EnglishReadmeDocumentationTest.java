package com.med.qa.docs;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard tests for the English {@code README.en.md} (D33).
 *
 * <p>A translated document rots faster than an original one: nobody notices when a section is
 * dropped, when a new endpoint never makes it across, or when a translator leaves a paragraph in the
 * source language. These tests pin the structure, cross-check the shared facts against the Chinese
 * README and the build files, and prove the document is genuinely English.</p>
 */
class EnglishReadmeDocumentationTest {

    /** Any CJK ideograph, CJK punctuation or fullwidth form. */
    private static final Pattern CJK = Pattern.compile("[\\u3000-\\u303f\\u3400-\\u4dbf\\u4e00-\\u9fff\\uff01-\\uff60]");

    private static String english;
    private static String chinese;
    private static String pom;

    @BeforeAll
    static void readDocuments() throws IOException {
        Path root = Path.of(System.getProperty("user.dir"));
        english = Files.readString(root.resolve("README.en.md"));
        chinese = Files.readString(root.resolve("README.md"));
        pom = Files.readString(root.resolve("pom.xml"));
    }

    @Test
    @DisplayName("the English README exists, has a title and is a real document")
    void existsAndHasBody() {
        assertThat(english).isNotBlank();
        assertThat(english).startsWith("# springai-med-qa");
        assertThat(english.lines().count()).isGreaterThan(200L);
    }

    @Test
    @DisplayName("the document is genuinely English: no CJK ideographs or fullwidth punctuation")
    void containsNoChineseCharacters() {
        // The language switcher is the one legitimate exception: a bilingual README labels the link
        // to the other language in that language, which is the whole point of a switcher. Every other
        // line has to be English.
        String body = english.lines()
                .filter(line -> !line.contains("./README.md"))
                .reduce("", (left, right) -> left + '\n' + right);

        var matcher = CJK.matcher(body);
        StringBuilder offenders = new StringBuilder();
        while (matcher.find()) {
            offenders.append(matcher.group());
        }

        assertThat(offenders.toString())
                .as("untranslated fragments leaked into README.en.md")
                .isEmpty();
    }

    @Test
    @DisplayName("the two READMEs link to each other")
    void languageSwitcherIsBidirectional() {
        assertThat(english).contains("./README.md");
        assertThat(chinese).contains("./README.en.md");
    }

    @Test
    @DisplayName("the English README covers the same sections as the Chinese one")
    void sectionParity() {
        List<String> sections = List.of(
                "## What this service is",
                "## Architecture",
                "## Tech stack and component choices",
                "## Module layout",
                "## Unified storage contract",
                "## API surface",
                "## Error codes",
                "## Environment variables",
                "## Quick start",
                "## Testing and coverage",
                "## Alerting",
                "## CI/CD",
                "## Deployment",
                "## Roadmap progress",
                "## License");

        sections.forEach(section -> assertThat(english)
                .as("README.en.md must contain %s", section)
                .contains(section));
        // Every English heading has a Chinese counterpart, so neither side can drift alone.
        assertThat(chinese).contains("## 核心定位").contains("## 架构图").contains("## 迭代进度");
    }

    @Test
    @DisplayName("the storage contract is restated verbatim, character for character")
    void storageContractIsIdentical() {
        List<String> contract = List.of(
                "med:chat:{tenant}:{dept}:{session}",
                "med_message_{crc32(session_id) % 16}",
                "med_session.proto",
                "PATIENT=0",
                "DOCTOR=1",
                "ASSISTANT=2",
                "SYSTEM=3");

        contract.forEach(fragment -> {
            assertThat(english).as("English README must restate %s", fragment).contains(fragment);
            assertThat(chinese).as("Chinese README must restate %s", fragment).contains(fragment);
        });
    }

    @Test
    @DisplayName("the architecture diagram is a real Mermaid graph, not a description of one")
    void architectureDiagramIsRendered() {
        assertThat(english).contains("```mermaid");
        assertThat(english).contains("RedisVectorStore");
        assertThat(english).contains("ShardingSphere");
        assertThat(english).contains("QuestionAnswerAdvisor");
        assertThat(english).contains("MessageWindowChatMemory");
    }

    @Test
    @DisplayName("every controller endpoint is documented in the English API table")
    void apiTableIsComplete() throws IOException {
        Path controllerDir = Path.of(System.getProperty("user.dir"), "src/main/java/com/med/qa/controller");
        try (var stream = Files.list(controllerDir)) {
            List<Path> controllers = stream
                    .filter(path -> path.getFileName().toString().endsWith("Controller.java"))
                    .toList();
            assertThat(controllers).isNotEmpty();
            controllers.forEach(controller -> {
                try {
                    String source = Files.readString(controller);
                    var matcher = Pattern
                            .compile("@(Get|Post|Put|Delete|Patch)Mapping\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"")
                            .matcher(source);
                    while (matcher.find()) {
                        String value = matcher.group(2);
                        if (!value.isEmpty()) {
                            assertThat(english)
                                    .as("README.en.md must document endpoint %s", value)
                                    .contains(value);
                        }
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("unable to read " + controller, e);
                }
            });
        }
        assertThat(english).contains("/actuator/health");
        assertThat(english).contains("/actuator/prometheus");
    }

    @Test
    @DisplayName("the advertised versions are the ones the build actually pins")
    void advertisedVersionsMatchTheBuild() {
        List<String> versions = List.of("3.4.5", "1.0.0", "5.5.2", "3.45.1", "4.29.3", "0.8.13", "2.8.5");
        versions.forEach(version -> {
            assertThat(pom).as("pom must pin %s", version).contains(version);
            assertThat(english).as("README.en.md must advertise %s", version).contains(version);
        });
    }

    @Test
    @DisplayName("the alerting chapter names the artefacts an operator has to find")
    void alertingChapterIsActionable() {
        assertThat(english).contains("med.alert.");
        assertThat(english).contains("med_qa_alert_total");
        assertThat(english).contains("deploy/prometheus/prometheus.yml");
        assertThat(english).contains("deploy/prometheus/med-qa-alerts.yml");
        assertThat(english).contains("deploy/alertmanager/alertmanager.yml");
        assertThat(english).contains("observability");
        // The placeholder caveat must survive translation: an operator must not assume it pages out of
        // the box.
        assertThat(english).contains("placeholder");
    }

    @Test
    @DisplayName("the documented alert variables exist as real placeholders in application.yml")
    void documentedAlertVariablesExist() throws IOException {
        String applicationYml = Files.readString(
                Path.of(System.getProperty("user.dir"), "src/main/resources/application.yml"));

        List<String> variables = List.of(
                "MED_ALERT_ENABLED",
                "MED_ALERT_CHECK_INTERVAL",
                "MED_ALERT_INITIAL_DELAY",
                "MED_ALERT_COOLDOWN",
                "MED_ALERT_MINIMUM_SEVERITY",
                "MED_ALERT_KEY_PREFIX");

        variables.forEach(variable -> {
            assertThat(english).contains(variable);
            assertThat(applicationYml).as("%s must be a real placeholder", variable).contains(variable);
        });
    }

    @Test
    @DisplayName("the CI chapter documents the integration stage and the mandatory-Docker switch")
    void ciChapterDocumentsTheIntegrationStage() {
        assertThat(english).contains("MED_TEST_INTEGRATION_REQUIRED");
        assertThat(english).contains("med.test.integration.required");
        assertThat(english).contains("com.med.qa.integration.*IntegrationTest");
        assertThat(english).contains("CiIntegrationStageConfigTest");
        assertThat(english).contains("docker save");
        assertThat(english).contains("continue-on-error");
    }

    @Test
    @DisplayName("the RAG chapter documents the RediSearch tag escaping and the defect it prevents")
    void ragChapterDocumentsTagEscaping() {
        assertThat(english).contains("escapeTagValue");
        assertThat(english).contains("MedRetrievalFilters");
        assertThat(english).contains("RedisFilterExpressionConverter");
        assertThat(english).contains("Syntax error");
        assertThat(english).contains("MedRagRetrievalIntegrationTest");
        assertThat(english).contains("DeterministicEmbeddingModel");
    }

    @Test
    @DisplayName("the linked handbook and roadmap exist and are linked from both READMEs")
    void linkedDocumentsExist() {
        Path root = Path.of(System.getProperty("user.dir"));

        assertThat(english).contains("./docs/DEPLOYMENT.md");
        assertThat(english).contains("ROADMAP.md");
        assertThat(root.resolve("docs/DEPLOYMENT.md")).exists();
        assertThat(root.resolve("ROADMAP.md")).exists();
        assertThat(root.resolve("scripts/verify-docker-build.sh")).exists();
    }

    @Test
    @DisplayName("boundary: the CJK detector really matches Chinese text")
    void cjkDetectorIsNotVacuous() {
        assertThat(CJK.matcher("部署手册").find()).isTrue();
        assertThat(CJK.matcher("plain english text").find()).isFalse();
    }
}
