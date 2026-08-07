package com.med.qa.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Externalized configuration of the Spring AI {@code RedisVectorStore} backing medical RAG.
 *
 * <p>Bound from {@code med.rag.vector-store.*}. Only index topology is configured here: the search
 * index name, the Redis key prefix scanned by that index, the JSON field names holding the document
 * text and its embedding, the vector index algorithm, the distance metric and the metadata fields
 * that RediSearch must index so retrieval can be scoped by department / patient tags.</p>
 *
 * <p>No retrieval logic lives in this project — similarity search, Top-K selection and filter
 * expression translation are all provided by the official {@code RedisVectorStore}. This class only
 * decides how that component is instantiated.</p>
 *
 * <p>Every setter validates its input so a typo surfaces as a startup failure instead of an index
 * that silently indexes the wrong keys.</p>
 */
@ConfigurationProperties(prefix = MedVectorStoreProperties.PREFIX)
public class MedVectorStoreProperties {

    /** Configuration prefix bound by Spring Boot. */
    public static final String PREFIX = "med.rag.vector-store";

    /**
     * Key namespace of the conversation memory cache. The vector index must never scan it: the
     * cached windows are Protobuf blobs, not JSON documents, and exposing them to RediSearch would
     * leak consultation content into retrieval results.
     */
    public static final String RESERVED_CHAT_PREFIX = "med:chat:";

    /** Vector index algorithm offered by RediSearch. */
    public enum VectorAlgorithm {
        /** Hierarchical navigable small world graph: approximate, scales to large corpora. */
        HNSW,
        /** Brute force scan: exact, preferable while the corpus is small. */
        FLAT
    }

    /** Vector distance metric of the index. */
    public enum DistanceMetric {
        /** Cosine similarity — the only metric the official {@code RedisVectorStore} creates. */
        COSINE,
        /** Squared euclidean distance. */
        L2,
        /** Inner product. */
        IP
    }

    /** RediSearch field type used to index a metadata attribute. */
    public enum MetadataFieldType {
        /** Exact-match tag, the right choice for identifiers such as {@code dept_id}. */
        TAG,
        /** Full text field. */
        TEXT,
        /** Numeric range field. */
        NUMERIC
    }

    /** A single metadata attribute that RediSearch indexes so it can be used as a filter. */
    public static class MetadataFieldSpec {

        private String name;

        private MetadataFieldType type = MetadataFieldType.TAG;

        public MetadataFieldSpec() {
        }

        /**
         * Convenience constructor used by the built-in defaults and by tests.
         *
         * @param name metadata attribute name, must not be blank
         * @param type RediSearch field type, must not be {@code null}
         * @throws IllegalArgumentException if {@code name} is blank or {@code type} is {@code null}
         */
        public MetadataFieldSpec(String name, MetadataFieldType type) {
            setName(name);
            setType(type);
        }

        public String getName() {
            return name;
        }

        /**
         * Sets the metadata attribute name.
         *
         * @param name attribute name as written in the {@code Document} metadata map
         * @throws IllegalArgumentException if the name is {@code null} or blank
         */
        public void setName(String name) {
            if (!StringUtils.hasText(name)) {
                throw new IllegalArgumentException(PREFIX + ".metadata-fields[].name must not be blank");
            }
            this.name = name;
        }

        public MetadataFieldType getType() {
            return type;
        }

        /**
         * Sets the RediSearch field type used to index the attribute.
         *
         * @param type field type, must not be {@code null}
         * @throws IllegalArgumentException if {@code type} is {@code null}
         */
        public void setType(MetadataFieldType type) {
            if (type == null) {
                throw new IllegalArgumentException(PREFIX + ".metadata-fields[].type must not be null");
            }
            this.type = type;
        }

        @Override
        public String toString() {
            return "MetadataFieldSpec{name='" + name + "', type=" + type + '}';
        }
    }

    private String indexName = "med-doc-index";

    private String prefix = "med:doc:";

    private String contentFieldName = "content";

    private String embeddingFieldName = "embedding";

    private VectorAlgorithm vectorAlgorithm = VectorAlgorithm.HNSW;

    private DistanceMetric distanceMetric = DistanceMetric.COSINE;

    private boolean initializeSchema = true;

    private List<MetadataFieldSpec> metadataFields = defaultMetadataFields();

    /**
     * Tenant / department / patient tags indexed by default, matching the isolation dimensions of
     * the unified storage specification.
     *
     * @return a fresh mutable list of the default metadata fields, never {@code null}
     */
    public static List<MetadataFieldSpec> defaultMetadataFields() {
        List<MetadataFieldSpec> defaults = new ArrayList<>(3);
        defaults.add(new MetadataFieldSpec("tenant_id", MetadataFieldType.TAG));
        defaults.add(new MetadataFieldSpec("dept_id", MetadataFieldType.TAG));
        defaults.add(new MetadataFieldSpec("patient_id", MetadataFieldType.TAG));
        return defaults;
    }

    public String getIndexName() {
        return indexName;
    }

