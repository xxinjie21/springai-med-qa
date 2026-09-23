package com.med.qa.docs;

import com.med.qa.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard tests for the root {@code README.md}.
 *
 * <p>The README is the entry point of the repository: it advertises the public endpoints, the
 * business error codes, the environment variables and the component versions. Those facts live in
 * code ({@code ErrorCode}, the controllers, {@code application.yml}, {@code pom.xml}), so the
 * document can silently drift -- an endpoint renamed without the table following, an error code
 * dropped, a version bumped only in the POM. These tests parse both sides and pin the contract, so
 * the drift fails {@code mvn test} rather than misleading a reader or an operator.</p>
 */
class ReadmeDocumentationTest {

    /** Full README text, used for every containment assertion. */
    private static String readme;

    /** Full {@code pom.xml} text, used to cross-check the advertised component versions. */
    private static String pom;

    /** Full {@code application.yml} text, used to prove documented variables really exist. */
    private static String applicationYml;

    /** Full {@code docker-compose.yml} text, the second home of environment variables. */
    private static String compose;

    /** Full {@code sharding/med-sharding.yaml} text, home of the MySQL placeholders. */
    private static String sharding;

    @BeforeAll
    static void readDocuments() throws IOException {
        Path root = Path.of(System.getProperty("user.dir"));
        readme = Files.readString(root.resolve("README.md"));
        pom = Files.readString(root.resolve("pom.xml"));
        applicationYml = Files.readString(root.resolve("src/main/resources/application.yml"));
        compose = Files.readString(root.resolve("docker-compose.yml"));
        sharding = Files.readString(root.resolve("src/main/resources/sharding/med-sharding.yaml"));
    }

    @Test
    @DisplayName("the README exists and is a real document, not a stub")
    void readmeExistsAndHasBody() {
        assertThat(readme).isNotBlank();
        assertThat(readme.lines().count()).isGreaterThan(80L);
        assertThat(readme).startsWith("# springai-med-qa");
    }

    @Test
    @DisplayName("badges point at the workflows and registry coordinates that actually exist")
    void badgesReferenceRealWorkflowsAndRegistry() {
        assertThat(readme).contains("actions/workflows/ci.yml/badge.svg");
        assertThat(readme).contains("actions/workflows/docker-publish.yml/badge.svg");
        assertThat(readme).contains("ghcr.io");
        assertThat(readme).contains("xxinjie21/springai-med-qa");

        Path root = Path.of(System.getProperty("user.dir"));
        assertThat(root.resolve(".github/workflows/ci.yml")).exists();
        assertThat(root.resolve(".github/workflows/docker-publish.yml")).exists();
    }

    @Test
    @DisplayName("every documented section is present so the table of contents resolves")
    void requiredSectionsArePresent() {
        List<String> sections = List.of(
                "## 核心定位",
                "## 架构图",
                "## 技术栈与组件选型",
                "## 模块结构",
                "## 统一存储对接规范",
                "## API 一览",
                "## 错误码",
                "## 环境变量",
                "## 快速开始",
                "## 测试与覆盖率",
                "## CI/CD",
                "## 部署",
                "## 迭代进度",
                "## License");
        sections.forEach(section -> assertThat(readme).contains(section));
    }

    @Test
    @DisplayName("the architecture diagram shows the real runtime chain, not a generic boxes-and-arrows sketch")
    void architectureDiagramDescribesRuntimeChain() {
        assertThat(readme).contains("```mermaid");
        assertThat(readme).contains("RedisVectorStore");
        assertThat(readme).contains("ShardingSphere");
        assertThat(readme).contains("med:chat:{tenant}:{dept}:{session}");
        assertThat(readme).contains("med_message_{0..15}");
        assertThat(readme).contains("QuestionAnswerAdvisor");
        assertThat(readme).contains("MessageWindowChatMemory");
    }

    @Test
    @DisplayName("the API table documents every endpoint declared by the controllers")
    void apiTableCoversEveryControllerEndpoint() {
        Set<String> implemented = controllerEndpoints();
        assertThat(implemented).isNotEmpty();
        implemented.forEach(path -> assertThat(readme)
                .as("README must document endpoint %s", path)
                .contains(path));
        assertThat(readme).contains("/actuator/health");
    }

