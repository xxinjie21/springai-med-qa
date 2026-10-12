package com.med.qa.service;

import com.med.qa.audit.annotation.MedAudit;
import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.entity.SessionArchiveManifestDO;
import com.med.qa.domain.enums.SessionStatus;
import com.med.qa.mapper.ChatMessageMapper;
import com.med.qa.mapper.ChatSessionMapper;
import com.med.qa.mapper.SessionArchiveMapper;
import com.med.qa.memory.lock.SessionLockService;
import com.med.qa.memory.serde.ProtoMessageCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Collections;
import java.util.List;

/**
 * Moves a wrongly archived consultation session back to the live tier (D51).
 *
 * <h2>The promise this closes</h2>
 * <p>Every cold-tier artefact built so far justifies itself with the same sentence: "a wrongly archived
 * session can be moved back". {@link MedSessionRetentionService} states it, the retention and archive
 * property classes state it, D50's cooling-off window exists <em>for</em> it (the purge refuses to
 * reclaim a recently archived transcript so that moving it back stays possible), and the handbook
 * repeats it verbatim. Until this class existed, no code could do it: the lifecycle service offered
 * create / get / close / archive / list only, and {@code closeSession} documented {@link
 * SessionStatus#ARCHIVED} as the terminal state. Sixth instance of the same defect class — a comment
 * promising behaviour no code provides — and this time the cost had already been paid, in a cooling-off
 * window that delayed reclamation for a capability that did not exist.</p>
 *
 * <h2>What "moving back" means, and what it deliberately does not</h2>
 * <p>Restoring flips the session to {@link SessionStatus#ACTIVE} so it accepts messages again, and
 * retires the export certification that governed it while it was cold. It does <em>not</em> rebuild the
 * live rows from {@code med_message_archive}: re-materialising the hot tier would turn archiving into a
 * physically reversible operation and would make D50's cooling-off window meaningless. The window is
 * exactly the interval in which the live copy still exists, so the rule is:</p>
 * <ul>
 *   <li>the live shard must still hold the transcript the certification describes — then it becomes the
 *       record of truth again;</li>
 *   <li>once the purge reclaimed it, the session cannot be moved back. The consultation is not lost: it
 *       stays readable through {@link MedTranscriptService}, which serves the certified cold copy.</li>
 * </ul>
 *
 * <h2>Why the certification is retired before the status is flipped</h2>
 * <p>The manifest is a <em>state</em> row — D48's export query and D50's purge query both read it —
 * while the cold rows are the evidence and are never touched. Order matters: retiring the certification
 * first leaves the interruption window in the shape "ARCHIVED with no manifest", which the next export
 * re-certifies from the very same transcript and therefore converges. The reverse order would leave an
 * ACTIVE session carrying a manifest that describes the transcript as it was before the session became
 * writable again, and since the export's candidate predicate is {@code NOT EXISTS (manifest)} the
 * session could never be re-certified — its cold copy would permanently claim to be a transcript that
 * has since grown.</p>
 *
 * <h2>Concurrency</h2>
 * <p>Runs inside the Redisson session lock of {@link SessionLockService} — the same lock the message
 * append path, {@code archiveSession} and the two cold-tier jobs take. The jobs only acquired it in
 * this iteration: D48 and D50 both argued they could skip it because the candidates were ARCHIVED and
 * "the write path rejects an archived session", so a checksum comparison was all they needed to detect
 * a torn read. Restoring is the first writer that may <em>leave</em> ARCHIVED, and a checksum cannot see
 * it — the transcript bytes do not change, only the status and the certification do. So the premise
 * died with this class, and both jobs now hold the lock and re-assert the status inside it.</p>
 *
 * <h2>Why there is no switch</h2>
 * <p>Restoring is not an optional background capability: it is the inverse of {@code archiveSession},
 * which is always available. A switch would let an operator make a mis-archived consultation
 * unrecoverable, which is precisely what the retention policy promised would not happen.</p>
 *
 * <p>Always present as a {@code @Service}, and it adds no new "mutate a session by id" primitive: scope
 * and ownership are resolved through {@link MedChatSessionService#getSession}, so an out-of-scope
 * session is reported absent and a patient may only restore their own consultation.</p>
 */
@Service
public class MedSessionRestoreService {

    private static final Logger log = LoggerFactory.getLogger(MedSessionRestoreService.class);

    private final MedChatSessionService sessionService;

    private final SessionLockService lockService;

    private final ChatSessionMapper sessionMapper;

    private final ChatMessageMapper messageMapper;

    private final SessionArchiveMapper archiveMapper;

    private final ProtoMessageCodec codec;

    private final Clock clock;

