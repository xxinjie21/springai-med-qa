package com.med.qa.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Externalized policy of the RAG index rebuild, bound from {@code med.rag.index.rebuild.*}.
 *
 * <pre>
 * med:
 *   rag:
 *     index:
 *       rebuild:
 *         enabled: false                  # master switch; false removes the rebuilder bean entirely
 *         allow-document-deletion: false  # second key for the destructive DROP_AND_REINGEST mode
 *         lock-wait-time: 5s              # how long a rebuild waits for the cluster-wide mutex
 *         lock-lease-time: 10m            # lease of the rebuild lock; 0 keeps the Redisson watchdog
 *         batch-size: 50                  # documents pushed per ingestion call while re-ingesting
 *         max-documents: 5000             # upper bound on one rebuild's corpus
 * </pre>
 *
 * <h2>Why {@code enabled} defaults to {@code false}</h2>
 * <p>Every other RAG switch in this project defaults to on, because monitoring something costs
 * nothing. A rebuild is different: it is the only operation in the service that can drop a
 * RediSearch index, and with {@link MedIndexRebuildMode#DROP_AND_REINGEST} it is the only one that
 * can delete indexed clinical documents. Making the capability present-but-unused by default keeps
 * the blast radius of a misconfigured deployment at zero, and turning it on becomes a deliberate
 * deployment decision that shows up in the environment-variable review.</p>
 *
 * <h2>Why the destructive mode needs a second switch</h2>
 * <p>{@code allow-document-deletion} is deliberately separate from {@code enabled}. Enabling the
 * rebuild is what an operator does to repair a drifted index, which is routine; authorising the
 * deletion of every document of the index is not, and an attacker or a careless script that can
 * reach the rebuild API must not be able to obtain the second permission from the first. A
 * {@code DROP_AND_REINGEST} request on a deployment that has not set this switch is refused without
 * touching Redis.</p>
 *
 * <p>Every setter validates eagerly so a typo fails the application context at startup rather than
 * silently disabling a guard.</p>
 */
@ConfigurationProperties(prefix = MedIndexRebuildProperties.PREFIX)
public class MedIndexRebuildProperties {

    /** Configuration namespace owned by this class. */
    public static final String PREFIX = "med.rag.index.rebuild";

    /** Default wait for the cluster-wide rebuild mutex. */
    public static final Duration DEFAULT_LOCK_WAIT_TIME = Duration.ofSeconds(5);

    /** Default lease of the rebuild lock; zero delegates renewal to the Redisson watchdog. */
    public static final Duration DEFAULT_LOCK_LEASE_TIME = Duration.ofMinutes(10);

    /** Default number of documents handed to one ingestion call during a rebuild. */
    public static final int DEFAULT_BATCH_SIZE = 50;

    /** Default upper bound on the corpus a single rebuild may re-ingest. */
    public static final int DEFAULT_MAX_DOCUMENTS = 5000;

    private boolean enabled;

    private boolean allowDocumentDeletion;

    private Duration lockWaitTime = DEFAULT_LOCK_WAIT_TIME;

    private Duration lockLeaseTime = DEFAULT_LOCK_LEASE_TIME;

    private int batchSize = DEFAULT_BATCH_SIZE;

    private int maxDocuments = DEFAULT_MAX_DOCUMENTS;

    /**
     * Tells whether the rebuilder bean is wired at all.
     *
     * @return {@code true} when a rebuild can be triggered in this deployment
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables or disables the whole rebuild capability.
     *
     * @param enabled {@code false} (the default) removes the rebuilder bean, so no code path in the
     *                service can drop the search index
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Tells whether {@link MedIndexRebuildMode#DROP_AND_REINGEST} may run.
     *
     * @return {@code true} when the deployment authorises deleting the indexed documents
     */
    public boolean isAllowDocumentDeletion() {
        return allowDocumentDeletion;
    }

    /**
     * Authorises or forbids the destructive rebuild mode.
     *
     * @param allowDocumentDeletion {@code true} to let a rebuild drop the index together with its
     *                              documents; leaving it {@code false} makes such a request be
     *                              refused before Redis is contacted
     */
    public void setAllowDocumentDeletion(boolean allowDocumentDeletion) {
        this.allowDocumentDeletion = allowDocumentDeletion;
    }

    /**
     * Returns how long a rebuild waits for the cluster-wide mutex.
     *
     * @return a non-negative duration, never {@code null}
     */
    public Duration getLockWaitTime() {
        return lockWaitTime;
    }

    /**
     * Sets how long a rebuild waits for the mutex before giving up.
     *
     * <p>Zero means "do not wait": a rebuild already in progress makes the new one report
     * {@code SKIPPED_LOCK_HELD} immediately, which is the right behaviour for an operator who
     * triggered it twice.</p>
     *
     * @param lockWaitTime a non-negative duration
     * @throws IllegalArgumentException if {@code lockWaitTime} is {@code null} or negative
     */
    public void setLockWaitTime(Duration lockWaitTime) {
        if (lockWaitTime == null || lockWaitTime.isNegative()) {
            throw new IllegalArgumentException(
                    PREFIX + ".lock-wait-time must not be negative but was " + lockWaitTime);
        }
        this.lockWaitTime = lockWaitTime;
    }

    /**
     * Returns the lease of the rebuild lock.
     *
     * @return a non-negative duration, never {@code null}; zero keeps the Redisson watchdog in
     *         charge of renewing the lease
     */
    public Duration getLockLeaseTime() {
        return lockLeaseTime;
    }

    /**
     * Sets the lease of the rebuild lock.
     *
     * <p>A rebuild embeds a whole corpus, which can take minutes; with a fixed short lease the lock
     * would expire mid-rebuild and let a second rebuild start on a half-built index. Zero therefore
     * hands renewal to the Redisson watchdog, which stops renewing as soon as the owning JVM dies —
     * the same policy the session lock uses.</p>
     *
     * @param lockLeaseTime a non-negative duration
     * @throws IllegalArgumentException if {@code lockLeaseTime} is {@code null} or negative
     */
    public void setLockLeaseTime(Duration lockLeaseTime) {
        if (lockLeaseTime == null || lockLeaseTime.isNegative()) {
            throw new IllegalArgumentException(
                    PREFIX + ".lock-lease-time must not be negative but was " + lockLeaseTime);
        }
        this.lockLeaseTime = lockLeaseTime;
    }

    /**
     * Returns how many documents one ingestion call receives during a rebuild.
     *
     * @return a strictly positive batch size
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * Sets the re-ingestion batch size.
     *
     * <p>Must not exceed {@code med.rag.ingestion.max-documents-per-request}, because the rebuild
     * pushes each batch through the ordinary ingestion path, which enforces that limit. The
     * cross-check happens when the rebuilder bean is created, so a contradiction is a startup
     * failure rather than a mid-rebuild exception.</p>
     *
     * @param batchSize a strictly positive number of documents
     * @throws IllegalArgumentException if {@code batchSize} is zero or negative
     */
    public void setBatchSize(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException(
                    PREFIX + ".batch-size must be positive but was " + batchSize);
        }
        this.batchSize = batchSize;
    }

    /**
     * Returns the upper bound on the corpus a single rebuild may re-ingest.
     *
     * @return a strictly positive document count
     */
    public int getMaxDocuments() {
        return maxDocuments;
    }

    /**
     * Sets the upper bound on the corpus of one rebuild.
     *
     * <p>A rebuild re-embeds everything it is given, so an accidental "re-ingest the whole archive"
     * would cost real money and real time. The bound turns that into an explicit refusal.</p>
     *
     * @param maxDocuments a strictly positive document count
     * @throws IllegalArgumentException if {@code maxDocuments} is zero or negative
     */
    public void setMaxDocuments(int maxDocuments) {
        if (maxDocuments <= 0) {
            throw new IllegalArgumentException(
                    PREFIX + ".max-documents must be positive but was " + maxDocuments);
        }
        this.maxDocuments = maxDocuments;
    }

    /**
     * Renders the policy as the values an operator needs when diagnosing a refused rebuild.
     *
     * @return a one-line description, never blank
     */
    public String describe() {
        return PREFIX + "{enabled=" + enabled
                + ", allowDocumentDeletion=" + allowDocumentDeletion
                + ", lockWaitTime=" + lockWaitTime
                + ", lockLeaseTime=" + lockLeaseTime
                + ", batchSize=" + batchSize
                + ", maxDocuments=" + maxDocuments + '}';
    }

    /**
     * Returns the configuration keys this class owns, for documentation guard tests.
     *
     * @return the fully qualified property names, never {@code null}
     */
    public static List<String> propertyNames() {
        return List.of(
                PREFIX + ".enabled",
                PREFIX + ".allow-document-deletion",
                PREFIX + ".lock-wait-time",
                PREFIX + ".lock-lease-time",
                PREFIX + ".batch-size",
                PREFIX + ".max-documents");
    }
}
