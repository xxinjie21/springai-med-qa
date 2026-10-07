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

    /**
     * Full {@code application-prod.yml} text.
     *
     * <p>Both the {@code Dockerfile} and {@code docker-compose.yml} activate the {@code prod} profile,
     * so this file -- not {@code application.yml} -- decides the production runtime behaviour. Until
     * D40 no test referenced it at all, which is how a narrowing
     * {@code management.endpoints.web.exposure.include} shipped.</p>
     */
    private static String applicationProdYml;

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
        applicationProdYml = Files.readString(root.resolve("src/main/resources/application-prod.yml"));
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
        // Assert the full list, never a prefix: "include: health,info" is satisfied by
        // "include: health,info,prometheus" and by the truncated production override that D40 fixed.
        assertThat(applicationYml).contains("include: health,info,prometheus");
    }

    @Test
    @DisplayName("the handbook documents that the prod profile must keep the scrape endpoint exposed (D40)")
    void prodProfileExposureContractIsDocumented() {
        // Both the Dockerfile and docker-compose.yml run the prod profile, so the production
        // exposure list is the one in application-prod.yml. An operator who edits it must be told
        // that Spring Boot replaces a list property rather than merging it, and that dropping
        // prometheus silently kills every rule under deploy/prometheus/.
        assertThat(applicationProdYml).contains("include: health,info,prometheus");
        assertThat(handbook).contains("application-prod.yml");
        assertThat(handbook).contains("ApplicationProfileContractTest");
        assertThat(handbook).contains("覆盖");
        assertThat(handbook).contains("med_qa_alert_total");
    }

    @Test
    @DisplayName("the handbook documents the D41 streaming identity contract and how to triage each refusal")
    void streamingIdentityContractIsDocumented() {
        // An operator seeing a 403 on /api/chat/stream must be able to tell an expired API key from a
        // client that hard-codes another tenant's identifiers. The triage table is the operator-facing
        // half of the contract, so it has to name the rule, every status and the guard order.
        assertThat(handbook).contains("RequestIdentityGuard");
        assertThat(handbook).contains("一致性声明");
        assertThat(handbook).contains("tenant mismatch");
        assertThat(handbook).contains("department mismatch");
        assertThat(handbook).contains("patient mismatch");
        assertThat(handbook).contains("PatientAccessGuard.assertScope");
        assertThat(handbook).contains("requireWritableSession");
        assertThat(handbook).contains("StreamingIdentityContractTest");
        // The trap worth writing down: disabling authentication removes the principal, and the
        // streaming endpoint then refuses every request rather than silently running unscoped.
        assertThat(handbook).contains("MED_SECURITY_ENABLED=false");
        // And what the D41 contract test caught: "no model configured" used to answer 500, not 503.
        assertThat(handbook).contains("BeansException");
        assertThat(handbook).contains("prototype bean");
    }

    @Test
    @DisplayName("the handbook documents the D42 RAG admin authorization contract and its deletion semantics")
    void ragAdminAuthorizationIsDocumented() {
        // An operator hitting a refusal on /api/rag/** must be able to tell the two shapes apart: the
        // interceptor writes a real 403, while a handler-raised refusal is HTTP 200 carrying business
        // code 40300. And a runbook that still deletes by documentId has to be told, in the handbook,
        // that the primitive is gone and why.
        assertThat(handbook).contains("10.2");
        assertThat(handbook).contains("RagAdminAuthorizationContractTest");
        assertThat(handbook).contains("confirmDepartmentWide");
        assertThat(handbook).contains("40300");
        assertThat(handbook).contains("40000");
        assertThat(handbook).contains("MedDocumentServiceDeleteTest");
        assertThat(handbook).contains("NoIdBasedDeletion");
        assertThat(handbook).contains("MED_SECURITY_DEPT_SCOPE_ENABLED");
    }

    @Test
    @DisplayName("the handbook documents the D43 alert delivery order and the probe-failure signal")
    void alertDeliveryOrderIsDocumented() {
        // Two behaviours an operator must be able to reason about from the handbook alone: an alert is
        // recorded only after it was delivered (so a totally failed delivery is retried, not swallowed
        // for the cooldown), and reason=unreachable is a different incident from index-missing.
        assertThat(handbook).contains("7.5");
        assertThat(handbook).contains("AlertDeliveryContractTest");
        assertThat(handbook).contains("reason=unreachable");
        assertThat(handbook).contains("MedQaRagIndexProbeFailed");
        assertThat(handbook).contains("MedQaRagIndexDegraded");
        // The triage commands have to be in the handbook: an on-call engineer needs the read, not a
        // description of the read.
        assertThat(handbook).contains("med:alert:dedupe");
        assertThat(handbook).contains("med-vector-index");
        // And the reason the severity split cannot live in the monitor must be stated, otherwise the
        // next reader will "simplify" it back into the unreachable form.
        assertThat(handbook).contains("AbstractHealthIndicator#health()");
        assertThat(handbook).contains("final");
    }

    @Test
    @DisplayName("the handbook documents the D44 transcript tiers and their triage query")
    void transcriptTiersAreDocumented() {
        // What an operator must be able to answer from the handbook alone: does shrinking the memory
        // window lose data (no), and how do I count a session's real rows in the physical shard.
        assertThat(handbook).contains("7.6");
        assertThat(handbook).contains("insertIfAbsent");
        assertThat(handbook).contains("不影响落库轨迹");
        assertThat(handbook).contains("SESSION_LOCKED");
        assertThat(handbook).contains("med_message_");
        assertThat(handbook).contains("session_id");
        // The second D44 defect, and the log line an operator will actually see.
        assertThat(handbook).contains("patient_id");
        assertThat(handbook).contains("cannot be attributed");
    }

    @Test
    @DisplayName("the handbook documents the D45 embedding-metadata rule and its triage command")
    void embeddingMetadataRuleIsDocumented() {
        // What an operator must be able to answer from the handbook alone: which mode the deployment
        // must run, why "not configured" is not safe, and how to read the value out of the image
        // (actuator/env is deliberately not exposed, so the handbook has to name the real path).
        assertThat(handbook).contains("7.7");
        assertThat(handbook).contains("metadata-mode");
        assertThat(handbook).contains("SAFE_METADATA_MODE");
        assertThat(handbook).contains("EmbeddingMetadataContractTest");
        assertThat(handbook).contains("excludedEmbedMetadataKeys");
        assertThat(handbook).contains("BOOT-INF/classes/application.yml");
        // Turning EMBED off must never be described as costing the tag filter.
        assertThat(handbook).contains("med.rag.vector-store.metadata-fields");
    }

    @Test
    @DisplayName("the handbook documents the D46 prerequisites: the utf8mb4 default and the H2 console")
    void deploymentPrerequisitesAreDocumented() {
        // Both are silent failures: a non-utf8mb4 database stores Chinese clinical text as mojibake
        // without an error, and an H2 console would be an unauthenticated SQL console on the
        // production classpath. The handbook has to state both and say how to verify them.
        assertThat(handbook).contains("utf8mb4");
        assertThat(handbook).contains("SHOW CREATE DATABASE");
        assertThat(handbook).contains("characterEncoding");
        assertThat(handbook).contains("UnsupportedEncodingException");
        assertThat(handbook).contains("spring.h2.console");
        assertThat(handbook).contains("H2 Web Console");
        // The handbook's claims are owned by other files; that split is asserted in
        // DeploymentPrerequisiteTest rather than duplicated here.
        assertThat(handbook).contains("DeploymentPrerequisiteTest");
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
    @DisplayName("the handbook documents the RAG retrieval integration test and the tag-escaping defect")
    void ragRetrievalVerificationIsDocumented() {
        assertThat(handbook).contains("MedRagRetrievalIntegrationTest");
        assertThat(handbook).contains("DeterministicEmbeddingModel");
        assertThat(handbook).contains("escapeTagValue");
        assertThat(handbook).contains("RedisFilterExpressionConverter");
        assertThat(handbook).contains("Syntax error");
    }

    @Test
    @DisplayName("the handbook documents the D37 vector-index health component and how to react to each reason")
    void vectorIndexHealthComponentIsDocumented() {
        assertThat(handbook).contains("med-vector-index");
        assertThat(handbook).contains("MedVectorIndexProbe");
        assertThat(handbook).contains("MedVectorIndexHealthIndicator");
        assertThat(handbook).contains("MedVectorIndexAlertMonitor");
        // Every reason the component can report must have a documented operator response.
        assertThat(handbook).contains("index-missing");
        assertThat(handbook).contains("schema-drift");
        assertThat(handbook).contains("unreachable");
        assertThat(handbook).contains("MED_RAG_INDEX_ENABLED");
        assertThat(handbook).contains("rag-index-degraded");
        assertThat(handbook).contains("MedQaRagIndexDegraded");
    }

    @Test
    @DisplayName("the handbook documents the D38 controlled rebuild and how to react to a degraded index")
    void vectorIndexRebuildIsDocumented() {
        assertThat(handbook).contains("MedVectorIndexRebuilder");
        assertThat(handbook).contains("MedIndexRebuildReport");
        assertThat(handbook).contains("med:lock:rag:index:rebuild:");
        assertThat(handbook).contains("FT.DROPINDEX");
        assertThat(handbook).contains("INDEX_ONLY");
        assertThat(handbook).contains("DROP_AND_REINGEST");
        assertThat(handbook).contains("MED_RAG_INDEX_REBUILD_ENABLED");
        assertThat(handbook).contains("MED_RAG_INDEX_REBUILD_ALLOW_DELETE");
        // Every alert code the rebuild raises must be listed with its severity.
        assertThat(handbook).contains("rag-index-rebuild-completed");
        assertThat(handbook).contains("rag-index-rebuild-skipped");
        assertThat(handbook).contains("rag-index-rebuild-failed");
        assertThat(handbook).contains("MedQaRagIndexRebuildFailed");
    }

    @Test
    @DisplayName("the handbook documents the D39 retrieval-quality baseline and how to triage each failure shape")
    void retrievalQualityBaselineIsDocumented() {
        assertThat(handbook).contains("MedRetrievalBaselineIntegrationTest");
        assertThat(handbook).contains("MedRetrievalBaselineResourceTest");
        assertThat(handbook).contains("retrieval-baseline.json");
        assertThat(handbook).contains("minRecall");
        // Every failure shape of the baseline must carry the keyword an operator will see and a
        // pointer to what to inspect first.
        assertThat(handbook).contains("is below the required");
        assertThat(handbook).contains("best-ranked document is");
        assertThat(handbook).contains("out-of-scope documents were returned");
        assertThat(handbook).contains("vector-algorithm");
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
