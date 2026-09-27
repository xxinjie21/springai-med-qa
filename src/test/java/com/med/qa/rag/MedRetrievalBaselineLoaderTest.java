package com.med.qa.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MedRetrievalBaselineLoader}.
 *
 * <p>The loader is deliberately stricter than Jackson's defaults, and these tests pin exactly why:
 * an unknown key, a wrong type or a blank identifier must fail loudly, because the alternative is a
 * baseline that parses into something weaker than what it was written to assert — a green build that
 * proves nothing. Every error message is asserted to carry the JSON path, so a broken golden set can
 * be fixed without guessing which case is at fault.</p>
 */
class MedRetrievalBaselineLoaderTest {

    private static final String VALID = """
            {
              "name": "rag-retrieval-baseline",
              "version": "1",
              "cases": [
                {
                  "name": "own-record",
                  "tenantId": "tenant-a",
                  "deptId": "dept-cardio",
                  "patientId": "patient-1",
                  "query": "高血压 首选 药物",
                  "topK": 10,
                  "includeSharedDocuments": true,
                  "expectedDocumentIds": ["doc-guide", "doc-record"],
                  "expectedTopDocumentId": "doc-guide",
                  "forbiddenDocumentIds": ["doc-other-patient"],
                  "minRecall": 1.0
                },
                {
                  "name": "guidelines-only",
                  "tenantId": "tenant-a",
                  "deptId": "dept-cardio",
                  "query": "糖尿病 指南",
                  "expectedDocumentIds": ["doc-diabetes-guideline"]
                },
                {
                  "name": "unknown-scope",
                  "tenantId": "tenant-a",
                  "deptId": "dept-oncology",
                  "patientId": "patient-1",
                  "query": "高血压 首选 药物",
                  "forbiddenDocumentIds": ["doc-guide"],
                  "minRecall": 0.0
                }
              ]
            }
            """;

    private static final MedRetrievalBaselineLoader LOADER = new MedRetrievalBaselineLoader();

