package com.med.qa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.enums.RoleType;
import com.med.qa.domain.enums.SessionStatus;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests of {@link SessionTranscript}, the value the read path returns (D49).
 *
 * <p>The record carries the tier a transcript came from and the digest of the sequence it returned,
 * so two properties matter: the message list is really immutable (a caller mutating it after the
 * digest was computed would make the digest describe something that no longer exists), and the
 * {@code Source} cannot be null — a transcript that does not say where it came from cannot be triaged.
 */
class SessionTranscriptTest {

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

    private static SessionTranscript transcript(List<ChatMessageDO> messages) {
        return new SessionTranscript("sess-1", SessionStatus.ARCHIVED,
                SessionTranscript.Source.COLD, SessionArchiveChecksum.EMPTY_TRANSCRIPT, messages);
    }

    @Test
    @DisplayName("exposes the transcript, its size, its source and its digest")
    void exposesItsContent() {
        SessionTranscript transcript = transcript(List.of(message("m-1"), message("m-2")));

        assertThat(transcript.messageCount()).isEqualTo(2);
        assertThat(transcript.fromColdStore()).isTrue();
        assertThat(transcript.summary())
                .contains("sess-1")
                .contains("COLD")
                .contains("messages=2")
                .contains(SessionArchiveChecksum.EMPTY_TRANSCRIPT);
    }

    @Test
    @DisplayName("a transcript read from the shard does not claim to come from the cold store")
    void hotTranscriptsAreNotCold() {
        SessionTranscript transcript = new SessionTranscript("sess-1", SessionStatus.ACTIVE,
                SessionTranscript.Source.HOT, SessionArchiveChecksum.EMPTY_TRANSCRIPT, List.of());

        assertThat(transcript.fromColdStore()).isFalse();
        assertThat(transcript.messageCount()).isZero();
    }

    @Test
    @DisplayName("the message list is copied, so a later mutation cannot invalidate the digest")
    void copiesTheMessageList() {
        List<ChatMessageDO> mutable = new ArrayList<>();
        mutable.add(message("m-1"));
        SessionTranscript transcript = transcript(mutable);

        mutable.add(message("m-2"));

        assertThat(transcript.messageCount()).isEqualTo(1);
        assertThatThrownBy(() -> transcript.messages().add(message("m-3")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("rejects a null message list")
    void rejectsANullMessageList() {
        assertThatThrownBy(() -> transcript(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects a null message inside the list")
    void rejectsANullElement() {
        List<ChatMessageDO> withNull = new ArrayList<>();
        withNull.add(message("m-1"));
        withNull.add(null);

        assertThatThrownBy(() -> transcript(withNull))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("rejects a blank session id, a null status, a null source and a blank digest")
    void rejectsIncompleteValues() {
        assertThatThrownBy(() -> new SessionTranscript("  ", SessionStatus.ACTIVE,
                SessionTranscript.Source.HOT, SessionArchiveChecksum.EMPTY_TRANSCRIPT, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionTranscript("sess-1", null,
                SessionTranscript.Source.HOT, SessionArchiveChecksum.EMPTY_TRANSCRIPT, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionTranscript("sess-1", SessionStatus.ACTIVE,
                null, SessionArchiveChecksum.EMPTY_TRANSCRIPT, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionTranscript("sess-1", SessionStatus.ACTIVE,
                SessionTranscript.Source.HOT, "", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
