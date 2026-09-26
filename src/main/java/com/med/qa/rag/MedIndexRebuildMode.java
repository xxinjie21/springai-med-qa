package com.med.qa.rag;

/**
 * What a RAG index rebuild is allowed to touch.
 *
 * <p>The two modes answer two different incidents, and conflating them would make the safe repair
 * dangerous:</p>
 *
 * <ul>
 *   <li>{@link #INDEX_ONLY} — the RediSearch index is dropped but the documents behind it are
 *       <em>kept</em>. RediSearch re-indexes every existing key that matches the prefix when
 *       {@code FT.CREATE} runs, so the new index is populated from the very same JSON documents,
 *       with the schema the configuration now declares. This is the repair for the schema drift
 *       D37 detects: no document is lost, no embedding call is made, no clinical content leaves the
 *       store.</li>
 *   <li>{@link #DROP_AND_REINGEST} — the index <em>and</em> its documents are removed
 *       ({@code FT.DROPINDEX ... DD}), then the caller-supplied corpus is written back through the
 *       ordinary ingestion path. This is the repair for a corrupted or superseded corpus, and it is
 *       destructive: every document of the index disappears before the new ones are written. It is
 *       therefore gated twice — the caller must ask for it explicitly and the deployment must set
 *       {@code med.rag.index.rebuild.allow-document-deletion=true}.</li>
 * </ul>
 *
 * <p>Nothing here computes vectors or queries: the mode only selects which official Jedis command
 * ({@code FT.DROPINDEX} with or without {@code DD}) the rebuild issues.</p>
 */
public enum MedIndexRebuildMode {

    /**
     * Recreates the index and its schema, keeping every indexed document.
     *
     * <p>The safe default: it repairs a drifted or missing index without deleting anything.</p>
     */
    INDEX_ONLY(false),

    /**
     * Drops the index together with its documents and re-ingests a supplied corpus.
     *
     * <p>Destructive and therefore opt-in on both the request and the deployment.</p>
     */
    DROP_AND_REINGEST(true);

    private final boolean deletesDocuments;

    MedIndexRebuildMode(boolean deletesDocuments) {
        this.deletesDocuments = deletesDocuments;
    }

    /**
     * Tells whether this mode deletes the documents of the index.
     *
     * @return {@code true} for {@link #DROP_AND_REINGEST}, {@code false} for {@link #INDEX_ONLY}
     */
    public boolean deletesDocuments() {
        return deletesDocuments;
    }
}
