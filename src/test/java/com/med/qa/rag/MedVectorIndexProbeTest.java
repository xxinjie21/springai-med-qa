package com.med.qa.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MedVectorIndexProbe}.
 *
 * <p>The probe is the only place in the project that reads a RediSearch reply, and the reply shape is
 * not obvious: Jedis 5.x returns {@code FT.INFO}'s nested structures as <em>flat alternating
 * key/value lists</em> ({@code [identifier, $.tenant_id, attribute, tenant_id, type, TAG]}) rather
 * than as maps. These tests pin both the shape the shipped client really produces and the map shape
 * newer clients produce, so an upgrade cannot silently turn every index into "zero TAG fields" — which
 * would look exactly like the schema drift the probe exists to detect.</p>
 */
class MedVectorIndexProbeTest {

    private final JedisPooled jedis = mock(JedisPooled.class);

    private final MedVectorStoreProperties storeProperties = new MedVectorStoreProperties();

    private MedVectorIndexProbe probe() {
        return new MedVectorIndexProbe(jedis, storeProperties);
    }

    /**
     * Builds an {@code FT.INFO} reply in the shape Jedis 5.x really returns: nested structures as flat
     * alternating key/value lists.
     *
     * @param numDocs    value of the {@code num_docs} entry
     * @param tagFields  TAG attributes to declare, each with its JSON path
     * @return the reply, never {@code null}
     */
    private static Map<String, Object> flatReply(long numDocs, List<String> tagFields) {
        List<Object> attributes = new ArrayList<>();
        attributes.add(List.of("identifier", "$.content", "attribute", "content", "type", "TEXT"));
        attributes.add(List.of("identifier", "$.embedding", "attribute", "embedding", "type", "VECTOR"));
        for (String field : tagFields) {
            attributes.add(List.of("identifier", "$." + field, "attribute", field, "type", "TAG"));
        }

        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("index_name", "med-doc-index");
        reply.put("index_definition",
                List.of("key_type", "JSON", "prefixes", List.of("med:doc:"), "default_score", "1"));
        reply.put("attributes", attributes);
        reply.put("num_docs", numDocs);
        return reply;
    }

    // ---------------------------------------------------------------- happy path

    @Test
    @DisplayName("an existing index is reported with its document count, prefix and TAG fields")
    void existingIndexIsReported() {
        when(jedis.ftList()).thenReturn(Set.of("med-doc-index"));
        when(jedis.ftInfo("med-doc-index"))
                .thenReturn(flatReply(42L, List.of("tenant_id", "dept_id", "patient_id")));

        MedVectorIndexReport report = probe().probe();

        assertThat(report.indexName()).isEqualTo("med-doc-index");
        assertThat(report.exists()).isTrue();
        assertThat(report.documentCount()).isEqualTo(42L);
        assertThat(report.prefixes()).containsExactly("med:doc:");
        assertThat(report.tagFields()).containsExactlyInAnyOrder("tenant_id", "dept_id", "patient_id");
        assertThat(report.isScopedRetrievalReady(List.of("tenant_id", "dept_id", "patient_id"))).isTrue();
    }

    @Test
    @DisplayName("the probe reads the index name from the vector-store configuration, not from a literal")
    void probedIndexNameComesFromConfiguration() {
        storeProperties.setIndexName("med-doc-index-canary");
        when(jedis.ftList()).thenReturn(Set.of("med-doc-index-canary"));
        when(jedis.ftInfo("med-doc-index-canary")).thenReturn(flatReply(1L, List.of("tenant_id")));

        MedVectorIndexProbe probe = probe();

        assertThat(probe.indexName()).isEqualTo("med-doc-index-canary");
        assertThat(probe.probe().indexName()).isEqualTo("med-doc-index-canary");
        verify(jedis).ftInfo("med-doc-index-canary");
    }

    @Test
    @DisplayName("an index whose TAG alias is absent falls back to the JSON path without its $. prefix")
    void attributeWithoutAliasFallsBackToTheJsonPath() {
        Map<String, Object> reply = flatReply(3L, List.of());
        reply.put("attributes", List.of(
                List.of("identifier", "$.tenant_id", "type", "TAG"),
                List.of("identifier", "$.dept_id", "type", "TAG")));

        MedVectorIndexReport report = MedVectorIndexProbe.toReport("med-doc-index", reply);

        assertThat(report.tagFields()).containsExactlyInAnyOrder("tenant_id", "dept_id");
    }

    @Test
    @DisplayName("a reply that materialises nested structures as maps is decoded the same way")
    void mapShapedReplyIsDecodedToo() {
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("num_docs", 9L);
        reply.put("index_definition", Map.of("key_type", "JSON", "prefixes", List.of("med:doc:")));
        reply.put("attributes", List.of(
                Map.of("identifier", "$.content", "attribute", "content", "type", "TEXT"),
                Map.of("identifier", "$.patient_id", "attribute", "patient_id", "type", "TAG")));

        MedVectorIndexReport report = MedVectorIndexProbe.toReport("med-doc-index", reply);

        assertThat(report.documentCount()).isEqualTo(9L);
        assertThat(report.prefixes()).containsExactly("med:doc:");
        assertThat(report.tagFields()).containsExactly("patient_id");
    }

    // ---------------------------------------------------------------- the failure modes that matter

