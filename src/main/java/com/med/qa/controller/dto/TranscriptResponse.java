package com.med.qa.controller.dto;

import com.med.qa.domain.enums.SessionStatus;
import com.med.qa.service.SessionTranscript;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Read-side view of a consultation transcript, shaped for the REST API (D49).
 *
 * <p>Besides the messages it carries the two facts a caller cannot recompute: {@code source} — the
 * tier the transcript was read from ({@code HOT} for the live shards, {@code COLD} for the certified
 * archive copy) — and {@code checksum}, the canonical digest of the returned sequence. Both are
 * exposed on purpose: an operator comparing a read against the archive manifest
 * ({@code med_session_archive.payload_checksum}) needs exactly that digest, and a client that wants to
 * detect a changed transcript between two reads does not have to hash anything itself.</p>
 *
 * @param sessionId    owning session id
 * @param status       lifecycle status of the session at read time: ACTIVE, CLOSED or ARCHIVED
 * @param source       tier the transcript was read from: HOT or COLD
 * @param messageCount number of messages returned
 * @param checksum     canonical digest of the returned messages, matching the archive manifest form
 * @param messages     the transcript in storage order, possibly empty, never {@code null}
 */
public record TranscriptResponse(
        @Schema(description = "consultation session identifier", example = "sess-9f2c1a")
        String sessionId,
        @Schema(description = "lifecycle status: ACTIVE, CLOSED or ARCHIVED")
        SessionStatus status,
        @Schema(description = "tier the transcript was read from: HOT (live shard) or COLD (archive)")
        SessionTranscript.Source source,
        @Schema(description = "number of messages returned", example = "12")
        int messageCount,
        @Schema(description = "canonical digest of the returned messages, same form as the archive manifest")
        String checksum,
        @Schema(description = "the transcript in storage order")
        List<MessageResponse> messages) {

    /**
     * Builds the API view of a transcript.
     *
     * @param transcript the transcript, must not be {@code null}
     * @return the API view, never {@code null}
     * @throws IllegalArgumentException if {@code transcript} is {@code null}
     */
    public static TranscriptResponse from(SessionTranscript transcript) {
        if (transcript == null) {
            throw new IllegalArgumentException("transcript must not be null");
        }
        List<MessageResponse> messages = transcript.messages().stream()
                .map(MessageResponse::from)
                .toList();
        return new TranscriptResponse(
                transcript.sessionId(),
                transcript.status(),
                transcript.source(),
                transcript.messageCount(),
                transcript.checksum(),
                messages);
    }
}
