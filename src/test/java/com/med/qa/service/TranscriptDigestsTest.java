package com.med.qa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.med.qa.domain.entity.ArchivedMessageDO;
import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.enums.RoleType;
import com.med.qa.memory.serde.ProtoMessageCodec;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests of {@link TranscriptDigests}, the single construction of the canonical transcript digest
 * (D49).
 *
 * <p>The value of this class is that the export, the verification and the read path all feed
 * {@link SessionArchiveChecksum} through it. The tests therefore pin the two properties that make it
 * usable as the shared definition: the digest of a live transcript equals the digest of the cold copy
 * of the same transcript (otherwise "the copy matches the source" would be unfalsifiable), and the
 * digest follows the sequence, not just the set.
 */
class TranscriptDigestsTest {

    private final ProtoMessageCodec codec = new ProtoMessageCodec();

    private static ChatMessageDO message(String id, long createdAt, String content) {
        return ChatMessageDO.builder()
                .messageId(id)
                .sessionId("sess-1")
                .tenantId("hosp-1")
                .deptId("cardiology")
                .patientId("pat-77")
                .role(RoleType.PATIENT)
                .content(content)
                .createdAt(createdAt)
                .build();
    }

    @Test
    @DisplayName("the live transcript and its cold copy hash to the same digest")
    void liveAndColdAgree() {
        List<ChatMessageDO> transcript = List.of(
                message("m-1", 1_000L, "question"),
                message("m-2", 2_000L, "answer"));

        String hot = TranscriptDigests.ofTranscript(transcript, codec);
        String cold = TranscriptDigests.ofArchived(transcript.stream()
                .map(message -> new ArchivedMessageDO("sess-1", message.getMessageId(), "hosp-1",
                        "cardiology", "pat-77", message.getCreatedAt(), 5_000L,
                        codec.encodeMessage(message)))
                .toList());

        assertThat(hot).isEqualTo(cold);
        assertThat(hot).hasSize(64);
    }

    @Test
    @DisplayName("an empty transcript hashes to the documented empty digest")
    void emptyTranscriptMatchesTheDocumentedConstant() {
        assertThat(TranscriptDigests.ofTranscript(List.of(), codec))
                .isEqualTo(SessionArchiveChecksum.EMPTY_TRANSCRIPT);
        assertThat(TranscriptDigests.ofArchived(List.of()))
                .isEqualTo(SessionArchiveChecksum.EMPTY_TRANSCRIPT);
    }

    @Test
    @DisplayName("the digest covers the sequence: reordering changes it")
    void digestCoversTheSequence() {
        ChatMessageDO first = message("m-1", 1_000L, "question");
        ChatMessageDO second = message("m-2", 2_000L, "answer");

        assertThat(TranscriptDigests.ofTranscript(List.of(first, second), codec))
                .isNotEqualTo(TranscriptDigests.ofTranscript(List.of(second, first), codec));
        // Changing the text changes it too - the digest is over the encoded bytes, not the ids.
        assertThat(TranscriptDigests.ofTranscript(List.of(first, second), codec))
                .isNotEqualTo(TranscriptDigests.ofTranscript(
                        List.of(first, message("m-2", 2_000L, "different answer")), codec));
    }

    @Test
    @DisplayName("rejects a null transcript, a null codec, a null cold row and null elements")
    void rejectsIncompleteInput() {
        assertThatThrownBy(() -> TranscriptDigests.ofTranscript(null, codec))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TranscriptDigests.ofTranscript(List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TranscriptDigests.ofArchived(null))
                .isInstanceOf(IllegalArgumentException.class);

        List<ChatMessageDO> withNull = new ArrayList<>();
        withNull.add(null);
        assertThatThrownBy(() -> TranscriptDigests.ofTranscript(withNull, codec))
                .isInstanceOf(IllegalArgumentException.class);

        List<ArchivedMessageDO> archivedWithNull = new ArrayList<>();
        archivedWithNull.add(null);
        assertThatThrownBy(() -> TranscriptDigests.ofArchived(archivedWithNull))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
