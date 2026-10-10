package com.med.qa.controller.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.enums.RoleType;
import com.med.qa.domain.enums.SessionStatus;
import com.med.qa.service.SessionArchiveChecksum;
import com.med.qa.service.SessionTranscript;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests of {@link TranscriptResponse}, the API projection of a transcript (D49).
 *
 * <p>Beyond the messages, the projection has to carry the two facts a client cannot recompute — which
 * tier answered and the canonical digest — because an operator comparing a read against the archive
 * manifest needs exactly that digest, and a reader of a {@code COLD} transcript has to be told that
 * the shard no longer holds it.
 */
class TranscriptResponseTest {

    private static ChatMessageDO message(String id) {
        return ChatMessageDO.builder()
                .messageId(id)
                .sessionId("sess-1")
                .tenantId("hosp-1")
                .deptId("cardiology")
                .patientId("pat-77")
                .role(RoleType.PATIENT)
                .content("content of " + id)
                .createdAt(1_000L)
                .build();
    }

    private static SessionTranscript transcript(SessionTranscript.Source source,
                                                List<ChatMessageDO> messages) {
        return new SessionTranscript("sess-1", SessionStatus.ARCHIVED, source,
                SessionArchiveChecksum.EMPTY_TRANSCRIPT, messages);
    }

    @Test
    @DisplayName("projects the transcript, naming the tier and the digest")
    void projectsTheTranscript() {
        TranscriptResponse response = TranscriptResponse.from(transcript(
                SessionTranscript.Source.COLD, List.of(message("m-1"), message("m-2"))));

        assertThat(response.sessionId()).isEqualTo("sess-1");
        assertThat(response.status()).isEqualTo(SessionStatus.ARCHIVED);
        assertThat(response.source()).isEqualTo(SessionTranscript.Source.COLD);
        assertThat(response.messageCount()).isEqualTo(2);
        assertThat(response.checksum()).isEqualTo(SessionArchiveChecksum.EMPTY_TRANSCRIPT);
        assertThat(response.messages()).extracting(MessageResponse::messageId)
                .containsExactly("m-1", "m-2");
    }

    @Test
    @DisplayName("an empty transcript projects an empty message list and a zero count")
    void projectsAnEmptyTranscript() {
        TranscriptResponse response = TranscriptResponse.from(
                transcript(SessionTranscript.Source.HOT, List.of()));

        assertThat(response.messageCount()).isZero();
        assertThat(response.messages()).isEmpty();
        assertThat(response.source()).isEqualTo(SessionTranscript.Source.HOT);
    }

    @Test
    @DisplayName("rejects a null transcript")
    void rejectsANullTranscript() {
        assertThatThrownBy(() -> TranscriptResponse.from(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
