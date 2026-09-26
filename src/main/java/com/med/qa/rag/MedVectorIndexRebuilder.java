package com.med.qa.rag;

import com.med.qa.alert.MedAlertNotifier;
import com.med.qa.alert.MedAlertSeverity;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.Nullable;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import redis.clients.jedis.JedisPooled;

/**
 * Rebuilds the RediSearch index behind medical RAG: drop, recreate with the configured schema,
 * optionally write the corpus back, verify.
 *
 * <h2>Why this exists</h2>
 * <p>D37 made a broken index <em>visible</em>. It did not make it <em>repairable</em>. Both failures
 * D37 detects — the index is missing, or its TAG schema drifted from the configuration — have the
 * same repair: the index has to be dropped and recreated from the current configuration. Until this
 * class existed that meant an operator typing {@code FT.DROPINDEX} into a production Redis by hand,
 * with no mutual exclusion (two operators, or an operator and a cron job, both dropping), no
 * verification that the new index actually works, and no record of who did it.</p>
 *
 * <h2>What is reused rather than re-implemented</h2>
 * <ul>
 *   <li><b>Schema creation</b> — {@link RedisVectorStore#afterPropertiesSet()} is the official
 *       lifecycle hook that issues {@code FT.CREATE} from {@code med.rag.vector-store.*}. It is
 *       idempotent (it returns early when the index already exists), so calling it after the drop
 *       recreates exactly the index the running configuration declares. This project contains no
 *       hand-written {@code FT.CREATE}, and this class does not add one.</li>
 *   <li><b>Document write-back</b> — {@link MedDocumentService#ingestAll(List)} is the ordinary
 *       ingestion path, so a rebuilt document is embedded and tagged exactly like a freshly ingested
 *       one.</li>
 *   <li><b>Mutual exclusion</b> — a Redisson {@link RLock}, the same primitive the session lock uses.
 *       A rebuild is a cluster-wide operation: without the mutex, pod B can recreate the index while
 *       pod A is still writing documents into it.</li>
 *   <li><b>Verdict</b> — {@link MedVectorIndexProbe}, so "the rebuild worked" is not the absence of an
 *       exception but the same scoped-retrieval-ready test the health endpoint uses.</li>
 * </ul>
 *
 * <h2>Two modes, and why the safe one is the default</h2>
 * <p>{@link MedIndexRebuildMode#INDEX_ONLY} drops the index but keeps its documents; RediSearch
 * re-indexes every existing key when the index is recreated, so a drifted schema is repaired without
 * losing a single document and without a single embedding call.
 * {@link MedIndexRebuildMode#DROP_AND_REINGEST} deletes the documents too and writes a supplied
 * corpus back. The second is destructive, so it needs both an explicit request and
 * {@code med.rag.index.rebuild.allow-document-deletion=true}.</p>
 *
 * <h2>Failure semantics</h2>
 * <p>A rebuild never throws for an operational failure: the caller receives a
 * {@link MedIndexRebuildReport} whose {@link MedIndexRebuildReport.Outcome} says what happened, and
 * the same information is pushed through the alert chain — a dropped index that nobody rebuilt
 * silently is the incident this class exists to prevent. Only a {@code null} request is a
 * programming error and raises {@link IllegalArgumentException}.</p>
 */
public class MedVectorIndexRebuilder {

    private static final Logger log = LoggerFactory.getLogger(MedVectorIndexRebuilder.class);

    /** Lock key prefix of the cluster-wide rebuild mutex; distinct from the session lock namespace. */
    public static final String LOCK_KEY_PREFIX = "med:lock:rag:index:rebuild:";

    /** Alert code raised when a rebuild produced a usable index. */
    public static final String REBUILD_COMPLETED = "rag-index-rebuild-completed";

    /** Alert code raised when a rebuild failed or left the index unusable. */
    public static final String REBUILD_FAILED = "rag-index-rebuild-failed";

    /** Alert code raised when a rebuild did nothing (mutex held, or the mode was refused). */
    public static final String REBUILD_SKIPPED = "rag-index-rebuild-skipped";

