package com.med.qa.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests of {@link SessionArchiveVerification} (D48).
 *
 * <p>The value of this record is that it names <em>why</em> a cold copy is not trusted, so the mapping
 * from verdict to {@link SessionArchiveVerification#verified()} and the "no manifest" shape are pinned
 * here - an operator reading "not verified" without the reason cannot tell a moved source from a
 * corrupt archive, and the two call for opposite reactions.</p>
 */
class SessionArchiveVerificationTest {

    private static final String DIGEST =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    @DisplayName("only VERIFIED counts as verified")
    void onlyVerifiedIsVerified() {
        assertThat(verification(SessionArchiveVerification.Status.VERIFIED, DIGEST).verified()).isTrue();
        for (SessionArchiveVerification.Status status : new SessionArchiveVerification.Status[] {
                SessionArchiveVerification.Status.MANIFEST_MISSING,
                SessionArchiveVerification.Status.TRANSCRIPT_CHANGED,
                SessionArchiveVerification.Status.COLD_COPY_DIFFERS}) {
            assertThat(verification(status, DIGEST).verified())
                    .as("%s must not read as verified", status)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("a session without a manifest reports an empty manifest digest, never a null")
    void missingManifestUsesAnEmptyDigest() {
        SessionArchiveVerification verification = new SessionArchiveVerification(
                SessionArchiveVerification.Status.MANIFEST_MISSING, 0, 0, DIGEST,
                SessionArchiveChecksum.EMPTY_TRANSCRIPT, "");

        assertThat(verification.manifestChecksum()).isEmpty();
        assertThat(verification.summary()).contains("status=MANIFEST_MISSING", "manifestChecksum=");
    }

    @Test
    @DisplayName("the summary carries both digests and both counts, so a log line is enough to triage")
    void summaryIsSelfContained() {
        SessionArchiveVerification verification = new SessionArchiveVerification(
                SessionArchiveVerification.Status.COLD_COPY_DIFFERS, 4, 3, DIGEST, DIGEST, DIGEST);

        assertThat(verification.summary())
                .contains("status=COLD_COPY_DIFFERS", "hotMessages=4", "archivedMessages=3",
                        "hotChecksum=" + DIGEST);
    }

    @Test
    @DisplayName("a null status, a negative count or a null digest is rejected")
    void invalidVerificationsAreRejected() {
        assertThatThrownBy(() -> new SessionArchiveVerification(null, 0, 0, DIGEST, DIGEST, DIGEST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status must not be null");
        assertThatThrownBy(() -> new SessionArchiveVerification(
                SessionArchiveVerification.Status.VERIFIED, -1, 0, DIGEST, DIGEST, DIGEST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");
        assertThatThrownBy(() -> new SessionArchiveVerification(
                SessionArchiveVerification.Status.VERIFIED, 0, 0, null, DIGEST, DIGEST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checksums must not be null");
    }

    private static SessionArchiveVerification verification(SessionArchiveVerification.Status status,
                                                           String digest) {
        return new SessionArchiveVerification(status, 1, 1, digest, digest, digest);
    }
}