    @Test
    @DisplayName("a full baseline is parsed, including the scope, the expectations and the defaults")
    void parsesAFullBaseline() {
        MedRetrievalBaseline baseline = LOADER.parse(VALID);

        assertThat(baseline.name()).isEqualTo("rag-retrieval-baseline");
        assertThat(baseline.version()).isEqualTo("1");
        assertThat(baseline.caseNames()).containsExactly("own-record", "guidelines-only", "unknown-scope");

        MedRetrievalBaselineCase ownRecord = baseline.findCase("own-record").orElseThrow();
        assertThat(ownRecord.scope()).isEqualTo(MedDocumentScope.ofPatient("tenant-a", "dept-cardio",
                "patient-1"));
        assertThat(ownRecord.query()).isEqualTo("高血压 首选 药物");
        assertThat(ownRecord.topK()).isEqualTo(10);
        assertThat(ownRecord.includeSharedDocuments()).isTrue();
        assertThat(ownRecord.expectedDocumentIds()).containsExactly("doc-guide", "doc-record");
        assertThat(ownRecord.expectedTopDocumentId()).isEqualTo("doc-guide");
        assertThat(ownRecord.forbiddenDocumentIds()).containsExactly("doc-other-patient");
        assertThat(ownRecord.minRecall()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("an omitted patientId makes the case department-wide, and the other knobs take their defaults")
    void omittedFieldsTakeTheirDefaults() {
        MedRetrievalBaselineCase departmentWide = LOADER.parse(VALID).findCase("guidelines-only")
                .orElseThrow();

        assertThat(departmentWide.scope().isPatientScoped()).isFalse();
        assertThat(departmentWide.scope().getPatientTag()).isEqualTo(MedDocumentScope.SHARED_PATIENT_TAG);
        assertThat(departmentWide.topK()).isEqualTo(MedRetrievalBaselineLoader.DEFAULT_TOP_K);
        assertThat(departmentWide.includeSharedDocuments())
                .isEqualTo(MedRetrievalBaselineLoader.DEFAULT_INCLUDE_SHARED_DOCUMENTS);
        assertThat(departmentWide.minRecall())
                .isEqualTo(MedRetrievalBaselineLoader.DEFAULT_MIN_RECALL);
        assertThat(departmentWide.expectedDocumentIds()).containsExactly("doc-diabetes-guideline");
        assertThat(departmentWide.expectedTopDocumentId()).isNull();
        assertThat(departmentWide.forbiddenDocumentIds()).isEmpty();
    }

    @Test
    @DisplayName("a negative case is parsed as such: nothing expected, zero recall required")
    void negativeCaseIsParsed() {
        MedRetrievalBaselineCase negative = LOADER.parse(VALID).findCase("unknown-scope").orElseThrow();

        assertThat(negative.hasExpectations()).isFalse();
        assertThat(negative.expectedDocumentIds()).isEmpty();
        assertThat(negative.minRecall()).isZero();
        assertThat(negative.forbiddenDocumentIds()).containsExactly("doc-guide");
    }

    @Test
    @DisplayName("an explicit JSON null patientId is the same thing as omitting it")
    void explicitNullPatientIdIsDepartmentWide() {
        MedRetrievalBaseline baseline = LOADER.parse("""
                {"name": "b", "version": "1", "cases": [
                  {"name": "c", "tenantId": "t", "deptId": "d", "patientId": null, "query": "问诊",
                   "expectedDocumentIds": ["doc"], "forbiddenDocumentIds": ["other"], "minRecall": 0.5}
                ]}
                """);

        assertThat(baseline.findCase("c").orElseThrow().scope().isPatientScoped()).isFalse();
    }

    @Test
    @DisplayName("an unknown field is refused, at the root and inside a case")
    void unknownFieldsAreRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LOADER.parse("""
                        {"name": "b", "version": "1", "comment": "typo", "cases": [
                          {"name": "c", "tenantId": "t", "deptId": "d", "query": "问诊",
                           "expectedDocumentIds": ["doc"], "forbiddenDocumentIds": ["other"]}
                        ]}
                        """))
                .withMessageContaining("$ declares unknown field 'comment'")
                .withMessageContaining("allowed fields are [cases, name, version]");

        // The typo that matters most: a dropped plural would silently remove the recall assertion.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LOADER.parse("""
                        {"name": "b", "version": "1", "cases": [
                          {"name": "c", "tenantId": "t", "deptId": "d", "query": "问诊",
                           "expectedDocumentId": ["doc"]}
                        ]}
                        """))
                .withMessageContaining("$.cases[0] declares unknown field 'expectedDocumentId'");
    }

    @Test
    @DisplayName("boundary: malformed JSON, a non-object root or a blank document is refused")
    void malformedInputIsRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LOADER.parse(" "))
                .withMessageContaining("must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LOADER.parse("{\"name\": \"b\","))
                .withMessageContaining("not valid JSON");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LOADER.parse("[]"))
                .withMessageContaining("must be a JSON object");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LOADER.parse("null"))
                .withMessageContaining("must be a JSON object");
    }

    @Test
    @DisplayName("boundary: the case array has to exist and hold at least one case")
    void theCaseArrayIsRequired() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LOADER.parse("{\"name\": \"b\", \"version\": \"1\"}"))
                .withMessageContaining("$.cases must be a non-empty JSON array");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LOADER.parse("{\"name\": \"b\", \"version\": \"1\", \"cases\": []}"))
                .withMessageContaining("$.cases must be a non-empty JSON array");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LOADER.parse("{\"name\": \"b\", \"version\": \"1\", \"cases\": {}}"))
                .withMessageContaining("$.cases must be a non-empty JSON array");
    }

    @Test
    @DisplayName("boundary: a missing identity or a missing case field is refused with its path")
    void requiredFieldsAreEnforced() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LOADER.parse("{\"version\": \"1\", \"cases\": []}"))
                .withMessageContaining("$.name is required");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LOADER.parse("{\"name\": \"b\", \"cases\": []}"))
                .withMessageContaining("$.version is required");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LOADER.parse("""
                        {"name": "b", "version": "1", "cases": [{"name": "c", "deptId": "d", "query": "问诊"}]}
                        """))
                .withMessageContaining("$.cases[0].tenantId is required");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LOADER.parse("""
                        {"name": "b", "version": "1", "cases": [{"name": "c", "tenantId": "t"}]}
                        """))
                .withMessageContaining("$.cases[0].deptId is required");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LOADER.parse("""
                        {"name": "b", "version": "1", "cases": [{"name": "c", "tenantId": "t", "deptId": "d"}]}
                        """))
                .withMessageContaining("$.cases[0].query is required");
    }

    @Test
    @DisplayName("boundary: a field of the wrong JSON type is refused")
    void wrongTypesAreRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LOADER.parse(caseJson("\"query\": \"问诊\", \"topK\": \"ten\"")))
                .withMessageContaining("$.cases[0].topK must be an integer");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LOADER.parse(caseJson("\"query\": \"问诊\", \"topK\": 1.5")))
                .withMessageContaining("$.cases[0].topK must be an integer");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LOADER.parse(
                        caseJson("\"query\": \"问诊\", \"includeSharedDocuments\": \"yes\"")))
                .withMessageContaining("$.cases[0].includeSharedDocuments must be a boolean");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LOADER.parse(caseJson("\"query\": \"问诊\", \"minRecall\": \"all\"")))
                .withMessageContaining("$.cases[0].minRecall must be a number");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LOADER.parse(
                        caseJson("\"query\": \"问诊\", \"expectedDocumentIds\": \"doc\"")))
                .withMessageContaining("$.cases[0].expectedDocumentIds must be a JSON array");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LOADER.parse(
                        caseJson("\"query\": \"问诊\", \"expectedDocumentIds\": [\"doc\", 7]")))
                .withMessageContaining("$.cases[0].expectedDocumentIds[1] must be a non-blank string");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LOADER.parse(caseJson("\"query\": \"\"")))
                .withMessageContaining("$.cases[0].query must not be blank");
    }

    @Test
    @DisplayName("an invalid case reports the JSON path of the case, not just the rule")
    void invalidCaseReportsItsPath() {
        MedRetrievalBaselineLoader loader = new MedRetrievalBaselineLoader();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> loader.parse("""
                        {"name": "b", "version": "1", "cases": [
                          {"name": "c1", "tenantId": "t", "deptId": "d", "query": "问诊",
                           "expectedDocumentIds": ["doc"], "forbiddenDocumentIds": ["other"]},
                          {"name": "c2", "tenantId": "t", "deptId": "d", "query": "问诊",
                           "expectedDocumentIds": ["doc"], "forbiddenDocumentIds": ["other"],
                           "minRecall": 1.5}
                        ]}
                        """))
                .withMessageContaining("$.cases[1] is invalid")
                .withMessageContaining("minRecall must be within [0, 1]");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> loader.parse("""
                        {"name": "b", "version": "1", "cases": [
                          {"name": "c1", "tenantId": "t", "deptId": "d", "query": "问诊",
                           "expectedDocumentIds": ["doc"], "forbiddenDocumentIds": ["other"]},
                          {"name": "c1", "tenantId": "t", "deptId": "d", "query": "问诊",
                           "expectedDocumentIds": ["doc"], "forbiddenDocumentIds": ["other"]}
                        ]}
                        """))
                .withMessageContaining("$ is invalid")
                .withMessageContaining("duplicate case name: c1");
    }

    @Test
    @DisplayName("a baseline can be read from a resource or from a stream")
    void resourceAndStreamAreSupported() throws IOException {
        Resource resource = new ByteArrayResource(VALID.getBytes(StandardCharsets.UTF_8));

        assertThat(LOADER.load(resource).caseNames())
                .containsExactly("own-record", "guidelines-only", "unknown-scope");
        assertThat(LOADER.load(new ByteArrayInputStream(VALID.getBytes(StandardCharsets.UTF_8)))
                .caseCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("boundary: a null resource, stream or mapper is refused")
    void nullInputsAreRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LOADER.load((Resource) null))
                .withMessageContaining("resource must not be null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LOADER.load((java.io.InputStream) null))
                .withMessageContaining("inputStream must not be null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MedRetrievalBaselineLoader(null))
                .withMessageContaining("objectMapper must not be null");
        assertThatThrownBy(() -> LOADER.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Wraps a case fragment into a complete baseline document.
     *
     * @param fragment JSON fragment holding the mandatory case fields and the field under test, must
     *                 not be {@code null}
     * @return a complete baseline JSON document, never {@code null}
     */
    private static String caseJson(String fragment) {
        return "{\"name\": \"b\", \"version\": \"1\", \"cases\": ["
                + "{\"name\": \"c\", \"tenantId\": \"t\", \"deptId\": \"d\", " + fragment + "}]}";
    }

    @Test
    @DisplayName("the shipped golden set parses through the same loader")
    void shippedGoldenSetParses() throws IOException {
        List<String> names = LOADER.load(new ClassPathResource("rag/retrieval-baseline.json"))
                .caseNames();

        assertThat(names).isNotEmpty().doesNotContainNull();
    }
}
