package com.med.qa.docs;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard tests for {@code docs/DEPLOYMENT.md}.
 *
 * <p>The deployment handbook is what an operator follows on a hospital network: it names the image
 * to pull, the ports to open, the variables to inject, the scripts that provision the schema and
 * the health endpoint to probe. Every one of those facts is owned by another file -- the compose
 * stack, the Flyway migrations, the init script, the Actuator configuration -- so the handbook can
 * rot silently. These tests pin the handbook against those owners.</p>
 */
class DeploymentDocumentationTest {

    /** Full handbook text. */
    private static String handbook;

    /** Full {@code docker-compose.yml} text: the source of truth for ports, images and health checks. */
    private static String compose;

    /** Full {@code application.yml} text: the source of truth for environment placeholders. */
    private static String applicationYml;

    /** Full {@code sharding/med-sharding.yaml} text: owner of the MySQL placeholders. */
    private static String sharding;

    /** Full {@code .github/workflows/docker-publish.yml} text: owner of the registry coordinates. */
    private static String publishWorkflow;

    @BeforeAll
    static void readDocuments() throws IOException {
        Path root = Path.of(System.getProperty("user.dir"));
        handbook = Files.readString(root.resolve("docs/DEPLOYMENT.md"));
        compose = Files.readString(root.resolve("docker-compose.yml"));
        applicationYml = Files.readString(root.resolve("src/main/resources/application.yml"));
        sharding = Files.readString(root.resolve("src/main/resources/sharding/med-sharding.yaml"));
        publishWorkflow = Files.readString(root.resolve(".github/workflows/docker-publish.yml"));
    }

    @Test
    @DisplayName("the handbook exists and is a complete document, not a placeholder")
    void handbookExistsAndHasBody() {
        assertThat(handbook).isNotBlank();
        assertThat(handbook.lines().count()).isGreaterThan(120L);
        assertThat(handbook).startsWith("# 部署手册");
    }

    @Test
    @DisplayName("every operational section an installer needs is present")
    void requiredSectionsArePresent() {
        List<String> sections = List.of(
                "## 1. 部署拓扑与前置条件",
                "## 2. 获取镜像",
                "## 3. Docker Compose 全栈部署",
                "## 4. 独立部署",
                "## 5. 环境变量全表",
                "## 6. 数据库与索引初始化",
                "## 7. 健康检查与探针",
                "## 8. 日志与故障排查",
                "## 9. 备份与回滚",
                "## 10. 安全加固清单",
                "## 11. 容量与调优建议");
        sections.forEach(section -> assertThat(handbook).contains(section));
    }

    @Test
    @DisplayName("the documented image coordinates match the registry the publish workflow targets")
    void imageCoordinatesMatchPublishWorkflow() {
        assertThat(publishWorkflow).contains("REGISTRY: ghcr.io");
        assertThat(handbook).contains("ghcr.io/xxinjie21/springai-med-qa");
        assertThat(handbook).contains("docker-publish.yml");
        assertThat(handbook).contains("git tag v");
    }

    @Test
    @DisplayName("the documented ports are exactly the ports the compose stack publishes")
    void documentedPortsMatchComposeStack() {
        assertThat(handbook).contains("8080").contains("3306").contains("6379");
        assertThat(compose).contains("8080:8080").contains("3306:3306").contains("6379:6379");
        assertThat(handbook).contains("8001");
        assertThat(compose).contains("8001:8001");
    }

    @Test
    @DisplayName("the documented health endpoint is the one compose actually probes")
    void documentedHealthEndpointMatchesComposeProbe() {
        assertThat(handbook).contains("/actuator/health");
        assertThat(compose).contains("/actuator/health");
        assertThat(applicationYml).contains("include: health,info");
    }

