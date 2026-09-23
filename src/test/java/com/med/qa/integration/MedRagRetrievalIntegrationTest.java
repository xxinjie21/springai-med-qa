package com.med.qa.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.med.qa.config.VectorStoreConfig;
import com.med.qa.rag.MedDocumentIngestionProperties;
import com.med.qa.rag.MedDocumentRequest;
import com.med.qa.rag.MedDocumentScope;
import com.med.qa.rag.MedDocumentService;
import com.med.qa.rag.MedRagAdvisorFactory;
import com.med.qa.rag.MedRagAdvisorProperties;
import com.med.qa.rag.MedRetrievalFilters;
import com.med.qa.rag.MedRetrievalProperties;
import com.med.qa.rag.MedRetrievalQuery;
import com.med.qa.rag.MedRetrievalService;
import com.med.qa.rag.MedVectorStoreProperties;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

/**
 * End-to-end integration test of the medical RAG retrieval chain, run against a genuine Redis Stack
 * instance (RediSearch + JSON) spun up by Testcontainers.
 *
 * <h2>What is real and what is replaced</h2>
 * <p>Real: the official {@link RedisVectorStore} built by the very same
 * {@link VectorStoreConfig#buildVectorStore} the application context uses, the RediSearch index it
 * creates, the JSON documents written into Redis, the {@code TAG} filters translated by the official
 * store from the project's {@code FilterExpressionBuilder} expressions, {@link MedDocumentService},
 * {@link MedRetrievalService} and the official {@link QuestionAnswerAdvisor} assembled by
 * {@link MedRagAdvisorFactory}. Replaced: only the embedding gateway, by
 * {@link DeterministicEmbeddingModel} — see that class for why it cannot be reached from a test.</p>
 *
 * <h2>The property under test</h2>
 * <p>Every retrieval is narrowed by the {@code tenant_id} / {@code dept_id} / {@code patient_id} tags
 * declared at ingestion time, and by nothing else. Concretely the suite proves that a consultation
 * cannot see another tenant's corpus, cannot see another department's corpus, cannot see another
 * patient's records, and that a department-wide query returns clinical guidelines only — never
 * somebody's chart. No text is ever parsed, tokenized or inspected by production code to reach that
 * conclusion; the decision is made entirely by RediSearch on the tags.</p>
 *
 * <p>This is the coverage D30/D35 could not give: those suites exercise MySQL and the lock, while the
 * RAG layer had only offline mocks, so nothing proved that the filter expressions the project builds
 * are actually honoured by a real index.</p>
 */
@ExtendWith(DockerAvailableCondition.class)
class MedRagRetrievalIntegrationTest {

    private static final String TENANT_A = "tenant-rag-a";
    private static final String TENANT_B = "tenant-rag-b";
    private static final String DEPT_CARDIO = "dept-cardio";
    private static final String DEPT_NEURO = "dept-neuro";
    private static final String DEPT_CLEANUP = "dept-cleanup";
    private static final String PATIENT_ONE = "patient-rag-1";
    private static final String PATIENT_TWO = "patient-rag-2";
    private static final String PATIENT_CLEANUP = "patient-rag-cleanup";

    /** Question used by every retrieval assertion; its tokens drive the fixture ranking. */
    private static final String QUERY = "高血压 首选 药物";

    private static final String DOC_PATIENT_ONE_CARDIO = "doc-rag-p1-cardio";
    private static final String DOC_SHARED_CARDIO = "doc-rag-shared-cardio";
    private static final String DOC_PATIENT_TWO_CARDIO = "doc-rag-p2-cardio";
    private static final String DOC_OTHER_TENANT = "doc-rag-tenant-b";
    private static final String DOC_OTHER_DEPT = "doc-rag-neuro";
    private static final String DOC_CLEANUP_PATIENT = "doc-rag-cleanup-patient";
    private static final String DOC_CLEANUP_SHARED = "doc-rag-cleanup-shared";

    /** Tokens that appear in exactly one fixture document, so their presence is a reliable signal. */
    private static final String TOKEN_PATIENT_ONE_CARDIO = "钙通道阻滞剂";
    private static final String TOKEN_SHARED_CARDIO = "随访";
    private static final String TOKEN_PATIENT_TWO_CARDIO = "二甲双胍";
    private static final String TOKEN_OTHER_TENANT = "血管紧张素";
    private static final String TOKEN_OTHER_DEPT = "硝苯地平";

    private static final int TOP_K = 10;

    /** The retrieval guard rails the application ships with, so the suite tests the real policy. */
    private static MedRetrievalProperties retrievalProperties;

