package com.med.qa.domain.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests of {@link SessionArchiveManifestDO}, the certification record of D48.
 *
 * <p>The checksum field is the reason this class validates instead of accepting any string: a manifest
 * whose checksum is not a SHA-256 hex digest would make every later verification report
 * {@code COLD_COPY_DIFFERS}, and the operator would go looking at the archive rather than at the row
 * that was written wrong.</p>
 */
class SessionArchiveManifestDOTest {

    private static final String DIGEST =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    @DisplayName("a fully populated manifest keeps every field, including a zero message count")
    void keepsEveryField() {
        SessionArchiveManifestDO manifest = new SessionArchiveManifestDO("session-1", "tenant-1",
                "dept-cardiology", "patient-1", 0, DIGEST, 5_000L);

        assertThat(manifest.getSessionId()).isEqualTo("session-1");
        assertThat(manifest.getTenantId()).isEqualTo("tenant-1");
        assertThat(manifest.getDeptId()).isEqualTo("dept-cardiology");
        assertThat(manifest.getPatientId()).isEqualTo("patient-1");
        assertThat(manifest.getMessageCount()).isZero();
        assertThat(manifest.getPayloadChecksum()).isEqualTo(DIGEST);
        assertThat(manifest.getExportedAt()).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("equality follows the primary key, session_id")
    void equalityFollowsThePrimaryKey() {
        SessionArchiveManifestDO first = new SessionArchiveManifestDO("session-1", "tenant-1",
                "dept-cardiology", "patient-1", 3, DIGEST, 5_000L);
        SessionArchiveManifestDO sameKey = new SessionArchiveManifestDO("session-1", "tenant-2",
                "dept-neurology", "patient-9", 9, DIGEST, 9_000L);
        SessionArchiveManifestDO other = new SessionArchiveManifestDO("session-2", "tenant-1",
                "dept-cardiology", "patient-1", 3, DIGEST, 5_000L);

        assertThat(first).isEqualTo(sameKey).hasSameHashCodeAs(sameKey);
        assertThat(first).isNotEqualTo(other);
    }

    @Test
    @DisplayName("a checksum that is not a 64-character lower-case hex SHA-256 is rejected")
    void checksumShapeIsEnforced() {
        assertThatThrownBy(() -> new SessionArchiveManifestDO("session-1", "tenant-1", "dept-cardiology",
                "patient-1", 1, "not-a-digest", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64-character lower-case hex");
        assertThatThrownBy(() -> new SessionArchiveManifestDO("session-1", "tenant-1", "dept-cardiology",
                "patient-1", 1, DIGEST.toUpperCase(java.util.Locale.ROOT), 1L))
                .as("the digest is defined in lower case, so an upper-case one would never compare equal")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionArchiveManifestDO("session-1", "tenant-1", "dept-cardiology",
                "patient-1", 1, DIGEST.substring(1), 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("blank identity fields and a negative count are rejected")
    void invalidManifestsAreRejected() {
        assertThatThrownBy(() -> new SessionArchiveManifestDO(" ", "tenant-1", "dept-cardiology",
                "patient-1", 1, DIGEST, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionId must not be blank");
        assertThatThrownBy(() -> new SessionArchiveManifestDO("session-1", "tenant-1", "dept-cardiology",
                "patient-1", -1, DIGEST, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageCount must not be negative");
    }
}
