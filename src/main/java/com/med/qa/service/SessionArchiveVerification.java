package com.med.qa.service;

/**
 * Verdict of re-checking one exported session against its manifest (D48).
 *
 * <p>The export certifies a session by writing a manifest whose {@code payload_checksum} was computed
 * from the source transcript. That claim is only worth something if it can be re-checked later,
 * independently of the run that made it — by an operator who suspects the cold store, by a future
 * purge job that must not delete rows it cannot account for, or by an integration test. This record is
 * that answer, and it names the <em>reason</em> rather than just saying "no": a moved source and a
 * corrupt copy call for completely different reactions.</p>
 *
 * @param status              the verdict
 * @param hotMessageCount     how many messages the live transcript holds now
 * @param archivedMessageCount how many messages the cold store holds now
 * @param hotChecksum         digest recomputed from the live transcript, never {@code null}
 * @param archivedChecksum    digest recomputed from the cold copy, never {@code null}
 * @param manifestChecksum    digest the manifest recorded, or an empty string when there is no manifest
 */
public record SessionArchiveVerification(Status status,
                                         long hotMessageCount,
                                         long archivedMessageCount,
                                         String hotChecksum,
                                         String archivedChecksum,
                                         String manifestChecksum) {

    /** Why a verification ended the way it did. */
    public enum Status {

        /** The cold copy and the live transcript both reproduce the digest the manifest recorded. */
        VERIFIED,

        /** The session has no manifest, so it was never exported (or its manifest was lost). */
        MANIFEST_MISSING,

        /**
         * The live transcript no longer reproduces the manifest digest, i.e. it changed after the
         * export. The cold copy is a faithful snapshot of an older state, so the fix is to re-export
         * (which the export job does on its own, since the session has no valid certification), not to
         * repair the archive.
         */
        TRANSCRIPT_CHANGED,

        /**
         * The live transcript still matches the manifest but the cold copy does not, i.e. the archive
         * rows were altered or lost after certification. This is the one verdict that points at the
         * cold store itself.
         */
        COLD_COPY_DIFFERS
    }

    /**
     * Normalises the verdict and validates the counters.
     *
     * @throws IllegalArgumentException if the status is {@code null}, a count is negative or a digest
     *                                  is {@code null}
     */
    public SessionArchiveVerification {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (hotMessageCount < 0 || archivedMessageCount < 0) {
            throw new IllegalArgumentException("message counts must not be negative");
        }
        if (hotChecksum == null || archivedChecksum == null || manifestChecksum == null) {
            throw new IllegalArgumentException(
                    "checksums must not be null; use an empty string when there is no manifest");
        }
    }

    /**
     * Tells whether the cold copy is trustworthy right now.
     *
     * @return {@code true} only for {@link Status#VERIFIED}
     */
    public boolean verified() {
        return status == Status.VERIFIED;
    }

    /**
     * Renders a one-line summary for logs and alert messages.
     *
     * @return a single-line, human-readable summary, never {@code null}
     */
    public String summary() {
        return "status=" + status
                + ", hotMessages=" + hotMessageCount
                + ", archivedMessages=" + archivedMessageCount
                + ", hotChecksum=" + hotChecksum
                + ", archivedChecksum=" + archivedChecksum
                + ", manifestChecksum=" + manifestChecksum;
    }
}