    /** Component label carried by every alert of this class. */
    public static final String REBUILD_COMPONENT = "vector-index";

    private final RedisVectorStore vectorStore;

    private final JedisPooled jedis;

    private final MedVectorStoreProperties storeProperties;

    private final MedRagIndexProperties indexProperties;

    private final MedIndexRebuildProperties rebuildProperties;

    private final MedDocumentService documentService;

    private final ObjectProvider<MedAlertNotifier> notifierProvider;

    private final RedissonClient redissonClient;

    private final Clock clock;

    private final MedVectorIndexProbe probe;

    private final AtomicReference<MedIndexRebuildProgress> lastProgress = new AtomicReference<>();

    private final AtomicReference<MedIndexRebuildReport> lastReport = new AtomicReference<>();

    private final AtomicLong rebuildCount = new AtomicLong();

    /**
     * Creates the rebuilder.
     *
     * @param vectorStore      the official store, used only for its schema-creation lifecycle hook,
     *                         must not be {@code null}
     * @param jedis            Jedis client dedicated to the vector index, must not be {@code null}
     * @param storeProperties  index topology (index name, key prefix), must not be {@code null}
     * @param indexProperties  expected TAG fields, the acceptance criterion of the rebuild, must not
     *                         be {@code null}
     * @param rebuildProperties policy of the rebuild (switches, timings, batch size), must not be
     *                         {@code null}
     * @param documentService  ingestion path used for the write-back, must not be {@code null}
     * @param notifierProvider provider of the alert dispatcher; absent when alerting is switched off,
     *                         must not be {@code null}
     * @param redissonClient   the lock provider, must not be {@code null}
     * @param clock            clock used for the report timestamps, must not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public MedVectorIndexRebuilder(RedisVectorStore vectorStore,
                                   JedisPooled jedis,
                                   MedVectorStoreProperties storeProperties,
                                   MedRagIndexProperties indexProperties,
                                   MedIndexRebuildProperties rebuildProperties,
                                   MedDocumentService documentService,
                                   ObjectProvider<MedAlertNotifier> notifierProvider,
                                   RedissonClient redissonClient,
                                   Clock clock) {
        if (vectorStore == null) {
            throw new IllegalArgumentException("vectorStore must not be null");
        }
        if (jedis == null) {
            throw new IllegalArgumentException("jedis must not be null");
        }
        if (storeProperties == null) {
            throw new IllegalArgumentException("storeProperties must not be null");
        }
        if (indexProperties == null) {
            throw new IllegalArgumentException("indexProperties must not be null");
        }
        if (rebuildProperties == null) {
            throw new IllegalArgumentException("rebuildProperties must not be null");
        }
        if (documentService == null) {
            throw new IllegalArgumentException("documentService must not be null");
        }
        if (notifierProvider == null) {
            throw new IllegalArgumentException("notifierProvider must not be null");
        }
        if (redissonClient == null) {
            throw new IllegalArgumentException("redissonClient must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.vectorStore = vectorStore;
        this.jedis = jedis;
        this.storeProperties = storeProperties;
        this.indexProperties = indexProperties;
        this.rebuildProperties = rebuildProperties;
        this.documentService = documentService;
        this.notifierProvider = notifierProvider;
        this.redissonClient = redissonClient;
        this.clock = clock;
        this.probe = new MedVectorIndexProbe(jedis, storeProperties);
    }

    /**
     * Runs one rebuild and returns its report.
     *
     * <p>The steps are: acquire the cluster-wide mutex, read the index state, drop the index, recreate
     * it from the configuration, write the corpus back when the mode asks for it, then re-read the
     * index and require it to be scoped-retrieval ready. Every step publishes a
     * {@link MedIndexRebuildProgress} snapshot, and the terminal state is both returned and pushed
     * through the alert chain.</p>
     *
     * @param request the rebuild instruction, must not be {@code null}
     * @return the report of what happened, never {@code null}; an operational failure is reported as
     *         an outcome, not thrown
     * @throws IllegalArgumentException if {@code request} is {@code null}
     */
    public MedIndexRebuildReport rebuild(MedIndexRebuildRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        String indexName = storeProperties.getIndexName();
        long startedAt = clock.millis();
        rebuildCount.incrementAndGet();
        publish(MedIndexRebuildProgress.pending(indexName, request.documentCount(),
                MedIndexRebuildProgress.batchesOf(request.documentCount(), rebuildProperties.getBatchSize()),
                startedAt));

        if (request.documentCount() > rebuildProperties.getMaxDocuments()) {
            return finish(refusal(indexName, request, startedAt,
                    "corpus of " + request.documentCount() + " documents exceeds "
                            + MedIndexRebuildProperties.PREFIX + ".max-documents="
                            + rebuildProperties.getMaxDocuments()));
        }
        if (request.deletesDocuments() && !rebuildProperties.isAllowDocumentDeletion()) {
            return finish(refusal(indexName, request, startedAt,
                    MedIndexRebuildMode.DROP_AND_REINGEST + " deletes the indexed documents, but "
                            + MedIndexRebuildProperties.PREFIX + ".allow-document-deletion is false; "
                            + "the request was refused before Redis was contacted"));
        }

        RLock lock = null;
        try {
            lock = redissonClient.getLock(lockKey());
            if (!tryAcquire(lock)) {
                return finish(skip(indexName, request, startedAt));
            }
            return finish(doRebuild(request, indexName, startedAt));
        } catch (RuntimeException ex) {
            log.error("rebuild of vector index '{}' failed", indexName, ex);
            return finish(failure(indexName, request, startedAt, "rebuild threw " + describe(ex)));
        } finally {
            if (lock != null) {
                release(lock);
            }
        }
    }