    @Test
    @DisplayName("every ErrorCode enum value appears in the documented error-code table")
    void errorCodeTableCoversEveryErrorCode() {
        assertThat(ErrorCode.values()).hasSizeGreaterThan(5);
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(readme)
                    .as("README must document error code %s (%s)", code.name(), code.getCode())
                    .contains("`" + code.getCode() + "`");
        }
    }

    @Test
    @DisplayName("the storage section restates the unified cross-language contract verbatim")
    void storageSpecRestatesUnifiedContract() {
        assertThat(readme).contains("med:chat:{tenant}:{dept}:{session}");
        assertThat(readme).contains("med_message_{crc32(session_id) % 16}");
        assertThat(readme).contains("PATIENT=0");
        assertThat(readme).contains("DOCTOR=1");
        assertThat(readme).contains("ASSISTANT=2");
        assertThat(readme).contains("SYSTEM=3");
        assertThat(readme).contains("med_session.proto");
    }

    @Test
    @DisplayName("documented environment variables really resolve to a configuration placeholder")
    void documentedEnvironmentVariablesResolveToConfiguration() {
        List<String> variables = List.of(
                "SPRING_PROFILES_ACTIVE",
                "SERVER_PORT",
                "REDIS_HOST",
                "REDIS_PORT",
                "MED_MYSQL_HOST",
                "MED_MYSQL_DATABASE",
                "MED_MYSQL_USERNAME",
                "MED_MIGRATION_URL",
                "MED_MIGRATION_LOCATIONS",
                "OPENAI_BASE_URL",
                "OPENAI_API_KEY",
                "MED_EMBEDDING_MODEL",
                "MED_EMBEDDING_DIMENSIONS",
                "MED_SECURITY_ENABLED",
                "MED_SECURITY_DEPT_SCOPE_ENABLED",
                "MED_SECURITY_HEADER",
                "MED_RATE_LIMIT_ENABLED",
                "MED_AUDIT_ENABLED",
                "MED_CACHE_TTL",
                "MED_CACHE_MAX_MESSAGES",
                "MED_CHAT_MAX_MESSAGES",
                "MED_CHAT_STREAM_HEARTBEAT",
                "MED_CHAT_STREAM_TIMEOUT",
                "MED_LOCK_WAIT_TIME",
                "MED_LOCK_LEASE_TIME",
                "MED_LOCK_WATCHDOG_TIMEOUT",
                "MED_RAG_INDEX_NAME",
                "MED_RAG_KEY_PREFIX",
                "MED_RAG_TOP_K",
                "MED_RAG_MAX_TOP_K",
                "MED_RAG_SIMILARITY_THRESHOLD",
                "MED_RAG_INGEST_BATCH_SIZE",
                "MED_RAG_INGEST_MAX_DOCUMENTS");

        String configuration = applicationYml + compose + sharding;
        variables.forEach(variable -> {
            assertThat(readme).as("README must document %s", variable).contains(variable);
            assertThat(configuration)
                    .as("%s must be a real placeholder in application.yml, compose or the sharding rules", variable)
                    .contains(variable);
        });
    }

    @Test
    @DisplayName("the advertised component versions match the versions the build actually pins")
    void advertisedVersionsMatchTheBuild() {
        Map<String, String> pinned = Map.of(
                "hutool.version", "Hutool",
                "spring-ai.version", "Spring AI 1.0.0",
                "shardingsphere.version", "ShardingSphere-JDBC 5.5.2",
                "redisson.version", "Redisson 3.45.1",
                "protobuf-java.version", "Protobuf 4.29.3",
                "jacoco-maven-plugin.version", "JaCoCo 0.8.13");
        pinned.forEach((property, label) -> {
            String value = pomProperty(property);
            assertThat(value).as("pom property %s", property).isNotBlank();
            assertThat(readme).as("README must advertise %s %s", label, value).contains(value);
            assertThat(readme).contains(label);
        });
        assertThat(readme).contains(springBootVersion());
    }

    @Test
    @DisplayName("the roadmap section states that all four phases are complete and links the roadmap file")
    void roadmapProgressIsCompleteAndLinked() {
        assertThat(readme).contains("ROADMAP.md");
        assertThat(readme).contains("D1–D5");
        assertThat(readme).contains("D27–D31");
        assertThat(readme).contains("已完成");
        assertThat(Path.of(System.getProperty("user.dir"), "ROADMAP.md")).exists();
    }

    @Test
    @DisplayName("the CI chapter documents the container integration stage and its mandatory-Docker switch")
    void ciChapterDocumentsTheIntegrationStage() throws IOException {
        assertThat(readme).contains("`integration` job");
        assertThat(readme).contains("MED_TEST_INTEGRATION_REQUIRED");
        assertThat(readme).contains("med.test.integration.required");
        assertThat(readme).contains("com.med.qa.integration.*IntegrationTest");
        assertThat(readme).contains("CiIntegrationStageConfigTest");
        assertThat(readme).contains("docker save");

        // The switch the README tells an operator about must be the one the workflow actually
        // exports; documenting a variable nobody sets would be worse than not documenting it.
        String workflow = Files.readString(
                Path.of(System.getProperty("user.dir"), ".github", "workflows", "ci.yml"));
        assertThat(workflow).contains("MED_TEST_INTEGRATION_REQUIRED");
    }

    @Test
    @DisplayName("the RAG chapter documents the RediSearch tag escaping and the defect it prevents")
    void ragChapterDocumentsTagEscaping() {
        assertThat(readme).contains("escapeTagValue");
        assertThat(readme).contains("MedRetrievalFilters");
        assertThat(readme).contains("RedisFilterExpressionConverter");
        assertThat(readme).contains("Syntax error");
        assertThat(readme).contains("MedRagRetrievalIntegrationTest");
        assertThat(readme).contains("DeterministicEmbeddingModel");
    }

    @Test
    @DisplayName("the README links the deployment handbook and the linked file exists")
    void deploymentHandbookIsLinkedAndPresent() {
        assertThat(readme).contains("./docs/DEPLOYMENT.md");
        assertThat(Path.of(System.getProperty("user.dir"), "docs", "DEPLOYMENT.md")).exists();
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Derives every HTTP path declared by the controllers by concatenating the class level
     * {@code @RequestMapping} base with each method level mapping.
     *
     * @return distinct endpoint paths such as {@code /api/sessions/{sessionId}/close}
     */
    private static Set<String> controllerEndpoints() {
        Set<String> endpoints = new LinkedHashSet<>();
        Path controllerDir = Path.of(System.getProperty("user.dir"), "src/main/java/com/med/qa/controller");
        try (var stream = Files.list(controllerDir)) {
            for (Path file : stream.filter(p -> p.getFileName().toString().endsWith("Controller.java")).toList()) {
                String source = Files.readString(file);
                String base = firstGroup(Pattern.compile("@RequestMapping\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\""), source);
                Matcher mapping = Pattern
                        .compile("@(Get|Post|Put|Delete|Patch)Mapping(\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\")?")
                        .matcher(source);
                while (mapping.find()) {
                    String value = mapping.group(3) == null ? "" : mapping.group(3);
                    endpoints.add(base + value);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("unable to read the controller sources", e);
        }
        return endpoints;
    }

    /** First capture group of {@code pattern} in {@code text}, or {@code null} when absent. */
    private static String firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    /** Value of a {@code <name>value</name>} property in the POM, or {@code ""} when absent. */
    private static String pomProperty(String name) {
        List<String> values = new ArrayList<>();
        Matcher matcher = Pattern.compile("<" + Pattern.quote(name) + ">([^<]+)</").matcher(pom);
        while (matcher.find()) {
            values.add(matcher.group(1).trim());
        }
        return values.isEmpty() ? "" : values.get(0);
    }

    /** Spring Boot version inherited from the parent POM. */
    private static String springBootVersion() {
        Matcher matcher = Pattern
                .compile("<artifactId>spring-boot-starter-parent</artifactId>\\s*<version>([^<]+)</version>")
                .matcher(pom);
        assertThat(matcher.find()).as("spring-boot-starter-parent version").isTrue();
        return matcher.group(1).trim();
    }
}