    private static GenericContainer<?> redis;
    private static JedisPooled jedis;
    private static RedisVectorStore vectorStore;
    private static MedDocumentService documentService;
    private static MedRetrievalService retrievalService;
    private static MedRagAdvisorFactory advisorFactory;

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

        MedVectorStoreProperties storeProperties = new MedVectorStoreProperties();
        retrievalProperties = new MedRetrievalProperties();

        // The production wiring, not a test-only shortcut: same builder, same index topology, same
        // TAG metadata fields. afterPropertiesSet() is what the Spring container calls in production,
        // and it issues the FT.CREATE that the assertions below rely on.
        vectorStore = VectorStoreConfig.buildVectorStore(
                jedis, new DeterministicEmbeddingModel(), storeProperties, null);
        vectorStore.afterPropertiesSet();

        documentService = new MedDocumentService(
                vectorStore, storeProperties, new MedDocumentIngestionProperties(), Clock.systemUTC());
        retrievalService = new MedRetrievalService(vectorStore, retrievalProperties);
        advisorFactory = new MedRagAdvisorFactory(
                vectorStore, retrievalService, new MedRagAdvisorProperties());

        ingestFixture();
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
    // The index really indexes the isolation tags as TAG fields
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("RediSearch indexes tenant_id / dept_id / patient_id as TAG fields on a JSON index")
    void indexDeclaresTheIsolationTagsAsTagFields() {
        Set<String> indexes = jedis.ftList();
        assertTrue(indexes.contains("med-doc-index"),
                "the official store must have created its index, found " + indexes);

        // A raw RediSearch TAG query only parses when the field really is a TAG. If a future config
        // change made one of them TEXT, this query would fail or match nothing — and tag-scoped
        // isolation would be silently gone. The values are escaped with the same helper the
        // production filter uses, because the fixture identifiers contain hyphens.
        List<String> keys = rawSearchKeys(
                "@tenant_id:{" + escape(TENANT_A) + "} @dept_id:{" + escape(DEPT_CARDIO) + "} "
                        + "@patient_id:{" + escape(MedDocumentScope.SHARED_PATIENT_TAG) + "}");

        assertEquals(List.of("med:doc:" + DOC_SHARED_CARDIO), keys,
                "only the department-wide cardiology guideline may match that triple of tags");
    }

    // ---------------------------------------------------------------------------------------------
    // Patient-scoped retrieval: own records + department guidelines, never another patient's
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a patient-scoped retrieval returns the patient's own record and the department's guideline only")
    void patientScopedRetrievalReturnsOwnRecordAndSharedGuideline() {
        List<String> ids = searchIds(patientScope(TENANT_A, DEPT_CARDIO, PATIENT_ONE), true);

        assertTrue(ids.contains(DOC_PATIENT_ONE_CARDIO),
                "the patient's own record must be retrievable, got " + ids);
        assertTrue(ids.contains(DOC_SHARED_CARDIO),
                "the department-wide guideline must be retrievable, got " + ids);
        assertFalse(ids.contains(DOC_PATIENT_TWO_CARDIO),
                "another patient's record must never be returned, got " + ids);

        // Ranking still comes from the real index: the record sharing three of the query's tokens
        // beats the guideline sharing one.
        assertEquals(DOC_PATIENT_ONE_CARDIO, ids.get(0),
                "the closest document must come back first, got " + ids);
    }

    @Test
    @DisplayName("excluding shared documents restricts the retrieval to the patient's own record")
    void excludingSharedDocumentsReturnsOnlyThePatientsOwnRecord() {
        List<String> ids = searchIds(patientScope(TENANT_A, DEPT_CARDIO, PATIENT_ONE), false);

        assertEquals(List.of(DOC_PATIENT_ONE_CARDIO), ids,
                "with shared documents excluded only the patient's own record may match");
    }

    @Test
    @DisplayName("a department-wide retrieval returns clinical guidelines only, never a patient record")
    void departmentWideRetrievalReturnsGuidelinesOnly() {
        List<String> ids = searchIds(
                MedDocumentScope.ofDepartment(TENANT_A, DEPT_CARDIO), true);

        assertTrue(ids.contains(DOC_SHARED_CARDIO), "the guideline must be retrievable, got " + ids);
        assertFalse(ids.contains(DOC_PATIENT_ONE_CARDIO),
                "a query without a patient in hand must not return a patient record, got " + ids);
        assertFalse(ids.contains(DOC_PATIENT_TWO_CARDIO),
                "a query without a patient in hand must not return a patient record, got " + ids);
    }

