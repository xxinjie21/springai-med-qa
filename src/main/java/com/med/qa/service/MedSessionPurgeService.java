package com.med.qa.service;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.config.MedSessionPurgeProperties;
import com.med.qa.domain.entity.ArchivedMessageDO;
import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.entity.SessionArchiveManifestDO;
import com.med.qa.domain.enums.SessionStatus;
import com.med.qa.mapper.ChatMessageMapper;
import com.med.qa.mapper.ChatSessionMapper;
import com.med.qa.mapper.SessionArchiveMapper;
import com.med.qa.memory.lock.SessionLockService;
import com.med.qa.memory.serde.ProtoMessageCodec;
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
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Retires the hot shard rows of a certified archived transcript — the "clean" half of the cold/hot
 * split D47/D48/D49 built up to, and the only code path in this project that deletes clinical data.
 *
 * <h2>Why this is allowed to delete anything</h2>
 * <p>D48 copies an archived transcript into the non-sharded cold store and certifies the copy with a
 * SHA-256 recorded in {@code med_session_archive}; D49 made that copy readable. Only then is a deletion
 * accountable: there exists a second, certified copy of exactly the bytes about to be removed. The
 * order of the two checks below is therefore not an implementation detail — the cold copy is verified
 * <em>before</em> the live shard is looked at, because until the cold copy reproduces the certification
 * the live rows are the only copy of the transcript there is, and deleting them would be data loss.</p>
 *
 * <h2>The three outcomes of one candidate</h2>
 * <ol>
 *   <li><b>Certified, hot rows still there</b> — the manifest digest is reproduced by the cold copy and
 *       by the live transcript, so the two are the same bytes and the shard copy is redundant. The rows
 *       are deleted and the session is marked retired.</li>
 *   <li><b>Certified, no hot rows left</b> — nothing to delete. This is the state an interrupted run
 *       leaves behind (the delete succeeded, the mark did not) and also the state of a genuinely empty
 *       transcript. The session is marked retired so it stops being a candidate, and counted as
 *       {@code skipped}. Without the mark the oldest such sessions would be fetched on every run and
 *       the job would never reach the ones behind them.</li>
 *   <li><b>Not accountable</b> — the manifest is missing, the cold copy does not reproduce it, or the
 *       live transcript does not. Nothing is deleted and nothing is marked; the candidate is counted as
 *       {@code refused} and reported with its reason. A refusal is not a retry loop: a session whose
 *       shard drifted away from its certification will be refused identically on every run, which is the
 *       point — it needs a human, and the report is what tells them.</li>
 * </ol>
 *
 * <h2>Why the live transcript must match too</h2>
 * <p>If the shard holds something other than the certified bytes, then the shard holds messages nobody
 * has certified. Deleting them would destroy the only record of them. So a mismatch refuses rather than
 * deletes; the certified copy is safe in the cold store either way.</p>
 *
 * <h2>Why there is a session lock (added in D51)</h2>
 * <p>This job was written without one, and the argument was explicit: its candidates are
 * {@code ARCHIVED}, which {@code SessionStatus#isWritable()} excludes, the write path checks that before
 * every turn, and the digest comparison is what actually detects a torn read (a lock prevents one, a
 * checksum notices one) — so a checksum on both copies was enough. D51 made that premise false by
 * adding {@link MedSessionRestoreService}, the first writer that may leave {@code ARCHIVED}, and a
 * checksum <em>cannot</em> see it: restoring changes the status and retires the certification without
 * touching a single transcript byte. Interleaved with a candidate already selected by this run, that
 * would delete the hot rows of a session a clinician had just moved back, leaving an {@code ACTIVE}
 * session whose read path serves an empty transcript.</p>
 *
 * <p>So every candidate is now processed inside {@link SessionLockService} — the same lock the append
 * path, {@code archiveSession} and the restore take — and the session is re-read inside it, because a
 * candidate selected before the restore is stale by construction. A candidate that is no longer
 * {@code ARCHIVED} is counted as {@code skipped} with zero writes; a busy lock is {@code skipped} too,
 * not a failure (the D47 convention). The digest checks stay exactly as they were: the lock prevents
 * the interleaving, the checksums still notice a torn read.</p>
 *
 * <h2>Failure semantics</h2>
 * <ul>
 *   <li><b>A single candidate never aborts the run.</b> Its failure is logged, counted and listed in
 *       {@link SessionPurgeReport#failures()}; the remaining candidates are still processed.</li>
 *   <li><b>A refusal is not an exception.</b> "Not accountable" is an expected outcome of a
 *       verification, counted in {@code refused} and reported with its reason, so an operator can tell a
 *       broken purge from a broken cold store.</li>
 *   <li><b>The cluster mutex is not optional.</b> Redis unreachable propagates as
 *       {@link ErrorCode#STORAGE_ERROR} instead of degrading to an unlocked run.</li>
 *   <li><b>The cold tables are read-only here.</b> No code path in this class deletes or updates a row
 *       of {@code med_message_archive} or {@code med_session_archive}.</li>
 * </ul>
 *
 * <p>Not annotated with {@code @Service} on purpose: {@code config/MedSessionPurgeConfig} declares it
 * behind {@code med.session.purge.enabled}, the same way the D47 sweep, the D48 export and the D38
 * rebuild are declared behind their switches. A bean that is always present could not be switched off,
 * and this is the last bean that should be un-switchable.</p>
 */
public class MedSessionPurgeService {

    private static final Logger log = LoggerFactory.getLogger(MedSessionPurgeService.class);

    private final ChatSessionMapper sessionMapper;

    private final SessionArchiveMapper archiveMapper;

    private final ChatMessageMapper messageMapper;

    private final ProtoMessageCodec codec;

    private final SessionLockService lockService;

    private final RedissonClient redissonClient;

    private final MedSessionPurgeProperties properties;

    private final Clock clock;

    /**
     * Creates the service used by the application context.
     *
     * <p>The Redisson client is injected lazily on purpose: creating it opens a connection, and the
     * application context must be able to start without Redis available.</p>
     *
     * @param sessionMapper  MyBatis mapper over {@code med_session}, must not be {@code null}
     * @param archiveMapper  MyBatis mapper over the cold archive tables, must not be {@code null}
     * @param messageMapper  MyBatis mapper over the sharded {@code med_message} table, must not be
     *                       {@code null}
     * @param codec          Protobuf codec of the unified storage spec, used to hash the live transcript
     *                       exactly as the archive certified it, must not be {@code null}
     * @param lockService    distributed session lock serializing one candidate against a restore, must
     *                       not be {@code null}
     * @param redissonClient the shared client, used only for the cluster-wide purge mutex, must not be
     *                       {@code null}
     * @param properties     the {@code med.session.purge.*} policy, must not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public MedSessionPurgeService(ChatSessionMapper sessionMapper,
                                 SessionArchiveMapper archiveMapper,
                                 ChatMessageMapper messageMapper,
                                 ProtoMessageCodec codec,
                                 SessionLockService lockService,
                                 @Lazy RedissonClient redissonClient,
                                 MedSessionPurgeProperties properties) {
        this(sessionMapper, archiveMapper, messageMapper, codec, lockService, redissonClient, properties,
                Clock.systemUTC());
    }

    /**
     * Creates the service with an explicit clock, so the cooling-off cutoff is deterministic in tests.
     *
     * @param sessionMapper  MyBatis mapper over {@code med_session}, must not be {@code null}
     * @param archiveMapper  MyBatis mapper over the cold archive tables, must not be {@code null}
     * @param messageMapper  MyBatis mapper over the sharded {@code med_message} table, must not be
     *                       {@code null}
     * @param codec          Protobuf codec of the unified storage spec, must not be {@code null}
     * @param lockService    distributed session lock serializing one candidate, must not be {@code null}
     * @param redissonClient the shared client, must not be {@code null}
     * @param properties     the {@code med.session.purge.*} policy, must not be {@code null}
     * @param clock          clock that stamps the cutoff and the retirement, must not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public MedSessionPurgeService(ChatSessionMapper sessionMapper,
                                 SessionArchiveMapper archiveMapper,
                                 ChatMessageMapper messageMapper,
                                 ProtoMessageCodec codec,
                                 SessionLockService lockService,
                                 @Lazy RedissonClient redissonClient,
                                 MedSessionPurgeProperties properties,
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
        if (codec == null) {
            throw new IllegalArgumentException("codec must not be null");
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
        this.codec = codec;
        this.lockService = lockService;
        this.redissonClient = redissonClient;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Runs one bounded purge.
     *
     * <p>The run is single-flight across the cluster: it acquires {@link MedSessionPurgeProperties#LOCK_KEY}
     * before touching the database and reports {@link SessionPurgeReport.Outcome#SKIPPED_LOCK_HELD} when
     * another replica is already purging. It then performs at most {@code max-batches} passes of
     * {@code batch-size} candidates — except in dry-run mode, which stops after the first pass because
     * nothing was marked and the next pass would simply fetch the same sessions again.</p>
     *
     * <p>A dry run still performs the full verification: it reads the manifest, both copies and their
     * digests, so the report says not only how many sessions <em>would</em> be retired but how many
     * would be refused. Reading is what the run does anyway; skipping it would turn the one report an
     * operator uses to decide whether to arm the job into a count of candidates.</p>
     *
     * @return the run report, never {@code null}
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when the mutex cannot be acquired because
     *                      Redis is unreachable, or when the candidate queries fail
     */
    public SessionPurgeReport purgeOnce() {
        long started = clock.millis();
        long archivedBefore = archivedBeforeEpochMillis();

        RLock lock = redissonClient.getLock(MedSessionPurgeProperties.LOCK_KEY);
        if (!acquire(lock)) {
            log.info("another replica is already purging certified transcripts, stepping aside");
            return SessionPurgeReport.skipped(archivedBefore, clock.millis() - started);
        }
        try {
            return runPurge(archivedBefore, started);
        } finally {
            release(lock);
        }
    }

    /**
     * Returns the cooling-off cutoff applied by {@link #purgeOnce()}: a session is eligible when it was
     * archived at or before this instant.
     *
     * @return epoch milliseconds of {@code now - min-archived-age}
     */
    public long archivedBeforeEpochMillis() {
        return clock.millis() - properties.getMinArchivedAge().toMillis();
    }

    /**
     * Reads the certified sessions the next run would consider, without changing anything.
     *
     * @param limit maximum number of rows, must be strictly positive
     * @return the candidates ordered by {@code updated_at} ascending, possibly empty, never {@code null}
     * @throws IllegalArgumentException if {@code limit} is not positive
     * @throws BizException             {@link ErrorCode#STORAGE_ERROR} when the query fails
     */
    public List<ChatSessionDO> findPurgeableCandidates(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive but was " + limit);
        }
        return selectBatch(archivedBeforeEpochMillis(), limit);
    }

    /**
     * Counts the certified sessions still holding hot rows right now.
     *
     * @return the backlog size, never negative
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when the query fails
     */
    public long countPurgeableCandidates() {
        return countPurgeable(archivedBeforeEpochMillis());
    }

    /**
     * Performs the bounded passes of one run that already holds the cluster mutex.
     *
     * @param archivedBefore the cooling-off cutoff
     * @param started        epoch milliseconds the run began, used for the duration
     * @return the run report
     */
    private SessionPurgeReport runPurge(long archivedBefore, long started) {
        int candidates = 0;
        int purged = 0;
        int skipped = 0;
        int refused = 0;
        int failed = 0;
        int batches = 0;
        List<String> failures = new ArrayList<>();

        // A dry run must not loop: it marks nothing, so the next pass would return the same sessions.
        int maxPasses = properties.isDryRun() ? 1 : properties.getMaxBatches();
        for (int pass = 1; pass <= maxPasses; pass++) {
            List<ChatSessionDO> batch = selectBatch(archivedBefore, properties.getBatchSize());
            if (batch.isEmpty()) {
                break;
            }
            batches++;
            candidates += batch.size();
            for (ChatSessionDO candidate : batch) {
                try {
                    CandidateResult result = purgeUnderSessionLock(candidate);
                    switch (result.outcome()) {
                        case PURGED -> purged++;
                        case SKIPPED -> skipped++;
                        case REFUSED -> {
                            refused++;
                            failures.add(candidate.getSessionId() + ": REFUSED: " + result.detail());
                        }
                    }
                } catch (RuntimeException ex) {
                    failed++;
                    failures.add(describeFailure(candidate.getSessionId(), ex));
                    log.warn("failed to purge certified session {}, continuing with the rest",
                            candidate.getSessionId(), ex);
                }
            }
            if (batch.size() < properties.getBatchSize()) {
                break;
            }
        }

        long remaining = countPurgeable(archivedBefore);
        SessionPurgeReport report = new SessionPurgeReport(SessionPurgeReport.Outcome.COMPLETED,
                properties.isDryRun(), archivedBefore, candidates, purged, skipped, refused, failed,
                batches, remaining, clock.millis() - started, failures);
        log.info("hot-shard purge finished: {}", report.summary());
        return report;
    }

    /**
     * Processes one candidate inside its session lock, re-asserting the status the candidate query
     * assumed.
     *
     * <p>The re-read is not paranoia about the lock: the batch was selected before the lock was taken,
     * so a candidate the restore moved back in the meantime arrives here stale. Only a candidate that is
     * still {@code ARCHIVED} may lose its hot rows. A busy session is a {@code skipped} outcome, not a
     * failure — the next run picks it up (the D47 convention).</p>
     *
     * @param candidate the session the candidate query returned
     * @return what happened to the candidate
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when the re-read fails
     */
    private CandidateResult purgeUnderSessionLock(ChatSessionDO candidate) {
        try {
            return lockService.executeLocked(candidate, () -> {
                ChatSessionDO current = readSession(candidate.getSessionId());
                if (current == null || current.getStatus() != SessionStatus.ARCHIVED) {
                    log.info("session {} is no longer archived ({}), skipping it",
                            candidate.getSessionId(),
                            current == null ? "row missing" : current.getStatus().name());
                    return CandidateResult.skipped();
                }
                return purgeOne(current);
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
                    "failed to re-read session " + sessionId + " before purging it", ex);
        }
    }

    /**
     * Verifies one candidate and, when it is accountable, retires its hot rows.
     *
     * @param session the certified session the candidate query returned
     * @return what happened to the candidate
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when a read, a delete or the mark fails
     */
    private CandidateResult purgeOne(ChatSessionDO session) {
        String sessionId = session.getSessionId();
        boolean dryRun = properties.isDryRun();

        // 1. The certification itself. Without a manifest nothing proves what the transcript was, so
        //    there is nothing a deletion could be measured against.
        SessionArchiveManifestDO manifest = readManifest(sessionId);
        if (manifest == null) {
            return CandidateResult.refused("no export manifest certifies this session");
        }
        String certified = manifest.getPayloadChecksum();

        // 2. The cold copy must be the certified transcript. This comes first on purpose: until it
        //    holds, the live shard is the only copy of the transcript and deleting it would be data loss.
        List<ArchivedMessageDO> cold = readArchived(sessionId);
        String coldDigest = TranscriptDigests.ofArchived(cold);
        if (!coldDigest.equals(certified)) {
            log.warn("cold copy of session {} does not reproduce its certification, refusing to purge it",
                    sessionId);
            return CandidateResult.refused("the cold copy does not reproduce the certified digest");
        }

        // 3. The live shard. An empty one means the rows are already gone (an interrupted run, or a
        //    genuinely empty transcript): nothing to delete, but the session must leave the candidate
        //    set or the job would fetch it again on every run.
        List<ChatMessageDO> hot = readTranscript(sessionId);
        if (hot.isEmpty()) {
            if (!dryRun) {
                markPurged(sessionId);
            }
            log.info("certified session {} has no hot rows left, retiring it from the candidate set",
                    sessionId);
            return CandidateResult.skipped();
        }

        // 4. The shard must hold the certified bytes too. A shard that drifted holds messages nobody
        //    certified; deleting them would destroy the only record of them.
        String hotDigest = TranscriptDigests.ofTranscript(hot, codec);
        if (!hotDigest.equals(certified)) {
            log.warn("live transcript of session {} does not reproduce its certification "
                    + "({} messages), refusing to purge it", sessionId, hot.size());
            return CandidateResult.refused("the live shard does not reproduce the certified digest");
        }

        if (dryRun) {
            log.info("dry run: the {} hot rows of certified session {} would be deleted",
                    hot.size(), sessionId);
            return CandidateResult.purged();
        }

        int deleted = deleteHot(sessionId);
        // Delete first, mark second: an interruption in between leaves a session whose rows are gone but
        // which is still a candidate, and the next run finds nothing to delete, marks it and converges.
        // The reverse order could leave rows nobody ever looks at again.
        markPurged(sessionId);
        if (deleted <= 0) {
            log.info("certified session {} had no hot rows left to delete, retiring it anyway",
                    sessionId);
            return CandidateResult.skipped();
        }
        log.info("purged {} hot rows of certified session {}", deleted, sessionId);
        return CandidateResult.purged();
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
     * Fetches one page of certified sessions whose hot rows are old enough to retire.
     *
     * @param archivedBefore the cooling-off cutoff
     * @param limit          maximum number of rows
     * @return the page, possibly empty, never {@code null}
     */
    private List<ChatSessionDO> selectBatch(long archivedBefore, int limit) {
        List<ChatSessionDO> batch;
        try {
            batch = archiveMapper.selectPurgeableArchivedSessions(SessionStatus.ARCHIVED, archivedBefore,
                    limit);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to list the certified sessions awaiting a purge", ex);
        }
        return batch == null ? Collections.emptyList() : batch;
    }

    /**
     * Counts the certified sessions still holding hot rows.
     *
     * @param archivedBefore the cooling-off cutoff
     * @return the backlog size, never negative
     */
    private long countPurgeable(long archivedBefore) {
        try {
            return Math.max(archiveMapper.countPurgeableArchivedSessions(SessionStatus.ARCHIVED,
                    archivedBefore), 0L);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to count the certified sessions awaiting a purge", ex);
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
                    "failed to read the transcript of session " + sessionId, ex);
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
     * Loads the export manifest of a session.
     *
     * @param sessionId the session id
     * @return the manifest, or {@code null} when the session was never exported
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
     * Deletes every hot row of a session. The {@code session_id} shard key makes this a single-shard
     * delete, and ShardingSphere performs the routing.
     *
     * @param sessionId the session id, also the sharding key
     * @return number of deleted rows
     */
    private int deleteHot(String sessionId) {
        try {
            return messageMapper.deleteBySessionId(sessionId);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to delete the hot rows of session " + sessionId, ex);
        }
    }

    /**
     * Retires a session from the candidate set.
     *
     * @param sessionId the session id
     * @return {@code 1} when the session was retired by this call, {@code 0} when another run had
     *         already retired it
     */
    private int markPurged(String sessionId) {
        try {
            return sessionMapper.markPurged(sessionId, SessionStatus.ARCHIVED, clock.millis());
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to retire session " + sessionId + " from the purge candidate set", ex);
        }
    }

    /**
     * Acquires the cluster-wide purge mutex.
     *
     * @param lock the mutex
     * @return {@code true} when this run may purge
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
                    "interrupted while acquiring the purge lock", e);
        } catch (RedisException e) {
            throw new BizException(ErrorCode.STORAGE_ERROR, "failed to acquire the purge lock", e);
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
            log.warn("failed to release the purge lock, leaving it to the lease expiry", e);
        }
    }

    /** What happened to one candidate. */
    private enum Outcome {

        /** The certified hot rows were deleted and the session retired. */
        PURGED,

        /** The session had no hot rows left; it was retired from the candidate set. */
        SKIPPED,

        /** The session was not accountable; nothing was deleted. */
        REFUSED
    }

    /**
     * Result of purging one candidate.
     *
     * @param outcome what happened
     * @param detail  the reason, empty unless the outcome is {@link Outcome#REFUSED}
     */
    private record CandidateResult(Outcome outcome, String detail) {

        static CandidateResult purged() {
            return new CandidateResult(Outcome.PURGED, "");
        }

        static CandidateResult skipped() {
            return new CandidateResult(Outcome.SKIPPED, "");
        }

        static CandidateResult refused(String detail) {
            return new CandidateResult(Outcome.REFUSED, detail);
        }
    }
}