    /**
     * Creates the service used by the application context.
     *
     * @param sessionService lifecycle service used to resolve and authorize the session, must not be
     *                       {@code null}
     * @param lockService    distributed session lock serializing the transition, must not be
     *                       {@code null}
     * @param sessionMapper  MyBatis mapper over {@code med_session}, must not be {@code null}
     * @param messageMapper  MyBatis mapper over the sharded {@code med_message} table, must not be
     *                       {@code null}
     * @param archiveMapper  MyBatis mapper over the cold archive tables, must not be {@code null}
     * @param codec          Protobuf codec of the unified storage spec, must not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    @Autowired
    public MedSessionRestoreService(MedChatSessionService sessionService,
                                    SessionLockService lockService,
                                    ChatSessionMapper sessionMapper,
                                    ChatMessageMapper messageMapper,
                                    SessionArchiveMapper archiveMapper,
                                    ProtoMessageCodec codec) {
        this(sessionService, lockService, sessionMapper, messageMapper, archiveMapper, codec,
                Clock.systemUTC());
    }

    /**
     * Creates the service with an explicit clock, so the transition timestamp is deterministic in tests.
     *
     * @param sessionService lifecycle service used to resolve and authorize the session, must not be
     *                       {@code null}
     * @param lockService    distributed session lock serializing the transition, must not be
     *                       {@code null}
     * @param sessionMapper  MyBatis mapper over {@code med_session}, must not be {@code null}
     * @param messageMapper  MyBatis mapper over the sharded {@code med_message} table, must not be
     *                       {@code null}
     * @param archiveMapper  MyBatis mapper over the cold archive tables, must not be {@code null}
     * @param codec          Protobuf codec of the unified storage spec, must not be {@code null}
     * @param clock          clock stamping {@code updated_at}, must not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public MedSessionRestoreService(MedChatSessionService sessionService,
                                    SessionLockService lockService,
                                    ChatSessionMapper sessionMapper,
                                    ChatMessageMapper messageMapper,
                                    SessionArchiveMapper archiveMapper,
                                    ProtoMessageCodec codec,
                                    Clock clock) {
        if (sessionService == null) {
            throw new IllegalArgumentException("sessionService must not be null");
        }
        if (lockService == null) {
            throw new IllegalArgumentException("lockService must not be null");
        }
        if (sessionMapper == null) {
            throw new IllegalArgumentException("sessionMapper must not be null");
        }
        if (messageMapper == null) {
            throw new IllegalArgumentException("messageMapper must not be null");
        }
        if (archiveMapper == null) {
            throw new IllegalArgumentException("archiveMapper must not be null");
        }
        if (codec == null) {
            throw new IllegalArgumentException("codec must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.sessionService = sessionService;
        this.lockService = lockService;
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.archiveMapper = archiveMapper;
        this.codec = codec;
        this.clock = clock;
    }

    /**
     * Moves one archived session back to {@link SessionStatus#ACTIVE}.
     *
     * <p>Three checks stand between the request and the transition, in this order:</p>
     * <ol>
     *   <li>the session must be {@link SessionStatus#ARCHIVED} — nothing else needs restoring;</li>
     *   <li>{@code med_session.purged_at} must be {@code NULL} — a session whose hot rows the purge
     *       already reclaimed has no live copy to move back;</li>
     *   <li>when an export manifest exists, the live transcript must reproduce its {@code
     *       payload_checksum} — the same digest rule the export, the verification, the read path and the
     *       purge all use, via {@link TranscriptDigests}.</li>
     * </ol>
     *
     * <p>Check 2 is not redundant with check 3: the digest comparison catches a shard that drifted,
     * while a retired-but-empty transcript reproduces {@code sha256("")} and would slip past it.</p>
     *
     * <p>The cached Redis window is deliberately left alone. An archived session cannot be written to,
     * so whatever the cache holds for it is the same content the shards hold, and the archive's eviction
     * has already dropped it in the common case; the next turn re-reads it from MySQL anyway.</p>
     *
     * @param tenantId  hospital/tenant id, must not be blank
     * @param deptId    department id, must not be blank
     * @param sessionId consultation session id, must not be blank
     * @return the session in its {@link SessionStatus#ACTIVE} state, never {@code null}
     * @throws IllegalArgumentException if an identity segment is blank
     * @throws BizException             {@link ErrorCode#NOT_FOUND} when the session does not exist in this
     *                                  department, {@link ErrorCode#FORBIDDEN} when the caller may not
     *                                  touch it, {@link ErrorCode#BAD_REQUEST} when the session is not
     *                                  archived or its live copy is no longer the certified transcript,
     *                                  {@link ErrorCode#SESSION_LOCKED} when the session is busy or left
     *                                  ARCHIVED concurrently, {@link ErrorCode#STORAGE_ERROR} when a
     *                                  read or write fails
     */
    @MedAudit(action = "SESSION_RESTORE", resourceType = "SESSION", target = "#sessionId")
    public ChatSessionDO restore(String tenantId, String deptId, String sessionId) {
        requireText(tenantId, "tenantId");
        requireText(deptId, "deptId");
        requireText(sessionId, "sessionId");
        return lockService.executeLocked(tenantId, deptId, sessionId,
                () -> restoreLocked(tenantId, deptId, sessionId));
    }

