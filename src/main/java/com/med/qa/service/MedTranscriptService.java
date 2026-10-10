package com.med.qa.service;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.domain.entity.ArchivedMessageDO;
import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.entity.SessionArchiveManifestDO;
import com.med.qa.domain.enums.SessionStatus;
import com.med.qa.mapper.ChatMessageMapper;
import com.med.qa.mapper.SessionArchiveMapper;
import com.med.qa.memory.serde.ProtoMessageCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads a consultation transcript back — from the live shards while they hold it, from the certified
 * cold copy when they no longer do (D49).
 *
 * <h2>The gap this closes</h2>
 * <p>D47 archives a stale session and evicts its Redis window; D48 copies its transcript into the
 * non-sharded cold store and certifies the copy with a digest. Neither added a way to <em>read</em>:
 * the streaming endpoint only appends turns, the session endpoints only create / get / close /
 * archive / list, and {@code MedSessionArchiveExportService#verify} reads the cold store solely to
 * recompute a digest — its own javadoc states it is deliberately unreachable from the request path.
 * So "archived" meant "gone": a clinician could see the {@code med_session} row and none of what was
 * said in it, and the roadmap's promise that the cold copy is readable by the heterogeneous
 * middleware was one the service could not make about itself.</p>
 *
 * <h2>Which tier answers</h2>
 * <p>The choice is made by recomputing digests, never by trusting an ordering or a flag:</p>
 * <ol>
 *   <li>A session that is not {@link SessionStatus#ARCHIVED}, or an archived session with no export
 *       manifest, has exactly one copy — the live shards — and it is returned as
 *       {@link SessionTranscript.Source#HOT}. An archived session with no manifest is the normal state
 *       of a deployment that has not armed {@code med.session.archive.enabled}; refusing to read it
 *       would turn an optional background job into a prerequisite for seeing clinical records.</li>
 *   <li>Otherwise the digest of the live transcript is recomputed and compared with the manifest's
 *       {@code payload_checksum}. A match means the shards still hold the certified transcript, so
 *       they are returned ({@code HOT}).</li>
 *   <li>Otherwise the cold copy is read and hashed the same way. A match means the shards were
 *       emptied or altered while the certified copy survived — the state D50 will deliberately create,
 *       and also the state a half-finished purge leaves behind — so the cold copy is returned
 *       ({@code COLD}), decoded back into the storage-spec entity by {@link ProtoMessageCodec}.</li>
 *   <li>Otherwise <em>neither</em> copy reproduces the certification and the read is refused with
 *       {@link ErrorCode#STORAGE_ERROR}. Returning the shards anyway would serve bytes that the
 *       archive says are not the transcript; returning the cold copy would serve bytes the cold store
 *       says are not the transcript; returning an empty transcript would report data loss as "this
 *       consultation was empty". A refusal is the only honest answer, and it is loud.</li>
 * </ol>
 *
 * <h2>Access control</h2>
 * <p>Scope and ownership are not re-implemented here: the session is resolved through
 * {@link MedChatSessionService#getSession}, which reports a session of another tenant/department as
 * absent ({@link ErrorCode#NOT_FOUND}) and lets {@code PatientAccessGuard} refuse a patient reading
 * somebody else's consultation ({@link ErrorCode#FORBIDDEN}). The read path therefore adds no new
 * "read by session id" primitive to the request surface — the hole class D41/D42 closed.</p>
 *
 * <h2>Why there is no lock and no switch</h2>
 * <p>No {@code RLock}: the append path re-sends the whole window on every turn and writes each message
 * idempotently (D44), so a concurrent read can only ever see complete rows, and the cold copy is a
 * frozen snapshot. No configuration switch either: reading is a core capability, and a switch would
 * only give an operator a way to make clinical records silently unreadable.</p>
 *
 * <p>Always present as a {@code @Service}, unlike the D38/D47/D48 background actors: the hot read
 * path has to work in a deployment that never enabled the archive export.</p>
 */
@Service
public class MedTranscriptService {

    private static final Logger log = LoggerFactory.getLogger(MedTranscriptService.class);

    private final MedChatSessionService sessionService;

    private final ChatMessageMapper messageMapper;

    private final SessionArchiveMapper archiveMapper;

    private final ProtoMessageCodec codec;

    /**
     * Creates the service.
     *
     * @param sessionService lifecycle service used to resolve and authorize the session, must not be
     *                       {@code null}
     * @param messageMapper  MyBatis mapper over the sharded {@code med_message} table, must not be
     *                       {@code null}
     * @param archiveMapper  MyBatis mapper over the cold archive tables, must not be {@code null}
     * @param codec          Protobuf codec of the unified storage spec, must not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public MedTranscriptService(MedChatSessionService sessionService,
                               ChatMessageMapper messageMapper,
                               SessionArchiveMapper archiveMapper,
                               ProtoMessageCodec codec) {
        if (sessionService == null) {
            throw new IllegalArgumentException("sessionService must not be null");
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
        this.sessionService = sessionService;
        this.messageMapper = messageMapper;
        this.archiveMapper = archiveMapper;
        this.codec = codec;
    }

    /**
     * Reads the transcript of one session, choosing the tier that reproduces its certification.
     *
     * @param tenantId  hospital/tenant id, must not be blank
     * @param deptId    department id, must not be blank
     * @param sessionId consultation session id, must not be blank
     * @return the transcript, never {@code null}
     * @throws IllegalArgumentException if an argument is blank
     * @throws BizException             {@link ErrorCode#NOT_FOUND} when the session does not exist in
     *                                  this department, {@link ErrorCode#FORBIDDEN} when the caller
     *                                  may not read it, {@link ErrorCode#STORAGE_ERROR} when a read
     *                                  fails, a stored payload is malformed, or neither copy
     *                                  reproduces the certified digest
     */
    public SessionTranscript read(String tenantId, String deptId, String sessionId) {
        ChatSessionDO session = sessionService.getSession(tenantId, deptId, sessionId);
        List<ChatMessageDO> hot = readHot(sessionId);
        String hotDigest = TranscriptDigests.ofTranscript(hot, codec);

        if (session.getStatus() != SessionStatus.ARCHIVED) {
            return new SessionTranscript(sessionId, session.getStatus(), SessionTranscript.Source.HOT,
                    hotDigest, hot);
        }

        SessionArchiveManifestDO manifest = readManifest(sessionId);
        if (manifest == null) {
            log.debug("archived session {} has no export manifest, serving its live shard", sessionId);
            return new SessionTranscript(sessionId, session.getStatus(), SessionTranscript.Source.HOT,
                    hotDigest, hot);
        }
        String certified = manifest.getPayloadChecksum();
        if (hotDigest.equals(certified)) {
            return new SessionTranscript(sessionId, session.getStatus(), SessionTranscript.Source.HOT,
                    hotDigest, hot);
        }

        List<ArchivedMessageDO> cold = readCold(sessionId);
        String coldDigest = TranscriptDigests.ofArchived(cold);
        if (!coldDigest.equals(certified)) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "neither the live shard (digest " + hotDigest + ", " + hot.size() + " messages) nor "
                            + "the cold copy (digest " + coldDigest + ", " + cold.size() + " messages) "
                            + "reproduces the certified digest " + certified + " of archived session "
                            + sessionId + "; refusing to serve an unaccountable transcript");
        }
        List<ChatMessageDO> decoded = decode(cold);
        log.info("serving the cold copy of archived session {} ({} messages)", sessionId, decoded.size());
        return new SessionTranscript(sessionId, session.getStatus(), SessionTranscript.Source.COLD,
                coldDigest, decoded);
    }

    /**
     * Decodes the frozen Protobuf payloads of a cold copy back into storage-spec entities.
     *
     * <p>Every payload is decoded; a malformed one aborts the read with
     * {@link ErrorCode#STORAGE_ERROR} rather than being skipped, because a transcript that silently
     * drops the message it could not parse is worse than one that refuses to render.</p>
     *
     * @param cold the archive rows in storage order
     * @return the decoded transcript, possibly empty, never {@code null}
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when a payload is malformed or
     *                      semantically incomplete
     */
    private List<ChatMessageDO> decode(List<ArchivedMessageDO> cold) {
        List<ChatMessageDO> decoded = new ArrayList<>(cold.size());
        for (ArchivedMessageDO row : cold) {
            decoded.add(codec.decodeMessage(row.getPayload()));
        }
        return decoded;
    }

    /**
     * Reads the live transcript of a session in total order.
     *
     * @param sessionId the session id
     * @return the transcript, possibly empty, never {@code null}
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when the query fails
     */
    private List<ChatMessageDO> readHot(String sessionId) {
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
     * @return the archive rows, possibly empty, never {@code null}
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when the query fails
     */
    private List<ArchivedMessageDO> readCold(String sessionId) {
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
}
