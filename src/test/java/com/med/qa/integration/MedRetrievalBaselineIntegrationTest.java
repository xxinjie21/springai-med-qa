package com.med.qa.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.med.qa.config.VectorStoreConfig;
import com.med.qa.rag.MedDocumentIngestionProperties;
import com.med.qa.rag.MedDocumentRequest;
import com.med.qa.rag.MedDocumentScope;
import com.med.qa.rag.MedDocumentService;
import com.med.qa.rag.MedRetrievalBaseline;
import com.med.qa.rag.MedRetrievalBaselineCase;
import com.med.qa.rag.MedRetrievalBaselineCaseResult;
import com.med.qa.rag.MedRetrievalBaselineEvaluator;
import com.med.qa.rag.MedRetrievalBaselineLoader;
import com.med.qa.rag.MedRetrievalBaselineReport;
import com.med.qa.rag.MedRetrievalFilters;
import com.med.qa.rag.MedRetrievalProperties;
import com.med.qa.rag.MedRetrievalQuery;
import com.med.qa.rag.MedRetrievalService;
import com.med.qa.rag.MedVectorStoreProperties;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

/**
 * Runs the frozen retrieval-quality golden set ({@code rag/retrieval-baseline.json}) against a
 * genuine Redis Stack instance, through the production retrieval path.
 *
 * <h2>Why this suite exists next to {@code MedRagRetrievalIntegrationTest}</h2>
 * <p>D36 proved that tag-scoped retrieval <em>can</em> work: it asserted on hand-picked identifiers
 * and on properties of a single query. That is a proof of correctness, not a regression test — it
 * cannot tell whether today's corpus still produces today's answers. This suite freezes the corpus
 * and the questions and asserts the whole outcome at once, so any change to the index topology, the
 * metadata written at ingestion time or the filter expressions that makes retrieval worse fails the
 * build with a report naming the case, the missing documents and the leaked ones.</p>
 *
 * <h2>What is real and what is replaced</h2>
 * <p>Real: the official {@link RedisVectorStore} built by the same
 * {@link VectorStoreConfig#buildVectorStore} the application uses, the RediSearch index it creates,
 * the JSON documents written into Redis, the {@code TAG} filters, {@link MedDocumentService},
 * {@link MedRetrievalService} and the baseline loader. Replaced: only the embedding gateway, by
 * {@link DeterministicEmbeddingModel} — the production model is an HTTP client for an external
 * gateway that a repeatable test cannot reach. Because that double is deterministic, the ranking the
 * golden set pins is reproducible on every machine.</p>
 *
 * <h2>The golden set is checked against the corpus before it is run</h2>
 * <p>A baseline whose identifiers no longer exist in the corpus would look like a catastrophic
 * retrieval regression rather than like a stale fixture. The suite therefore asserts, using the
 * production {@link MedRetrievalFilters#matches} predicate, that every expected document really is
 * inside the case's scope and every forbidden one really is outside it. That check is what keeps the
 * two files honest as the fixture evolves.</p>
 */
@ExtendWith(DockerAvailableCondition.class)
class MedRetrievalBaselineIntegrationTest {

    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";
    private static final String DEPT_CARDIO = "dept-cardio";
    private static final String DEPT_NEURO = "dept-neuro";
    private static final String PATIENT_ONE = "patient-1";
    private static final String PATIENT_TWO = "patient-2";

    private static final String DOC_GUIDE_HYPERTENSION = "doc-baseline-guide-hypertension";
    private static final String DOC_RECORD_PATIENT_ONE = "doc-baseline-record-patient-1";
    private static final String DOC_RECORD_PATIENT_TWO = "doc-baseline-record-patient-2";
    private static final String DOC_GUIDE_DIABETES = "doc-baseline-guide-diabetes";
    private static final String DOC_GUIDE_STROKE = "doc-baseline-guide-stroke";
    private static final String DOC_GUIDE_HYPERTENSION_B = "doc-baseline-guide-hypertension-b";

    private static final String BASELINE_RESOURCE = "rag/retrieval-baseline.json";

    /** Negative case of the golden set: a scope the corpus holds nothing for. */
    private static final String NEGATIVE_CASE = "unknown-scope-returns-nothing";

    /**
     * Vector width of the deterministic double.
     *
     * <p>Wider than the double's own default: the golden set pins an order of relevance, and a narrow
     * vector makes accidental hash collisions between unrelated clinical terms more likely to shuffle
     * that order. 256 components keep the fixture's ranking a property of its shared tokens.</p>
     */
    private static final int DIMENSIONS = 256;

    private static GenericContainer<?> redis;
    private static JedisPooled jedis;
    private static RedisVectorStore vectorStore;
    private static MedDocumentService documentService;
    private static MedRetrievalService retrievalService;
    private static MedRetrievalBaselineEvaluator evaluator;
    private static MedRetrievalBaseline baseline;

