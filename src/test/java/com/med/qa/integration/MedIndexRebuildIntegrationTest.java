package com.med.qa.integration;

import com.med.qa.alert.MedAlertNotifier;
import com.med.qa.config.VectorStoreConfig;
import com.med.qa.rag.MedDocumentIngestionProperties;
import com.med.qa.rag.MedDocumentRequest;
import com.med.qa.rag.MedDocumentScope;
import com.med.qa.rag.MedDocumentService;
import com.med.qa.rag.MedIndexRebuildMode;
import com.med.qa.rag.MedIndexRebuildProperties;
import com.med.qa.rag.MedIndexRebuildReport;
import com.med.qa.rag.MedIndexRebuildRequest;
import com.med.qa.rag.MedRagIndexProperties;
import com.med.qa.rag.MedRetrievalProperties;
import com.med.qa.rag.MedRetrievalService;
import com.med.qa.rag.MedVectorIndexProbe;
import com.med.qa.rag.MedVectorIndexRebuilder;
import com.med.qa.rag.MedVectorIndexReport;
import com.med.qa.rag.MedVectorStoreProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * End-to-end integration test of the RAG index rebuild (D38), against a genuine Redis Stack instance
 * spun up by Testcontainers.
 *
 * <h2>The claim a mock cannot check</h2>
 * <p>{@link MedVectorIndexRebuilder} is unit-tested against mocks, which proves which command each
 * mode issues but not what those commands <em>do</em>. The safe mode rests entirely on one property
 * of RediSearch: dropping an index without {@code DD} leaves the JSON documents in place, and the
 * following {@code FT.CREATE} re-indexes every existing key that matches the prefix. If that were not
 * true, {@link MedIndexRebuildMode#INDEX_ONLY} would silently erase the whole corpus — the exact
 * data-loss incident the mode was designed to avoid — and every offline test would still pass.</p>
 *
 * <p>So this suite drives the production path — {@link VectorStoreConfig#buildVectorStore} for the
 * store, {@link MedDocumentService} for ingestion, {@link MedRetrievalService} for retrieval,
 * {@link MedVectorIndexProbe} for the verdict, a real Redisson {@code RLock} for the mutex — and
 * asserts what actually happens to the documents and to retrieval across a rebuild.</p>
 *
 * <p>Each test builds its own index (a canary), so no assertion depends on JUnit's method order.</p>
 */
@ExtendWith(DockerAvailableCondition.class)
class MedIndexRebuildIntegrationTest {

    private static final String TENANT = "tenant-rebuild-a";
    private static final String DEPT = "dept-rebuild-cardio";
    private static final String PATIENT = "patient-rebuild-1";

    private static GenericContainer<?> redis;
    private static JedisPooled jedis;
    private static RedissonClient redisson;
    private static RedissonClient otherRedisson;

    @BeforeAll
    static void startInfrastructure() {
        // D35: when the run declares Docker mandatory (med.test.integration.required / CI), a missing
        // daemon must fail here with a message that names the switch, instead of the suite reporting
        // itself skipped. DockerAvailableCondition deliberately leaves the class enabled in that case.
        IntegrationTestRequirements.verifyDockerAvailableForCurrentRun(DockerAvailabilityProbe.testcontainers());

        redis = new GenericContainer<>(DockerImageName.parse(MedIntegrationImages.REDIS_STACK))
                .withExposedPorts(6379);
        redis.start();

        String address = "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
        jedis = new JedisPooled(new HostAndPort(redis.getHost(), redis.getMappedPort(6379)));
        redisson = Redisson.create(singleServer(address));
        // A second client stands in for a second pod: Redisson locks are owned per client, so a lock
        // taken here is genuinely foreign to the rebuilder under test.
        otherRedisson = Redisson.create(singleServer(address));
    }

    @AfterAll
    static void stopInfrastructure() {
        if (otherRedisson != null) {
            otherRedisson.shutdown();
        }
        if (redisson != null) {
            redisson.shutdown();
        }
        if (jedis != null) {
            jedis.close();
        }
        if (redis != null) {
            redis.stop();
        }
    }

    private static Config singleServer(String address) {
        Config config = new Config();
        config.useSingleServer().setAddress(address);
        return config;
    }

    // ---------------------------------------------------------------------------------------------
    // The safe mode really is lossless
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("INDEX_ONLY re-indexes the existing documents, so retrieval works again after a drop")
    void indexOnlyRebuildKeepsTheDocumentsAndRestoresRetrieval() {
        Harness harness = harness("keep");
        MedDocumentScope patientScope = MedDocumentScope.ofPatient(TENANT, DEPT, PATIENT);
        harness.documentService().ingestAll(List.of(
                new MedDocumentRequest("rebuild-keep-patient", "高血压 首选 药物 钙通道阻滞剂",
                        patientScope, Map.of("source", "discharge-summary")),
                new MedDocumentRequest("rebuild-keep-shared", "科室 指南 高血压 随访",
                        MedDocumentScope.ofDepartment(TENANT, DEPT), Map.of("source", "guideline"))));

        assertThat(harness.probe().probe().documentCount()).isEqualTo(2);
        assertThat(retrievedTexts(harness, "高血压 首选 药物 钙通道阻滞剂", patientScope))
                .as("retrieval must work before the rebuild, otherwise the test proves nothing")
                .anyMatch(text -> text.contains("钙通道阻滞剂"));

        // Drop the index only: the documents stay, the index does not. This is exactly the state a
        // hand-dropped index leaves behind, and the state the drift repair starts from.
        jedis.ftDropIndex(harness.storeProperties().getIndexName());
        assertThat(harness.probe().probe().exists()).isFalse();
        assertThat(jedis.exists(harness.storeProperties().getPrefix() + "rebuild-keep-patient"))
                .as("dropping an index without DD must leave the documents alone")
                .isTrue();

        MedIndexRebuildReport report =
                harness.rebuilder().rebuild(MedIndexRebuildRequest.indexOnly("ops-oncall"));

        assertThat(report.outcome()).isEqualTo(MedIndexRebuildReport.Outcome.COMPLETED);
        assertThat(report.documentsIngested())
                .as("the safe mode must not re-embed anything")
                .isZero();
        MedVectorIndexReport after = harness.probe().probe();
        assertThat(after.exists()).isTrue();
        assertThat(after.documentCount())
                .as("FT.CREATE must re-index the documents that were already in Redis")
                .isEqualTo(2);
        assertThat(after.tagFields()).contains("tenant_id", "dept_id", "patient_id");
        assertThat(after.isScopedRetrievalReady(harness.expectedTagFields())).isTrue();

        assertThat(retrievedTexts(harness, "高血压 首选 药物 钙通道阻滞剂", patientScope))
                .as("the repaired index must answer the same scoped query as before the drop")
                .anyMatch(text -> text.contains("钙通道阻滞剂"));
        assertThat(harness.rebuilder().currentProgress().stage())
                .isEqualTo(com.med.qa.rag.MedIndexRebuildProgress.Stage.COMPLETED);
    }

    @Test
    @DisplayName("INDEX_ONLY on an index that never existed creates it, and new writes are then indexed")
    void indexOnlyRebuildCreatesAMissingIndex() {
        Harness harness = harness("fresh");
        // The harness creates the index through the store's lifecycle hook, so remove it again: the
        // scenario under test is a deployment whose index is genuinely absent (a dropped index, or an
        // FT.CREATE that never succeeded).
        jedis.ftDropIndexDD(harness.storeProperties().getIndexName());
        assertThat(harness.probe().probe().exists()).isFalse();

        MedIndexRebuildReport report =
                harness.rebuilder().rebuild(MedIndexRebuildRequest.indexOnly("ops-oncall"));

        assertThat(report.outcome()).isEqualTo(MedIndexRebuildReport.Outcome.COMPLETED);
        assertThat(report.indexDropped())
                .as("there was nothing to drop, so the rebuild must not claim it dropped one")
                .isFalse();
        assertThat(report.documentsBefore()).isZero();
        assertThat(report.documentsAfter()).isZero();
        assertThat(harness.probe().probe().exists()).isTrue();

        MedDocumentScope scope = MedDocumentScope.ofPatient(TENANT, DEPT, PATIENT);
        harness.documentService().ingest(new MedDocumentRequest("rebuild-fresh-1",
                "糖尿病 首选 药物 二甲双胍", scope, Map.of()));

        assertThat(harness.probe().probe().documentCount()).isEqualTo(1);
        assertThat(retrievedTexts(harness, "糖尿病 首选 药物 二甲双胍", scope))
                .as("the recreated index must index documents written after the rebuild")
                .anyMatch(text -> text.contains("二甲双胍"));
    }

    // ---------------------------------------------------------------------------------------------
    // The destructive mode really replaces the corpus
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("DROP_AND_REINGEST deletes the old documents and writes the new corpus back")
    void destructiveRebuildReplacesTheCorpus() {
        Harness harness = harness("replace");
        MedDocumentScope scope = MedDocumentScope.ofPatient(TENANT, DEPT, PATIENT);
        harness.documentService().ingest(new MedDocumentRequest("rebuild-old-1",
                "旧的 病历 内容 哮喘", scope, Map.of()));

        assertThat(harness.probe().probe().documentCount()).isEqualTo(1);

        MedIndexRebuildReport report = harness.rebuilder().rebuild(MedIndexRebuildRequest.dropAndReingest(
                List.of(new MedDocumentRequest("rebuild-new-1", "新的 病历 内容 冠心病", scope, Map.of()),
                        new MedDocumentRequest("rebuild-new-2", "新的 病历 内容 心衰", scope, Map.of()),
                        new MedDocumentRequest("rebuild-new-3", "新的 病历 内容 房颤", scope, Map.of())),
                "ops-oncall"));

        assertThat(report.mode()).isEqualTo(MedIndexRebuildMode.DROP_AND_REINGEST);
        assertThat(report.outcome()).isEqualTo(MedIndexRebuildReport.Outcome.COMPLETED);
        assertThat(report.documentsBefore()).isEqualTo(1);
        assertThat(report.documentsAfter()).isEqualTo(3);
        assertThat(report.documentsIngested()).isEqualTo(3);
        assertThat(report.batchesCompleted()).as("3 documents at batch size 2").isEqualTo(2);

        String prefix = harness.storeProperties().getPrefix();
        assertThat(jedis.exists(prefix + "rebuild-old-1"))
                .as("DD must have removed the previous documents")
                .isFalse();
        assertThat(jedis.exists(prefix + "rebuild-new-1")).isTrue();
        assertThat(jedis.exists(prefix + "rebuild-new-3")).isTrue();
        assertThat(harness.probe().probe().documentCount()).isEqualTo(3);

        assertThat(retrievedTexts(harness, "新的 病历 内容 冠心病", scope))
                .anyMatch(text -> text.contains("冠心病"));
        assertThat(retrievedTexts(harness, "旧的 病历 内容 哮喘", scope))
                .as("the replaced document must no longer be retrievable")
                .noneMatch(text -> text.contains("哮喘"));
    }

    // ---------------------------------------------------------------------------------------------
    // The mutex is the reason two pods cannot rebuild the same index at once
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a rebuild whose mutex is held by another instance does nothing at all")
    void rebuildIsSkippedWhileAnotherInstanceHoldsTheMutex() {
        Harness harness = harness("mutex");
        // Start from a state that a skipped rebuild must leave untouched: an index that does not
        // exist. If the rebuild ran despite the foreign mutex, the index would be there afterwards.
        jedis.ftDropIndexDD(harness.storeProperties().getIndexName());
        assertThat(harness.probe().probe().exists()).isFalse();

        RLock foreignLock = otherRedisson.getLock(harness.rebuilder().lockKey());
        foreignLock.lock();
        try {
            MedIndexRebuildReport report =
                    harness.rebuilder().rebuild(MedIndexRebuildRequest.indexOnly("ops-oncall"));

            assertThat(report.outcome()).isEqualTo(MedIndexRebuildReport.Outcome.SKIPPED_LOCK_HELD);
            assertThat(report.indexDropped()).isFalse();
            assertThat(harness.probe().probe().exists())
                    .as("a skipped rebuild must not have created or dropped anything")
                    .isFalse();
            assertThat(harness.rebuilder().isRebuilding()).isFalse();
        } finally {
            foreignLock.unlock();
        }

        // Once the foreign holder is gone the same instance can rebuild, which is what an operator
        // retrying after the first rebuild finished relies on.
        MedIndexRebuildReport retried =
                harness.rebuilder().rebuild(MedIndexRebuildRequest.indexOnly("ops-oncall"));
        assertThat(retried.outcome()).isEqualTo(MedIndexRebuildReport.Outcome.COMPLETED);
        assertThat(harness.probe().probe().exists()).isTrue();
    }

    // ---------------------------------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------------------------------

    /**
     * One independent canary index plus the production collaborators wired against it.
     *
     * @param storeProperties   index topology of the canary
     * @param documentService   ingestion path
     * @param rebuilder         the rebuilder under test
     * @param probe             the probe reading the canary
     * @param retrieval         scoped retrieval over the canary
     */
    private record Harness(MedVectorStoreProperties storeProperties,
                           MedDocumentService documentService,
                           MedVectorIndexRebuilder rebuilder,
                           MedVectorIndexProbe probe,
                           MedRetrievalService retrieval) {

        List<String> expectedTagFields() {
            return new MedRagIndexProperties().getExpectedTagFields();
        }
    }

    /**
     * Builds a canary index through the production wiring.
     *
     * @param suffix suffix making the index and key prefix unique to one test
     * @return the wired harness, never {@code null}
     */
    @SuppressWarnings("unchecked")
    private static Harness harness(String suffix) {
        MedVectorStoreProperties storeProperties = new MedVectorStoreProperties();
        storeProperties.setIndexName("med-doc-index-it-" + suffix);
        storeProperties.setPrefix("med:doc-it-" + suffix + ":");

        RedisVectorStore store = VectorStoreConfig.buildVectorStore(
                jedis, new DeterministicEmbeddingModel(), storeProperties, null);
        store.afterPropertiesSet();

        MedDocumentService documentService = new MedDocumentService(store, storeProperties,
                new MedDocumentIngestionProperties(), Clock.systemUTC());

        MedIndexRebuildProperties rebuildProperties = new MedIndexRebuildProperties();
        rebuildProperties.setEnabled(true);
        // The destructive mode is exercised here, so the deployment switch has to be on; the refusal
        // path is pinned by the offline unit tests, which can assert it without touching Redis.
        rebuildProperties.setAllowDocumentDeletion(true);
        rebuildProperties.setBatchSize(2);
        // Do not queue: the only test that needs to lose the race must not spend 5 seconds doing it.
        rebuildProperties.setLockWaitTime(Duration.ZERO);

        // No alert chain in this suite: what is asserted here is what the rebuild does to the index.
        ObjectProvider<MedAlertNotifier> notifierProvider = mock(ObjectProvider.class);

        MedVectorIndexRebuilder rebuilder = new MedVectorIndexRebuilder(store, jedis, storeProperties,
                new MedRagIndexProperties(), rebuildProperties, documentService, notifierProvider,
                redisson, Clock.systemUTC());

        return new Harness(storeProperties, documentService, rebuilder,
                new MedVectorIndexProbe(jedis, storeProperties),
                new MedRetrievalService(store, new MedRetrievalProperties()));
    }

    /**
     * Runs a scoped retrieval and returns the text of every document it found.
     *
     * <p>Assertions are made on the text rather than on the result count because the shipped
     * similarity threshold is {@code 0}, i.e. "accept everything in scope": a count would stay
     * non-zero even if the index had stopped matching the query at all.</p>
     *
     * @param harness the wired canary
     * @param query   the question to ask
     * @param scope   the tags to search within
     * @return the retrieved document texts, never {@code null}
     */
    private static List<String> retrievedTexts(Harness harness, String query, MedDocumentScope scope) {
        return harness.retrieval().search(query, scope).stream().map(Document::getText).toList();
    }

    /** Guards against an accidental no-op: the harness must really have wired a store. */
    @Test
    @DisplayName("the harness wires the production collaborators, not a stub")
    void harnessUsesTheProductionComponents() {
        Harness harness = harness("harness");

        assertThat(harness.probe().probe().exists()).isTrue();
        assertThat(harness.rebuilder().lockKey()).isEqualTo("med:lock:rag:index:rebuild:med-doc-index-it-harness");
        assertThat(harness.retrieval().search("nothing has been ingested yet",
                MedDocumentScope.ofDepartment(TENANT, DEPT))).isEmpty();
    }
}