    /**
     * Sets the RediSearch index name.
     *
     * @param indexName index name, must not be blank
     * @throws IllegalArgumentException if the name is {@code null} or blank
     */
    public void setIndexName(String indexName) {
        if (!StringUtils.hasText(indexName)) {
            throw new IllegalArgumentException(PREFIX + ".index-name must not be blank");
        }
        this.indexName = indexName;
    }

    public String getPrefix() {
        return prefix;
    }

    /**
     * Sets the Redis key prefix scanned by the index.
     *
     * @param prefix key prefix, must not be blank and must not overlap the conversation cache
     *               namespace {@value #RESERVED_CHAT_PREFIX}
     * @throws IllegalArgumentException if the prefix is blank or would make the index scan cached
     *                                  conversation windows
     */
    public void setPrefix(String prefix) {
        if (!StringUtils.hasText(prefix)) {
            throw new IllegalArgumentException(PREFIX + ".prefix must not be blank");
        }
        if (prefix.startsWith(RESERVED_CHAT_PREFIX)) {
            throw new IllegalArgumentException(
                    PREFIX + ".prefix must not start with " + RESERVED_CHAT_PREFIX
                            + " (reserved for the conversation memory cache)");
        }
        this.prefix = prefix;
    }

    public String getContentFieldName() {
        return contentFieldName;
    }

    /**
     * Sets the JSON field holding the document text.
     *
     * @param contentFieldName field name, must not be blank
     * @throws IllegalArgumentException if the name is {@code null} or blank
     */
    public void setContentFieldName(String contentFieldName) {
        if (!StringUtils.hasText(contentFieldName)) {
            throw new IllegalArgumentException(PREFIX + ".content-field-name must not be blank");
        }
        this.contentFieldName = contentFieldName;
    }

    public String getEmbeddingFieldName() {
        return embeddingFieldName;
    }

    /**
     * Sets the JSON field holding the embedding vector.
     *
     * @param embeddingFieldName field name, must not be blank
     * @throws IllegalArgumentException if the name is {@code null} or blank
     */
    public void setEmbeddingFieldName(String embeddingFieldName) {
        if (!StringUtils.hasText(embeddingFieldName)) {
            throw new IllegalArgumentException(PREFIX + ".embedding-field-name must not be blank");
        }
        this.embeddingFieldName = embeddingFieldName;
    }

    public VectorAlgorithm getVectorAlgorithm() {
        return vectorAlgorithm;
    }

    /**
     * Sets the vector index algorithm.
     *
     * @param vectorAlgorithm algorithm, must not be {@code null}
     * @throws IllegalArgumentException if {@code vectorAlgorithm} is {@code null}
     */
    public void setVectorAlgorithm(VectorAlgorithm vectorAlgorithm) {
        if (vectorAlgorithm == null) {
            throw new IllegalArgumentException(PREFIX + ".vector-algorithm must not be null");
        }
        this.vectorAlgorithm = vectorAlgorithm;
    }

    public DistanceMetric getDistanceMetric() {
        return distanceMetric;
    }

    /**
     * Sets the vector distance metric of the index.
     *
     * @param distanceMetric metric, must not be {@code null}
     * @throws IllegalArgumentException if {@code distanceMetric} is {@code null}
     */
    public void setDistanceMetric(DistanceMetric distanceMetric) {
        if (distanceMetric == null) {
            throw new IllegalArgumentException(PREFIX + ".distance-metric must not be null");
        }
        this.distanceMetric = distanceMetric;
    }

    public boolean isInitializeSchema() {
        return initializeSchema;
    }

    /**
     * Enables or disables automatic {@code FT.CREATE} of the index on first use.
     *
     * @param initializeSchema {@code true} to let the store create the index when missing
     */
    public void setInitializeSchema(boolean initializeSchema) {
        this.initializeSchema = initializeSchema;
    }

    public List<MetadataFieldSpec> getMetadataFields() {
        return metadataFields;
    }

    /**
     * Sets the metadata attributes RediSearch has to index.
     *
     * @param metadataFields field specs, must not be {@code null}, must not contain {@code null}
     *                       entries and must not repeat an attribute name
     * @throws IllegalArgumentException if the list is {@code null}, holds a {@code null} entry, an
     *                                  entry without a name, or duplicate names
     */
    public void setMetadataFields(List<MetadataFieldSpec> metadataFields) {
        if (metadataFields == null) {
            throw new IllegalArgumentException(PREFIX + ".metadata-fields must not be null");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (MetadataFieldSpec spec : metadataFields) {
            if (spec == null) {
                throw new IllegalArgumentException(PREFIX + ".metadata-fields must not contain null entries");
            }
            if (!StringUtils.hasText(spec.getName())) {
                throw new IllegalArgumentException(PREFIX + ".metadata-fields[].name must not be blank");
            }
            if (!seen.add(spec.getName())) {
                throw new IllegalArgumentException(
                        PREFIX + ".metadata-fields contains duplicate name: " + spec.getName());
            }
        }
        this.metadataFields = new ArrayList<>(metadataFields);
    }
}
