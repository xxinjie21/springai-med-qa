package com.med.qa.service;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.config.MedSessionArchiveProperties;
import com.med.qa.domain.entity.ArchivedMessageDO;
import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.entity.SessionArchiveManifestDO;
import com.med.qa.domain.enums.SessionStatus;
import com.med.qa.mapper.ChatMessageMapper;
import com.med.qa.mapper.SessionArchiveMapper;
import com.med.qa.memory.serde.ProtoMessageCodec;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Copies the transcript of every archived consultation into the cold store and certifies it with a
 * checksum — the other half of the cold/hot split D47 started.
 *
 * <h2>Why the archive exists at all</h2>
 * <p>D47 flips {@code med_session.status} to {@code ARCHIVED} and evicts the Redis window, but the
 * transcript never moves: it stays in the 16 sharded {@code med_message} tables. "Archived" was
 * therefore a claim about a status column, with nothing in storage that could back it up — no cold
 * copy, and no digest that could prove one complete. This service produces both: the transcript is
 * copied into the non-sharded {@code med_message_archive} table in the storage-spec Protobuf encoding
 * (so the heterogeneous Python middleware can read it from {@code med_session.proto} alone), and a
 * manifest row records how many messages it holds and the SHA-256 of their canonical form.</p>
 *
 * <h2>Why a copy can be trusted</h2>
 * <p>Two checks, both performed before the manifest is written — the manifest is the certification, so
 * it must not exist until the evidence does:</p>
 * <ol>
 *   <li><b>The source must not have moved.</b> The transcript is read once to build the copy and read
 *       again afterwards; if the digest changed in between, the export refuses to certify. The
 *       invariant that makes this a formality is that {@code ARCHIVED} is terminal and the write path
 *       refuses non-{@code ACTIVE} sessions ({@code MedChatSessionService#requireWritableSession} →
 *       {@code SessionStatus#isWritable()}). But that invariant belongs to <em>another component</em>,
 *       so the export does not take it on faith: a future change that made archived sessions writable
 *       again would show up here as a mismatch instead of as a silently stale cold copy.</li>
 *   <li><b>The cold copy must reproduce the digest.</b> The archive rows are read back and hashed with
 *       the same canonical form. This is what turns "the INSERT returned 1" into "the bytes that are
 *       now in the cold store are the bytes that were in the transcript".</li>
 * </ol>
 *
 * <h2>Why a refusal is not a dead end</h2>
 * <p>Rows are copied <em>before</em> certification, and the copy is idempotent (primary key
 * {@code (session_id, message_id)} plus {@code ON DUPLICATE KEY UPDATE message_id = message_id}). A
 * session whose certification was refused keeps no manifest, so it stays a candidate for the next run,
 * which copies only the messages that are missing and then certifies. Retrying converges.</p>
 *
 * <h2>Failure semantics</h2>
 * <ul>
 *   <li><b>A single candidate never aborts the run.</b> Its failure is logged, counted and listed in
 *       {@link SessionArchiveExportReport#failures()}; the remaining candidates are still processed.</li>
 *   <li><b>A refusal is not an exception.</b> "Source moved" and "cold copy differs" are expected
 *       outcomes of a verification, counted in {@code mismatched} and reported with their reason, so
 *       an operator sees the difference between a broken export and a broken cold store.</li>
 *   <li><b>The cluster mutex is not optional.</b> Redis unreachable propagates as
 *       {@link ErrorCode#STORAGE_ERROR} instead of degrading to an unlocked run.</li>
 *   <li><b>Nothing here deletes anything.</b> The hot transcript is read-only for this service, and no
 *       code path removes an archive row.</li>
 * </ul>
 *
 * <h2>Why there is no session lock</h2>
 * <p>D47's sweep takes the session lock because it races the message-append path for a session that is
 * still being written. This export does not: its candidates are {@code ARCHIVED}, which
 * {@code SessionStatus#isWritable()} excludes, and the write path checks that before every turn. Adding
 * the lock would buy nothing and cost a failure mode (a busy lock would have to be counted as
 * {@code skipped}), while the digest comparison above is what actually detects a torn read — a lock
 * prevents one, a checksum notices one. If a future iteration makes archived sessions writable again,
 * that change must add the lock here <em>and</em> keep the check.</p>
 *
 * <p>Not annotated with {@code @Service} on purpose: {@code config/MedSessionArchiveConfig} declares it
 * behind {@code med.session.archive.enabled}, the same way the D47 sweep and the D38 rebuild are
 * declared behind their switches. A bean that is always present could not be switched off.</p>
 */
public class MedSessionArchiveExportService {

    private static final Logger log = LoggerFactory.getLogger(MedSessionArchiveExportService.class);

    private final ChatMessageMapper messageMapper;

    private final SessionArchiveMapper archiveMapper;

    private final ProtoMessageCodec codec;

    private final RedissonClient redissonClient;

    private final MedSessionArchiveProperties properties;

    private final Clock clock;

    /**
     * Creates the service used by the application context.
     *
     * <p>The Redisson client is injected lazily on purpose: creating it opens a connection, and the
     * application context must be able to start without Redis available.</p>
     *
     * @param messageMapper  MyBatis mapper over the sharded {@code med_message} table, must not be
     *                       {@code null}
     * @param archiveMapper  MyBatis mapper over the cold archive tables, must not be {@code null}
     * @param codec          Protobuf codec of the unified storage spec, must not be {@code null}
     * @param redissonClient the shared client, used only for the cluster-wide export mutex, must not be
     *                       {@code null}
     * @param properties     the {@code med.session.archive.*} policy, must not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public MedSessionArchiveExportService(ChatMessageMapper messageMapper,
                                          SessionArchiveMapper archiveMapper,
                                          ProtoMessageCodec codec,
                                          @Lazy RedissonClient redissonClient,
                                          MedSessionArchiveProperties properties) {
        this(messageMapper, archiveMapper, codec, redissonClient, properties, Clock.systemUTC());
    }

    /**
     * Creates the service with an explicit clock, so the archived-at stamp is deterministic in tests.
     *
     * @param messageMapper  MyBatis mapper over the sharded {@code med_message} table, must not be
     *                       {@code null}
     * @param archiveMapper  MyBatis mapper over the cold archive tables, must not be {@code null}
     * @param codec          Protobuf codec of the unified storage spec, must not be {@code null}
     * @param redissonClient the shared client, must not be {@code null}
     * @param properties     the {@code med.session.archive.*} policy, must not be {@code null}
     * @param clock          clock that stamps {@code archived_at}, must not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public MedSessionArchiveExportService(ChatMessageMapper messageMapper,
                                          SessionArchiveMapper archiveMapper,
                                          ProtoMessageCodec codec,
                                          @Lazy RedissonClient redissonClient,
                                          MedSessionArchiveProperties properties,
                                          Clock clock) {
        if (messageMapper == null) {
            throw new IllegalArgumentException("messageMapper must not be null");
        }
        if (archiveMapper == null) {
            throw new IllegalArgumentException("archiveMapper must not be null");
        }
        if (codec == null) {
            throw new IllegalArgumentException("codec must not be null");
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
        this.messageMapper = messageMapper;
        this.archiveMapper = archiveMapper;
        this.codec = codec;
        this.redissonClient = redissonClient;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Runs one bounded export.
     *
     * <p>The run is single-flight across the cluster: it acquires
     * {@link MedSessionArchiveProperties#LOCK_KEY} before touching the database and reports
     * {@link SessionArchiveExportReport.Outcome#SKIPPED_LOCK_HELD} when another replica is already
     * exporting. It then performs at most {@code max-batches} passes of {@code batch-size} candidates —
     * except in dry-run mode, which stops after the first pass because nothing was written and the next
     * pass would simply fetch the same sessions again.</p>
     *
     * @return the run report, never {@code null}
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when the mutex cannot be acquired because
     *                      Redis is unreachable, or when the candidate queries fail
     */
    public SessionArchiveExportReport exportOnce() {
        long started = clock.millis();
        RLock lock = redissonClient.getLock(MedSessionArchiveProperties.LOCK_KEY);
        if (!acquire(lock)) {
            log.info("another replica is already exporting archived transcripts, stepping aside");
            return SessionArchiveExportReport.skipped(clock.millis() - started);
        }
        try {
            return runExport(started);
        } finally {
            release(lock);
        }
    }

    /**
     * Counts the archived sessions that have no export manifest right now.
     *
     * @return the backlog size, never negative
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when the query fails
     */
    public long countPending() {
        return countUnexported();
    }

    /**
     * Re-checks one exported session against its manifest, without changing anything.
     *
     * <p>Exists so that the certification the export wrote can be audited independently of the run that
     * wrote it: by an operator investigating the cold store, by a future purge job that must not delete
     * rows it cannot account for, and by the contract tests. It shares
     * {@link SessionArchiveChecksum} with the export, so "what the export certified" and "what a
     * verification recomputes" cannot drift into two different notions of equality.</p>
     *
     * <p>System-side operation: it is deliberately not reachable from any controller, because it reads
     * a transcript by session id without a tenant/department scope and therefore must never be handed
     * to a request-scoped caller (the same reasoning that kept D41/D42 from adding unguarded
     * primitives to the request path).</p>
     *
     * @param sessionId the session to verify, must not be blank
     * @return the verdict, never {@code null}
     * @throws IllegalArgumentException if {@code sessionId} is blank
     * @throws BizException             {@link ErrorCode#STORAGE_ERROR} when a read fails
     */
    public SessionArchiveVerification verify(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        List<ChatMessageDO> transcript = readTranscript(sessionId);
        List<ArchivedMessageDO> archived = readArchived(sessionId);
        String hotChecksum = digestOfTranscript(transcript);
        String archivedChecksum = digestOfArchived(archived);
        SessionArchiveManifestDO manifest = readManifest(sessionId);

        SessionArchiveVerification.Status status;
        if (manifest == null) {
            status = SessionArchiveVerification.Status.MANIFEST_MISSING;
        } else if (!hotChecksum.equals(manifest.getPayloadChecksum())) {
            status = SessionArchiveVerification.Status.TRANSCRIPT_CHANGED;
        } else if (!archivedChecksum.equals(manifest.getPayloadChecksum())) {
            status = SessionArchiveVerification.Status.COLD_COPY_DIFFERS;
        } else {
            status = SessionArchiveVerification.Status.VERIFIED;
        }
        SessionArchiveVerification verification = new SessionArchiveVerification(status,
                transcript.size(), archived.size(), hotChecksum, archivedChecksum,
                manifest == null ? "" : manifest.getPayloadChecksum());
        log.info("archive verification of session {}: {}", sessionId, verification.summary());
        return verification;
    }

    /**
     * Performs the bounded passes of one run that already holds the cluster mutex.
     *
     * @param started epoch milliseconds the run began, used for the duration
     * @return the run report
     */
    private SessionArchiveExportReport runExport(long started) {
        int candidates = 0;
        int exported = 0;
        int skipped = 0;
        int mismatched = 0;
        int failed = 0;
        int batches = 0;
        List<String> failures = new ArrayList<>();

        // A dry run must not loop: it writes nothing, so the next pass would return the same sessions.
        int maxPasses = properties.isDryRun() ? 1 : properties.getMaxBatches();
        for (int pass = 1; pass <= maxPasses; pass++) {
            List<ChatSessionDO> batch = selectBatch(properties.getBatchSize());
            if (batch.isEmpty()) {
                break;
            }
            batches++;
            candidates += batch.size();
            for (ChatSessionDO candidate : batch) {
                if (properties.isDryRun()) {
                    log.info("dry run: transcript of archived session {} would be exported",
                            candidate.getSessionId());
                    continue;
                }
                try {
                    CandidateResult result = exportOne(candidate);
                    switch (result.outcome()) {
                        case EXPORTED -> exported++;
                        case SKIPPED -> skipped++;
                        case MISMATCH -> {
                            mismatched++;
                            failures.add(candidate.getSessionId() + ": MISMATCH: " + result.detail());
                        }
                    }
                } catch (RuntimeException ex) {
                    failed++;
                    failures.add(describeFailure(candidate.getSessionId(), ex));
                    log.warn("failed to export archived session {}, continuing with the rest",
                            candidate.getSessionId(), ex);
                }
            }
            if (batch.size() < properties.getBatchSize()) {
                break;
            }
        }

        long remaining = countUnexported();
        SessionArchiveExportReport report = new SessionArchiveExportReport(
                SessionArchiveExportReport.Outcome.COMPLETED, properties.isDryRun(), candidates,
                exported, skipped, mismatched, failed, batches, remaining, clock.millis() - started,
                failures);
        log.info("cold archive export finished: {}", report.summary());
        return report;
    }

    /**
     * Copies one archived transcript into the cold store and certifies it, unless it was exported
     * already or the copy does not verify.
     *
     * @param session the archived session to export
     * @return what happened to the candidate
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when a read or write fails
     */
    private CandidateResult exportOne(ChatSessionDO session) {
        String sessionId = session.getSessionId();
        long archivedAt = clock.millis();

        List<ChatMessageDO> source = readTranscript(sessionId);
        EncodedTranscript encoded = encode(session, source, archivedAt);
        for (ArchivedMessageDO row : encoded.rows()) {
            insertArchived(row);
        }

        // The copy is written; now prove it is worth certifying.
        String rereadChecksum = digestOfTranscript(readTranscript(sessionId));
        if (!rereadChecksum.equals(encoded.checksum())) {
            log.warn("archived session {} changed while its transcript was being copied, "
                    + "refusing to certify it", sessionId);
            return CandidateResult.mismatch("the source transcript changed while it was being copied");
        }
        String archivedChecksum = digestOfArchived(readArchived(sessionId));
        if (!archivedChecksum.equals(encoded.checksum())) {
            log.warn("cold copy of archived session {} does not reproduce the source digest, "
                    + "refusing to certify it", sessionId);
            return CandidateResult.mismatch("the cold copy does not reproduce the source digest");
        }

        int inserted = insertManifest(new SessionArchiveManifestDO(sessionId, session.getTenantId(),
                session.getDeptId(), session.getPatientId(), source.size(), encoded.checksum(),
                archivedAt));
        if (inserted <= 0) {
            log.info("archived session {} already has an export manifest, leaving it untouched",
                    sessionId);
            return CandidateResult.skipped();
        }
        log.info("exported the transcript of archived session {} ({} messages, checksum {})",
                sessionId, source.size(), encoded.checksum());
        return CandidateResult.exported();
    }

    /**
     * Encodes a transcript once, producing both the archive rows and the digest they certify.
     *
     * <p>Encoding here rather than at each use is what keeps the digest and the stored payloads
     * identical by construction: they come from the same byte arrays.</p>
     *
     * @param session    the session owning the transcript
     * @param transcript the transcript in storage order
     * @param archivedAt copy timestamp to stamp on every row
     * @return the encoded rows and their canonical digest
     */
    private EncodedTranscript encode(ChatSessionDO session, List<ChatMessageDO> transcript,
                                     long archivedAt) {
        List<ArchivedMessageDO> rows = new ArrayList<>(transcript.size());
        List<SessionArchiveChecksum.Entry> entries = new ArrayList<>(transcript.size());
        for (ChatMessageDO message : transcript) {
            byte[] payload = codec.encodeMessage(message);
            rows.add(new ArchivedMessageDO(session.getSessionId(), message.getMessageId(),
                    session.getTenantId(), session.getDeptId(), session.getPatientId(),
                    message.getCreatedAt(), archivedAt, payload));
            entries.add(new SessionArchiveChecksum.Entry(message.getMessageId(), message.getCreatedAt(),
                    payload));
        }
        return new EncodedTranscript(SessionArchiveChecksum.of(entries), rows);
    }

    /**
     * Computes the canonical digest of a live transcript by encoding it exactly as the export does.
     *
     * @param transcript the transcript in storage order
     * @return the 64-character hex digest, never {@code null}
     */
    private String digestOfTranscript(List<ChatMessageDO> transcript) {
        List<SessionArchiveChecksum.Entry> entries = new ArrayList<>(transcript.size());
        for (ChatMessageDO message : transcript) {
            entries.add(new SessionArchiveChecksum.Entry(message.getMessageId(),
                    message.getCreatedAt(), codec.encodeMessage(message)));
        }
        return SessionArchiveChecksum.of(entries);
    }

    /**
     * Computes the canonical digest of a cold copy from the payloads it actually stores.
     *
     * @param archived the archive rows in storage order
     * @return the 64-character hex digest, never {@code null}
     */
    private static String digestOfArchived(List<ArchivedMessageDO> archived) {
        List<SessionArchiveChecksum.Entry> entries = new ArrayList<>(archived.size());
        for (ArchivedMessageDO row : archived) {
            entries.add(new SessionArchiveChecksum.Entry(row.getMessageId(), row.getCreatedAt(),
                    row.getPayload()));
        }
        return SessionArchiveChecksum.of(entries);
    }

    /**
     * Renders one failure line for the report.
     *
     * <p>A {@link BizException} is rendered with its {@link ErrorCode} rather than its class name:
     * "STORAGE_ERROR" tells an operator whether MySQL was unreachable or a write was rejected, while
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
     * Fetches one page of archived sessions that still have no manifest.
     *
     * @param limit maximum number of rows
     * @return the page, possibly empty, never {@code null}
     */
    private List<ChatSessionDO> selectBatch(int limit) {
        List<ChatSessionDO> batch;
        try {
            batch = archiveMapper.selectUnexportedArchivedSessions(SessionStatus.ARCHIVED, limit);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to list the archived sessions awaiting an export", ex);
        }
        return batch == null ? Collections.emptyList() : batch;
    }

    /**
     * Counts the archived sessions outstanding for an export.
     *
     * @return the backlog size, never negative
     */
    private long countUnexported() {
        try {
            return Math.max(archiveMapper.countUnexportedArchivedSessions(SessionStatus.ARCHIVED), 0L);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to count the archived sessions awaiting an export", ex);
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
     * Copies one message into the cold store.
     *
     * @param row the archive row to write
     */
    private void insertArchived(ArchivedMessageDO row) {
        try {
            archiveMapper.insertIfAbsent(row);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to archive message " + row.getMessageId(), ex);
        }
    }

    /**
     * Writes the export manifest, i.e. certifies the session.
     *
     * @param manifest the manifest to write
     * @return {@code 1} when written, {@code 0} when the session was already exported
     */
    private int insertManifest(SessionArchiveManifestDO manifest) {
        try {
            return archiveMapper.insertManifestIfAbsent(manifest);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to record the export manifest of session " + manifest.getSessionId(), ex);
        }
    }

    /**
     * Acquires the cluster-wide export mutex.
     *
     * @param lock the mutex
     * @return {@code true} when this run may export
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
                    "interrupted while acquiring the archive export lock", e);
        } catch (RedisException e) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to acquire the archive export lock", e);
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
            log.warn("failed to release the archive export lock, leaving it to the lease expiry", e);
        }
    }

    /** What happened to one candidate. */
    private enum Outcome {

        /** The transcript was copied and certified with a manifest. */
        EXPORTED,

        /** Another run had already certified the session. */
        SKIPPED,

        /** The copy was refused certification; {@link CandidateResult#detail()} says why. */
        MISMATCH
    }

    /**
     * Result of exporting one candidate.
     *
     * @param outcome what happened
     * @param detail  the reason, empty unless the outcome is {@link Outcome#MISMATCH}
     */
    private record CandidateResult(Outcome outcome, String detail) {

        static CandidateResult exported() {
            return new CandidateResult(Outcome.EXPORTED, "");
        }

        static CandidateResult skipped() {
            return new CandidateResult(Outcome.SKIPPED, "");
        }

        static CandidateResult mismatch(String detail) {
            return new CandidateResult(Outcome.MISMATCH, detail);
        }
    }

    /**
     * A transcript encoded once: the archive rows to write and the digest they certify.
     *
     * @param checksum the canonical digest of the rows
     * @param rows     the archive rows, in transcript order
     */
    private record EncodedTranscript(String checksum, List<ArchivedMessageDO> rows) {
    }
}
