package com.med.qa.rag;

import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One medical document submitted for indexing into the RAG vector store.
 *
 * <p>The request carries text that is already in its final form. This project performs
 * <em>no</em> text preprocessing: the content is neither split, normalized, tokenized nor scanned
 * for clinical entities. Everything retrieval needs to narrow its scope is declared explicitly as
 * a {@link MedDocumentScope} plus optional descriptive metadata.</p>
 *
 * <h2>Identifier semantics</h2>
 * <p>Supplying an {@code id} makes ingestion idempotent: the vector store overwrites the record
 * under the same key, which is how a corrected guideline replaces its previous revision. Leaving it
 * {@code null} lets Spring AI generate one.</p>
 *
 * <p>Instances are immutable; the metadata map is defensively copied and exposed unmodifiable.</p>
 */
public final class MedDocumentRequest {

    private final String id;

    private final String text;

    private final MedDocumentScope scope;

    private final Map<String, Object> metadata;

    /**
     * Creates an ingestion request.
     *
     * @param id       stable document identifier for idempotent re-ingestion, or {@code null} to let
     *                 Spring AI generate one; must not be blank when present
     * @param text     document content, must not be blank
     * @param scope    tenant / department / patient isolation tags, must not be {@code null}
     * @param metadata additional descriptive attributes (title, source, revision, ...), may be
     *                 {@code null} or empty; keys must not be blank, must not collide with the
     *                 reserved scope tags and values must be a {@link String}, {@link Number} or
     *                 {@link Boolean} so they survive the JSON round trip of the vector store
     * @throws IllegalArgumentException if any of the above rules is violated
     */
    public MedDocumentRequest(@Nullable String id,
                              String text,
                              MedDocumentScope scope,
                              @Nullable Map<String, Object> metadata) {
        if (id != null && !StringUtils.hasText(id)) {
            throw new IllegalArgumentException("document id must not be blank when supplied");
        }
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("document text must not be blank");
        }
        if (scope == null) {
            throw new IllegalArgumentException("document scope must not be null");
        }
        this.id = id;
        this.text = text;
        this.scope = scope;
        this.metadata = copyMetadata(metadata);
    }

    /**
     * Creates a request without extra metadata and without a caller-supplied identifier.
     *
     * @param text  document content, must not be blank
     * @param scope isolation tags, must not be {@code null}
     * @return the immutable request, never {@code null}
     * @throws IllegalArgumentException if the text is blank or the scope is {@code null}
     */
    public static MedDocumentRequest of(String text, MedDocumentScope scope) {
        return new MedDocumentRequest(null, text, scope, null);
    }

    /**
     * Creates a request with extra metadata but without a caller-supplied identifier.
     *
     * @param text     document content, must not be blank
     * @param scope    isolation tags, must not be {@code null}
     * @param metadata descriptive attributes, may be {@code null}
     * @return the immutable request, never {@code null}
     * @throws IllegalArgumentException if any argument violates the constructor contract
     */
    public static MedDocumentRequest of(String text, MedDocumentScope scope,
                                        @Nullable Map<String, Object> metadata) {
        return new MedDocumentRequest(null, text, scope, metadata);
    }

    /**
     * Returns the caller-supplied identifier.
     *
     * @return the document id, or {@code null} when Spring AI has to generate one
     */
    @Nullable
    public String getId() {
        return id;
    }

    /**
     * Returns the document content to embed.
     *
     * @return the text, never blank
     */
    public String getText() {
        return text;
    }

    /**
     * Returns the isolation tags of the document.
     *
     * @return the scope, never {@code null}
     */
    public MedDocumentScope getScope() {
        return scope;
    }

    /**
     * Returns the descriptive metadata supplied by the caller.
     *
     * @return an unmodifiable map, never {@code null}, possibly empty and never containing a
     *         reserved scope tag
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    private static Map<String, Object> copyMetadata(@Nullable Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>(source.size());
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            if (!StringUtils.hasText(key)) {
                throw new IllegalArgumentException("document metadata keys must not be blank");
            }
            if (MedDocumentScope.RESERVED_METADATA_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                        "document metadata must not override the reserved scope tag '" + key
                                + "'; use MedDocumentScope instead");
            }
            Object value = entry.getValue();
            if (value == null) {
                throw new IllegalArgumentException("document metadata value of '" + key + "' must not be null");
            }
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                throw new IllegalArgumentException(
                        "document metadata value of '" + key + "' must be a string, number or boolean but is "
                                + value.getClass().getName());
            }
            copy.put(key, value);
        }
        return Collections.unmodifiableMap(copy);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MedDocumentRequest that)) {
            return false;
        }
        return Objects.equals(id, that.id)
                && text.equals(that.text)
                && scope.equals(that.scope)
                && metadata.equals(that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, text, scope, metadata);
    }

    /**
     * Renders the request without its clinical content.
     *
     * <p>The text is medical data and is never written to a log; only its length is reported.</p>
     *
     * @return a privacy-safe description, never {@code null}
     */
    @Override
    public String toString() {
        return "MedDocumentRequest{id='" + id + "', scope=" + scope
                + ", textLength=" + text.length() + ", metadataKeys=" + metadata.keySet() + '}';
    }
}
