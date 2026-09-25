package com.med.qa.rag;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable snapshot of the live RediSearch index behind medical RAG, as reported by
 * {@code FT.INFO}.
 *
 * <p>Only index <em>metadata</em> is carried here: whether the index exists, how many documents it
 * holds, which Redis key prefixes it scans and which attributes it indexes as {@code TAG} fields.
 * Document content, embeddings and query results are deliberately absent — this type feeds a health
 * endpoint and an alert message, both of which live outside the patient-data boundary.</p>
 *
 * <p>{@link #missingTagFields(Collection)} is the whole point: it turns "the index exists" into "the
 * index can still answer a scoped query". A field that the configuration declares but the index does
 * not index as a TAG makes the filter expression of {@link MedRetrievalFilters} match nothing, which
 * a caller cannot distinguish from "no such guideline exists".</p>
 *
 * @param indexName     name of the probed index, never blank
 * @param exists        {@code true} when {@code FT.LIST} reported the index
 * @param documentCount number of documents the index holds; {@code 0} when it does not exist
 * @param prefixes      Redis key prefixes the index scans; empty when it does not exist
 * @param tagFields     attributes the index declares as {@code TAG} fields; empty when it does not
 *                      exist
 */
public record MedVectorIndexReport(String indexName,
                                   boolean exists,
                                   long documentCount,
                                   Set<String> prefixes,
                                   Set<String> tagFields) {

    /**
     * Validates the fields and takes defensive, unmodifiable copies of both sets.
     *
     * @throws IllegalArgumentException if {@code indexName} is {@code null} or blank, or
     *                                  {@code documentCount} is negative
     */
    public MedVectorIndexReport {
        if (indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("indexName must not be blank");
        }
        if (documentCount < 0) {
            throw new IllegalArgumentException("documentCount must not be negative but was " + documentCount);
        }
        prefixes = copy(prefixes);
        tagFields = copy(tagFields);
    }

    /**
     * Builds the report of an index that does not exist.
     *
     * <p>Distinguishing "absent" from "present but empty" matters operationally: the first means
     * nothing will ever be retrieved until the index is recreated, the second is a healthy index whose
     * corpus has simply not been populated yet. Both would otherwise read as "zero documents".</p>
     *
     * @param indexName name of the missing index, must not be blank
     * @return the report of an absent index, never {@code null}
     * @throws IllegalArgumentException if {@code indexName} is {@code null} or blank
     */
    public static MedVectorIndexReport missing(String indexName) {
        return new MedVectorIndexReport(indexName, false, 0L, Set.of(), Set.of());
    }

    /**
     * Returns the expected TAG fields the live index does not declare.
     *
     * <p>An absent index reports every expected field as missing, which is accurate: until the index
     * exists, none of them can be used to scope a query.</p>
     *
     * @param expected expected TAG field names, may be {@code null} for "nothing expected"
     * @return the missing field names in the order of {@code expected}, never {@code null}; empty when
     *         nothing is missing
     */
    public Set<String> missingTagFields(Collection<String> expected) {
        if (expected == null || expected.isEmpty()) {
            return Set.of();
        }
        Set<String> missing = new LinkedHashSet<>();
        for (String field : expected) {
            if (field != null && !tagFields.contains(field)) {
                missing.add(field);
            }
        }
        return Collections.unmodifiableSet(missing);
    }

    /**
     * Tells whether the index exists and declares every expected TAG field.
     *
     * <p>This is the condition a health probe should report {@code UP} for. It intentionally does not
     * consider {@link #documentCount()}: an empty but correctly shaped index is a deployment that has
     * not ingested its corpus yet, not a broken one.</p>
     *
     * @param expected expected TAG field names, may be {@code null} for "nothing expected"
     * @return {@code true} when the index exists and no expected TAG field is missing
     */
    public boolean isScopedRetrievalReady(Collection<String> expected) {
        return exists && missingTagFields(expected).isEmpty();
    }

    /**
     * Tells whether the index declares a field as a TAG.
     *
     * @param fieldName the field to test, may be {@code null}
     * @return {@code true} when the index indexes that attribute as a TAG
     */
    public boolean hasTagField(String fieldName) {
        return fieldName != null && tagFields.contains(fieldName);
    }

    /**
     * Returns the prefixes as an immutable list, for a stable rendering in health details.
     *
     * @return the prefixes in the order the index reported them, never {@code null}
     */
    public List<String> prefixesAsList() {
        return List.copyOf(prefixes);
    }

    private static Set<String> copy(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    @Override
    public String toString() {
        return "MedVectorIndexReport{indexName='" + indexName
                + "', exists=" + exists
                + ", documentCount=" + documentCount
                + ", prefixes=" + prefixes
                + ", tagFields=" + Objects.toString(tagFields) + '}';
    }
}