    /**
     * Returns the lock key of the cluster-wide rebuild mutex for this index.
     *
     * @return the Redis lock key, never blank
     */
    public String lockKey() {
        return LOCK_KEY_PREFIX + storeProperties.getIndexName();
    }

    /**
     * Returns the most recent progress snapshot.
     *
     * @return the last published snapshot, or {@code null} when no rebuild ran on this instance
     */
    @Nullable
    public MedIndexRebuildProgress currentProgress() {
        return lastProgress.get();
    }

    /**
     * Returns the report of the most recent rebuild.
     *
     * @return the last report, or {@code null} when no rebuild ran on this instance
     */
    @Nullable
    public MedIndexRebuildReport lastReport() {
        return lastReport.get();
    }

    /**
     * Returns how many rebuilds were attempted on this instance, refused and skipped ones included.
     *
     * @return the attempt count, never negative
     */
    public long rebuildCount() {
        return rebuildCount.get();
    }

    /**
     * Tells whether a rebuild is running right now, according to the local progress snapshot.
     *
     * <p>A local read: it says whether <em>this</em> instance is mid-rebuild, not whether the mutex is
     * held somewhere in the cluster. The mutex is the authority, and it is what
     * {@link #rebuild(MedIndexRebuildRequest)} consults.</p>
     *
     * @return {@code true} while the last snapshot describes a non-terminal stage
     */
    public boolean isRebuilding() {
        MedIndexRebuildProgress progress = lastProgress.get();
        return progress != null && !progress.stage().isTerminal();
    }