    /** The frozen corpus, keyed by document identifier. */
    private static Map<String, CorpusDocument> corpus;

    @BeforeAll
    static void startInfrastructure() throws IOException {
        IntegrationTestRequirements.verifyDockerAvailableForCurrentRun(DockerAvailabilityProbe.testcontainers());

        redis = new GenericContainer<>(DockerImageName.parse(MedIntegrationImages.REDIS_STACK))
                .withExposedPorts(6379);
        redis.start();

        jedis = new JedisPooled(new HostAndPort(redis.getHost(), redis.getMappedPort(6379)));

        MedVectorStoreProperties storeProperties = new MedVectorStoreProperties();
        // Production wiring: same builder, same index topology, same TAG metadata fields.
        vectorStore = VectorStoreConfig.buildVectorStore(jedis,
                new DeterministicEmbeddingModel(DIMENSIONS), storeProperties, null);
        vectorStore.afterPropertiesSet();

        documentService = new MedDocumentService(vectorStore, storeProperties,
                new MedDocumentIngestionProperties(), Clock.systemUTC());
        retrievalService = new MedRetrievalService(vectorStore, new MedRetrievalProperties());
        evaluator = new MedRetrievalBaselineEvaluator(retrievalService);

        corpus = corpus();
        List<MedDocumentRequest> requests = new ArrayList<>(corpus.size());
        for (CorpusDocument document : corpus.values()) {
            requests.add(new MedDocumentRequest(document.id(), document.text(), document.scope(),
                    Map.of("source", document.source())));
        }
        List<String> indexed = documentService.ingestAll(requests);
        assertEquals(requests.size(), indexed.size(), "the whole frozen corpus must be indexed");

        baseline = new MedRetrievalBaselineLoader().load(new ClassPathResource(BASELINE_RESOURCE));
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
    // The golden set still describes the corpus it was written for
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("every identifier of the golden set exists in the corpus and sits on the side of the scope it claims")
    void theGoldenSetMatchesTheFrozenCorpus() {
        List<String> referenced = baseline.cases().stream()
                .flatMap(baselineCase -> Stream.concat(
                        baselineCase.expectedDocumentIds().stream(),
                        baselineCase.forbiddenDocumentIds().stream()))
                .distinct()
                .toList();

        assertThat(referenced).as("the golden set references a document the corpus does not hold")
                .allSatisfy(id -> assertThat(corpus).containsKey(id));

        for (MedRetrievalBaselineCase baselineCase : baseline.cases()) {
            for (String expected : baselineCase.expectedDocumentIds()) {
                assertThat(inScope(baselineCase, expected))
                        .as("case '%s' expects '%s', which its own scope cannot reach",
                                baselineCase.name(), expected)
                        .isTrue();
            }
            for (String forbidden : baselineCase.forbiddenDocumentIds()) {
                assertThat(inScope(baselineCase, forbidden))
                        .as("case '%s' forbids '%s', which its own scope would return",
                                baselineCase.name(), forbidden)
                        .isFalse();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The assertion CI runs
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("every case of the golden set passes against the real index")
    void everyGoldenSetCasePassesAgainstTheRealIndex() {
        MedRetrievalBaselineReport report = evaluator.evaluate(baseline);

        // The failure summary carries the whole diagnosis, so it is the assertion message.
        assertTrue(report.passed(), () -> report.failureSummary());
        assertThat(report.caseCount()).isEqualTo(baseline.caseCount());
        assertThat(report.failedCases()).isEmpty();
        assertThat(report.meanRecall()).isEqualTo(1.0d);
        // Every case pins its best-ranked document, and all of them are ranked first by the real index.
        assertThat(report.meanReciprocalRank()).isEqualTo(1.0d);
        assertThat(report.caseResults()).allSatisfy(result ->
                assertThat(result.forbiddenHitDocumentIds()).isEmpty());
    }

    @Test
    @DisplayName("the negative case really returns nothing from the real index")
    void theNegativeCaseIsGenuinelyEmpty() {
        MedRetrievalBaselineCase negativeCase = baseline.findCase(NEGATIVE_CASE).orElseThrow();

        assertThat(evaluator.retrieve(negativeCase)).isEmpty();
        assertThat(evaluator.evaluateCase(negativeCase).passed()).isTrue();
    }

    // ---------------------------------------------------------------------------------------------
    // The baseline has teeth
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a retrieval that ignores the case scope breaks the baseline, so isolation is really asserted")
    void aRetrievalThatIgnoresTheScopeBreaksTheBaseline() {
        // The D36 defect in one line: instead of honouring each case's own tags, ask the index for a
        // neighbouring patient's scope every time. If the golden set did not assert isolation, this
        // would still pass.
        MedRetrievalBaselineReport report = evaluator.evaluate(baseline, entry -> idsOf(
                retrievalService.search(MedRetrievalQuery.builder(entry.query(), patientScope(PATIENT_TWO))
                        .topK(entry.topK())
                        .includeSharedDocuments(entry.includeSharedDocuments())
                        .build())));

        assertThat(report.passed()).isFalse();
        assertThat(report.failedCases()).isNotEmpty();
        assertThat(report.failureSummary()).contains("out-of-scope");
    }

    @Test
    @DisplayName("a retrieval that returns nothing breaks the baseline, so recall is really asserted")
    void anEmptyRetrievalBreaksTheBaseline() {
        MedRetrievalBaselineReport report = evaluator.evaluate(baseline, entry -> List.of());

        assertThat(report.passed()).isFalse();
        assertThat(report.failedCount()).isEqualTo(baseline.cases().stream()
                .filter(MedRetrievalBaselineCase::hasExpectations).count());
        assertThat(report.failureSummary()).contains("recall 0.0");
    }

    // ---------------------------------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------------------------------

    /**
     * One document of the frozen corpus.
     *
     * @param id     identifier written to the index, must not be blank
     * @param scope  isolation tags of the document, must not be {@code null}
     * @param text   document text, indexed verbatim, must not be blank
     * @param source caller metadata attribute, used to prove that non-reserved metadata survives
     */
    private record CorpusDocument(String id, MedDocumentScope scope, String text, String source) {
    }

    /**
     * Builds the frozen corpus the golden set was written against.
     *
     * <p>Each document carries one distinctive clinical term that appears nowhere else, so the golden
     * set can pin a ranking without depending on the exact scores a model produces.</p>
     *
     * @return the corpus keyed by document identifier, in a stable order, never {@code null}
     */
    private static Map<String, CorpusDocument> corpus() {
        Map<String, CorpusDocument> documents = new LinkedHashMap<>();
        put(documents, DOC_GUIDE_HYPERTENSION, departmentScope(TENANT_A, DEPT_CARDIO),
                "高血压 诊疗 指南 首选 药物 钙通道阻滞剂 随访 复诊", "department-guideline");
        put(documents, DOC_RECORD_PATIENT_ONE, patientScope(PATIENT_ONE),
                "患者 高血压 用药 记录 钙通道阻滞剂 依从性", "discharge-summary");
        put(documents, DOC_RECORD_PATIENT_TWO, patientScope(PATIENT_TWO),
                "患者 糖尿病 用药 记录 二甲双胍 血糖", "discharge-summary");
        put(documents, DOC_GUIDE_DIABETES, departmentScope(TENANT_A, DEPT_CARDIO),
                "糖尿病 诊疗 指南 首选 药物 二甲双胍 随访 复诊", "department-guideline");
        put(documents, DOC_GUIDE_STROKE, departmentScope(TENANT_A, DEPT_NEURO),
                "脑卒中 诊疗 指南 首选 药物 阿司匹林 随访", "department-guideline");
        put(documents, DOC_GUIDE_HYPERTENSION_B, departmentScope(TENANT_B, DEPT_CARDIO),
                "高血压 诊疗 指南 首选 药物 血管紧张素 随访 复诊", "department-guideline");
        return documents;
    }

    private static void put(Map<String, CorpusDocument> documents, String id, MedDocumentScope scope,
                            String text, String source) {
        documents.put(id, new CorpusDocument(id, scope, text, source));
    }

    private static MedDocumentScope patientScope(String patientId) {
        return MedDocumentScope.ofPatient(TENANT_A, DEPT_CARDIO, patientId);
    }

    private static MedDocumentScope departmentScope(String tenantId, String deptId) {
        return MedDocumentScope.ofDepartment(tenantId, deptId);
    }

    /**
     * Tells whether the case's own scope reaches a corpus document, using the production predicate.
     *
     * @param baselineCase case to check
     * @param documentId   identifier of the corpus document
     * @return {@code true} when the document is inside the case's scope
     */
    private static boolean inScope(MedRetrievalBaselineCase baselineCase, String documentId) {
        CorpusDocument document = corpus.get(documentId);
        return MedRetrievalFilters.matches(document.scope().toMetadata(), baselineCase.scope(),
                baselineCase.includeSharedDocuments());
    }

    /**
     * Collects the identifiers of a result list.
     *
     * @param documents documents returned by the store, must not be {@code null}
     * @return the identifiers in the order the store ranked them, never {@code null}
     */
    private static List<String> idsOf(List<Document> documents) {
        return documents.stream().map(Document::getId).collect(Collectors.toList());
    }
}