    /**
     * Performs the restore while the session lock is held.
     *
     * @param tenantId  hospital/tenant id
     * @param deptId    department id
     * @param sessionId consultation session id
     * @return the session in its {@link SessionStatus#ACTIVE} state
     * @throws BizException as documented on {@link #restore(String, String, String)}
     */
    private ChatSessionDO restoreLocked(String tenantId, String deptId, String sessionId) {
        ChatSessionDO session = sessionService.getSession(tenantId, deptId, sessionId);

        if (session.getStatus() != SessionStatus.ARCHIVED) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "session " + sessionId + " is " + session.getStatus()
                            + "; only an archived session can be restored");
        }
        if (session.getPurgedAt() != null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "the hot rows of session " + sessionId + " were retired at " + session.getPurgedAt()
                            + "; its live copy no longer exists, so the session cannot be moved back "
                            + "(the certified cold copy stays readable through the transcript endpoint)");
        }

        SessionArchiveManifestDO manifest = readManifest(sessionId);
        if (manifest != null) {
            verifyLiveCopy(sessionId, manifest);
        }

        // Retire the certification first: an interruption in between leaves "ARCHIVED with no manifest",
        // which the next export re-certifies and converges. The other order would leave a session that is
        // already writable while its certification still claims the hot copy is frozen — see the class javadoc.
        retireCertification(sessionId, manifest);
        return activate(session);
    }

    /**
     * Requires the live shard to hold the transcript the certification describes.
     *
     * @param sessionId the session being restored
     * @param manifest  the certification that governed it
     * @throws BizException {@link ErrorCode#BAD_REQUEST} when the shard does not reproduce the certified
     *                      digest, {@link ErrorCode#STORAGE_ERROR} when the read fails
     */
    private void verifyLiveCopy(String sessionId, SessionArchiveManifestDO manifest) {
        List<ChatMessageDO> hot = readTranscript(sessionId);
        String hotDigest = TranscriptDigests.ofTranscript(hot, codec);
        String certified = manifest.getPayloadChecksum();
        if (!hotDigest.equals(certified)) {
            log.warn("live shard of archived session {} does not reproduce its certification "
                    + "({} messages), refusing to move it back", sessionId, hot.size());
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "the live shard of session " + sessionId + " (digest " + hotDigest + ", "
                            + hot.size() + " messages) does not reproduce the certified digest "
                            + certified + "; the session cannot be moved back");
        }
    }

    /**
     * Retires the export certification of the session being restored.
     *
     * @param sessionId the session being restored
     * @param manifest  its manifest, or {@code null} when it was never exported
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when the delete fails
     */
    private void retireCertification(String sessionId, SessionArchiveManifestDO manifest) {
        if (manifest == null) {
            // Never exported: the live shard has always been the only copy, so there is no claim to retire.
            return;
        }
        int retired;
        try {
            retired = archiveMapper.deleteManifest(sessionId);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to retire the export certification of session " + sessionId, ex);
        }
        log.info("retired the export certification of session {} ({} manifest rows)", sessionId, retired);
    }

    /**
     * Flips the session to {@link SessionStatus#ACTIVE} with the same compare-and-set the lifecycle
     * service uses.
     *
     * <p>The expected status is spelled out as {@link SessionStatus#ARCHIVED} and the target as
     * {@link SessionStatus#ACTIVE}, so this is not a general-purpose "set any status" primitive — the
     * hole class D41/D42 removed. A failed compare-and-set means the session left ARCHIVED underneath
     * this call, which is retryable ({@link ErrorCode#SESSION_LOCKED}, as elsewhere).</p>
     *
     * @param session the archived session, already verified
     * @return the same entity, now ACTIVE
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when the update fails,
     *                      {@link ErrorCode#SESSION_LOCKED} when no row matched
     */
    private ChatSessionDO activate(ChatSessionDO session) {
        String sessionId = session.getSessionId();
        long now = clock.millis();
        int rows;
        try {
            rows = sessionMapper.updateStatus(sessionId, SessionStatus.ACTIVE, SessionStatus.ARCHIVED, now);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to move session " + sessionId + " back to ACTIVE", ex);
        }
        if (rows <= 0) {
            throw new BizException(ErrorCode.SESSION_LOCKED,
                    "session " + sessionId + " left ARCHIVED concurrently, retry the restore");
        }
        session.setStatus(SessionStatus.ACTIVE);
        session.setUpdatedAt(now);
        log.info("restored session {} to ACTIVE", sessionId);
        return session;
    }

    /**
     * Reads the live transcript of a session in storage order.
     *
     * @param sessionId the session id
     * @return the transcript, possibly empty, never {@code null}
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when the query fails
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
     * Loads the export manifest that certifies a session.
     *
     * @param sessionId the session id
     * @return the manifest, or {@code null} when the session was never exported
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

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
