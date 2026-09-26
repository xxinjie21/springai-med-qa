package com.med.qa.rag;

import com.med.qa.alert.MedAlertNotifier;
import com.med.qa.alert.MedAlertSeverity;
import com.med.qa.rag.MedIndexRebuildReport.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisConnectionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MedVectorIndexRebuilder}.
 *
 * <p>Every middleware dependency is a mock, so what is asserted is the <em>orchestration</em>: which
 * official command each mode issues, that a destructive request cannot run without the deployment's
 * second permission, that a held mutex does nothing at all, and that every failure shape ends as a
 * report plus an alert rather than as an exception. The real RediSearch behaviour behind those
 * commands — in particular that dropping an index without {@code DD} and recreating it really does
 * re-index the existing documents — is verified against a live Redis Stack by
 * {@code MedIndexRebuildIntegrationTest}; a mock cannot prove it.</p>
 */
class MedVectorIndexRebuilderTest {

    private static final String INDEX = "med-doc-index";
    private static final String OTHER_INDEX = "med-doc-index-alt";
    private static final List<String> TAG_FIELDS = List.of("tenant_id", "dept_id", "patient_id");

    private RedisVectorStore vectorStore;
    private JedisPooled jedis;
    private RLock lock;
    private RedissonClient redisson;
    private MedAlertNotifier notifier;
    private ObjectProvider<MedAlertNotifier> notifierProvider;
    private MedDocumentService documentService;
    private MedIndexRebuildProperties rebuildProperties;
    private MedVectorIndexRebuilder rebuilder;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        vectorStore = mock(RedisVectorStore.class);
        jedis = mock(JedisPooled.class);
        lock = mock(RLock.class);
        redisson = mock(RedissonClient.class);
        notifier = mock(MedAlertNotifier.class);
        notifierProvider = mock(ObjectProvider.class);
        documentService = mock(MedDocumentService.class);
        rebuildProperties = new MedIndexRebuildProperties();
        rebuildProperties.setEnabled(true);

        when(redisson.getLock(anyString())).thenReturn(lock);
        when(notifierProvider.getIfAvailable()).thenReturn(notifier);

        rebuilder = newRebuilder(new MedVectorStoreProperties(), rebuildProperties, TAG_FIELDS);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private MedVectorIndexRebuilder newRebuilder(MedVectorStoreProperties storeProperties,
                                                 MedIndexRebuildProperties properties,
                                                 List<String> expectedTagFields) {
        MedRagIndexProperties indexProperties = new MedRagIndexProperties();
        indexProperties.setExpectedTagFields(expectedTagFields);
        return new MedVectorIndexRebuilder(vectorStore, jedis, storeProperties, indexProperties,
                properties, documentService, notifierProvider, redisson, fixedClock());
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-09-26T12:00:00Z"), ZoneOffset.UTC);
    }

    private static MedDocumentRequest document(String id) {
        return new MedDocumentRequest(id, "高血压 首选 药物",
                MedDocumentScope.ofDepartment("tenant-a", "dept-cardio"), Map.of());
    }

