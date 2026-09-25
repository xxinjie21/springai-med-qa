package com.med.qa.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.med.qa.actuator.MedVectorIndexHealthIndicator;
import com.med.qa.config.VectorStoreConfig;
import com.med.qa.rag.MedDocumentIngestionProperties;
import com.med.qa.rag.MedDocumentRequest;
import com.med.qa.rag.MedDocumentScope;
import com.med.qa.rag.MedDocumentService;
import com.med.qa.rag.MedRagIndexProperties;
import com.med.qa.rag.MedVectorIndexProbe;
import com.med.qa.rag.MedVectorIndexReport;
import com.med.qa.rag.MedVectorStoreProperties;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

/**
 * End-to-end integration test of the RAG vector-index probe, run against a genuine Redis Stack
 * instance (RediSearch + JSON) spun up by Testcontainers.
 *
 * <h2>Why this suite exists</h2>
 * <p>The D37 probe reads {@code FT.LIST} and {@code FT.INFO} through the official Jedis client. Both
 * replies are structural, and neither is obvious: {@code FT.INFO} returns nested structures that Jedis
 * 5.x surfaces as flat alternating key/value lists rather than maps, and the TAG alias an attribute is
 * addressed by only appears under a specific sub-key. An offline test with a hand-written reply proves
 * the decoder matches the shape the author believed the client produces — not the shape it really
 * produces. If those two ever diverge, every index reads as "zero TAG fields", which is
 * indistinguishable from the schema drift the probe is supposed to detect.</p>
 *
 * <p>So this suite drives the production path — {@link VectorStoreConfig#buildVectorStore} creating the
 * index, {@link MedDocumentService} writing into it — and then asserts what
 * {@link MedVectorIndexProbe} and {@link MedVectorIndexHealthIndicator} report about the index that
 * <em>actually</em> exists. It then removes the index and asserts the verdict flips to
 * {@code index-missing} rather than to an exception, which is the whole point of the probe.</p>
 */
@ExtendWith(DockerAvailableCondition.class)
class MedVectorIndexProbeIntegrationTest {

    private static final String TENANT = "tenant-index-a";
    private static final String DEPT = "dept-index-cardio";
    private static final String PATIENT = "patient-index-1";

    private static final String DOC_ONE = "doc-index-1";
    private static final String DOC_TWO = "doc-index-2";

    private static GenericContainer<?> redis;
    private static JedisPooled jedis;
    private static RedisVectorStore vectorStore;
    private static MedDocumentService documentService;
    private static MedVectorStoreProperties storeProperties;
    private static MedRagIndexProperties indexProperties;
    private static MedVectorIndexProbe probe;
    private static MedVectorIndexHealthIndicator healthIndicator;

    @BeforeAll
    static void startInfrastructure() {
        // D35: when the run declares Docker mandatory (med.test.integration.required / CI), a missing
        // daemon must fail here with a message that names the switch, instead of the suite reporting
        // itself skipped. DockerAvailableCondition deliberately leaves the class enabled in that case.
        IntegrationTestRequirements.verifyDockerAvailableForCurrentRun(DockerAvailabilityProbe.testcontainers());

        redis = new GenericContainer<>(DockerImageName.parse(MedIntegrationImages.REDIS_STACK))
                .withExposedPorts(6379);
        redis.start();

        jedis = new JedisPooled(new HostAndPort(redis.getHost(), redis.getMappedPort(6379)));

        storeProperties = new MedVectorStoreProperties();
        indexProperties = new MedRagIndexProperties();

        // The production wiring, not a test-only shortcut: the same builder the application context
        // uses, and the same afterPropertiesSet() the container calls, which is what issues the
        // FT.CREATE the probe is about to read.
        vectorStore = VectorStoreConfig.buildVectorStore(
                jedis, new DeterministicEmbeddingModel(), storeProperties, null);
        vectorStore.afterPropertiesSet();

        documentService = new MedDocumentService(
                vectorStore, storeProperties, new MedDocumentIngestionProperties(), Clock.systemUTC());
        probe = new MedVectorIndexProbe(jedis, storeProperties);
        healthIndicator = new MedVectorIndexHealthIndicator(probe, indexProperties);
    }

