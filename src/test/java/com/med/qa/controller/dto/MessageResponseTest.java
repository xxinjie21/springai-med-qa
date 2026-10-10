package com.med.qa.controller.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.enums.RoleType;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests of {@link MessageResponse}, the API projection of one stored message (D49).
 *
 * <p>Two things are pinned here. The projection must be lossless for the fields the storage contract
 * defines — a transcript that dropped the {@code masked} flag or the metadata would make the read view
 * disagree with the stored record. And it must be defensive about metadata: the map arrives from a
 * heterogeneous writer, so a {@code null} key or value has to be dropped rather than turned into a
 * {@code NullPointerException} inside the read path.
 */
class MessageResponseTest {

    private static ChatMessageDO message(Map<String, String> metadata) {
        return ChatMessageDO.builder()
                .messageId("m-1")
                .sessionId("sess-1")
                .tenantId("hosp-1")
                .deptId("cardiology")
                .patientId("pat-77")
                .role(RoleType.DOCTOR)
                .content("请描述症状")
                .tokenCount(12)
                .masked(true)
                .createdAt(1_700_000_000_000L)
                .metadata(metadata)
                .build();
    }

    @Test
    @DisplayName("projects every storage field, metadata included")
    void projectsEveryField() {
        MessageResponse response = MessageResponse.from(
                message(Map.of("source", "triage-form", "lang", "zh")));

        assertThat(response.messageId()).isEqualTo("m-1");
        assertThat(response.sessionId()).isEqualTo("sess-1");
        assertThat(response.role()).isEqualTo(RoleType.DOCTOR);
        assertThat(response.content()).isEqualTo("请描述症状");
        assertThat(response.tokenCount()).isEqualTo(12);
        assertThat(response.masked()).isTrue();
        assertThat(response.createdAt()).isEqualTo(1_700_000_000_000L);
        assertThat(response.metadata()).containsEntry("source", "triage-form");
    }

    @Test
    @DisplayName("a message without metadata projects an empty map, not null")
    void projectsAnEmptyMetadataMap() {
        assertThat(MessageResponse.from(message(null)).metadata()).isEmpty();
        assertThat(MessageResponse.from(message(Map.of())).metadata()).isEmpty();
    }

    @Test
    @DisplayName("metadata entries a JSON map cannot carry are dropped, not fatal")
    void dropsNullMetadataEntries() {
        Map<String, String> withNulls = new LinkedHashMap<>();
        withNulls.put("ok", "value");
        withNulls.put("null-value", null);

        assertThat(MessageResponse.from(message(withNulls)).metadata())
                .containsExactlyEntriesOf(Map.of("ok", "value"));
    }

    @Test
    @DisplayName("the projected metadata is immutable")
    void projectsImmutableMetadata() {
        MessageResponse response = MessageResponse.from(message(Map.of("k", "v")));

        assertThatThrownBy(() -> response.metadata().put("other", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("rejects a null message")
    void rejectsANullMessage() {
        assertThatThrownBy(() -> MessageResponse.from(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
