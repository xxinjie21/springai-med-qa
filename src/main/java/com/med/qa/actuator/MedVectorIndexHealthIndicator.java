package com.med.qa.actuator;

import com.med.qa.rag.MedRagIndexProperties;
import com.med.qa.rag.MedVectorIndexProbe;
import com.med.qa.rag.MedVectorIndexReport;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;

import java.util.List;
import java.util.Set;

/**
 * Health probe for the RAG vector index.
 *
 * <p>{@link MedStorageHealthIndicator} answers "is Redis reachable?". That is not the same question as
 * "can a consultation retrieve anything?", and the gap between them is where the RAG layer fails
 * silently. Redis can be perfectly reachable while the RediSearch index is missing (dropped by hand,
 * or never created because the instance is a plain Redis build) or while its schema has drifted from
 * the configuration — for instance a TAG field was renamed, so the filter expression the retrieval
 * layer builds no longer matches any document. Neither case raises an exception: the consultation
 * endpoint keeps returning HTTP 200 and simply retrieves less, so a clinician receives an answer
 * assembled from an incomplete corpus.</p>
 *
 * <p>This indicator turns those two states into an explicit {@code DOWN} with a machine-readable
 * {@code reason}:</p>
 * <ul>
 *   <li>{@link #REASON_INDEX_MISSING} — {@code FT.LIST} does not report the index;</li>
 *   <li>{@link #REASON_SCHEMA_DRIFT} — the index exists but does not declare every expected TAG
 *       field;</li>
 *   <li>{@link #REASON_UNREACHABLE} — the probe itself threw, so no verdict could be reached.</li>
 * </ul>
 *
 * <p>The details carry index metadata only — name, existence, document count, TAG field names and the
 * scanned key prefixes. No document text, embedding or query result ever reaches
 * {@code /actuator/health}.</p>
 *
 * <h2>Deployment note</h2>
 * <p>The probe needs Redis Stack (RediSearch). A deployment running the conversation cache on a plain
 * Redis build must set {@code med.rag.index.enabled=false}; the bean is then not created and the
 * component disappears from the health report instead of failing it.</p>
 */
public class MedVectorIndexHealthIndicator extends AbstractHealthIndicator {

    /** Detail key carrying the probed index name. */
    public static final String INDEX_DETAIL = "index";

    /** Detail key telling whether the index exists. */
    public static final String EXISTS_DETAIL = "exists";

    /** Detail key carrying the number of indexed documents. */
    public static final String DOCUMENTS_DETAIL = "documents";

    /** Detail key carrying the TAG fields the index declares. */
    public static final String TAG_FIELDS_DETAIL = "tag-fields";

    /** Detail key carrying the expected TAG fields the index does not declare. */
    public static final String MISSING_TAG_FIELDS_DETAIL = "missing-tag-fields";

    /** Detail key carrying the machine-readable cause of a {@code DOWN} verdict. */
    public static final String REASON_DETAIL = "reason";

    /** Detail key carrying the Redis key prefixes the index scans. */
    public static final String PREFIXES_DETAIL = "prefixes";

    /** Reason reported when the index does not exist at all. */
    public static final String REASON_INDEX_MISSING = "index-missing";

    /** Reason reported when the index exists but its TAG schema drifted from the configuration. */
    public static final String REASON_SCHEMA_DRIFT = "schema-drift";

    /** Reason reported when the probe threw, so no verdict could be reached. */
    public static final String REASON_UNREACHABLE = "unreachable";

    private final MedVectorIndexProbe probe;

    private final MedRagIndexProperties indexProperties;

    /**
     * Creates the indicator.
     *
     * @param probe           reads the live index state, must not be {@code null}
     * @param indexProperties expected TAG fields, must not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public MedVectorIndexHealthIndicator(MedVectorIndexProbe probe, MedRagIndexProperties indexProperties) {
        if (probe == null) {
            throw new IllegalArgumentException("probe must not be null");
        }
        if (indexProperties == null) {
            throw new IllegalArgumentException("indexProperties must not be null");
        }
        this.probe = probe;
        this.indexProperties = indexProperties;
    }

    /**
     * Probes the index and aggregates the outcome into a health status.
     *
     * <p>The probe is documented not to swallow a connection failure, so any exception raised here
     * means Redis could not be reached or the command was rejected. That is reported as {@code DOWN}
     * with {@link #REASON_UNREACHABLE} rather than propagated, because an Actuator probe must always
     * answer.</p>
     *
     * @param builder the Actuator builder collecting status and details
     */
    @Override
    protected void doHealthCheck(Health.Builder builder) {
        MedVectorIndexReport report;
        try {
            report = probe.probe();
        } catch (RuntimeException ex) {
            builder.down()
                    .withDetail(INDEX_DETAIL, probe.indexName())
                    .withDetail(REASON_DETAIL, REASON_UNREACHABLE)
                    .withDetail(EXISTS_DETAIL, false)
                    .withDetail(DOCUMENTS_DETAIL, 0L);
            return;
        }

        builder.withDetail(INDEX_DETAIL, report.indexName())
                .withDetail(EXISTS_DETAIL, report.exists())
                .withDetail(DOCUMENTS_DETAIL, report.documentCount());

        if (!report.exists()) {
            builder.down().withDetail(REASON_DETAIL, REASON_INDEX_MISSING);
            return;
        }

        Set<String> missing = report.missingTagFields(indexProperties.getExpectedTagFields());
        builder.withDetail(TAG_FIELDS_DETAIL, report.tagFields())
                .withDetail(PREFIXES_DETAIL, report.prefixesAsList());

        if (!missing.isEmpty()) {
            builder.down()
                    .withDetail(REASON_DETAIL, REASON_SCHEMA_DRIFT)
                    .withDetail(MISSING_TAG_FIELDS_DETAIL, List.copyOf(missing));
            return;
        }

        builder.up();
    }
}