    @Test
    @DisplayName("an index that FT.LIST does not report is reported missing, without asking FT.INFO")
    void absentIndexIsReportedMissing() {
        when(jedis.ftList()).thenReturn(Set.of("some-other-index"));

        MedVectorIndexReport report = probe().probe();

        assertThat(report.exists()).isFalse();
        assertThat(report.tagFields()).isEmpty();
        assertThat(report.isScopedRetrievalReady(List.of("tenant_id"))).isFalse();
        // A missing index is a verdict, not a reason to ask a follow-up question.
        verify(jedis, never()).ftInfo(anyString());
    }

    @Test
    @DisplayName("boundary: an empty index list is reported missing rather than throwing")
    void emptyIndexListIsReportedMissing() {
        when(jedis.ftList()).thenReturn(Set.of());

        assertThat(probe().probe().exists()).isFalse();
    }

    @Test
    @DisplayName("boundary: a null index list from the client is reported missing rather than throwing")
    void nullIndexListIsReportedMissing() {
        when(jedis.ftList()).thenReturn(null);

        assertThat(probe().probe().exists()).isFalse();
    }

    @Test
    @DisplayName("an unreachable Redis propagates, so the caller can tell it apart from a missing index")
    void unreachableRedisPropagates() {
        when(jedis.ftList()).thenThrow(new JedisConnectionException("connection refused"));

        assertThatThrownBy(() -> probe().probe())
                .isInstanceOf(JedisConnectionException.class)
                .hasMessageContaining("connection refused");
    }

    @Test
    @DisplayName("a rejected command propagates, it is not silently read as a missing index")
    void rejectedCommandPropagates() {
        when(jedis.ftList()).thenThrow(new JedisDataException("ERR unknown command 'FT.LIST'"));

        assertThatThrownBy(() -> probe().probe())
                .isInstanceOf(JedisDataException.class)
                .hasMessageContaining("FT.LIST");
    }

    // ---------------------------------------------------------------- decoding boundaries

    @Test
    @DisplayName("boundary: a reply without num_docs or attributes reads as zero documents and no tags")
    void replyWithoutMetadataReadsAsEmpty() {
        MedVectorIndexReport report = MedVectorIndexProbe.toReport("med-doc-index", Map.of());

        assertThat(report.exists()).isTrue();
        assertThat(report.documentCount()).isZero();
        assertThat(report.tagFields()).isEmpty();
        assertThat(report.prefixes()).isEmpty();
    }

    @Test
    @DisplayName("boundary: a numeric document count sent as a string is still parsed")
    void stringDocumentCountIsParsed() {
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("num_docs", "17");

        assertThat(MedVectorIndexProbe.toReport("med-doc-index", reply).documentCount()).isEqualTo(17L);
    }

    @Test
    @DisplayName("boundary: an unparsable or negative document count reads as zero instead of throwing")
    void unparsableDocumentCountReadsAsZero() {
        Map<String, Object> notANumber = new LinkedHashMap<>();
        notANumber.put("num_docs", "many");
        Map<String, Object> negative = new LinkedHashMap<>();
        negative.put("num_docs", -5L);

        assertThat(MedVectorIndexProbe.toReport("med-doc-index", notANumber).documentCount()).isZero();
        assertThat(MedVectorIndexProbe.toReport("med-doc-index", negative).documentCount()).isZero();
    }

    @Test
    @DisplayName("boundary: non-TAG attributes never count as tag fields")
    void nonTagAttributesAreNotTagFields() {
        Map<String, Object> reply = flatReply(5L, List.of("tenant_id"));

        MedVectorIndexReport report = MedVectorIndexProbe.toReport("med-doc-index", reply);

        assertThat(report.tagFields()).containsExactly("tenant_id");
        assertThat(report.hasTagField("content")).isFalse();
        assertThat(report.hasTagField("embedding")).isFalse();
    }

    @Test
    @DisplayName("boundary: a single prefix sent as a bare string is still read")
    void singleStringPrefixIsRead() {
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("index_definition", List.of("key_type", "JSON", "prefixes", "med:doc:"));

        assertThat(MedVectorIndexProbe.toReport("med-doc-index", reply).prefixes())
                .containsExactly("med:doc:");
    }

    @Test
    @DisplayName("boundary: a truncated structure keeps its complete pairs and drops the dangling key")
    void truncatedStructureKeepsCompletePairs() {
        // Redis always sends well-formed replies, so this only guards against a client that changes
        // its mind mid-structure. Dropping the dangling key is the tolerant reading; throwing here
        // would turn a cosmetic client change into a health probe that can never report anything.
        assertThat(MedVectorIndexProbe.toFieldMap(List.of("key_type", "JSON", "dangling")))
                .containsExactlyEntriesOf(Map.of("key_type", "JSON"));
        assertThat(MedVectorIndexProbe.toFieldMap("not a structure")).isEmpty();
        assertThat(MedVectorIndexProbe.toFieldMap(null)).isEmpty();
        assertThat(MedVectorIndexProbe.toFieldMap(List.of())).isEmpty();
    }

    @Test
    @DisplayName("boundary: a blank index name or a null reply is rejected by the decoder")
    void invalidDecoderArgumentsAreRejected() {
        assertThatThrownBy(() -> MedVectorIndexProbe.toReport(" ", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MedVectorIndexProbe.toReport("med-doc-index", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("boundary: a null client or null store properties is rejected at construction")
    void nullConstructorArgumentsAreRejected() {
        assertThatThrownBy(() -> new MedVectorIndexProbe(null, storeProperties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jedis");
        assertThatThrownBy(() -> new MedVectorIndexProbe(jedis, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("storeProperties");
    }
}