    @AfterAll
    static void stopInfrastructure() {
        if (jedis != null) {
            jedis.close();
        }
        if (redis != null) {
            redis.stop();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The probe reads the index the official store really created
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the probe reports the index the official store created, with its real TAG schema")
    void probeReadsTheIndexTheStoreCreated() {
        MedVectorIndexReport report = probe.probe();

        assertTrue(report.exists(), "the official store must have created its index");
        assertEquals(storeProperties.getIndexName(), report.indexName());
        assertEquals(Set.of(storeProperties.getPrefix()), report.prefixes(),
                "the probe must read the configured key prefix back out of the index definition");
        assertTrue(report.tagFields().containsAll(indexProperties.getExpectedTagFields()),
                "the index must declare the isolation tags as TAG fields, found " + report.tagFields());
        assertTrue(report.isScopedRetrievalReady(indexProperties.getExpectedTagFields()),
                "scoped retrieval must be reported as ready on a freshly created index");
    }

    @Test
    @DisplayName("the probe does not mistake the TEXT and VECTOR attributes for TAG fields")
    void probeSeparatesTagFieldsFromTheOtherAttributes() {
        MedVectorIndexReport report = probe.probe();

        assertFalse(report.hasTagField(storeProperties.getContentFieldName()),
                "the content field is indexed as TEXT, not as a TAG");
        assertFalse(report.hasTagField(storeProperties.getEmbeddingFieldName()),
                "the embedding field is indexed as VECTOR, not as a TAG");
    }

    @Test
    @DisplayName("the health indicator reports UP for the freshly created index")
    void healthIndicatorReportsUpForAReadyIndex() {
        Health health = healthIndicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(storeProperties.getIndexName(),
                health.getDetails().get(MedVectorIndexHealthIndicator.INDEX_DETAIL));
        assertEquals(Boolean.TRUE, health.getDetails().get(MedVectorIndexHealthIndicator.EXISTS_DETAIL));
        assertNotNull(health.getDetails().get(MedVectorIndexHealthIndicator.DOCUMENTS_DETAIL));
    }

    // ---------------------------------------------------------------------------------------------
    // The document count tracks what ingestion really wrote
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the document count reported by the probe tracks what ingestion actually wrote")
    void documentCountTracksIngestion() {
        long before = probe.probe().documentCount();

        documentService.ingestAll(List.of(
                new MedDocumentRequest(DOC_ONE, "高血压 首选 药物 钙通道阻滞剂",
                        MedDocumentScope.ofPatient(TENANT, DEPT, PATIENT),
                        Map.of("source", "discharge-summary")),
                new MedDocumentRequest(DOC_TWO, "科室 指南 高血压 随访",
                        MedDocumentScope.ofDepartment(TENANT, DEPT),
                        Map.of("source", "department-guideline"))));

        long after = probe.probe().documentCount();

        assertEquals(before + 2, after,
                "the probe must report the documents the store really indexed");
    }

    // ---------------------------------------------------------------------------------------------
    // The failure the probe exists for: a reachable Redis with no usable index
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an index that is dropped after creation is reported as index-missing, not as an exception")
    void droppedIndexIsReportedAsMissing() {
        // A canary index rather than the shared one: dropping the shared index would make this class
        // order-dependent, and a suite whose result depends on method order is a suite that will
        // eventually fail for the wrong reason. The canary is created by the very same production
        // wiring, so what is exercised is identical.
        MedVectorStoreProperties canaryProperties = new MedVectorStoreProperties();
        canaryProperties.setIndexName("med-doc-index-canary");
        canaryProperties.setPrefix("med:doc-canary:");

        RedisVectorStore canaryStore = VectorStoreConfig.buildVectorStore(
                jedis, new DeterministicEmbeddingModel(), canaryProperties, null);
        canaryStore.afterPropertiesSet();

        MedVectorIndexProbe canaryProbe = new MedVectorIndexProbe(jedis, canaryProperties);
        MedVectorIndexHealthIndicator canaryIndicator =
                new MedVectorIndexHealthIndicator(canaryProbe, indexProperties);

        assertTrue(canaryProbe.probe().exists(), "the canary index must exist before the drop");
        assertEquals(Status.UP, canaryIndicator.health().getStatus());

        jedis.ftDropIndexDD(canaryProperties.getIndexName());

        MedVectorIndexReport report = canaryProbe.probe();
        assertFalse(report.exists(), "the dropped index must no longer be reported");
        assertEquals(0L, report.documentCount());
        assertFalse(report.isScopedRetrievalReady(indexProperties.getExpectedTagFields()));

        Health health = canaryIndicator.health();
        assertEquals(Status.DOWN, health.getStatus(),
                "a missing index must take the health component down");
        assertEquals(MedVectorIndexHealthIndicator.REASON_INDEX_MISSING,
                health.getDetails().get(MedVectorIndexHealthIndicator.REASON_DETAIL));
    }

    @Test
    @DisplayName("an index created with a different TAG schema is reported as schema-drift")
    void driftedIndexIsReportedAsSchemaDrift() {
        // The failure that leaves a service answering HTTP 200 while retrieving less: the index exists,
        // but the TAG field the filter expression addresses is not there. Reproduced by dropping one
        // isolation tag from the expectation, which is exactly what a configuration change does.
        MedRagIndexProperties stricter = new MedRagIndexProperties();
        stricter.setExpectedTagFields(List.of("tenant_id", "dept_id", "patient_id", "encounter_id"));

        MedVectorIndexHealthIndicator strictIndicator =
                new MedVectorIndexHealthIndicator(probe, stricter);

        Health health = strictIndicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals(MedVectorIndexHealthIndicator.REASON_SCHEMA_DRIFT,
                health.getDetails().get(MedVectorIndexHealthIndicator.REASON_DETAIL));
        assertEquals(List.of("encounter_id"),
                health.getDetails().get(MedVectorIndexHealthIndicator.MISSING_TAG_FIELDS_DETAIL));
    }

    // ---------------------------------------------------------------------------------------------
    // A reachable Redis is not the same question as a reachable RediSearch
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a Redis that cannot be reached makes the probe throw, so it is not read as a missing index")
    void unreachableRedisPropagates() {
        // Port 1 on the container host is not listening, so the connection fails immediately.
        try (JedisPooled unreachable = new JedisPooled(new HostAndPort(redis.getHost(), 1))) {
            MedVectorIndexProbe unreachableProbe = new MedVectorIndexProbe(unreachable, storeProperties);

            assertThrows(RuntimeException.class, unreachableProbe::probe,
                    "an unreachable Redis must not be silently reported as a missing index");
        }
    }
}
