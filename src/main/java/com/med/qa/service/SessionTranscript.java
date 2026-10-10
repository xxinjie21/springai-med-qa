package com.med.qa.service;

import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.enums.SessionStatus;

import java.util.List;

/**
 * One consultation transcript as read back by {@link MedTranscriptService} (D49).
 *
 * <p>It carries three things a caller cannot derive on its own: the messages themselves, the
 * {@link Source} they were read from, and the {@link #checksum()} of the exact sequence that was
 * returned. The digest matters because it is the same canonical form the cold archive certifies with
 * ({@link SessionArchiveChecksum}), so an operator — or a later purge job — can compare a transcript
 * read today against the manifest written months ago without re-implementing anything.</p>
 *
 * <h2>Why the source is part of the answer</h2>
 * <p>D47 evicts the Redis window when a session is archived and D48 copies the transcript into the
 * non-sharded cold store. Those two tiers can diverge, so "where did these bytes come from?" is a
 * question the reader has to be able to answer: a {@code HOT} read is the live shard, a {@code COLD}
 * read is the certified copy, and a response that did not say which would leave an operator unable to
 * tell "the shards still hold it" from "the shards were purged and the archive is carrying the
 * consultation".</p>
 *
 * @param sessionId owning session id, never blank
 * @param status    lifecycle status of the session at read time, never {@code null}
 * @param source    tier the messages were read from, never {@code null}
 * @param checksum  canonical digest of the returned messages, never {@code null}
 * @param messages  the transcript in storage order, never {@code null} and never holding {@code null}
 */
public record SessionTranscript(String sessionId,
                                SessionStatus status,
                                Source source,
                                String checksum,
                                List<ChatMessageDO> messages) {

    /** Tier a transcript was read from. */
    public enum Source {

        /** The live, sharded {@code med_message} tables. */
        HOT,

        /** The certified, non-sharded {@code med_message_archive} copy. */
        COLD
    }

    /**
     * Validates the transcript and freezes its message list.
     *
     * <p>The list is copied so the record really is immutable: a caller that kept a handle on the
     * mapper's list could otherwise mutate a transcript after its digest had been computed, which is
     * exactly the inconsistency the digest exists to rule out.</p>
     *
     * @throws IllegalArgumentException if a field is {@code null}, the session id is blank or the
     *                                  checksum is blank
     */
    public SessionTranscript {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (checksum == null || checksum.isBlank()) {
            throw new IllegalArgumentException("checksum must not be blank");
        }
        if (messages == null) {
            throw new IllegalArgumentException("messages must not be null");
        }
        messages = List.copyOf(messages);
    }

    /**
     * Returns how many messages the transcript holds.
     *
     * @return the message count, never negative
     */
    public int messageCount() {
        return messages.size();
    }

    /**
     * Tells whether the transcript was served from the cold store.
     *
     * @return {@code true} for a {@link Source#COLD} read
     */
    public boolean fromColdStore() {
        return source == Source.COLD;
    }

    /**
     * Renders a one-line summary for logs.
     *
     * <p>Only the shape of the transcript is printed — never the clinical content, which the caller
     * already holds.</p>
     *
     * @return a single-line, human-readable summary, never {@code null}
     */
    public String summary() {
        return "session=" + sessionId
                + ", status=" + status
                + ", source=" + source
                + ", messages=" + messages.size()
                + ", checksum=" + checksum;
    }
}