    private MedIndexRebuildReport doRebuild(MedIndexRebuildRequest request, String indexName,
                                           long startedAt) {
        MedVectorIndexReport before = probe.probe();
        long documentsBefore = before.documentCount();

        boolean dropped = false;
        if (before.exists()) {
            publish(requireProgress().withStage(MedIndexRebuildProgress.Stage.DROPPING, clock.millis()));
            if (request.deletesDocuments()) {
                // FT.DROPINDEX ... DD: the index and its documents go away together.
                jedis.ftDropIndexDD(indexName);
            } else {
                // FT.DROPINDEX: the index goes away, the JSON documents stay and are re-indexed by
                // the FT.CREATE below.
                jedis.ftDropIndex(indexName);
            }
            dropped = true;
        } else {
            log.info("vector index '{}' did not exist before the rebuild; creating it", indexName);
        }

        // The official store's own lifecycle hook issues FT.CREATE from med.rag.vector-store.*.
        vectorStore.afterPropertiesSet();

        int ingested = 0;
        int batches = 0;
        if (request.deletesDocuments()) {
            publish(requireProgress().withStage(MedIndexRebuildProgress.Stage.REINGESTING, clock.millis()));
            int batchSize = rebuildProperties.getBatchSize();
            List<MedDocumentRequest> documents = request.documents();
            for (int from = 0; from < documents.size(); from += batchSize) {
                int to = Math.min(from + batchSize, documents.size());
                List<MedDocumentRequest> batch = documents.subList(from, to);
                documentService.ingestAll(batch);
                ingested += batch.size();
                batches++;
                publish(requireProgress().advance(batch.size(), clock.millis()));
            }
        }

        publish(requireProgress().withStage(MedIndexRebuildProgress.Stage.VERIFYING, clock.millis()));
        MedVectorIndexReport after = probe.probe();
        long documentsAfter = after.documentCount();

        if (!after.exists()) {
            return report(indexName, request, MedIndexRebuildReport.Outcome.FAILED, documentsBefore,
                    documentsAfter, ingested, batches, dropped, startedAt,
                    "the index was not recreated: check " + MedVectorStoreProperties.PREFIX
                            + ".initialize-schema, which must be true for a rebuild to recreate it");
        }
        Set<String> missing = after.missingTagFields(indexProperties.getExpectedTagFields());
        if (!missing.isEmpty()) {
            return report(indexName, request, MedIndexRebuildReport.Outcome.VERIFICATION_FAILED,
                    documentsBefore, documentsAfter, ingested, batches, dropped, startedAt,
                    "the recreated index does not declare the expected TAG fields " + missing
                            + "; scoped retrieval would still match nothing");
        }
        return report(indexName, request, MedIndexRebuildReport.Outcome.COMPLETED, documentsBefore,
                documentsAfter, ingested, batches, dropped, startedAt,
                dropped ? "index dropped and recreated with the configured schema"
                        : "index created with the configured schema");
    }