    @Test
    @DisplayName("documented environment variables resolve to real placeholders and cover the secrets")
    void documentedEnvironmentVariablesResolveToConfiguration() {
        List<String> variables = List.of(
                "SPRING_PROFILES_ACTIVE",
                "SERVER_PORT",
                "REDIS_HOST",
                "REDIS_PORT",
                "MED_MYSQL_HOST",
                "MED_MYSQL_PORT",
                "MED_MYSQL_DATABASE",
                "MED_MYSQL_USERNAME",
                "MED_MYSQL_PASSWORD",
                "MED_MYSQL_ROOT_PASSWORD",
                "OPENAI_BASE_URL",
                "OPENAI_API_KEY",
                "MED_EMBEDDING_MODEL",
                "MED_EMBEDDING_DIMENSIONS",
                "MED_SECURITY_ENABLED",
                "MED_SECURITY_DEPT_SCOPE_ENABLED",
                "MED_SECURITY_HEADER",
                "MED_RATE_LIMIT_ENABLED",
                "MED_RATE_LIMIT_DEFAULT_RATE",
                "MED_RATE_LIMIT_DEFAULT_DURATION",
                "MED_AUDIT_ENABLED",
                "MED_CACHE_TTL",
                "MED_CHAT_STREAM_HEARTBEAT",
                "MED_CHAT_STREAM_TIMEOUT",
                "MED_LOCK_LEASE_TIME",
                "MED_LOCK_WATCHDOG_TIMEOUT",
                "MED_RAG_INDEX_NAME",
                "MED_RAG_KEY_PREFIX",
                "MED_RAG_VECTOR_ALGORITHM",
                "MED_RAG_TOP_K",
                "MED_RAG_ADVISOR_ORDER");

        String configuration = applicationYml + compose + sharding;
        variables.forEach(variable -> {
            assertThat(handbook).as("handbook must document %s", variable).contains(variable);
            assertThat(configuration)
                    .as("%s must be a real placeholder in application.yml, compose or the sharding rules", variable)
                    .contains(variable);
        });
    }

    @Test
    @DisplayName("the schema chapter points at the init script and the Flyway migrations that exist")
    void schemaChapterReferencesRealScripts() throws IOException {
        assertThat(handbook).contains("docker/mysql/init/01-create-db.sql");
        assertThat(Path.of(System.getProperty("user.dir"), "docker/mysql/init/01-create-db.sql")).exists();

        assertThat(handbook).contains("Flyway V1–V3");
        Path migrationDir = Path.of(System.getProperty("user.dir"), "src/main/resources/db/migration");
        try (var stream = Files.list(migrationDir)) {
            List<String> migrations = stream.map(p -> p.getFileName().toString()).sorted().toList();
            assertThat(migrations).hasSize(3);
            assertThat(migrations.get(0)).startsWith("V1__");
            assertThat(migrations.get(1)).startsWith("V2__");
            assertThat(migrations.get(2)).startsWith("V3__");
        }
        assertThat(handbook).contains("med_message_{0..15}");
        assertThat(sharding).contains("MED_CRC32_MOD");
    }

    @Test
    @DisplayName("the schema chapter explains why the migration must bypass the sharding proxy")
    void schemaChapterExplainsThePhysicalMigrationTarget() {
        // An operator who repoints spring.datasource at a real proxy, or who wonders why Boot's own
        // Flyway auto-configuration is excluded, must be able to read the answer here.
        assertThat(handbook).contains("MedFlywayConfig");
        assertThat(handbook).contains("spring.flyway.enabled");
        assertThat(handbook).contains("1007");
    }

    @Test
    @DisplayName("the Redis Stack prerequisite matches the image the compose stack runs")
    void redisStackPrerequisiteMatchesComposeImage() {
        assertThat(handbook).contains("redis/redis-stack");
        assertThat(handbook).contains("RediSearch");
        assertThat(compose).contains("image: redis/redis-stack");
    }

    @Test
    @DisplayName("the handbook documents the CI integration stage and how to reproduce it locally")
    void integrationStageIsDocumented() {
        assertThat(handbook).contains("MED_TEST_INTEGRATION_REQUIRED");
        assertThat(handbook).contains("med.test.integration.required");
        assertThat(handbook).contains("com.med.qa.integration.*IntegrationTest");
        assertThat(handbook).contains("docker load");
        assertThat(handbook).contains("1.21.0");
    }

    @Test
    @DisplayName("the troubleshooting table explains every business error code an operator can observe")
    void troubleshootingCoversBusinessErrorCodes() {
        assertThat(handbook).contains("401");
        assertThat(handbook).contains("403");
        assertThat(handbook).contains("40900");
        assertThat(handbook).contains("42900");
        assertThat(handbook).contains("50201");
        assertThat(handbook).contains("50301");
    }

    @Test
    @DisplayName("backup, rollback and hardening guidance reference the concrete commands and switches")
    void operationalRunbooksAreConcrete() {
        assertThat(handbook).contains("mysqldump");
        assertThat(handbook).contains("BGSAVE");
        assertThat(handbook).contains("docker compose down -v");
        assertThat(handbook).contains("MED_SECURITY_ENABLED=true");
        assertThat(handbook).contains("MED_RATE_LIMIT_ENABLED=true");
        assertThat(handbook).contains("MED_AUDIT_ENABLED=true");
        assertThat(handbook).contains("medqa");
    }
}
