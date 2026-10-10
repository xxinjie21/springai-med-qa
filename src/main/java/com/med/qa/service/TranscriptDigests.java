package com.med.qa.service;

import com.med.qa.domain.entity.ArchivedMessageDO;
import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.memory.serde.ProtoMessageCodec;

import java.util.ArrayList;
import java.util.List;

/**
 * The one place that turns a transcript — live or cold — into the canonical digest of
 * {@link SessionArchiveChecksum} (D49).
 *
 * <p>{@link SessionArchiveChecksum} defines <em>what</em> the digest is; this class defines
 * <em>how</em> the two tiers feed it. Keeping both constructions side by side is the point: D48's
 * export certifies a session with the digest of the hot transcript, D49's read path decides between
 * the two tiers by recomputing both digests, and a purge job (D50) will have to recompute them once
 * more before it is allowed to delete anything. If each caller assembled the entry list itself, the
 * three would drift — and a drifted digest does not fail loudly, it just reports a mismatch forever
 * (or, worse, matches the wrong thing).</p>
 *
 * <p>Both methods re-encode the live messages with {@link ProtoMessageCodec}, so "the digest of the
 * transcript" always means "the digest of the bytes the storage spec would persist", independent of
 * whether the caller holds entities or stored payloads.</p>
 */
public final class TranscriptDigests {

    private TranscriptDigests() {
    }

    /**
     * Computes the canonical digest of a live transcript by encoding it exactly as the storage spec
     * would.
     *
     * @param transcript the transcript in storage order, must not be {@code null}
     * @param codec      Protobuf codec of the unified storage spec, must not be {@code null}
     * @return the 64-character lower-case hex digest, never {@code null}
     * @throws IllegalArgumentException if an argument is {@code null} or holds a {@code null} message
     */
    public static String ofTranscript(List<ChatMessageDO> transcript, ProtoMessageCodec codec) {
        if (transcript == null) {
            throw new IllegalArgumentException("transcript must not be null");
        }
        if (codec == null) {
            throw new IllegalArgumentException("codec must not be null");
        }
        List<SessionArchiveChecksum.Entry> entries = new ArrayList<>(transcript.size());
        for (ChatMessageDO message : transcript) {
            if (message == null) {
                throw new IllegalArgumentException("transcript must not hold null elements");
            }
            entries.add(new SessionArchiveChecksum.Entry(message.getMessageId(), message.getCreatedAt(),
                    codec.encodeMessage(message)));
        }
        return SessionArchiveChecksum.of(entries);
    }

    /**
     * Computes the canonical digest of a cold copy from the payloads it actually stores.
     *
     * <p>This reads the stored bytes rather than re-encoding an entity, which is what makes it an
     * answer to "are the bytes in the cold store the bytes that were certified?" instead of a
     * restatement of the source.</p>
     *
     * @param archived the archive rows in storage order, must not be {@code null}
     * @return the 64-character lower-case hex digest, never {@code null}
     * @throws IllegalArgumentException if {@code archived} is {@code null} or holds a {@code null} row
     */
    public static String ofArchived(List<ArchivedMessageDO> archived) {
        if (archived == null) {
            throw new IllegalArgumentException("archived must not be null");
        }
        List<SessionArchiveChecksum.Entry> entries = new ArrayList<>(archived.size());
        for (ArchivedMessageDO row : archived) {
            if (row == null) {
                throw new IllegalArgumentException("archived must not hold null elements");
            }
            entries.add(new SessionArchiveChecksum.Entry(row.getMessageId(), row.getCreatedAt(),
                    row.getPayload()));
        }
        return SessionArchiveChecksum.of(entries);
    }
}
