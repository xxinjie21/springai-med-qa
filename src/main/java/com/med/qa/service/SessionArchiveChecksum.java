package com.med.qa.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;

/**
 * Canonical digest of an archived consultation transcript (D48).
 *
 * <p>This is the one place that defines what "the cold copy matches the source" means, so the export
 * and the on-demand verification cannot drift apart into two different notions of equality. It uses
 * nothing but the JDK's SHA-256 - no custom hashing, no rolling checksum, no length framing.</p>
 *
 * <h2>The canonical form</h2>
 * <p>For every message, in transcript order, one line:</p>
 * <pre>
 * &lt;message_id&gt; \t &lt;created_at&gt; \t &lt;sha256hex(payload)&gt;
 * </pre>
 * <p>joined with {@code '\n'} and hashed once more with SHA-256; the result is the 64-character
 * lower-case hex digest stored in the manifest. Hashing each payload first (rather than concatenating
 * raw payloads) removes any ambiguity about where one message ends and the next begins, which is what
 * lets the same digest be recomputed by the Python middleware from {@code med_session.proto} without
 * knowing this class.</p>
 *
 * <h2>Why the message id is part of it</h2>
 * <p>The digest covers the <em>sequence</em>, not just the set: reordering two messages, dropping one,
 * or duplicating one all change it. The count is stored separately in the manifest because an empty
 * transcript is a legal transcript, and its digest ({@link #EMPTY_TRANSCRIPT}) has to be
 * distinguishable from "never exported" - which is what the manifest row itself expresses.</p>
 */
public final class SessionArchiveChecksum {

    /** Digest of a transcript with no messages, i.e. {@code sha256("")}. */
    public static final String EMPTY_TRANSCRIPT =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private SessionArchiveChecksum() {
    }

    /**
     * One message of a transcript, reduced to what the digest depends on.
     *
     * <p>A carrier, not a value object: {@link #payload()} is an array, so the record's generated
     * {@code equals}/{@code hashCode} compare it by identity. Nothing in this class compares entries -
     * they are only hashed.</p>
     *
     * @param messageId storage-spec message id, must not be blank
     * @param createdAt original creation time as epoch milliseconds
     * @param payload   frozen Protobuf payload, must not be {@code null} or empty
     */
    public record Entry(String messageId, long createdAt, byte[] payload) {

        /**
         * Validates the entry.
         *
         * @throws IllegalArgumentException if {@code messageId} is blank or {@code payload} is
         *                                  {@code null} or empty
         */
        public Entry {
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException("messageId must not be blank");
            }
            if (payload == null || payload.length == 0) {
                throw new IllegalArgumentException("payload must not be empty");
            }
        }
    }

    /**
     * Computes the canonical digest of a transcript.
     *
     * @param entries the transcript in its storage order, must not be {@code null}; a {@code null}
     *                element is rejected rather than skipped, because silently dropping a message
     *                would certify an incomplete copy
     * @return the 64-character lower-case hex SHA-256 of the canonical form, never {@code null}
     * @throws IllegalArgumentException if {@code entries} is {@code null} or holds a {@code null}
     */
    public static String of(List<Entry> entries) {
        if (entries == null) {
            throw new IllegalArgumentException("entries must not be null");
        }
        StringBuilder canonical = new StringBuilder();
        for (Entry entry : entries) {
            if (entry == null) {
                throw new IllegalArgumentException("entries must not hold null elements");
            }
            canonical.append(entry.messageId()).append('\t')
                    .append(entry.createdAt()).append('\t')
                    .append(sha256Hex(entry.payload())).append('\n');
        }
        return sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns the hex SHA-256 of a byte array.
     *
     * @param payload the bytes to hash, must not be {@code null}
     * @return the 64-character lower-case hex digest, never {@code null}
     * @throws IllegalArgumentException if {@code payload} is {@code null}
     */
    public static String sha256Hex(byte[] payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        return toHex(digest().digest(payload));
    }

    /**
     * Creates a fresh SHA-256 digest.
     *
     * <p>{@link MessageDigest} instances are stateful and not thread-safe, so one is created per call
     * rather than shared. SHA-256 is mandated for every Java platform, so a missing provider is an
     * unusable runtime rather than a recoverable condition.</p>
     *
     * @return a new SHA-256 digest
     * @throws IllegalStateException if the runtime has no SHA-256 provider
     */
    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available in this runtime", ex);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