    private static List<MedDocumentRequest> documents(int count) {
        List<MedDocumentRequest> documents = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            documents.add(document("doc-" + index));
        }
        return documents;
    }

    /**
     * Builds an {@code FT.INFO} reply in the shape Jedis 5.x really returns: nested structures as
     * flat alternating key/value lists rather than maps.
     */
    private static Map<String, Object> ftInfo(String indexName, long documents,
                                             Collection<String> tagFields) {
        List<Object> attributes = new ArrayList<>();
        attributes.add(List.of("identifier", "$.content", "attribute", "content", "type", "TEXT"));
        attributes.add(List.of("identifier", "$.embedding", "attribute", "embedding", "type", "VECTOR"));
        for (String field : tagFields) {
            attributes.add(List.of("identifier", "$." + field, "attribute", field, "type", "TAG"));
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("index_name", indexName);
        info.put("num_docs", documents);
        info.put("index_definition",
                List.of("key_type", "JSON", "prefixes", List.of("med:doc:"), "default_score", 1));
        info.put("attributes", attributes);
        return info;
    }

    private void indexExistsWith(long documents, Collection<String> tagFields) {
        when(jedis.ftList()).thenReturn(Set.of(INDEX));
        when(jedis.ftInfo(INDEX)).thenReturn(ftInfo(INDEX, documents, tagFields));
    }

    /**
     * Stubs both forms of {@code RLock#tryLock} as successful.
     *
     * <p>Both are stubbed because which one the rebuilder calls depends on the configured lease, and
     * a test that only stubbed the form it expected would fail with a misleading "skipped" outcome if
     * the other one was used.</p>
     */
    private void mutexIsFree() {
        try {
            when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
            when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
            // The rebuilder only unlocks a mutex it still owns, which is what keeps a rebuild whose
            // lease already expired from releasing somebody else's lock.
            when(lock.isHeldByCurrentThread()).thenReturn(true);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("stubbing RLock#tryLock was interrupted", ex);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The safe mode: repair the schema without touching a single document
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("INDEX_ONLY drops the index without DD, recreates it and keeps every document")
    void indexOnlyRebuildKeepsTheDocuments() {
        mutexIsFree();
        indexExistsWith(4, TAG_FIELDS);

        MedIndexRebuildReport report = rebuilder.rebuild(MedIndexRebuildRequest.indexOnly("ops-oncall"));

        assertThat(report.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(report.isSuccess()).isTrue();
        assertThat(report.indexDropped()).isTrue();
        assertThat(report.documentsBefore()).isEqualTo(4);
        assertThat(report.documentsAfter()).isEqualTo(4);
        assertThat(report.documentsIngested()).isZero();
        assertThat(report.documentCountChanged()).isFalse();

        // The whole point of the mode: FT.DROPINDEX, never FT.DROPINDEX ... DD.
        verify(jedis).ftDropIndex(INDEX);
        verify(jedis, never()).ftDropIndexDD(anyString());
        // The schema is recreated by the official store's own lifecycle hook, not by hand.
        verify(vectorStore).afterPropertiesSet();
        verify(documentService, never()).ingestAll(anyList());
        verify(lock).unlock();
        verify(notifier).raise(eq(MedAlertSeverity.INFO), eq(MedVectorIndexRebuilder.REBUILD_COMPLETED),
                eq("vector-index"), anyString());
    }

    @Test
    @DisplayName("a rebuild of an index that does not exist yet creates it instead of dropping nothing")
    void missingIndexIsCreated() {
        mutexIsFree();
        // Absent before the rebuild, present after: this is a cold start or a manually dropped index.
        when(jedis.ftList()).thenReturn(Set.of(), Set.of(INDEX));
        when(jedis.ftInfo(INDEX)).thenReturn(ftInfo(INDEX, 0, TAG_FIELDS));

        MedIndexRebuildReport report = rebuilder.rebuild(MedIndexRebuildRequest.indexOnly("ops-oncall"));

        assertThat(report.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(report.indexDropped()).isFalse();
        verify(jedis, never()).ftDropIndex(anyString());
        verify(jedis, never()).ftDropIndexDD(anyString());
        verify(vectorStore).afterPropertiesSet();
    }

    @Test
    @DisplayName("progress is published, ends in a terminal stage and is readable afterwards")
    void progressIsReported() {
        mutexIsFree();
        indexExistsWith(2, TAG_FIELDS);

        MedIndexRebuildReport report = rebuilder.rebuild(MedIndexRebuildRequest.indexOnly("ops-oncall"));

        assertThat(rebuilder.currentProgress()).isNotNull();
        assertThat(rebuilder.currentProgress().stage()).isEqualTo(MedIndexRebuildProgress.Stage.COMPLETED);
        assertThat(rebuilder.currentProgress().stage().isTerminal()).isTrue();
        assertThat(rebuilder.isRebuilding()).isFalse();
        assertThat(rebuilder.rebuildCount()).isEqualTo(1);
        assertThat(rebuilder.lastReport()).isSameAs(report);
    }

    // ---------------------------------------------------------------------------------------------
    // The destructive mode: gated twice, then batched
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("DROP_AND_REINGEST drops the documents too and writes the corpus back in batches")
    void destructiveRebuildWritesTheCorpusBackInBatches() {
        mutexIsFree();
        rebuildProperties.setAllowDocumentDeletion(true);
        rebuildProperties.setBatchSize(2);
        indexExistsWith(7, TAG_FIELDS);
        rebuilder = newRebuilder(new MedVectorStoreProperties(), rebuildProperties, TAG_FIELDS);

        MedIndexRebuildReport report = rebuilder.rebuild(
                MedIndexRebuildRequest.dropAndReingest(documents(5), "ops-oncall"));

        assertThat(report.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(report.documentsIngested()).isEqualTo(5);
        assertThat(report.batchesCompleted()).isEqualTo(3);
        verify(jedis).ftDropIndexDD(INDEX);
        verify(jedis, never()).ftDropIndex(anyString());

        ArgumentCaptor<List<MedDocumentRequest>> batches = ArgumentCaptor.forClass(List.class);
        verify(documentService, times(3)).ingestAll(batches.capture());
        assertThat(batches.getAllValues().stream().map(List::size).toList())
                .as("a corpus of 5 with batch size 2 must be written as 2 + 2 + 1")
                .containsExactly(2, 2, 1);
        assertThat(rebuilder.currentProgress().percentComplete()).isEqualTo(100);
    }

    @Test
    @DisplayName("boundary: a destructive rebuild is refused unless the deployment authorises it")
    void destructiveRebuildIsRefusedWithoutTheDeploymentSwitch() {
        mutexIsFree();
        rebuildProperties.setAllowDocumentDeletion(false);
        rebuilder = newRebuilder(new MedVectorStoreProperties(), rebuildProperties, TAG_FIELDS);

        MedIndexRebuildReport report = rebuilder.rebuild(
                MedIndexRebuildRequest.dropAndReingest(documents(1), "ops-oncall"));

        assertThat(report.outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(report.message()).contains("allow-document-deletion");
        // The refusal has to happen before Redis is contacted: a rejected request must not be able to
        // drop anything, not even for a moment.
        verifyNoInteractions(jedis);
        verifyNoInteractions(vectorStore);
        verify(redisson, never()).getLock(anyString());
        verify(notifier).raise(eq(MedAlertSeverity.WARNING), eq(MedVectorIndexRebuilder.REBUILD_SKIPPED),
                eq("vector-index"), anyString());
        assertThat(rebuilder.currentProgress().stage()).isEqualTo(MedIndexRebuildProgress.Stage.SKIPPED);
    }

    @Test
    @DisplayName("boundary: a corpus larger than the configured maximum is refused before touching Redis")
    void oversizedCorpusIsRefused() {
        mutexIsFree();
        rebuildProperties.setAllowDocumentDeletion(true);
        rebuildProperties.setMaxDocuments(2);
        rebuilder = newRebuilder(new MedVectorStoreProperties(), rebuildProperties, TAG_FIELDS);

        MedIndexRebuildReport report = rebuilder.rebuild(
                MedIndexRebuildRequest.dropAndReingest(documents(3), "ops-oncall"));

        assertThat(report.outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(report.message()).contains("max-documents");
        verifyNoInteractions(jedis);
        verifyNoInteractions(documentService);
    }

    // ---------------------------------------------------------------------------------------------
    // Mutual exclusion
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a mutex held elsewhere makes the rebuild do nothing at all")
    void heldMutexSkipsTheRebuild() throws Exception {
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        MedIndexRebuildReport report = rebuilder.rebuild(MedIndexRebuildRequest.indexOnly("ops-oncall"));

        assertThat(report.outcome()).isEqualTo(Outcome.SKIPPED_LOCK_HELD);
        assertThat(report.message()).contains("med:lock:rag:index:rebuild:" + INDEX);
        verifyNoInteractions(jedis);
        verifyNoInteractions(vectorStore);
        // Nothing was acquired, so nothing may be released.
        verify(lock, never()).unlock();
        verify(notifier).raise(eq(MedAlertSeverity.WARNING), eq(MedVectorIndexRebuilder.REBUILD_SKIPPED),
                eq("vector-index"), anyString());
        assertThat(rebuilder.currentProgress().stage()).isEqualTo(MedIndexRebuildProgress.Stage.SKIPPED);
    }

    @Test
    @DisplayName("the mutex key is namespaced per index, so two indexes do not serialize each other")
    void mutexKeyIsNamespacedPerIndex() {
        MedVectorStoreProperties other = new MedVectorStoreProperties();
        other.setIndexName(OTHER_INDEX);

        MedVectorIndexRebuilder otherRebuilder =
                newRebuilder(other, rebuildProperties, TAG_FIELDS);

        assertThat(rebuilder.lockKey()).isEqualTo("med:lock:rag:index:rebuild:" + INDEX);
        assertThat(otherRebuilder.lockKey()).isEqualTo("med:lock:rag:index:rebuild:" + OTHER_INDEX);
        assertThat(otherRebuilder.lockKey()).isNotEqualTo(rebuilder.lockKey());
    }

    @Test
    @DisplayName("a zero lease uses the watchdog form of tryLock, a positive one a fixed lease")
    void leaseDecidesWhichTryLockFormIsUsed() throws Exception {
        mutexIsFree();
        indexExistsWith(1, TAG_FIELDS);

        rebuilder.rebuild(MedIndexRebuildRequest.indexOnly("ops-oncall"));

        // Default lease 10m -> explicit lease form.
        verify(lock).tryLock(5000L, 600_000L, TimeUnit.MILLISECONDS);

        MedIndexRebuildProperties watchdog = new MedIndexRebuildProperties();
        watchdog.setEnabled(true);
        watchdog.setLockLeaseTime(java.time.Duration.ZERO);
        MedVectorIndexRebuilder watchdogRebuilder =
                newRebuilder(new MedVectorStoreProperties(), watchdog, TAG_FIELDS);

        watchdogRebuilder.rebuild(MedIndexRebuildRequest.indexOnly("ops-oncall"));

        // Zero lease -> two-argument form, which leaves renewal to the Redisson watchdog.
        verify(lock).tryLock(5000L, TimeUnit.MILLISECONDS);
    }

    // ---------------------------------------------------------------------------------------------
    // Verification is the acceptance criterion, not the absence of an exception
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a recreated index that still misses a TAG field is a verification failure, not a success")
    void driftedSchemaAfterTheRebuildIsAVerificationFailure() {
        mutexIsFree();
        when(jedis.ftList()).thenReturn(Set.of(INDEX));
        when(jedis.ftInfo(INDEX)).thenReturn(
                ftInfo(INDEX, 3, TAG_FIELDS),
                ftInfo(INDEX, 3, List.of("tenant_id", "dept_id")));

        MedIndexRebuildReport report = rebuilder.rebuild(MedIndexRebuildRequest.indexOnly("ops-oncall"));

        assertThat(report.outcome()).isEqualTo(Outcome.VERIFICATION_FAILED);
        assertThat(report.isSuccess()).isFalse();
        assertThat(report.message()).contains("patient_id");
        verify(notifier).raise(eq(MedAlertSeverity.CRITICAL), eq(MedVectorIndexRebuilder.REBUILD_FAILED),
                eq("vector-index"), anyString());
        assertThat(rebuilder.currentProgress().stage()).isEqualTo(MedIndexRebuildProgress.Stage.FAILED);
    }

    @Test
    @DisplayName("an index that could not be recreated at all fails and names the switch to check")
    void indexThatCannotBeRecreatedFails() {
        mutexIsFree();
        when(jedis.ftList()).thenReturn(Set.of(INDEX), Set.of());
        when(jedis.ftInfo(INDEX)).thenReturn(ftInfo(INDEX, 3, TAG_FIELDS));

        MedIndexRebuildReport report = rebuilder.rebuild(MedIndexRebuildRequest.indexOnly("ops-oncall"));

        assertThat(report.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(report.message()).contains("initialize-schema");
        verify(notifier).raise(eq(MedAlertSeverity.CRITICAL), eq(MedVectorIndexRebuilder.REBUILD_FAILED),
                eq("vector-index"), anyString());
    }

    @Test
    @DisplayName("a Redis failure mid-rebuild becomes a FAILED report, and the mutex is still released")
    void redisFailureBecomesAFailedReport() {
        mutexIsFree();
        indexExistsWith(3, TAG_FIELDS);
        when(jedis.ftDropIndex(INDEX)).thenThrow(new JedisConnectionException("connection refused"));

        MedIndexRebuildReport report = rebuilder.rebuild(MedIndexRebuildRequest.indexOnly("ops-oncall"));

        assertThat(report.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(report.message()).contains("JedisConnectionException");
        verify(lock).unlock();
        verify(notifier).raise(eq(MedAlertSeverity.CRITICAL), eq(MedVectorIndexRebuilder.REBUILD_FAILED),
                eq("vector-index"), anyString());
        assertThat(rebuilder.lastReport()).isSameAs(report);
    }

    @Test
    @DisplayName("a lock provider that cannot be reached is a failure, not a skip")
    void unreachableLockProviderIsAFailure() {
        when(redisson.getLock(anyString())).thenThrow(new JedisConnectionException("no route to host"));

        MedIndexRebuildReport report = rebuilder.rebuild(MedIndexRebuildRequest.indexOnly("ops-oncall"));

        assertThat(report.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(report.message()).contains("JedisConnectionException");
        verifyNoInteractions(jedis);
    }

    // ---------------------------------------------------------------------------------------------
    // The report is the contract, so nothing else may break it
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a broken alert chain does not cost the caller the rebuild report")
    void alertFailureDoesNotBreakTheReport() {
        mutexIsFree();
        indexExistsWith(1, TAG_FIELDS);
        when(notifier.raise(any(MedAlertSeverity.class), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("alert sink exploded"));

        MedIndexRebuildReport report = rebuilder.rebuild(MedIndexRebuildRequest.indexOnly("ops-oncall"));

        assertThat(report.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(rebuilder.lastReport()).isSameAs(report);
    }

    @Test
    @DisplayName("boundary: a context without an alert chain still rebuilds and reports")
    void absentAlertChainIsTolerated() {
        mutexIsFree();
        indexExistsWith(1, TAG_FIELDS);
        when(notifierProvider.getIfAvailable()).thenReturn(null);

        MedIndexRebuildReport report = rebuilder.rebuild(MedIndexRebuildRequest.indexOnly("ops-oncall"));

        assertThat(report.outcome()).isEqualTo(Outcome.COMPLETED);
        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("boundary: a null request is a programming error and is the only thing thrown")
    void nullRequestIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> rebuilder.rebuild(null))
                .withMessageContaining("request must not be null");
        verifyNoInteractions(jedis);
    }

    @Test
    @DisplayName("boundary: every constructor argument is required")
    void constructorRejectsNulls() {
        MedRagIndexProperties indexProperties = new MedRagIndexProperties();

        assertThatIllegalArgumentException().isThrownBy(() -> new MedVectorIndexRebuilder(
                null, jedis, new MedVectorStoreProperties(), indexProperties, rebuildProperties,
                documentService, notifierProvider, redisson, fixedClock()));
        assertThatIllegalArgumentException().isThrownBy(() -> new MedVectorIndexRebuilder(
                vectorStore, null, new MedVectorStoreProperties(), indexProperties, rebuildProperties,
                documentService, notifierProvider, redisson, fixedClock()));
        assertThatIllegalArgumentException().isThrownBy(() -> new MedVectorIndexRebuilder(
                vectorStore, jedis, null, indexProperties, rebuildProperties,
                documentService, notifierProvider, redisson, fixedClock()));
        assertThatIllegalArgumentException().isThrownBy(() -> new MedVectorIndexRebuilder(
                vectorStore, jedis, new MedVectorStoreProperties(), null, rebuildProperties,
                documentService, notifierProvider, redisson, fixedClock()));
        assertThatIllegalArgumentException().isThrownBy(() -> new MedVectorIndexRebuilder(
                vectorStore, jedis, new MedVectorStoreProperties(), indexProperties, null,
                documentService, notifierProvider, redisson, fixedClock()));
        assertThatIllegalArgumentException().isThrownBy(() -> new MedVectorIndexRebuilder(
                vectorStore, jedis, new MedVectorStoreProperties(), indexProperties, rebuildProperties,
                null, notifierProvider, redisson, fixedClock()));
        assertThatIllegalArgumentException().isThrownBy(() -> new MedVectorIndexRebuilder(
                vectorStore, jedis, new MedVectorStoreProperties(), indexProperties, rebuildProperties,
                documentService, null, redisson, fixedClock()));
        assertThatIllegalArgumentException().isThrownBy(() -> new MedVectorIndexRebuilder(
                vectorStore, jedis, new MedVectorStoreProperties(), indexProperties, rebuildProperties,
                documentService, notifierProvider, null, fixedClock()));
        assertThatIllegalArgumentException().isThrownBy(() -> new MedVectorIndexRebuilder(
                vectorStore, jedis, new MedVectorStoreProperties(), indexProperties, rebuildProperties,
                documentService, notifierProvider, redisson, null));
    }
}
