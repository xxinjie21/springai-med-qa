package com.med.qa.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the live state of the RediSearch index behind medical RAG.
 *
 * <h2>What this class is — and is not</h2>
 * <p>It issues the official Jedis RediSearch commands {@code FT.LIST} (does the index exist?) and
 * {@code FT.INFO} (what does it hold?) and turns the replies into a {@link MedVectorIndexReport}. It
 * contains no vector math, no query building and no search logic: nothing here computes similarity,
 * ranks documents or evaluates a filter. The only non-trivial work is decoding {@code FT.INFO}, whose
 * nested structures the Jedis 5.x client returns as flat alternating key/value lists rather than maps
 * — see {@link #toFieldMap(Object)}.</p>
 *
 * <h2>Failure semantics</h2>
 * <p>An unreachable Redis propagates its {@link RuntimeException} (typically a
 * {@code JedisConnectionException}) to the caller: a probe that swallowed the difference between "the
 * index is missing" and "Redis is down" would report the wrong incident. An index that simply does not
 * exist is <em>not</em> an exception — it is the most important thing this probe can report, so it
 * comes back as {@link MedVectorIndexReport#missing(String)}.</p>
 */
public class MedVectorIndexProbe {

    /** {@code FT.INFO} key holding the index name. */
    public static final String FT_INFO_INDEX_NAME = "index_name";

    /** {@code FT.INFO} key holding the number of indexed documents. */
    public static final String FT_INFO_NUM_DOCS = "num_docs";

    /** {@code FT.INFO} key holding the index definition (key type + scanned prefixes). */
    public static final String FT_INFO_INDEX_DEFINITION = "index_definition";

    /** {@code FT.INFO} key holding the indexed attributes. */
    public static final String FT_INFO_ATTRIBUTES = "attributes";

    /** {@code FT.INFO} sub-key holding the key prefixes an index scans. */
    public static final String FT_INFO_PREFIXES = "prefixes";

    /** Attribute sub-key holding the name the attribute is addressable by, i.e. the TAG alias. */
    public static final String ATTRIBUTE_NAME = "attribute";

    /** Attribute sub-key holding the JSON path the attribute is sourced from. */
    public static final String ATTRIBUTE_IDENTIFIER = "identifier";

    /** Attribute sub-key holding the RediSearch field type. */
    public static final String ATTRIBUTE_TYPE = "type";

    /** RediSearch field type of an exact-match tag, the only one usable in a scoped filter. */
    public static final String TAG_TYPE = "TAG";

    /** JSON path prefix stripped when an attribute carries no explicit alias. */
    private static final String JSON_PATH_PREFIX = "$.";

    private static final Logger log = LoggerFactory.getLogger(MedVectorIndexProbe.class);

    private final JedisPooled jedis;

    private final MedVectorStoreProperties storeProperties;

    /**
     * Creates the probe.
     *
     * @param jedis           Jedis client dedicated to the vector index, must not be {@code null}
     * @param storeProperties index topology, source of the index name, must not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public MedVectorIndexProbe(JedisPooled jedis, MedVectorStoreProperties storeProperties) {
        if (jedis == null) {
            throw new IllegalArgumentException("jedis must not be null");
        }
        if (storeProperties == null) {
            throw new IllegalArgumentException("storeProperties must not be null");
        }
        this.jedis = jedis;
        this.storeProperties = storeProperties;
    }

    /**
     * Probes the configured index.
     *
     * @return the live state of the index, never {@code null}; an absent index is reported as
     *         {@link MedVectorIndexReport#missing(String)} rather than as a failure
     * @throws RuntimeException when Redis cannot be reached or the command fails
     */
    public MedVectorIndexReport probe() {
        String indexName = storeProperties.getIndexName();
        Set<String> indexes = jedis.ftList();
        if (indexes == null || !indexes.contains(indexName)) {
            log.debug("vector index '{}' is absent; RediSearch reports {}", indexName, indexes);
            return MedVectorIndexReport.missing(indexName);
        }
        return toReport(indexName, jedis.ftInfo(indexName));
    }

    /**
     * Returns the name of the index this probe inspects.
     *
     * @return the configured index name, never blank
     */
    public String indexName() {
        return storeProperties.getIndexName();
    }

    /**
     * Converts an {@code FT.INFO} reply into a report.
     *
     * <p>Exposed as a static method so the decoding can be asserted against a recorded reply without
     * a Redis instance. The reply is read defensively: a missing {@code num_docs} reads as zero
     * documents and a missing {@code attributes} section as "no TAG fields", which is exactly the
     * drift the caller must see rather than an exception.</p>
     *
     * @param indexName the probed index name, must not be blank
     * @param ftInfo    the raw {@code FT.INFO} reply, must not be {@code null}
     * @return the report, never {@code null}
     * @throws IllegalArgumentException if {@code indexName} is blank or {@code ftInfo} is {@code null}
     */
    public static MedVectorIndexReport toReport(String indexName, Map<String, Object> ftInfo) {
        if (indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("indexName must not be blank");
        }
        if (ftInfo == null) {
            throw new IllegalArgumentException("ftInfo must not be null");
        }
        Map<String, Object> definition = toFieldMap(ftInfo.get(FT_INFO_INDEX_DEFINITION));
        return new MedVectorIndexReport(
                indexName,
                true,
                toDocumentCount(ftInfo.get(FT_INFO_NUM_DOCS)),
                toPrefixes(definition.get(FT_INFO_PREFIXES)),
                toTagFields(ftInfo.get(FT_INFO_ATTRIBUTES)));
    }

    /**
     * Decodes one nested {@code FT.INFO} structure into a map.
     *
     * <p>Redis returns nested replies as flat arrays, and Jedis 5.x surfaces them verbatim: the index
     * definition arrives as {@code [key_type, JSON, prefixes, [med:doc:], default_score, 1]} and each
     * attribute as {@code [identifier, $.tenant_id, attribute, tenant_id, type, TAG]}. Newer client
     * versions materialise the same structures as maps, so both shapes are accepted and a map is
     * passed through untouched.</p>
     *
     * @param raw a nested {@code FT.INFO} value, may be {@code null}
     * @return the decoded field map, never {@code null}; empty when {@code raw} is {@code null}, an
     *         empty list or an unrecognised shape
     */
    public static Map<String, Object> toFieldMap(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> decoded = new LinkedHashMap<>(map.size());
            map.forEach((key, value) -> {
                if (key != null) {
                    decoded.put(String.valueOf(key), value);
                }
            });
            return decoded;
        }
        if (!(raw instanceof List<?> flat)) {
            return Map.of();
        }
        Map<String, Object> decoded = new LinkedHashMap<>(Math.max(flat.size() / 2, 1));
        for (int index = 0; index + 1 < flat.size(); index += 2) {
            Object key = flat.get(index);
            if (key != null) {
                decoded.put(String.valueOf(key), flat.get(index + 1));
            }
        }
        return decoded;
    }

    /**
     * Reads the document count out of an {@code FT.INFO} reply.
     *
     * @param raw the {@code num_docs} value, may be {@code null}
     * @return the document count, or {@code 0} when the value is absent or not a number
     */
    private static long toDocumentCount(Object raw) {
        if (raw instanceof Number number) {
            return Math.max(number.longValue(), 0L);
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Math.max(Long.parseLong(text.trim()), 0L);
            } catch (NumberFormatException ex) {
                return 0L;
            }
        }
        return 0L;
    }

    /**
     * Collects the attributes the index declares as {@code TAG} fields.
     *
     * <p>The alias ({@code attribute}) is preferred because that is the name a filter expression
     * addresses; the JSON path ({@code identifier}) is only a fallback for a reply that omits the
     * alias.</p>
     *
     * @param raw the {@code attributes} value, may be {@code null}
     * @return the TAG field names, never {@code null}
     */
    private static Set<String> toTagFields(Object raw) {
        if (!(raw instanceof List<?> attributes)) {
            return Set.of();
        }
        Set<String> tagFields = new LinkedHashSet<>();
        for (Object element : attributes) {
            Map<String, Object> attribute = toFieldMap(element);
            if (!TAG_TYPE.equalsIgnoreCase(String.valueOf(attribute.get(ATTRIBUTE_TYPE)))) {
                continue;
            }
            String name = attributeName(attribute);
            if (name != null) {
                tagFields.add(name);
            }
        }
        return tagFields;
    }

    /**
     * Resolves the addressable name of an attribute entry.
     *
     * @param attribute a decoded attribute map, must not be {@code null}
     * @return the alias, or the JSON path without its {@code $.} prefix, or {@code null} when neither
     *         is present
     */
    private static String attributeName(Map<String, Object> attribute) {
        Object alias = attribute.get(ATTRIBUTE_NAME);
        if (alias instanceof String text && !text.isBlank()) {
            return text;
        }
        Object identifier = attribute.get(ATTRIBUTE_IDENTIFIER);
        if (identifier instanceof String path && path.startsWith(JSON_PATH_PREFIX)) {
            String stripped = path.substring(JSON_PATH_PREFIX.length());
            return stripped.isBlank() ? null : stripped;
        }
        return null;
    }

    /**
     * Collects the key prefixes an index scans.
     *
     * <p>Useful in a health detail: a prefix that no longer matches the keys the ingestion writes
     * would make every document invisible even though the index itself is well formed.</p>
     *
     * @param raw the {@code prefixes} value, may be {@code null}
     * @return the prefixes, never {@code null}
     */
    private static Set<String> toPrefixes(Object raw) {
        if (raw instanceof String single) {
            return single.isBlank() ? Set.of() : Set.of(single);
        }
        if (!(raw instanceof List<?> list)) {
            return Set.of();
        }
        Set<String> prefixes = new LinkedHashSet<>();
        for (Object element : list) {
            if (element instanceof String text && !text.isBlank()) {
                prefixes.add(text);
            }
        }
        return prefixes;
    }
}
