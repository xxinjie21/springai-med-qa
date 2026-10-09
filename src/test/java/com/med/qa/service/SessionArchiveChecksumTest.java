package com.med.qa.service;

import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.enums.RoleType;
import com.med.qa.memory.serde.ProtoMessageCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests of {@link SessionArchiveChecksum}, the single definition of "the cold copy matches the source"
 * (D48).
 *
 * <p>This class is small and easy to get subtly wrong, and a wrong digest would not fail loudly: it
 * would either certify a copy that differs (if it ignored something) or refuse a copy that is fine (if
 * it depended on something unstable). So the properties that matter are pinned one by one: the empty
 * transcript has a known digest, order counts, identity counts, content counts, and the Protobuf
 * encoding the digest is computed over is deterministic for equal field values - the last one being an
 * assumption of the whole design, not a detail.</p>
 */
class SessionArchiveChecksumTest {

    private final ProtoMessageCodec codec = new ProtoMessageCodec();

    @Test
    @DisplayName("an empty transcript hashes to sha256(\"\"), so it is distinguishable from never exported")
    void emptyTranscriptHasTheKnownDigest() {
        assertThat(SessionArchiveChecksum.of(List.of()))
                .isEqualTo(SessionArchiveChecksum.EMPTY_TRANSCRIPT)
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(SessionArchiveChecksum.sha256Hex(new byte[0]))
                .isEqualTo(SessionArchiveChecksum.EMPTY_TRANSCRIPT);
    }

    @Test
    @DisplayName("the digest is stable for the same transcript read twice")
    void digestIsStable() {
        List<SessionArchiveChecksum.Entry> entries = List.of(
                entry("msg-1", 1_000L, "first answer"),
                entry("msg-2", 1_001L, "second answer"));

        assertThat(SessionArchiveChecksum.of(entries)).isEqualTo(SessionArchiveChecksum.of(entries));
    }

    @Test
    @DisplayName("reordering two messages changes the digest, so the sequence is covered")
    void orderIsPartOfTheDigest() {
        SessionArchiveChecksum.Entry first = entry("msg-1", 1_000L, "question");
        SessionArchiveChecksum.Entry second = entry("msg-2", 1_001L, "answer");

        assertThat(SessionArchiveChecksum.of(List.of(first, second)))
                .isNotEqualTo(SessionArchiveChecksum.of(List.of(second, first)));
    }

    @Test
    @DisplayName("dropping or duplicating a message changes the digest")
    void cardinalityIsPartOfTheDigest() {
        SessionArchiveChecksum.Entry first = entry("msg-1", 1_000L, "question");
        SessionArchiveChecksum.Entry second = entry("msg-2", 1_001L, "answer");
        String both = SessionArchiveChecksum.of(List.of(first, second));

        assertThat(SessionArchiveChecksum.of(List.of(first))).isNotEqualTo(both);
        assertThat(SessionArchiveChecksum.of(List.of(first, first, second))).isNotEqualTo(both);
    }

    @Test
    @DisplayName("the message id is part of the digest, not just its position")
    void identityIsPartOfTheDigest() {
        assertThat(SessionArchiveChecksum.of(List.of(entry("msg-1", 1_000L, "text"))))
                .isNotEqualTo(SessionArchiveChecksum.of(List.of(entry("msg-9", 1_000L, "text"))));
    }

    @Test
    @DisplayName("the payload bytes are part of the digest, and the timestamp is too")
    void payloadAndTimestampArePartOfTheDigest() {
        assertThat(SessionArchiveChecksum.of(List.of(entry("msg-1", 1_000L, "text"))))
                .isNotEqualTo(SessionArchiveChecksum.of(List.of(entry("msg-1", 1_000L, "texT"))));
        assertThat(SessionArchiveChecksum.of(List.of(entry("msg-1", 1_000L, "text"))))
                .isNotEqualTo(SessionArchiveChecksum.of(List.of(entry("msg-1", 2_000L, "text"))));
    }

    @Test
    @DisplayName("the digest is 64 lower-case hex characters, whatever the transcript")
    void digestShapeIsFixed() {
        assertThat(SessionArchiveChecksum.of(List.of(entry("msg-1", 1L, "x")))).matches("[0-9a-f]{64}");
        assertThat(SessionArchiveChecksum.EMPTY_TRANSCRIPT).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("the Protobuf encoding the digest runs over is deterministic for equal field values")
    void protoEncodingIsDeterministic() {
        // The whole design assumes this: the digest is computed over encoded payloads, so two encodes of
        // the same entity must be byte-identical. protobuf-java writes fields in field-number order and
        // map entries in insertion order; if that ever stopped holding, every export would report a
        // mismatch, and this test says so before production does.
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source", "consultation");
        metadata.put("dept", "cardiology");
        ChatMessageDO message = ChatMessageDO.builder()
                .messageId("msg-1")
                .sessionId("session-1")
                .tenantId("tenant-1")
                .deptId("dept-cardiology")
                .patientId("patient-1")
                .role(RoleType.ASSISTANT)
                .content("take the medication twice a day")
                .tokenCount(7)
                .masked(true)
                .createdAt(1_000L)
                .metadata(metadata)
                .build();

        assertThat(codec.encodeMessage(message)).isEqualTo(codec.encodeMessage(message));
    }

    @Test
    @DisplayName("a null or incomplete entry is rejected instead of silently dropped")
    void entriesAreValidated() {
        assertThatThrownBy(() -> SessionArchiveChecksum.of(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entries must not be null");

        SessionArchiveChecksum.Entry valid = entry("msg-1", 1L, "x");
        assertThatThrownBy(() -> SessionArchiveChecksum.of(java.util.Arrays.asList(valid, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null elements");

        assertThatThrownBy(() -> new SessionArchiveChecksum.Entry(" ", 1L, "x".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageId must not be blank");
        assertThatThrownBy(() -> new SessionArchiveChecksum.Entry("msg-1", 1L, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload must not be empty");
        assertThatThrownBy(() -> SessionArchiveChecksum.sha256Hex(null))
                .isInstanceOf(NullPointerException.class);
    }

    private static SessionArchiveChecksum.Entry entry(String messageId, long createdAt, String payload) {
        return new SessionArchiveChecksum.Entry(messageId, createdAt,
                payload.getBytes(StandardCharsets.UTF_8));
    }
}
