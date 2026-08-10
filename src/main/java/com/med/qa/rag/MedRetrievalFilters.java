package com.med.qa.rag;

import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder.Op;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Factory of the metadata filter expressions that scope medical RAG retrieval.
 *
 * <h2>What this class is</h2>
 * <p>A thin, declarative assembly of Spring AI's official {@link FilterExpressionBuilder}. It owns
 * no matching logic: the expressions produced here are handed to the {@code VectorStore}, which
 * translates them into a native RediSearch {@code TAG} query and evaluates them inside Redis. There
 * is no post-filtering loop, no scoring and no text analysis anywhere in this class — retrieval is
 * narrowed exclusively by the tags declared at ingestion time.</p>
 *
 * <h2>The isolation rule</h2>
 * <p>Every expression is the conjunction of three tag predicates:</p>
 * <pre>{@code tenant_id == <tenant> AND dept_id == <dept> AND <patient predicate>}</pre>
 * <p>The patient predicate is what makes a retrieval safe:</p>
 * <ul>
 *   <li><b>patient-scoped query, shared documents included</b> (the default for a consultation) —
 *       {@code patient_id IN [<patient>, __shared__]}: the patient's own records plus the
 *       department's guidelines.</li>
 *   <li><b>patient-scoped query, shared documents excluded</b> — {@code patient_id == <patient>}:
 *       used when only the patient's own record is wanted, for instance to summarize a chart.</li>
 *   <li><b>department-wide query</b> — {@code patient_id == __shared__}: guidelines only. It
 *       deliberately does <em>not</em> widen to every patient of the department; a query without a
 *       patient in hand must never return someone's discharge summary.</li>
 * </ul>
 *
 * <p>The class is a stateless utility and cannot be instantiated.</p>
 */
public final class MedRetrievalFilters {

    private MedRetrievalFilters() {
        throw new AssertionError("MedRetrievalFilters is a utility class");
    }

    /**
     * Builds the isolation filter of a scope, including the department-wide shared documents.
     *
     * @param scope tenant / department / patient tags the caller is entitled to, must not be
     *              {@code null}
     * @return the filter expression to attach to a {@code SearchRequest}, never {@code null}
     * @throws IllegalArgumentException if {@code scope} is {@code null}
     */
    public static Filter.Expression scope(MedDocumentScope scope) {
        return scope(scope, true);
    }

    /**
     * Builds the isolation filter of a scope.
     *
     * @param scope                   tenant / department / patient tags the caller is entitled to,
     *                                must not be {@code null}
     * @param includeSharedDocuments  whether department-wide documents tagged
     *                                {@value MedDocumentScope#SHARED_PATIENT_TAG} take part in the
     *                                retrieval
     * @return the filter expression to attach to a {@code SearchRequest}, never {@code null}
     * @throws IllegalArgumentException if {@code scope} is {@code null}, or if a department-wide
     *                                  scope excludes shared documents — that combination selects
     *                                  nothing at all and is a caller mistake rather than an empty
     *                                  result
     */
    public static Filter.Expression scope(MedDocumentScope scope, boolean includeSharedDocuments) {
        List<Object> patientTags = patientTags(scope, includeSharedDocuments);
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        Op tenant = builder.eq(MedDocumentScope.METADATA_TENANT_ID, scope.getTenantId());
        Op dept = builder.eq(MedDocumentScope.METADATA_DEPT_ID, scope.getDeptId());
        Op patient = patientTags.size() == 1
                ? builder.eq(MedDocumentScope.METADATA_PATIENT_ID, patientTags.get(0))
                : builder.in(MedDocumentScope.METADATA_PATIENT_ID, patientTags);
        return builder.and(builder.and(tenant, dept), patient).build();
    }

    /**
     * Returns the patient tags a scope is allowed to match.
     *
     * @param scope                  scope of the caller, must not be {@code null}
     * @param includeSharedDocuments whether department-wide documents take part in the retrieval
     * @return the accepted values of {@value MedDocumentScope#METADATA_PATIENT_ID}, in a stable
     *         order, never empty
     * @throws IllegalArgumentException if {@code scope} is {@code null}, or if a department-wide
     *                                  scope excludes shared documents
     */
    public static List<Object> patientTags(MedDocumentScope scope, boolean includeSharedDocuments) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        List<Object> tags = new ArrayList<>(2);
        if (scope.isPatientScoped()) {
            tags.add(scope.getPatientId());
            if (includeSharedDocuments) {
                tags.add(MedDocumentScope.SHARED_PATIENT_TAG);
            }
        } else {
            if (!includeSharedDocuments) {
                throw new IllegalArgumentException(
                        "a department-wide scope retrieves only documents tagged "
                                + MedDocumentScope.SHARED_PATIENT_TAG
                                + "; excluding them would match nothing");
            }
            tags.add(MedDocumentScope.SHARED_PATIENT_TAG);
        }
        return tags;
    }

    /**
     * Conjoins the isolation filter with an optional caller-supplied filter.
     *
     * <p>The isolation filter is always the left operand and can never be dropped: a caller may
     * narrow a retrieval further (by document type, revision date, ...) but may not widen it beyond
     * the tags it is entitled to.</p>
     *
     * @param isolation isolation filter produced by {@link #scope(MedDocumentScope, boolean)}, must
     *                  not be {@code null}
     * @param extra     additional caller filter, may be {@code null}
     * @return {@code isolation} when {@code extra} is {@code null}, otherwise their conjunction;
     *         never {@code null}
     * @throws IllegalArgumentException if {@code isolation} is {@code null}
     */
    public static Filter.Expression and(Filter.Expression isolation, @Nullable Filter.Expression extra) {
        if (isolation == null) {
            throw new IllegalArgumentException("isolation filter must not be null");
        }
        if (extra == null) {
            return isolation;
        }
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        return builder.and(new Op(isolation), new Op(extra)).build();
    }

    /**
     * Verifies that a retrieved document really carries the tags of the scope that asked for it.
     *
     * <p>Defense in depth. The vector store is trusted to apply the filter, but a wrong index
     * definition, a document indexed by another writer without tags or a partially rebuilt index
     * would all surface as a cross-department leak rather than as an error. This predicate lets the
     * caller fail closed instead.</p>
     *
     * @param metadata               metadata of the retrieved document, may be {@code null}
     * @param scope                  scope the retrieval was issued for, must not be {@code null}
     * @param includeSharedDocuments whether department-wide documents were part of the retrieval
     * @return {@code true} when all three tags are present and allowed by the scope
     * @throws IllegalArgumentException if {@code scope} is {@code null}, or if a department-wide
     *                                  scope excludes shared documents
     */
    public static boolean matches(@Nullable Map<String, Object> metadata,
                                  MedDocumentScope scope,
                                  boolean includeSharedDocuments) {
        List<Object> patientTags = patientTags(scope, includeSharedDocuments);
        if (metadata == null) {
            return false;
        }
        return scope.getTenantId().equals(metadata.get(MedDocumentScope.METADATA_TENANT_ID))
                && scope.getDeptId().equals(metadata.get(MedDocumentScope.METADATA_DEPT_ID))
                && patientTags.contains(metadata.get(MedDocumentScope.METADATA_PATIENT_ID));
    }
}