    // ---------------------------------------------------------------------------------------------
    // Tenant and department isolation
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an identical department/patient pair in another tenant stays invisible")
    void tenantIsolationHoldsForIdenticalDepartmentAndPatient() {
        // DOC_OTHER_TENANT carries the same dept_id and patient_id, and is an equally good textual
        // match — only the tenant tag separates it.
        List<String> sameTenant = searchIds(patientScope(TENANT_A, DEPT_CARDIO, PATIENT_ONE), true);
        assertFalse(sameTenant.contains(DOC_OTHER_TENANT),
                "another tenant's document must never be returned, got " + sameTenant);

        List<String> otherTenant = searchIds(patientScope(TENANT_B, DEPT_CARDIO, PATIENT_ONE), true);
        assertTrue(otherTenant.contains(DOC_OTHER_TENANT),
                "its own tenant must see it, got " + otherTenant);
        assertFalse(otherTenant.contains(DOC_PATIENT_ONE_CARDIO),
                "tenant A's document must not leak into tenant B, got " + otherTenant);
    }

    @Test
    @DisplayName("another department of the same tenant stays invisible to a cardiology retrieval")
    void departmentIsolationHoldsWithinTheSameTenant() {
        List<String> cardio = searchIds(patientScope(TENANT_A, DEPT_CARDIO, PATIENT_ONE), true);
        assertFalse(cardio.contains(DOC_OTHER_DEPT),
                "another department's document must never be returned, got " + cardio);

        List<String> neuro = searchIds(patientScope(TENANT_A, DEPT_NEURO, PATIENT_ONE), true);
        assertTrue(neuro.contains(DOC_OTHER_DEPT),
                "its own department must see it, got " + neuro);
        assertFalse(neuro.contains(DOC_PATIENT_ONE_CARDIO),
                "a cardiology record must not leak into neurology, got " + neuro);
    }

    // ---------------------------------------------------------------------------------------------
    // The official QuestionAnswerAdvisor, against the real index
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("QuestionAnswerAdvisor augments the prompt with in-scope evidence only")
    void questionAnswerAdvisorInjectsOnlyInScopeDocuments() {
        QuestionAnswerAdvisor advisor = assertInstanceOf(QuestionAnswerAdvisor.class,
                advisorFactory.createAdvisor(patientScope(TENANT_A, DEPT_CARDIO, PATIENT_ONE)));

        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(new UserMessage(QUERY)))
                .build();
        // before(...) is where the advisor retrieves and stitches the evidence into the prompt; the
        // chain is never consulted there, so a no-op chain is enough and no ChatModel is required.
        ChatClientRequest augmented = advisor.before(request, NOOP_CHAIN);

        List<Document> retrieved = retrievedDocuments(augmented);
        List<String> ids = retrieved.stream().map(Document::getId).toList();
        assertEquals(List.of(DOC_PATIENT_ONE_CARDIO, DOC_SHARED_CARDIO), ids,
                "the advisor must retrieve exactly the in-scope evidence, got " + ids);

