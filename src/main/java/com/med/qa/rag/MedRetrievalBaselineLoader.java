package com.med.qa.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Reads a {@link MedRetrievalBaseline} from its JSON representation.
 *
 * <h2>Why the golden set is a file</h2>
 * <p>A retrieval-quality baseline is a living contract: cases get added as defects are found, and a
 * recall requirement gets tightened when a filter proves unreliable. Keeping it in JSON makes every
 * such change a reviewable data diff instead of a code change, and it lets the same file be read by
 * the CI suite and by an operator who wants to re-run the check against a real deployment.</p>
 *
 * <h2>Every mistake is a hard failure</h2>
 * <p>A silently mis-parsed baseline is worse than a missing one: a typo in a key name, an identifier
 * written twice or a case that expects nothing and forbids nothing would all produce a set that
 * passes no matter what retrieval does — a green build that asserts nothing. The loader therefore
 * rejects unknown fields instead of ignoring them (Jackson's default), rejects duplicate identifiers
 * and empty assertions, and wraps the record's own validation so the error message names the exact
 * JSON path, for instance {@code $.cases[3].minRecall}.</p>
 *
 * <h2>Documented shape</h2>
 * <pre>{@code
 * {
 *   "name": "rag-retrieval-baseline",
 *   "version": "1",
 *   "cases": [
 *     {
 *       "name": "patient-scope-includes-own-record",
 *       "tenantId": "tenant-a", "deptId": "dept-cardio", "patientId": "patient-1",
 *       "query": "高血压 首选 药物",
 *       "topK": 10, "includeSharedDocuments": true,
 *       "expectedDocumentIds": ["doc-a", "doc-b"],
 *       "expectedTopDocumentId": "doc-a",
 *       "forbiddenDocumentIds": ["doc-other-tenant"],
 *       "minRecall": 1.0
 *     }
 *   ]
 * }
 * }</pre>
 * <p>{@code patientId} omitted (or {@code null}) makes the case department-wide. {@code topK} defaults
 * to {@value #DEFAULT_TOP_K}, {@code includeSharedDocuments} to {@code true} and {@code minRecall} to
 * {@value #DEFAULT_MIN_RECALL}; a case with an empty {@code expectedDocumentIds} must state
 * {@code "minRecall": 0.0} explicitly, because the default would make it self-contradictory.</p>
 *
 * <p>Instances are immutable and safe to share.</p>
 */
public class MedRetrievalBaselineLoader {

    /** Top-K applied when a case does not declare one. */
    public static final int DEFAULT_TOP_K = 10;

    /** Whether department-wide documents take part when a case does not say. */
    public static final boolean DEFAULT_INCLUDE_SHARED_DOCUMENTS = true;

    /** Recall required when a case does not declare one. */
    public static final double DEFAULT_MIN_RECALL = 1.0d;

    private static final Set<String> ROOT_FIELDS = Set.of("name", "version", "cases");

    private static final Set<String> CASE_FIELDS = Set.of("name", "tenantId", "deptId", "patientId",
            "query", "topK", "includeSharedDocuments", "expectedDocumentIds", "expectedTopDocumentId",
            "forbiddenDocumentIds", "minRecall");

    private final ObjectMapper objectMapper;

    /**
     * Creates a loader using its own {@link ObjectMapper}.
     *
     * <p>No shared mapper is used on purpose: the baseline file is read from a classpath resource or
     * from a mounted file and is not part of the service's HTTP contract, so it must not pick up
     * serialization customisations made for the API layer.</p>
     */
    public MedRetrievalBaselineLoader() {
        this(new ObjectMapper());
    }

    /**
     * Creates a loader using an explicit mapper.
     *
     * @param objectMapper mapper used to read the tree, must not be {@code null}
     * @throws IllegalArgumentException if {@code objectMapper} is {@code null}
     */
    public MedRetrievalBaselineLoader(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper must not be null");
        }
        this.objectMapper = objectMapper;
    }

    /**
     * Reads a baseline from a Spring resource.
     *
     * @param resource resource holding the JSON document, must not be {@code null}
     * @return the parsed and validated baseline, never {@code null}
     * @throws IOException              if the resource cannot be read
     * @throws IllegalArgumentException if {@code resource} is {@code null} or its content is not a
     *                                  valid baseline
     */
    public MedRetrievalBaseline load(Resource resource) throws IOException {
        if (resource == null) {
            throw new IllegalArgumentException("resource must not be null");
        }
        try (InputStream inputStream = resource.getInputStream()) {
            return parse(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * Reads a baseline from a stream, which the caller owns and closes.
     *
     * @param inputStream stream holding the JSON document, must not be {@code null}
     * @return the parsed and validated baseline, never {@code null}
     * @throws IOException              if the stream cannot be read
     * @throws IllegalArgumentException if {@code inputStream} is {@code null} or its content is not a
     *                                  valid baseline
     */
    public MedRetrievalBaseline load(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            throw new IllegalArgumentException("inputStream must not be null");
        }
        return parse(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
    }

    /**
     * Parses a baseline from a JSON document.
     *
     * @param json JSON text, must not be blank
     * @return the parsed and validated baseline, never {@code null}
     * @throws IllegalArgumentException if the text is blank, is not valid JSON, is not a JSON object,
     *                                  declares an unknown field, omits a required field, or produces
     *                                  a case the record itself rejects
     */
    public MedRetrievalBaseline parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("baseline JSON must not be blank");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("baseline is not valid JSON: " + ex.getOriginalMessage(), ex);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("baseline must be a JSON object");
        }
        rejectUnknownFields(root, ROOT_FIELDS, "$");
        String name = requireText(root, "name", "$");
        String version = requireText(root, "version", "$");
        JsonNode casesNode = root.get("cases");
        if (casesNode == null || !casesNode.isArray() || casesNode.isEmpty()) {
            throw new IllegalArgumentException("$.cases must be a non-empty JSON array");
        }
        List<MedRetrievalBaselineCase> cases = new ArrayList<>(casesNode.size());
        for (int index = 0; index < casesNode.size(); index++) {
            cases.add(parseCase(casesNode.get(index), "$.cases[" + index + ']'));
        }
        try {
            return new MedRetrievalBaseline(name, version, cases);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("$ is invalid: " + ex.getMessage(), ex);
        }
    }

    /**
     * Parses one case node.
     *
     * @param node case object, must not be {@code null}
     * @param path JSON path of the node, used in error messages
     * @return the parsed case, never {@code null}
     * @throws IllegalArgumentException if the node is not an object, carries an unknown field, omits a
     *                                  required field or fails the record's validation
     */
    private MedRetrievalBaselineCase parseCase(JsonNode node, String path) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(path + " must be a JSON object");
        }
        rejectUnknownFields(node, CASE_FIELDS, path);
        String name = requireText(node, "name", path);
        String tenantId = requireText(node, "tenantId", path);
        String deptId = requireText(node, "deptId", path);
        String query = requireText(node, "query", path);
        String patientId = optionalText(node, "patientId", path);
        MedDocumentScope scope = patientId == null
                ? MedDocumentScope.ofDepartment(tenantId, deptId)
                : MedDocumentScope.ofPatient(tenantId, deptId, patientId);
        int topK = optionalInt(node, "topK", path, DEFAULT_TOP_K);
        boolean includeShared = optionalBoolean(node, "includeSharedDocuments", path,
                DEFAULT_INCLUDE_SHARED_DOCUMENTS);
        List<String> expected = optionalTextArray(node, "expectedDocumentIds", path);
        String expectedTop = optionalText(node, "expectedTopDocumentId", path);
        List<String> forbidden = optionalTextArray(node, "forbiddenDocumentIds", path);
        double minRecall = optionalDouble(node, "minRecall", path, DEFAULT_MIN_RECALL);
        try {
            return new MedRetrievalBaselineCase(name, scope, query, topK, includeShared, expected,
                    expectedTop, forbidden, minRecall);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(path + " is invalid: " + ex.getMessage(), ex);
        }
    }

    /**
     * Rejects any field the shape does not define.
     *
     * <p>Jackson ignores unknown properties by default, which is exactly the wrong behaviour for a
     * golden set: {@code expectedDocumentId} instead of {@code expectedDocumentIds} would be dropped
     * and the case would silently stop asserting what it was written to assert.</p>
     *
     * @param node        object to inspect, must not be {@code null}
     * @param allowed     permitted field names
     * @param path        JSON path of the node, used in error messages
     * @throws IllegalArgumentException if the node carries a field outside {@code allowed}
     */
    private static void rejectUnknownFields(JsonNode node, Set<String> allowed, String path) {
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String field = names.next();
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException(path + " declares unknown field '" + field
                        + "'; allowed fields are " + new java.util.TreeSet<>(allowed));
            }
        }
    }

    /**
     * Reads a required non-blank text field.
     *
     * @param node  object holding the field, must not be {@code null}
     * @param field field name
     * @param path  JSON path used in error messages
     * @return the value, never blank
     * @throws IllegalArgumentException if the field is missing, {@code null}, not a string or blank
     */
    private static String requireText(JsonNode node, String field, String path) {
        String value = optionalText(node, field, path);
        if (value == null) {
            throw new IllegalArgumentException(path + '.' + field + " is required");
        }
        return value;
    }

    /**
     * Reads an optional non-blank text field.
     *
     * @param node  object holding the field, must not be {@code null}
     * @param field field name
     * @param path  JSON path used in error messages
     * @return the value, or {@code null} when the field is absent or JSON {@code null}
     * @throws IllegalArgumentException if the field is present but not a non-blank string
     */
    private static String optionalText(JsonNode node, String field, String path) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(path + '.' + field + " must be a string");
        }
        String text = value.textValue();
        if (text.isBlank()) {
            throw new IllegalArgumentException(path + '.' + field + " must not be blank");
        }
        return text;
    }

    /**
     * Reads an optional integer field.
     *
     * @param node         object holding the field, must not be {@code null}
     * @param field        field name
     * @param path         JSON path used in error messages
     * @param defaultValue value returned when the field is absent
     * @return the value, never {@code null}
     * @throws IllegalArgumentException if the field is present but not an integral number
     */
    private static int optionalInt(JsonNode node, String field, String path, int defaultValue) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException(path + '.' + field + " must be an integer");
        }
        return value.intValue();
    }

    /**
     * Reads an optional boolean field.
     *
     * @param node         object holding the field, must not be {@code null}
     * @param field        field name
     * @param path         JSON path used in error messages
     * @param defaultValue value returned when the field is absent
     * @return the value
     * @throws IllegalArgumentException if the field is present but not a boolean
     */
    private static boolean optionalBoolean(JsonNode node, String field, String path, boolean defaultValue) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isBoolean()) {
            throw new IllegalArgumentException(path + '.' + field + " must be a boolean");
        }
        return value.booleanValue();
    }

    /**
     * Reads an optional decimal field.
     *
     * @param node         object holding the field, must not be {@code null}
     * @param field        field name
     * @param path         JSON path used in error messages
     * @param defaultValue value returned when the field is absent
     * @return the value
     * @throws IllegalArgumentException if the field is present but not a number
     */
    private static double optionalDouble(JsonNode node, String field, String path, double defaultValue) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isNumber()) {
            throw new IllegalArgumentException(path + '.' + field + " must be a number");
        }
        return value.doubleValue();
    }

    /**
     * Reads an optional array of non-blank strings.
     *
     * @param node  object holding the field, must not be {@code null}
     * @param field field name
     * @param path  JSON path used in error messages
     * @return the values in declaration order, empty when the field is absent, never {@code null}
     * @throws IllegalArgumentException if the field is present but not an array of non-blank strings
     */
    private static List<String> optionalTextArray(JsonNode node, String field, String path) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException(path + '.' + field + " must be a JSON array");
        }
        List<String> values = new ArrayList<>(value.size());
        for (int index = 0; index < value.size(); index++) {
            JsonNode element = value.get(index);
            if (!element.isTextual() || element.textValue().isBlank()) {
                throw new IllegalArgumentException(path + '.' + field + '[' + index
                        + "] must be a non-blank string");
            }
            values.add(element.textValue());
        }
        return values;
    }
}
