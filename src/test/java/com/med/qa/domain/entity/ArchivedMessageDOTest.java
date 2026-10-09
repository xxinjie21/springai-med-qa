package com.med.qa.domain.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests of {@link ArchivedMessageDO}, the cold-store row of D48.
 *
 * <p>The row carries the frozen Protobuf payload, and the guards here exist so that a malformed row
 * fails where it is built rather than producing an archive that silently misses a message: an empty
 * payload would be hashed as if the message were empty, and a blank identity column would make a future
 * scoped purge unable to see the row.</p>
 */
class ArchivedMessageDOTest {

    private static final byte[] PAYLOAD = "payload".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("a fully populated row keeps every field")
    void keepsEveryField() {
        ArchivedMessageDO row = new ArchivedMessageDO("session-1", "msg-1", "tenant-1",
                "dept-cardiology", "patient-1", 1_000L, 2_000L, PAYLOAD);

        assertThat(row.getSessionId()).isEqualTo("session-1");
        assertThat(row.getMessageId()).isEqualTo("msg-1");
        assertThat(row.getTenantId()).isEqualTo("tenant-1");
        assertThat(row.getDeptId()).isEqualTo("dept-cardiology");
        assertThat(row.getPatientId()).isEqualTo("patient-1");
        assertThat(row.getCreatedAt()).isEqualTo(1_000L);
        assertThat(row.getArchivedAt()).isEqualTo(2_000L);
        assertThat(row.getPayload()).isEqualTo(PAYLOAD);
    }

    @Test
    @DisplayName("equality follows the storage primary key (session_id, message_id)")
    void equalityFollowsThePrimaryKey() {
        ArchivedMessageDO first = new ArchivedMessageDO("session-1", "msg-1", "tenant-1",
                "dept-cardiology", "patient-1", 1_000L, 2_000L, PAYLOAD);
        ArchivedMessageDO sameKey = new ArchivedMessageDO("session-1", "msg-1", "tenant-2",
                "dept-neurology", "patient-9", 9_000L, 9_000L, "other".getBytes(StandardCharsets.UTF_8));
        ArchivedMessageDO otherMessage = new ArchivedMessageDO("session-1", "msg-2", "tenant-1",
                "dept-cardiology", "patient-1", 1_000L, 2_000L, PAYLOAD);
        ArchivedMessageDO otherSession = new ArchivedMessageDO("session-2", "msg-1", "tenant-1",
                "dept-cardiology", "patient-1", 1_000L, 2_000L, PAYLOAD);

        assertThat(first).isEqualTo(sameKey).hasSameHashCodeAs(sameKey);
        assertThat(first).isNotEqualTo(otherMessage).isNotEqualTo(otherSession);
    }

    @Test
    @DisplayName("the string form prints the payload length, never the payload")
    void toStringNeverPrintsThePayload() {
        ArchivedMessageDO row = new ArchivedMessageDO("session-1", "msg-1", "tenant-1",
                "dept-cardiology", "patient-1", 1_000L, 2_000L, "confidential".getBytes(
                        StandardCharsets.UTF_8));

        assertThat(row.toString())
                .contains("payloadLength=12")
                .doesNotContain("confidential");
    }

    @Test
    @DisplayName("blank identity fields and an empty payload are rejected")
    void invalidRowsAreRejected() {
        assertThatThrownBy(() -> new ArchivedMessageDO(" ", "msg-1", "tenant-1", "dept-cardiology",
                "patient-1", 1L, 1L, PAYLOAD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionId must not be blank");
        assertThatThrownBy(() -> new ArchivedMessageDO("session-1", " ", "tenant-1", "dept-cardiology",
                "patient-1", 1L, 1L, PAYLOAD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageId must not be blank");
        assertThatThrownBy(() -> new ArchivedMessageDO("session-1", "msg-1", "tenant-1", "dept-cardiology",
                "patient-1", 1L, 1L, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload must not be empty");
    }
}