    /**
     * Tries to take the rebuild mutex inside the configured wait window.
     *
     * <p>Only "the mutex is held elsewhere" returns {@code false}. A failure to reach Redis, or an
     * interruption while queuing, is propagated: reporting them as "another rebuild is running" would
     * tell an operator to wait for a rebuild that does not exist.</p>
     *
     * @param lock the lock to acquire
     * @return {@code true} when this thread now owns the mutex
     * @throws IllegalStateException when the thread is interrupted while waiting
     * @throws RuntimeException      when Redis cannot be reached
     */
    private boolean tryAcquire(RLock lock) {
        long waitMillis = rebuildProperties.getLockWaitTime().toMillis();
        try {
            if (rebuildProperties.getLockLeaseTime().isZero()) {
                // No explicit lease: Redisson's watchdog renews it for as long as this JVM lives.
                return lock.tryLock(waitMillis, TimeUnit.MILLISECONDS);
            }
            return lock.tryLock(waitMillis, rebuildProperties.getLockLeaseTime().toMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted while waiting for the rebuild mutex " + lockKey(), ex);
        }
    }

    private void release(RLock lock) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException ex) {
            // The rebuild already produced its report; masking it with a release failure would be
            // worse than letting the lease expire on its own.
            log.warn("failed to release rebuild mutex {}, leaving it to the lease expiry",
                    lockKey(), ex);
        }
    }

    private MedIndexRebuildReport skip(String indexName, MedIndexRebuildRequest request, long startedAt) {
        log.warn("skipping rebuild of vector index '{}': mutex {} is held elsewhere",
                indexName, lockKey());
        return report(indexName, request, MedIndexRebuildReport.Outcome.SKIPPED_LOCK_HELD, 0L, 0L, 0, 0,
                false, startedAt, "another rebuild holds " + lockKey());
    }

    private MedIndexRebuildReport refusal(String indexName, MedIndexRebuildRequest request,
                                         long startedAt, String message) {
        log.warn("refusing rebuild of vector index '{}': {}", indexName, message);
        return report(indexName, request, MedIndexRebuildReport.Outcome.REFUSED, 0L, 0L, 0, 0,
                false, startedAt, message);
    }

    private MedIndexRebuildReport failure(String indexName, MedIndexRebuildRequest request,
                                         long startedAt, String message) {
        return report(indexName, request, MedIndexRebuildReport.Outcome.FAILED, 0L, 0L, 0, 0,
                false, startedAt, message);
    }

    private MedIndexRebuildReport report(String indexName, MedIndexRebuildRequest request,
                                        MedIndexRebuildReport.Outcome outcome, long documentsBefore,
                                        long documentsAfter, int ingested, int batches, boolean dropped,
                                        long startedAt, String message) {
        return new MedIndexRebuildReport(indexName, request.mode(), outcome, documentsBefore,
                documentsAfter, ingested, batches, dropped, clock.millis() - startedAt,
                message + " (requested by " + request.requestedBy() + ")");
    }

    /**
     * Stores the report, publishes the terminal progress stage and pushes the alert.
     *
     * @param report the report to record
     * @return the same report, so callers can return it directly
     */
    private MedIndexRebuildReport finish(MedIndexRebuildReport report) {
        MedIndexRebuildProgress progress = lastProgress.get();
        if (progress != null) {
            publish(progress.withStage(
                    report.isSuccess() ? MedIndexRebuildProgress.Stage.COMPLETED
                            : report.outcome() == MedIndexRebuildReport.Outcome.SKIPPED_LOCK_HELD
                                    || report.outcome() == MedIndexRebuildReport.Outcome.REFUSED
                                    ? MedIndexRebuildProgress.Stage.SKIPPED
                                    : MedIndexRebuildProgress.Stage.FAILED,
                    clock.millis()));
        }
        lastReport.set(report);
        log.info("rebuild of vector index '{}' finished: {}", report.indexName(), report.describe());
        raise(report);
        return report;
    }

    private void raise(MedIndexRebuildReport report) {
        MedAlertNotifier notifier = notifierProvider.getIfAvailable();
        if (notifier == null) {
            log.debug("alerting is not wired in this context; rebuild outcome stays in the report");
            return;
        }
        try {
            if (report.isSuccess()) {
                notifier.raise(MedAlertSeverity.INFO, REBUILD_COMPLETED, REBUILD_COMPONENT,
                        report.describe());
            } else if (report.outcome() == MedIndexRebuildReport.Outcome.SKIPPED_LOCK_HELD
                    || report.outcome() == MedIndexRebuildReport.Outcome.REFUSED) {
                notifier.raise(MedAlertSeverity.WARNING, REBUILD_SKIPPED, REBUILD_COMPONENT,
                        report.describe());
            } else {
                // A failed rebuild can leave the index missing or empty, which degrades every
                // consultation until someone looks: that is a page, not a notice.
                notifier.raise(MedAlertSeverity.CRITICAL, REBUILD_FAILED, REBUILD_COMPONENT,
                        report.describe());
            }
        } catch (RuntimeException ex) {
            // The rebuild itself already finished; losing its report because the notification path
            // is broken would be the worst of both worlds, and the caller still gets the report.
            log.warn("failed to raise the {} alert for index '{}': {}",
                    report.outcome().label(), report.indexName(), describe(ex));
        }
    }

    private void publish(MedIndexRebuildProgress progress) {
        lastProgress.set(progress);
        log.debug("rebuild progress: {}", progress);
    }

    private MedIndexRebuildProgress requireProgress() {
        MedIndexRebuildProgress progress = lastProgress.get();
        if (progress == null) {
            throw new IllegalStateException("rebuild progress was never published");
        }
        return progress;
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
