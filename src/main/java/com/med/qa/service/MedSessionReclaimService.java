package com.med.qa.service;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.config.MedSessionReclaimProperties;
import com.med.qa.domain.entity.ArchivedMessageDO;
import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.entity.SessionArchiveManifestDO;
import com.med.qa.domain.enums.SessionStatus;
import com.med.qa.mapper.ChatMessageMapper;
import com.med.qa.mapper.ChatSessionMapper;
import com.med.qa.mapper.SessionArchiveMapper;
import com.med.qa.memory.lock.SessionLockService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.lang.Nullable;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Retires the cold copies a restore leaves behind — the last piece of the cold/hot lifecycle D47–D51
 * built up, and the second job in this project that deletes clinical data.
 *
 * <h2>The orphan this job exists for</h2>
 * <p>D51 moves a mis-archived session back to the live tier and, deliberately, retires its export
 * certification rather than its bytes: the manifest is a <em>state</em> row while the cold rows are the
 * evidence, and retiring a claim that the live copy has just been proved to reproduce costs nothing.
 * What it leaves behind is a snapshot with no manifest — and such a snapshot is neither evidence nor
 * state. The read path never consults it ({@link MedTranscriptService} decides which tier answers by
 * reproducing the manifest digest, and there is no manifest), and D50 never touches it (the purge deletes
 * from {@code med_message} only). It is a duplicate of bytes the live transcript still holds, occupying
 * a table nobody will ever query for it.</p>
 *
 * <h2>Why deleting it cannot lose a consultation</h2>
 * <p>Three predicates, in this order, and none of them is a formality:</p>
 * <ol>
 *   <li><b>The session must not be cold.</b> A candidate is selected because its status is below
 *       {@link SessionStatus#ARCHIVED} (see {@link SessionArchiveMapper#selectReclaimableOrphans}), and
 *       the status is re-read inside the session lock, because the batch was selected before the lock
 *       was taken. An {@code ARCHIVED} session with an uncertified cold copy is the export's work in
 *       progress, not an orphan: the export is about to certify those very rows, and reclaiming them
 *       would be the job fighting its sibling for no benefit.</li>
 *   <li><b>There must be no live certification.</b> A manifest makes the cold copy evidence, and a row
 *       that is evidence may not be deleted by anything. The check is repeated inside the lock rather
 *       than trusted from the candidate query, because the candidate query ran outside it: a session
 *       that was archived and exported in between would otherwise have its freshly certified copy
 *       deleted — destroying exactly the thing D48 exists to produce. This is the same shape as D50's
 *       in-lock status re-read, for the same reason.</li>
 *   <li><b>The live transcript must hold every message the cold copy holds.</b> This is the guard that
 *       makes the deletion accountable, and it replaces the digest comparison the purge uses: a digest
 *       is a claim about <em>a</em> certified transcript, and an orphan has no certification to compare
 *       against. What can be checked instead is containment — the cold copy is a subset of the live
 *       transcript, so removing it removes nothing that is not still there.</li>
 * </ol>
 *
 * <p>Check 3 is what keeps this job safe <em>without</em> relying on another component's invariant. The
 * argument "a cold copy is always redundant because the only path that destroys the hot rows, the purge,
 * refuses to run without a manifest" is true today, but it is an argument about D50, and D48/D49/D50 all
 * learned the same lesson: an invariant that belongs to another component has to be re-established
 * locally or it will be broken by the next iteration. So the job reads the live transcript and refuses
 * when the cold copy holds anything it does not. In a healthy deployment that branch is unreachable,
 * which is the point — it costs one read and it is the difference between "a redundant copy was
 * released" and "the only record of a consultation was deleted". A refusal is reported and alerted, and
 * it deletes nothing.</p>
 *
 * <h2>Why containment is compared by message id, not by bytes</h2>
 * <p>The cold row's payload was encoded from the same message the live row holds, and no code path in
 * this project rewrites a stored message: the append path is {@code insertIfAbsent} and the only update
 * {@code med_message} has ever had is the {@code masked} flag (the D44 contract: the durable transcript
 * is append-only). Re-encoding the live rows to compare bytes would introduce a second, weaker notion of
 * equality — one that could refuse a healthy copy over a cosmetic difference in map ordering — while
 * answering exactly the same question. The digest machinery is deliberately not reused here either: it
 * needs a certification to compare against, and the whole premise of this job is that there is none.</p>
 *
 * <h2>Why there is no marker and no cooling-off window</h2>
 * <p>Unlike the purge, this job needs no {@code purged_at} equivalent: its completion is observable by
 * the same predicate that selected it (the cold rows are gone, so the session stops being a candidate),
 * so an interrupted run simply converges on the next pass and there is nothing to mark. And unlike the
 * purge, no capability is destroyed by deleting what it deletes — the bytes stay in the live transcript
 * and the session is writable again — so a cooling-off window would delay the release of space while
 * pretending to protect something (see {@link MedSessionReclaimProperties}).</p>
 *
 * <h2>Concurrency</h2>
 * <p>One candidate at a time, inside {@link SessionLockService}'s session lock — the same lock the append
 * path, {@code archiveSession}, the restore and the two cold-tier jobs take. The cluster-wide mutex
 * {@link MedSessionReclaimProperties#LOCK_KEY} keeps replicas from running the sweep concurrently, and a
 * replica that loses that race reports {@link SessionReclaimReport.Outcome#SKIPPED_LOCK_HELD} without
 * reading a single row.</p>
 *
 * <h2>Failure semantics</h2>
 * <ul>
 *   <li><b>A single candidate never aborts the run.</b> Its failure is logged, counted and listed in
 *       {@link SessionReclaimReport#failures()}; the remaining candidates are still processed.</li>
 *   <li><b>A refusal is not an exception.</b> "The cold copy holds something the live transcript does
 *       not" is an expected outcome of a verification, counted in {@code refused} and reported with its
 *       reason, so an operator can tell a broken reclaim from a broken cold store.</li>
 *   <li><b>The cluster mutex is not optional.</b> Redis unreachable propagates as
 *       {@link ErrorCode#STORAGE_ERROR} instead of degrading to an unlocked run.</li>
 *   <li><b>Only the cold copy of a candidate is ever deleted.</b> No code path in this class deletes from
 *       {@code med_message}, and none deletes a manifest row.</li>
 * </ul>
 *
 * <p>Not annotated with {@code @Service} on purpose: {@code config/MedSessionReclaimConfig} declares it
 * behind {@code med.session.reclaim.enabled}, the same way the D47 sweep, the D48 export, the D50 purge
 * and the D38 rebuild are declared behind their switches. A bean that is always present could not be
 * switched off, and this is the last bean that should be un-switchable.</p>
 */
public class MedSessionReclaimService {

    private static final Logger log = LoggerFactory.getLogger(MedSessionReclaimService.class);

    private final ChatSessionMapper sessionMapper;

    private final SessionArchiveMapper archiveMapper;

    private final ChatMessageMapper messageMapper;

    private final SessionLockService lockService;

    private final RedissonClient redissonClient;

    private final MedSessionReclaimProperties properties;

    private final Clock clock;

    /**
     * Creates the service used by the application context.
     *
     * <p>The Redisson client is injected lazily on purpose: creating it opens a connection, and the
     * application context must be able to start without Redis available.</p>
     *
     * @param sessionMapper  MyBatis mapper over {@code med_session}, used for the candidate query and the
     *                       in-lock status re-read, must not be {@code null}
     * @param archiveMapper  MyBatis mapper over the cold archive tables, must not be {@code null}
     * @param messageMapper  MyBatis mapper over the sharded {@code med_message} table, used to prove the
     *                       cold copy is a subset of the live transcript, must not be {@code null}
     * @param lockService    distributed session lock serializing one candidate against an export or a
     *                       restore, must not be {@code null}
     * @param redissonClient the shared client, used only for the cluster-wide reclaim mutex, must not be
     *                       {@code null}
     * @param properties     the {@code med.session.reclaim.*} policy, must not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public MedSessionReclaimService(ChatSessionMapper sessionMapper,
                                    SessionArchiveMapper archiveMapper,
                                    ChatMessageMapper messageMapper,
                                    SessionLockService lockService,
                                    @Lazy RedissonClient redissonClient,
                                    MedSessionReclaimProperties properties) {
        this(sessionMapper, archiveMapper, messageMapper, lockService, redissonClient, properties,
                Clock.systemUTC());
    }

    /**
     * Creates the service with an explicit clock, so the run duration is deterministic in tests.
     *
     * @param sessionMapper  MyBatis mapper over {@code med_session}, must not be {@code null}
     * @param archiveMapper  MyBatis mapper over the cold archive tables, must not be {@code null}
     * @param messageMapper  MyBatis mapper over the sharded {@code med_message} table, must not be
     *                       {@code null}
     * @param lockService    distributed session lock serializing one candidate, must not be {@code null}
     * @param redissonClient the shared client, must not be {@code null}
     * @param properties     the {@code med.session.reclaim.*} policy, must not be {@code null}
     * @param clock          clock measuring the run duration, must not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public MedSessionReclaimService(ChatSessionMapper sessionMapper,
                                    SessionArchiveMapper archiveMapper,
                                    ChatMessageMapper messageMapper,
                                    SessionLockService lockService,
                                    @Lazy RedissonClient redissonClient,
                                    MedSessionReclaimProperties properties,
                                    Clock clock) {
        if (sessionMapper == null) {
            throw new IllegalArgumentException("sessionMapper must not be null");
        }
        if (archiveMapper == null) {
            throw new IllegalArgumentException("archiveMapper must not be null");
        }
        if (messageMapper == null) {
            throw new IllegalArgumentException("messageMapper must not be null");
        }
        if (lockService == null) {
            throw new IllegalArgumentException("lockService must not be null");
        }
        if (redissonClient == null) {
            throw new IllegalArgumentException("redissonClient must not be null");
        }
        if (properties == null) {
            throw new IllegalArgumentException("properties must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.sessionMapper = sessionMapper;
        this.archiveMapper = archiveMapper;
        this.messageMapper = messageMapper;
        this.lockService = lockService;
        this.redissonClient = redissonClient;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Runs one bounded reclaim.
     *
     * <p>The run is single-flight across the cluster: it acquires {@link MedSessionReclaimProperties#LOCK_KEY}
     * before touching the database and reports {@link SessionReclaimReport.Outcome#SKIPPED_LOCK_HELD} when
     * another replica is already reclaiming. It then performs at most {@code max-batches} passes of
     * {@code batch-size} candidates — except in dry-run mode, which stops after the first pass because
     * nothing was deleted and the next pass would simply fetch the same sessions again.</p>
     *
     * <p>A dry run still performs the full verification: it reads the manifest, the cold copy and the
     * live transcript, so the report says not only how many copies <em>would</em> be retired but how many
     * would be refused. Reading is what the run does anyway; skipping it would turn the one report an
     * operator uses to decide whether to arm the job into a count of candidates.</p>
     *
     * @return the run report, never {@code null}
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when the mutex cannot be acquired because
     *                      Redis is unreachable, or when the candidate queries fail
     */
    public SessionReclaimReport reclaimOnce() {
        long started = clock.millis();
        RLock lock = redissonClient.getLock(MedSessionReclaimProperties.LOCK_KEY);
        if (!acquire(lock)) {
            log.info("another replica is already reclaiming orphaned cold copies, stepping aside");
            return SessionReclaimReport.skipped(clock.millis() - started);
        }
        try {
            return runReclaim(started);
        } finally {
            release(lock);
        }
    }

    /**
     * Reads the orphaned copies the next run would consider, without changing anything.
     *
     * <p>Exists for the same reason the purge's equivalent does: it lets an operator (and the contract
     * tests) ask what the job would touch without arming it.</p>
     *
     * @param limit maximum number of rows, must be strictly positive
     * @return the candidates ordered by {@code updated_at} ascending, possibly empty, never {@code null}
     * @throws IllegalArgumentException if {@code limit} is not positive
     * @throws BizException             {@link ErrorCode#STORAGE_ERROR} when the query fails
     */
    public List<ChatSessionDO> findReclaimableOrphans(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive but was " + limit);
        }
        return selectBatch(limit);
    }

    /**
     * Counts the orphaned copies still holding cold rows right now.
     *
     * @return the backlog size, never negative
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when the query fails
     */
    public long countReclaimableOrphans() {
        return countReclaimable();
    }

    /**
     * Performs the bounded passes of one run that already holds the cluster mutex.
     *
     * @param started epoch milliseconds the run began, used for the duration
     * @return the run report
     */
    private SessionReclaimReport runReclaim(long started) {
        int candidates = 0;
        int reclaimed = 0;
        long reclaimedMessages = 0;
        int skipped = 0;
        int refused = 0;
        int failed = 0;
        int batches = 0;
        List<String> failures = new ArrayList<>();

        // A dry run must not loop: it deletes nothing, so the next pass would return the same sessions.
        int maxPasses = properties.isDryRun() ? 1 : properties.getMaxBatches();
        for (int pass = 1; pass <= maxPasses; pass++) {
            List<ChatSessionDO> batch = selectBatch(properties.getBatchSize());
            if (batch.isEmpty()) {
                break;
            }
            batches++;
            candidates += batch.size();
            for (ChatSessionDO candidate : batch) {
                try {
                    CandidateResult result = reclaimUnderSessionLock(candidate);
                    switch (result.outcome()) {
                        case RECLAIMED -> {
                            reclaimed++;
                            reclaimedMessages += result.messages();
                        }
                        case SKIPPED -> skipped++;
                        case REFUSED -> {
                            refused++;
                            failures.add(candidate.getSessionId() + ": REFUSED: " + result.detail());
                        }
                    }
                } catch (RuntimeException ex) {
                    failed++;
                    failures.add(describeFailure(candidate.getSessionId(), ex));
                    log.warn("failed to reclaim the orphaned cold copy of session {}, continuing with the rest",
                            candidate.getSessionId(), ex);
                }
            }
            if (batch.size() < properties.getBatchSize()) {
                break;
            }
        }

        long remaining = countReclaimable();
        SessionReclaimReport report = new SessionReclaimReport(SessionReclaimReport.Outcome.COMPLETED,
                properties.isDryRun(), candidates, reclaimed, reclaimedMessages, skipped, refused, failed,
                batches, remaining, clock.millis() - started, failures);
        log.info("orphaned cold copy reclaim finished: {}", report.summary());
        return report;
    }

    /**
     * Processes one candidate inside its session lock, re-asserting the two premises the candidate query
     * assumed.
     *
     * <p>The batch was selected before the lock was taken, so a candidate may have changed underneath it.
     * A restore can only make a session <em>more</em> eligible — it is the operation that creates the
     * orphan in the first place — but the opposite direction is what invalidates a candidate: a session
     * archived again (and possibly exported again) between the query and the lock must not lose a cold
     * copy that has just become evidence. The re-read catches it. A busy session is a {@code skipped}
     * outcome, not a failure — the next run picks it up (the D47 convention).</p>
     *
     * @param candidate the session the candidate query returned
     * @return what happened to the candidate
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when the re-read fails
     */
    private CandidateResult reclaimUnderSessionLock(ChatSessionDO candidate) {
        try {
            return lockService.executeLocked(candidate, () -> {
                ChatSessionDO current = readSession(candidate.getSessionId());
                if (current == null) {
                    log.info("session {} disappeared before its orphaned cold copy could be reclaimed",
                            candidate.getSessionId());
                    return CandidateResult.skipped();
                }
                if (current.getStatus() == SessionStatus.ARCHIVED) {
                    log.info("session {} became cold again ({}), leaving its cold copy to the export",
                            candidate.getSessionId(), current.getStatus().name());
                    return CandidateResult.skipped();
                }
                return reclaimOne(current);
            });
        } catch (BizException ex) {
            if (ex.getErrorCode() == ErrorCode.SESSION_LOCKED) {
                log.info("session {} is busy, skipping it for this run", candidate.getSessionId());
                return CandidateResult.skipped();
            }
            throw ex;
        }
    }

    /**
     * Re-reads one session row.
     *
     * @param sessionId the session id
     * @return the row, or {@code null} when it disappeared
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when the query fails
     */
    @Nullable
    private ChatSessionDO readSession(String sessionId) {
        try {
            return sessionMapper.selectById(sessionId);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to re-read session " + sessionId + " before reclaiming its cold copy", ex);
        }
    }

    /**
     * Verifies one candidate and, when it is accountable, retires its orphaned cold copy.
     *
     * @param session the live session the candidate query returned
     * @return what happened to the candidate
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when a read or a delete fails
     */
    private CandidateResult reclaimOne(ChatSessionDO session) {
        String sessionId = session.getSessionId();
        boolean dryRun = properties.isDryRun();

        // 1. A live certification makes the cold copy evidence, not an orphan. This is checked inside the
        //    lock because the candidate query ran outside it: a session archived and exported in between
        //    would otherwise lose the very rows that certification describes.
        if (readManifest(sessionId) != null) {
            log.info("session {} is certified again, leaving its cold copy untouched", sessionId);
            return CandidateResult.skipped();
        }

        // 2. The copy itself. Empty means another run (or a previous pass) already retired it: the
        //    candidate predicate observes the cold rows directly, so no marker is needed to converge.
        List<ArchivedMessageDO> cold = readArchived(sessionId);
        if (cold.isEmpty()) {
            log.info("session {} has no cold copy left to reclaim", sessionId);
            return CandidateResult.skipped();
        }

        // 3. Every byte about to be deleted must still exist in the live transcript. The cold copy is a
        //    snapshot of it, so anything the live transcript does not hold may be the only record of it.
        Set<String> live = messageIds(readTranscript(sessionId));
        List<String> missing = cold.stream()
                .map(ArchivedMessageDO::getMessageId)
                .filter(messageId -> !live.contains(messageId))
                .toList();
        if (!missing.isEmpty()) {
            log.warn("the cold copy of session {} holds {} message(s) the live transcript does not, "
                    + "refusing to reclaim it", sessionId, missing.size());
            return CandidateResult.refused(missing.size() + " archived message(s) are not present in the "
                    + "live transcript, so the cold copy may be their only surviving record");
        }

        if (dryRun) {
            log.info("dry run: the {} orphaned cold row(s) of session {} would be deleted",
                    cold.size(), sessionId);
            return CandidateResult.reclaimed(cold.size());
        }

        int deleted = deleteColdCopy(sessionId);
        if (deleted <= 0) {
            // Raced with another run between the read and the delete: nothing left, nothing lost.
            log.info("session {} had no cold rows left to reclaim", sessionId);
            return CandidateResult.skipped();
        }
        log.info("reclaimed {} orphaned cold row(s) of session {}", deleted, sessionId);
        return CandidateResult.reclaimed(deleted);
    }

    /**
     * Collects the message ids of a transcript.
     *
     * @param transcript the transcript, possibly empty
     * @return the ids, never {@code null}
     */
    private static Set<String> messageIds(List<ChatMessageDO> transcript) {
        Set<String> ids = new HashSet<>(transcript.size());
        for (ChatMessageDO message : transcript) {
            ids.add(message.getMessageId());
        }
        return ids;
    }

    /**
     * Renders one failure line for the report.
     *
     * <p>A {@link BizException} is rendered with its {@link ErrorCode} rather than its class name:
     * "STORAGE_ERROR" tells an operator whether MySQL was unreachable or a delete was rejected, while
     * "BizException" tells them nothing.</p>
     *
     * @param sessionId the candidate that failed
     * @param failure   the failure
     * @return a single-line description, never {@code null}
     */
    private static String describeFailure(String sessionId, RuntimeException failure) {
        if (failure instanceof BizException bizException) {
            return sessionId + ": " + bizException.getErrorCode().name() + ": "
                    + bizException.getMessage();
        }
        return sessionId + ": " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }

    /**
     * Fetches one page of live sessions whose cold copy was orphaned.
     *
     * @param limit maximum number of rows
     * @return the page, possibly empty, never {@code null}
     */
    private List<ChatSessionDO> selectBatch(int limit) {
        List<ChatSessionDO> batch;
        try {
            batch = archiveMapper.selectReclaimableOrphans(SessionStatus.ARCHIVED, limit);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to list the sessions whose cold copy was orphaned", ex);
        }
        return batch == null ? Collections.emptyList() : batch;
    }

    /**
     * Counts the orphaned copies still present.
     *
     * @return the backlog size, never negative
     */
    private long countReclaimable() {
        try {
            return Math.max(archiveMapper.countReclaimableOrphans(SessionStatus.ARCHIVED), 0L);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to count the sessions whose cold copy was orphaned", ex);
        }
    }

    /**
     * Reads the live transcript of a session in total order.
     *
     * @param sessionId the session id
     * @return the transcript, possibly empty, never {@code null}
     */
    private List<ChatMessageDO> readTranscript(String sessionId) {
        List<ChatMessageDO> transcript;
        try {
            transcript = messageMapper.selectTranscriptBySessionId(sessionId);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to read the live transcript of session " + sessionId, ex);
        }
        return transcript == null ? Collections.emptyList() : transcript;
    }

    /**
     * Reads the cold copy of a session in total order.
     *
     * @param sessionId the session id
     * @return the archived messages, possibly empty, never {@code null}
     */
    private List<ArchivedMessageDO> readArchived(String sessionId) {
        List<ArchivedMessageDO> archived;
        try {
            archived = archiveMapper.selectBySessionId(sessionId);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to read the cold copy of session " + sessionId, ex);
        }
        return archived == null ? Collections.emptyList() : archived;
    }

    /**
     * Reads the export manifest of a session, i.e. asks whether its cold copy is still certified.
     *
     * @param sessionId the session id
     * @return the manifest, or {@code null} when the session has none
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when the query fails
     */
    private SessionArchiveManifestDO readManifest(String sessionId) {
        try {
            return archiveMapper.selectManifest(sessionId);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to read the export manifest of session " + sessionId, ex);
        }
    }

    /**
     * Deletes every cold row of a session. The cold table is not sharded, so this is a single-table
     * delete and ShardingSphere routes it through its {@code SINGLE} rule.
     *
     * @param sessionId the session whose orphaned copy is being retired
     * @return number of deleted rows
     */
    private int deleteColdCopy(String sessionId) {
        try {
            return archiveMapper.deleteColdCopy(sessionId);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to delete the orphaned cold copy of session " + sessionId, ex);
        }
    }

    /**
     * Acquires the cluster-wide reclaim mutex.
     *
     * @param lock the mutex
     * @return {@code true} when this run may reclaim
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when Redis is unreachable,
     *                      {@link ErrorCode#INTERNAL_ERROR} when interrupted while queuing
     */
    private boolean acquire(RLock lock) {
        long waitMillis = properties.getLockWaitTime().toMillis();
        try {
            if (properties.isLockWatchdogEnabled()) {
                // Two-argument form: no explicit lease, so Redisson's watchdog keeps renewing it.
                return lock.tryLock(waitMillis, TimeUnit.MILLISECONDS);
            }
            return lock.tryLock(waitMillis, properties.getLockLeaseTime().toMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "interrupted while acquiring the reclaim lock", e);
        } catch (RedisException e) {
            throw new BizException(ErrorCode.STORAGE_ERROR, "failed to acquire the reclaim lock", e);
        }
    }

    /**
     * Releases the mutex; a failed release is logged and left to the lease, because the run it guarded
     * already finished.
     *
     * @param lock the mutex
     */
    private void release(RLock lock) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException e) {
            log.warn("failed to release the reclaim lock, leaving it to the lease expiry", e);
        }
    }

    /** What happened to one candidate. */
    private enum Outcome {

        /** The orphaned cold copy was deleted. */
        RECLAIMED,

        /** The candidate stopped being reclaimable; nothing was deleted. */
        SKIPPED,

        /** The cold copy holds something the live transcript does not; nothing was deleted. */
        REFUSED
    }

    /**
     * Result of reclaiming one candidate.
     *
     * @param outcome  what happened
     * @param messages how many cold rows were deleted, {@code 0} unless the outcome is
     *                 {@link Outcome#RECLAIMED}
     * @param detail   the reason, empty unless the outcome is {@link Outcome#REFUSED}
     */
    private record CandidateResult(Outcome outcome, long messages, String detail) {

        static CandidateResult reclaimed(long messages) {
            return new CandidateResult(Outcome.RECLAIMED, messages, "");
        }

        static CandidateResult skipped() {
            return new CandidateResult(Outcome.SKIPPED, 0L, "");
        }

        static CandidateResult refused(String detail) {
            return new CandidateResult(Outcome.REFUSED, 0L, detail);
        }
    }
}