        String prompt = promptText(augmented);
        assertTrue(prompt.contains(TOKEN_PATIENT_ONE_CARDIO),
                "the patient's own record must reach the prompt");
        assertTrue(prompt.contains(TOKEN_SHARED_CARDIO),
                "the department guideline must reach the prompt");
        assertTrue(prompt.contains(QUERY), "the question itself must survive augmentation");
        assertFalse(prompt.contains(TOKEN_PATIENT_TWO_CARDIO),
                "another patient's record must never reach the prompt");
        assertFalse(prompt.contains(TOKEN_OTHER_TENANT),
                "another tenant's document must never reach the prompt");
        assertFalse(prompt.contains(TOKEN_OTHER_DEPT),
                "another department's document must never reach the prompt");
    }

    // ---------------------------------------------------------------------------------------------
    // Scope-bounded deletion, against the real index
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("deleteByScope removes exactly the documents of the scope, leaving the guidelines alone")
    void deleteByScopeIsBoundedByTheIsolationTags() {
        MedDocumentScope patient = patientScope(TENANT_A, DEPT_CLEANUP, PATIENT_CLEANUP);

        List<String> before = searchIds(patient, true);
        assertTrue(before.contains(DOC_CLEANUP_PATIENT) && before.contains(DOC_CLEANUP_SHARED),
                "both cleanup fixtures must be indexed before the delete, got " + before);

        documentService.deleteByScope(patient);

        assertTrue(searchIds(patient, false).isEmpty(),
                "the patient's own documents must be gone");
        assertEquals(List.of(DOC_CLEANUP_SHARED), searchIds(patient, true),
                "a patient-scoped delete must leave the department's guidelines untouched");

        documentService.deleteByScope(MedDocumentScope.ofDepartment(TENANT_A, DEPT_CLEANUP));

        assertTrue(searchIds(MedDocumentScope.ofDepartment(TENANT_A, DEPT_CLEANUP), true).isEmpty(),
                "a department-wide delete must remove the guidelines too");
    }

    // ---------------------------------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------------------------------

    private static void ingestFixture() {
        List<MedDocumentRequest> documents = new ArrayList<>();

        documents.add(new MedDocumentRequest(DOC_PATIENT_ONE_CARDIO,
                "高血压 首选 药物 钙通道阻滞剂", patientScope(TENANT_A, DEPT_CARDIO, PATIENT_ONE),
                Map.of("source", "discharge-summary")));
        documents.add(new MedDocumentRequest(DOC_SHARED_CARDIO,
                "科室 指南 高血压 随访 复诊", MedDocumentScope.ofDepartment(TENANT_A, DEPT_CARDIO),
                Map.of("source", "department-guideline")));
        documents.add(new MedDocumentRequest(DOC_PATIENT_TWO_CARDIO,
                "糖尿病 首选 药物 二甲双胍", patientScope(TENANT_A, DEPT_CARDIO, PATIENT_TWO),
                Map.of("source", "discharge-summary")));
        documents.add(new MedDocumentRequest(DOC_OTHER_TENANT,
                "高血压 首选 药物 血管紧张素", patientScope(TENANT_B, DEPT_CARDIO, PATIENT_ONE),
                Map.of("source", "discharge-summary")));
        documents.add(new MedDocumentRequest(DOC_OTHER_DEPT,
                "高血压 首选 药物 硝苯地平", patientScope(TENANT_A, DEPT_NEURO, PATIENT_ONE),
                Map.of("source", "discharge-summary")));
        documents.add(new MedDocumentRequest(DOC_CLEANUP_PATIENT,
                "高血压 首选 药物 复方制剂", patientScope(TENANT_A, DEPT_CLEANUP, PATIENT_CLEANUP),
                Map.of("source", "discharge-summary")));
        documents.add(new MedDocumentRequest(DOC_CLEANUP_SHARED,
                "科室 指南 高血压 复诊 宣教", MedDocumentScope.ofDepartment(TENANT_A, DEPT_CLEANUP),
                Map.of("source", "department-guideline")));

        List<String> indexed = documentService.ingestAll(documents);
        assertEquals(documents.size(), indexed.size(), "every fixture document must be indexed");
    }

    private static MedDocumentScope patientScope(String tenant, String dept, String patient) {
        return MedDocumentScope.ofPatient(tenant, dept, patient);
    }

    /**
     * Escapes a tag value for a raw RediSearch query, using the production helper so the test cannot
     * drift from the escaping the filter expressions actually apply.
     *
     * @param value tag value, must not be {@code null}
     * @return the escaped value, never {@code null}
     */
    private static String escape(String value) {
        return MedRetrievalFilters.escapeTagValue(value);
    }

    private static List<String> searchIds(MedDocumentScope scope, boolean includeSharedDocuments) {
        MedRetrievalQuery query = MedRetrievalQuery.builder(QUERY, scope)
                .topK(TOP_K)
                .includeSharedDocuments(includeSharedDocuments)
                .build();
        return retrievalService.search(query).stream().map(Document::getId).toList();
    }

    /**
     * Runs a raw RediSearch query straight through Jedis, bypassing the store, and returns the
     * document keys in the order the server ranked them.
     *
     * @param query RediSearch query string, must not be {@code null}
     * @return the matched keys, in server order, never {@code null}
     */
    private static List<String> rawSearchKeys(String query) {
        return jedis.ftSearch("med-doc-index", query).getDocuments().stream()
                .map(result -> result.getId())
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private static List<Document> retrievedDocuments(ChatClientRequest request) {
        Object retrieved = request.context().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        assertNotNull(retrieved, "the advisor must publish the retrieved documents in the context");
        return (List<Document>) retrieved;
    }

    private static String promptText(ChatClientRequest request) {
        return request.prompt().getInstructions().stream()
                .map(Message::getText)
                .collect(Collectors.joining(System.lineSeparator()));
    }

    /**
     * A chain that is never followed.
     *
     * <p>{@code QuestionAnswerAdvisor.before} performs the retrieval and the prompt augmentation
     * without consulting the chain, so the suite can drive the advisor's real work while leaving the
     * downstream model call out of the picture. {@link AdvisorChain} declares no abstract method
     * (only a default observation-registry accessor), hence the empty implementation.</p>
     */
    private static final AdvisorChain NOOP_CHAIN = new AdvisorChain() {
    };
}
