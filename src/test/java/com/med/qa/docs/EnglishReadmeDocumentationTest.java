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
    @DisplayName("the English README documents the D37 vector-index probe and its two silent failure modes")
    void ragChapterDocumentsTheVectorIndexProbe() {
        assertThat(english).contains("MedVectorIndexProbe");
        assertThat(english).contains("MedVectorIndexHealthIndicator");
        assertThat(english).contains("MedVectorIndexAlertMonitor");
        assertThat(english).contains("MedVectorIndexProbeIntegrationTest");
        assertThat(english).contains("index-missing");
        assertThat(english).contains("schema-drift");
        assertThat(english).contains("expected-tag-fields");
        assertThat(english).contains("MED_RAG_INDEX_ENABLED");
        assertThat(english).contains("FT.INFO");
        // The deployment note matters: without the switch a plain-Redis deployment would be unhealthy.
        assertThat(english).contains("Redis Stack");
    }

    @Test
    @DisplayName("the English README documents the D38 controlled rebuild and why the safe mode is the default")
    void ragChapterDocumentsTheControlledRebuild() {
        assertThat(english).contains("MedVectorIndexRebuilder");
        assertThat(english).contains("MedIndexRebuildReport");
        assertThat(english).contains("MedIndexRebuildIntegrationTest");
        assertThat(english).contains("FT.DROPINDEX");
        assertThat(english).contains("INDEX_ONLY");
        assertThat(english).contains("DROP_AND_REINGEST");
        assertThat(english).contains("MED_RAG_INDEX_REBUILD_ENABLED");
        assertThat(english).contains("MED_RAG_INDEX_REBUILD_ALLOW_DELETE");
        assertThat(english).contains("med:lock:rag:index:rebuild:");
        // The reason the capability is off by default has to be stated, not implied.
        assertThat(english).contains("off by default");
    }

    @Test
    @DisplayName("the English README documents the D39 retrieval-quality baseline and its three assertions")
    void ragChapterDocumentsTheRetrievalQualityBaseline() {
        assertThat(english).contains("MedRetrievalBaselineEvaluator");
        assertThat(english).contains("MedRetrievalBaselineLoader");
        assertThat(english).contains("MedRetrievalBaselineReport");
        assertThat(english).contains("MedRetrievalBaselineIntegrationTest");
        assertThat(english).contains("MedRetrievalBaselineResourceTest");
        assertThat(english).contains("retrieval-baseline.json");
        // All three assertions a case can make have to be named, so a reader can pick the right one.
        assertThat(english).contains("expectedDocumentIds");
        assertThat(english).contains("expectedTopDocumentId");
        assertThat(english).contains("forbiddenDocumentIds");
        assertThat(english).contains("minRecall");
        // Why the loader refuses unknown fields must be stated: it is the difference between a broken
        // golden set failing and one silently asserting nothing.
        assertThat(english).contains("unknown fields");
        // The evaluator measures, it does not retrieve - and a failure propagates instead of being
        // reported as zero recall.
        assertThat(english).contains("document identifiers only");
        assertThat(english).contains("zero recall");
    }

    @Test
    @DisplayName("the English README documents the D40 prod-profile exposure contract")
    void deploymentChapterDocumentsTheProfileExposureContract() {
        assertThat(english).contains("application-prod.yml");
        assertThat(english).contains("ApplicationProfileContractTest");
        assertThat(english).contains("med_qa_alert_total");
        // The mechanism has to be named: a profile replaces a list property instead of merging it.
        // Without that sentence the paragraph reads as an arbitrary rule rather than a Spring Boot
        // behaviour an operator can verify.
        assertThat(english).contains("replaces a list property");
    }

    @Test
    @DisplayName("the English README documents the D41 streaming identity contract and its cross-component guard")
    void apiChapterDocumentsTheStreamingIdentityContract() {
        assertThat(english).contains("RequestIdentityGuard");
        assertThat(english).contains("MedCallerScope");
        assertThat(english).contains("StreamingIdentityContractTest");
        assertThat(english).contains("MedChatSessionService.requireWritableSession");
        assertThat(english).contains("PatientAccessGuard.assertScope");
        assertThat(english).contains("consistency");
        assertThat(english).contains("tenant mismatch");
        assertThat(english).contains("department mismatch");
        assertThat(english).contains("patient mismatch");
        // The three refusals a caller can meet before the stream opens must all be named, and so must
        // the reason they bypass the global advice.
        assertThat(english).contains("503");
        assertThat(english).contains("text/event-stream");
        assertThat(english).contains("content-negotiation");
        // What the D41 cross-component test caught on its first run.
        assertThat(english).contains("BeansException");
        assertThat(english).contains("ObjectProvider");
    }

    @Test
    @DisplayName("the English README documents the D42 RAG admin authorization contract")
    void apiChapterDocumentsTheRagAdminAuthorization() {
        assertThat(english).contains("RagAdminAuthorizationContractTest");
        assertThat(english).contains("confirmDepartmentWide");
        assertThat(english).contains("RequestIdentityGuard");
        assertThat(english).contains("40300");
        assertThat(english).contains("40000");
        assertThat(english).contains("MED_SECURITY_DEPT_SCOPE_ENABLED");
        // The reason an id-based delete was removed rather than guarded has to survive in the English
        // text too, because that is the sentence an operator needs when a runbook breaks.
        assertThat(english).contains("scope-less");
    }

    @Test
    @DisplayName("the English README documents the D43 alert delivery order and probe-failure signal")
    void alertChapterDocumentsTheDeliveryContract() {
        assertThat(english).contains("AlertDeliveryContractTest");
        assertThat(english).contains("putIfAbsent");
        assertThat(english).contains("containsKey");
        assertThat(english).contains("isProbeFailure");
        assertThat(english).contains("rag-index-probe-failed");
        assertThat(english).contains("MedQaRagIndexProbeFailed");
        // Why the split lives in the indicator and not in the monitor: without it a later reader will
        // "simplify" the code back into the form where the branch is unreachable.
        assertThat(english).contains("final");
        assertThat(english).contains("AbstractHealthIndicator");
    }

    @Test
    @DisplayName("the English README documents the D44 durable-transcript contract and its guards")
    void apiChapterDocumentsTheDurableTranscriptContract() {
        assertThat(english).contains("insertIfAbsent");
        assertThat(english).contains("ON DUPLICATE KEY UPDATE");
        assertThat(english).contains("saveWindow");
        assertThat(english).contains("ChatMemoryWindowIntegrationTest");
        assertThat(english).contains("memoryWindowTrimsButTranscriptKeepsEveryTurn");
        // The operational sentence: shrinking the window bounds the prompt, it never deletes data.
        assertThat(english).contains("MED_CHAT_MAX_MESSAGES");
        assertThat(english).contains("bounds the prompt only");
        // The second defect the same contract test found: a framework message carries no patient.
        assertThat(english).contains("ChatSessionMapper");
        assertThat(english).contains("patient_id");
    }

    @Test
    @DisplayName("the English README documents the D45 rule that isolation metadata stays out of the vector")
    void apiChapterDocumentsTheEmbeddingMetadataRule() {
        // The defect this guards: the YAML said EMBED under a comment claiming the tags stay out of
        // the vector, so the isolation tags were spliced into the embedded text.
        assertThat(english).contains("metadata-mode: NONE");
        assertThat(english).contains("EmbeddingMetadataContractTest");
        assertThat(english).contains("SAFE_METADATA_MODE");
        assertThat(english).contains("DefaultContentFormatter");
        assertThat(english).contains("excludedEmbedMetadataKeys");
        // Turning EMBED off must never be described as costing the tag filter.
        assertThat(english).contains("med.rag.vector-store.metadata-fields");
    }

    @Test
    @DisplayName("the English README documents the D46 window-semantics and prerequisite corrections")
    void apiChapterDocumentsTheD46Corrections() {
        // The English README is a translation of the same handbook: it must carry the bounded-read
        // statement, the value that unbounds it, the deployment prerequisites and the privacy fix.
        assertThat(english).contains("D46");
        assertThat(english).contains("MED_CACHE_MAX_MESSAGES=0");
        assertThat(english).contains("reload");
        assertThat(english).contains("maskKeepEdges");
        assertThat(english).contains("utf8mb4");
        assertThat(english).contains("MemoryWindowSemanticsTest");
        assertThat(english).contains("PrivacyMaskingDocumentationTest");
        assertThat(english).contains("DeploymentPrerequisiteTest");
    }

    @Test
    @DisplayName("the English README documents the D47 retention sweep and its conservative defaults")
    void apiChapterDocumentsTheD47RetentionSweep() {
        // The English README is a translation of the same handbook: it must carry the two mechanisms
        // that keep the sweep away from live consultations, the mutex key, the switch, and the guards.
        assertThat(english).contains("D47");
        assertThat(english).contains("MedSessionRetentionService");
        assertThat(english).contains("med:lock:session:retention");
        assertThat(english).contains("updateStatusIfStale");
        assertThat(english).contains("MED_SESSION_RETENTION_ENABLED");
        assertThat(english).contains("session-retention-failed");
        assertThat(english).contains("idx_med_session_retention");
        assertThat(english).contains("ChatSessionMapperRetentionShardingTest");
        assertThat(english).contains("MedSessionRetentionConfigTest");
    }

    @Test
    @DisplayName("the English README documents the D48 cold archive and what it refuses to do")
    void apiChapterDocumentsTheD48ColdArchive() {
        // The English README is a translation of the same handbook: it must carry the certification
        // digest, the mutex key, the switch and - most importantly - that nothing is deleted.
        assertThat(english).contains("D48");
        assertThat(english).contains("MedSessionArchiveExportService");
        assertThat(english).contains("med:lock:session:archive:export");
        assertThat(english).contains("med_message_archive");
        assertThat(english).contains("SessionArchiveChecksum");
        assertThat(english).contains("MED_SESSION_ARCHIVE_ENABLED");
        assertThat(english).contains("session-archive-mismatch");
        assertThat(english).contains("SessionArchiveMapperShardingTest");
        assertThat(english).contains("MedSessionArchiveConfigTest");
    }

    @Test
    @DisplayName("the English README documents the D49 transcript read path and its refusal")
    void apiChapterDocumentsTheD49TranscriptRead() {
        // The English README is a translation of the same handbook: it must carry the tier the
        // response came from, the shared digest, and the fact that an unaccountable transcript is
        // refused rather than guessed.
        assertThat(english).contains("D49");
        assertThat(english).contains("MedTranscriptService");
        assertThat(english).contains("TranscriptDigests");
        assertThat(english).contains("SessionTranscript");
        assertThat(english).contains("HOT");
        assertThat(english).contains("COLD");
        assertThat(english).contains("payload_checksum");
        assertThat(english).contains("MedTranscriptServiceTest");
        assertThat(english).contains("MedStorageAndLockIntegrationTest");
    }

    @Test
    @DisplayName("the English README documents the D50 hot-shard purge and the two proofs it demands")
    void apiChapterDocumentsTheD50HotShardPurge() {
        // The English README is a translation of the same handbook: it must carry the two proofs, the
        // ordering that puts the cold copy first, the marker column, the mutex key, the switches and the
        // fact that only med_message is ever deleted.
        assertThat(english).contains("D50");
        assertThat(english).contains("MedSessionPurgeService");
        assertThat(english).contains("med:lock:session:purge");
        assertThat(english).contains("purged_at");
        assertThat(english).contains("MED_SESSION_PURGE_ENABLED");
        assertThat(english).contains("MED_SESSION_PURGE_DRY_RUN");
        assertThat(english).contains("session-purge-refused");
        assertThat(english).contains("session-purge-failed");
        assertThat(english).contains("min-archived-age");
        assertThat(english).contains("MedSessionPurgeServiceTest");
        assertThat(english).contains("SessionPurgeMapperShardingTest");
        assertThat(english).contains("MedSessionPurgeConfigTest");
    }

    @Test
    @DisplayName("the English README documents the D52 orphan reclaim, its containment guard and why it needs no window")
    void apiChapterDocumentsTheD52OrphanReclaim() {
        // A translation gap here is worse than a translation gap elsewhere: the two things a reader is
        // most likely to misread - that the guard is containment by message id rather than a digest, and
        // that the missing cooling-off window is deliberate rather than an omission - both invert the
        // safety story if they are dropped.
        assertThat(english).contains("D52");
        // Two assertions here have to be stronger than the obvious form. A bare `contains("D52")` is
        // satisfied by the roadmap row, and `contains("MedSessionReclaimService")` is satisfied by the
        // guard-test class name `MedSessionReclaimServiceTest` - both would stay green after the whole
        // chapter was deleted. The heading and the configuration prefix cannot be satisfied that way.
        assertThat(english).contains("#### Reclaiming orphaned cold copies (D52)");
        assertThat(english).contains("med.session.reclaim.enabled");
        assertThat(english).contains("MedSessionReclaimService");
        assertThat(english).contains("med:lock:session:reclaim");
        assertThat(english).contains("MED_SESSION_RECLAIM_ENABLED");
        assertThat(english).contains("MED_SESSION_RECLAIM_DRY_RUN");
        assertThat(english).contains("session-reclaim-refused");
        assertThat(english).contains("session-reclaim-failed");
        assertThat(english).contains("deleteColdCopy");
        assertThat(english).contains("MedSessionReclaimServiceTest");
        assertThat(english).contains("SessionReclaimMapperShardingTest");
        assertThat(english).contains("MedSessionReclaimConfigTest");
    }

    @Test
    @DisplayName("the English README documents the D53 job run ledger, the two rules and the reserved-label trap")
    void apiChapterDocumentsTheD53JobLedger() {
        // The English README is the only place an English-speaking operator learns that the four jobs'
        // own alerts are structurally blind to the job being gone, and that the last-alert gauge was
        // described as a dead-man's switch no rule ever read. Dropping either turns the chapter back into
        // the false confidence D53 exists to remove.
        assertThat(english).contains("D53");
        // As in D52: a bare `contains("D53")` is satisfied by the roadmap row and `MedJobRunLedger` is a
        // substring of `MedJobRunLedgerTest`, so the heading is asserted whole.
        assertThat(english).contains("#### Background job run ledger and stall detection (D53)");
        assertThat(english).contains("MedJobRunLedger");
        assertThat(english).contains("med_qa_job_last_run_epoch_seconds");
        assertThat(english).contains("med_qa_job_last_success_epoch_seconds");
        assertThat(english).contains("med_qa_job_expected_interval_seconds");
        assertThat(english).contains("med_qa_job_runs_total");
        assertThat(english).contains("MedQaBackgroundJobStalled");
        assertThat(english).contains("MedQaBackgroundJobNotCompleting");
        assertThat(english).contains("SKIPPED_LOCK_HELD");
        assertThat(english).contains("honor_labels");
        assertThat(english).contains("MedJobRunLedgerTest");
    }

    @Test
    @DisplayName("the English README documents the D54 cold store gauges and the derivative-free rule")
    void apiChapterDocumentsTheD54ColdStoreMetrics() {
        // D53 answers "is the job alive"; D54 is where the English reader learns the job may be alive
        // and still falling behind. The three separable claims are the two boundaries a reader would
        // otherwise undo: only a run that was allowed to delete publishes the backlog (publishing a
        // dry run's non-draining backlog would page the deployments that only measure), and the gauges
        // are lazily registered with no switch (an eagerly registered zero reads as "the cold store is
        // empty"). The heading is asserted whole: a bare "D54" is satisfied by the roadmap row.
        assertThat(english).contains("D54");
        assertThat(english).contains("#### Cold store size and age reporting (D54)");
        assertThat(english).contains("med_qa_cold_store_rows");
        assertThat(english).contains("med_qa_cold_store_oldest_row_age_seconds");
        assertThat(english).contains("med_qa_cold_store_reclaim_backlog");
        assertThat(english).contains("MedQaColdStoreBacklogGrowing");
        assertThat(english).contains("min_over_time");
        assertThat(english).contains("MedColdStoreMetrics");
        assertThat(english).contains("MedColdStoreMetricsTest");
        assertThat(english).contains("MedColdStoreMetricsConfigTest");
    }

    @Test
    @DisplayName("the English README documents the D55 audit trim, the floor and the no-cold-export decision")
    void apiChapterDocumentsTheD55AuditLogRetention() {
        // After D53/D54, the last append-only table is the audit trail. The three boundaries a reader
        // would otherwise undo: the retention window itself is the guard (an audit row has no second
        // copy, so D50/D52's prove-it-exists-elsewhere pattern is unavailable), the 90-day floor turns a
        // misconfiguration into a startup failure, and no cold store is built - which is V3's own
        // argument applied to itself. The heading is asserted whole: a bare "D55" is satisfied by the
        // roadmap row.
        assertThat(english).contains("D55");
        assertThat(english).contains("#### Trimming the audit trail after its retention window (D55)");
        assertThat(english).contains("MedAuditLogRetentionService");
        assertThat(english).contains("MED_AUDIT_LOG_RETENTION_ENABLED");
        assertThat(english).contains("med:lock:audit-log:retention");
        assertThat(english).contains("audit-log-retention-completed");
        assertThat(english).contains("MedQaAuditLogRetentionFailed");
        assertThat(english).contains("AuditLogRetentionMapperShardingTest");
        assertThat(english).contains("MedAuditLogRetentionConfigTest");
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
