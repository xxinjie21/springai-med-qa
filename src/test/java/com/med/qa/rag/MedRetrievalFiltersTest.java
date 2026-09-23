package com.med.qa.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.redis.RedisFilterExpressionConverter;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;

import redis.clients.jedis.search.Schema;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests of the tag filter factory.
 *
 * <p>Two levels are asserted. The structural tests pin the {@link Filter.Expression} tree handed to
 * the vector store; the conversion tests run the official
 * {@link RedisFilterExpressionConverter} over that tree and check the RediSearch query it produces,
 * which is what actually decides whether a document can be reached. Neither needs a Redis
 * instance.</p>
 */
class MedRetrievalFiltersTest {

    private static final String TENANT = "hosp1";

    private static final String DEPT = "cardio";

    private static final String PATIENT = "p9001";

    private static final List<RedisVectorStore.MetadataField> TAG_FIELDS = List.of(
            new RedisVectorStore.MetadataField(MedDocumentScope.METADATA_TENANT_ID, Schema.FieldType.TAG),
            new RedisVectorStore.MetadataField(MedDocumentScope.METADATA_DEPT_ID, Schema.FieldType.TAG),
            new RedisVectorStore.MetadataField(MedDocumentScope.METADATA_PATIENT_ID, Schema.FieldType.TAG));

    private static String toRedisQuery(Filter.Expression expression) {
        return new RedisFilterExpressionConverter(TAG_FIELDS).convertExpression(expression);
    }

    private static Filter.Expression rightOf(Filter.Expression expression) {
        return (Filter.Expression) expression.right();
    }

    private static Filter.Expression leftOf(Filter.Expression expression) {
        return (Filter.Expression) expression.left();
    }

    private static String keyOf(Filter.Expression expression) {
        return ((Filter.Key) expression.left()).key();
    }

    private static Object valueOf(Filter.Expression expression) {
        return ((Filter.Value) expression.right()).value();
    }

    @Test
    @DisplayName("the utility class cannot be instantiated by reflection misuse")
    void utilityClassIsNotInstantiable() throws Exception {
        var constructor = MedRetrievalFilters.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(constructor::newInstance)
                .withCauseInstanceOf(AssertionError.class);
    }

    @Nested
    @DisplayName("patientTags")
    class PatientTags {

        @Test
        @DisplayName("a patient scope may reach its own documents and the shared ones")
        void patientScopeIncludesShared() {
            List<Object> tags = MedRetrievalFilters.patientTags(
                    MedDocumentScope.ofPatient(TENANT, DEPT, PATIENT), true);

            assertThat(tags).containsExactly(PATIENT, MedDocumentScope.SHARED_PATIENT_TAG);
        }

        @Test
        @DisplayName("a patient scope can be narrowed to the patient's own documents")
        void patientScopeCanExcludeShared() {
            List<Object> tags = MedRetrievalFilters.patientTags(
                    MedDocumentScope.ofPatient(TENANT, DEPT, PATIENT), false);

            assertThat(tags).containsExactly(PATIENT);
        }

        @Test
        @DisplayName("a department scope reaches shared documents only, never a patient record")
        void departmentScopeIsSharedOnly() {
            List<Object> tags = MedRetrievalFilters.patientTags(
                    MedDocumentScope.ofDepartment(TENANT, DEPT), true);

            assertThat(tags).containsExactly(MedDocumentScope.SHARED_PATIENT_TAG);
        }

        @Test
        @DisplayName("a department scope excluding shared documents is rejected as a caller mistake")
        void departmentScopeWithoutSharedIsRejected() {
            MedDocumentScope scope = MedDocumentScope.ofDepartment(TENANT, DEPT);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MedRetrievalFilters.patientTags(scope, false))
                    .withMessageContaining("would match nothing");
        }

        @Test
        @DisplayName("a null scope is a programming error")
        void nullScopeIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MedRetrievalFilters.patientTags(null, true))
                    .withMessageContaining("scope must not be null");
        }
    }

    @Nested
    @DisplayName("scope")
    class Scope {

        @Test
        @DisplayName("a patient scope becomes tenant AND dept AND patient IN [patient, shared]")
        void patientScopeExpressionShape() {
            Filter.Expression expression =
                    MedRetrievalFilters.scope(MedDocumentScope.ofPatient(TENANT, DEPT, PATIENT));

            assertThat(expression.type()).isEqualTo(Filter.ExpressionType.AND);

            Filter.Expression identity = leftOf(expression);
            assertThat(identity.type()).isEqualTo(Filter.ExpressionType.AND);
            Filter.Expression tenant = leftOf(identity);
            Filter.Expression dept = rightOf(identity);
            assertThat(tenant.type()).isEqualTo(Filter.ExpressionType.EQ);
            assertThat(keyOf(tenant)).isEqualTo(MedDocumentScope.METADATA_TENANT_ID);
            assertThat(valueOf(tenant)).isEqualTo(TENANT);
            assertThat(dept.type()).isEqualTo(Filter.ExpressionType.EQ);
            assertThat(keyOf(dept)).isEqualTo(MedDocumentScope.METADATA_DEPT_ID);
            assertThat(valueOf(dept)).isEqualTo(DEPT);

            Filter.Expression patient = rightOf(expression);
            assertThat(patient.type()).isEqualTo(Filter.ExpressionType.IN);
            assertThat(keyOf(patient)).isEqualTo(MedDocumentScope.METADATA_PATIENT_ID);
            assertThat(valueOf(patient))
                    .isEqualTo(List.of(PATIENT, MedDocumentScope.SHARED_PATIENT_TAG));
        }

        @Test
        @DisplayName("a single accepted tag degrades to an equality instead of a one-element IN")
        void singleTagUsesEquality() {
            Filter.Expression expression = MedRetrievalFilters.scope(
                    MedDocumentScope.ofPatient(TENANT, DEPT, PATIENT), false);

            Filter.Expression patient = rightOf(expression);
            assertThat(patient.type()).isEqualTo(Filter.ExpressionType.EQ);
            assertThat(valueOf(patient)).isEqualTo(PATIENT);
        }

        @Test
        @DisplayName("a department scope pins the patient tag to the shared sentinel")
        void departmentScopeUsesSharedSentinel() {
            Filter.Expression expression =
                    MedRetrievalFilters.scope(MedDocumentScope.ofDepartment(TENANT, DEPT));

            Filter.Expression patient = rightOf(expression);
            assertThat(patient.type()).isEqualTo(Filter.ExpressionType.EQ);
            assertThat(valueOf(patient)).isEqualTo(MedDocumentScope.SHARED_PATIENT_TAG);
        }

        @Test
        @DisplayName("a null scope is a programming error")
        void nullScopeIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MedRetrievalFilters.scope(null))
                    .withMessageContaining("scope must not be null");
        }

        @Test
        @DisplayName("the official converter turns a patient scope into a three-tag redisearch query")
        void patientScopeConvertsToTagQuery() {
            String query = toRedisQuery(
                    MedRetrievalFilters.scope(MedDocumentScope.ofPatient(TENANT, DEPT, PATIENT)));

            assertThat(query)
                    .contains(MedDocumentScope.METADATA_TENANT_ID)
                    .contains(TENANT)
                    .contains(MedDocumentScope.METADATA_DEPT_ID)
                    .contains(DEPT)
                    .contains(MedDocumentScope.METADATA_PATIENT_ID)
                    .contains(PATIENT)
                    .contains("shared");
        }

        @Test
        @DisplayName("a department query never mentions a patient identifier")
        void departmentScopeQueryHasNoPatientId() {
            String query = toRedisQuery(
                    MedRetrievalFilters.scope(MedDocumentScope.ofDepartment(TENANT, DEPT)));

            assertThat(query).contains("shared").doesNotContain(PATIENT);
        }

        @Test
        @DisplayName("two departments of the same tenant produce different queries")
        void differentDepartmentsAreIsolated() {
            String cardiology = toRedisQuery(
                    MedRetrievalFilters.scope(MedDocumentScope.ofDepartment(TENANT, DEPT)));
            String neurology = toRedisQuery(
                    MedRetrievalFilters.scope(MedDocumentScope.ofDepartment(TENANT, "neuro")));

            assertThat(cardiology).isNotEqualTo(neurology);
            assertThat(neurology).doesNotContain(DEPT);
        }
    }

    @Nested
    @DisplayName("and")
    class And {

        @Test
        @DisplayName("a null extra filter leaves the isolation filter untouched")
        void nullExtraReturnsIsolation() {
            Filter.Expression isolation =
                    MedRetrievalFilters.scope(MedDocumentScope.ofDepartment(TENANT, DEPT));

            assertThat(MedRetrievalFilters.and(isolation, null)).isSameAs(isolation);
        }

        @Test
        @DisplayName("an extra filter is conjoined and can only narrow the result set")
        void extraFilterIsConjoined() {
            Filter.Expression isolation =
                    MedRetrievalFilters.scope(MedDocumentScope.ofDepartment(TENANT, DEPT));
            Filter.Expression extra = new FilterExpressionBuilder().eq("doc_type", "guideline").build();

            Filter.Expression combined = MedRetrievalFilters.and(isolation, extra);

            assertThat(combined.type()).isEqualTo(Filter.ExpressionType.AND);
            assertThat(combined.left()).isEqualTo(isolation);
            assertThat(combined.right()).isEqualTo(extra);
        }

        @Test
        @DisplayName("the isolation filter is mandatory")
        void nullIsolationIsRejected() {
            Filter.Expression extra = new FilterExpressionBuilder().eq("doc_type", "guideline").build();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MedRetrievalFilters.and(null, extra))
                    .withMessageContaining("isolation filter must not be null");
        }
    }

    @Nested
    @DisplayName("matches")
    class Matches {

        private Map<String, Object> metadata(String tenant, String dept, String patient) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(MedDocumentScope.METADATA_TENANT_ID, tenant);
            metadata.put(MedDocumentScope.METADATA_DEPT_ID, dept);
            metadata.put(MedDocumentScope.METADATA_PATIENT_ID, patient);
            return metadata;
        }

        @Test
        @DisplayName("the patient's own document is in scope")
        void ownDocumentMatches() {
            boolean matches = MedRetrievalFilters.matches(metadata(TENANT, DEPT, PATIENT),
                    MedDocumentScope.ofPatient(TENANT, DEPT, PATIENT), true);

            assertThat(matches).isTrue();
        }

        @Test
        @DisplayName("a department guideline is in scope of a patient query")
        void sharedDocumentMatches() {
            boolean matches = MedRetrievalFilters.matches(
                    metadata(TENANT, DEPT, MedDocumentScope.SHARED_PATIENT_TAG),
                    MedDocumentScope.ofPatient(TENANT, DEPT, PATIENT), true);

            assertThat(matches).isTrue();
        }

        @Test
        @DisplayName("another patient's record is out of scope")
        void foreignPatientDoesNotMatch() {
            boolean matches = MedRetrievalFilters.matches(metadata(TENANT, DEPT, "p9002"),
                    MedDocumentScope.ofPatient(TENANT, DEPT, PATIENT), true);

            assertThat(matches).isFalse();
        }

        @Test
        @DisplayName("another department is out of scope")
        void foreignDepartmentDoesNotMatch() {
            boolean matches = MedRetrievalFilters.matches(metadata(TENANT, "neuro", PATIENT),
                    MedDocumentScope.ofPatient(TENANT, DEPT, PATIENT), true);

            assertThat(matches).isFalse();
        }

        @Test
        @DisplayName("another tenant is out of scope")
        void foreignTenantDoesNotMatch() {
            boolean matches = MedRetrievalFilters.matches(metadata("hosp2", DEPT, PATIENT),
                    MedDocumentScope.ofPatient(TENANT, DEPT, PATIENT), true);

            assertThat(matches).isFalse();
        }

        @Test
        @DisplayName("a shared document is out of scope when shared documents were excluded")
        void sharedDocumentIsRejectedWhenExcluded() {
            boolean matches = MedRetrievalFilters.matches(
                    metadata(TENANT, DEPT, MedDocumentScope.SHARED_PATIENT_TAG),
                    MedDocumentScope.ofPatient(TENANT, DEPT, PATIENT), false);

            assertThat(matches).isFalse();
        }

        @Test
        @DisplayName("an untagged document is out of scope: verification fails closed")
        void untaggedDocumentDoesNotMatch() {
            assertThat(MedRetrievalFilters.matches(Map.of(),
                    MedDocumentScope.ofDepartment(TENANT, DEPT), true)).isFalse();
            assertThat(MedRetrievalFilters.matches(null,
                    MedDocumentScope.ofDepartment(TENANT, DEPT), true)).isFalse();
        }

        @Test
        @DisplayName("a null scope is a programming error")
        void nullScopeIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MedRetrievalFilters.matches(Map.of(), null, true))
                    .withMessageContaining("scope must not be null");
        }
    }

    @Nested
    @DisplayName("escapeTagValue")
    class TagEscaping {

        @Test
        @DisplayName("an identifier made of ordinary characters is returned unchanged")
        void plainIdentifiersAreUntouched() {
            assertThat(MedRetrievalFilters.escapeTagValue(TENANT)).isEqualTo(TENANT);
            assertThat(MedRetrievalFilters.escapeTagValue(DEPT)).isEqualTo(DEPT);
            assertThat(MedRetrievalFilters.escapeTagValue(PATIENT)).isEqualTo(PATIENT);
            assertThat(MedRetrievalFilters.escapeTagValue(MedDocumentScope.SHARED_PATIENT_TAG))
                    .isEqualTo(MedDocumentScope.SHARED_PATIENT_TAG);
        }

        @Test
        @DisplayName("every character RediSearch reserves in a TAG query gets a backslash")
        void everyReservedCharacterIsEscaped() {
            MedRetrievalFilters.REDISEARCH_TAG_SPECIAL_CHARACTERS.chars().forEach(character -> {
                String value = String.valueOf((char) character);

                assertThat(MedRetrievalFilters.escapeTagValue(value))
                        .as("'%s' must be escaped", value)
                        .isEqualTo("\\" + value);
            });
        }

        @Test
        @DisplayName("a UUID-shaped identifier keeps its hyphens, escaped")
        void uuidShapedIdentifierIsEscaped() {
            String uuid = "3f2b8c1e-1a2b-4c5d-8e9f-0123456789ab";

            assertThat(MedRetrievalFilters.escapeTagValue(uuid))
                    .isEqualTo("3f2b8c1e\\-1a2b\\-4c5d\\-8e9f\\-0123456789ab");
        }

        @Test
        @DisplayName("an already escaped value is escaped once more, never interpreted")
        void backslashItselfIsEscaped() {
            assertThat(MedRetrievalFilters.escapeTagValue("a\\b")).isEqualTo("a\\\\b");
        }

        @Test
        @DisplayName("a null value is a programming error")
        void nullValueIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MedRetrievalFilters.escapeTagValue(null))
                    .withMessageContaining("tag value must not be null");
        }

        @Test
        @DisplayName("escapeAllTagValues escapes every entry in order")
        void allValuesAreEscapedInOrder() {
            assertThat(MedRetrievalFilters.escapeAllTagValues(List.of("p-1", MedDocumentScope.SHARED_PATIENT_TAG)))
                    .containsExactly("p\\-1", MedDocumentScope.SHARED_PATIENT_TAG);
        }

        @Test
        @DisplayName("escapeAllTagValues rejects a null list or a null entry")
        void malformedValueListIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MedRetrievalFilters.escapeAllTagValues(null))
                    .withMessageContaining("tag values must not be null");

            List<Object> withNull = Arrays.asList("p-1", null);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MedRetrievalFilters.escapeAllTagValues(withNull))
                    .withMessageContaining("tag value must not be null");
        }
    }

    @Nested
    @DisplayName("the converted RediSearch query escapes the scope tags")
    class EscapedRedisQuery {

        private static final String TENANT_WITH_HYPHEN = "tenant-rag-a";
        private static final String DEPT_WITH_HYPHEN = "dept-cardio";
        private static final String PATIENT_WITH_HYPHEN = "patient-rag-1";

        @Test
        @DisplayName("a hyphenated scope produces escaped TAG values, not the raw identifiers")
        void hyphenatedScopeConvertsToAnEscapedQuery() {
            String query = toRedisQuery(MedRetrievalFilters.scope(MedDocumentScope.ofPatient(
                    TENANT_WITH_HYPHEN, DEPT_WITH_HYPHEN, PATIENT_WITH_HYPHEN)));

            // Regression: RediSearch rejects `@tenant_id:{tenant-rag-a}` with "Syntax error", and the
            // unescaped form inside an IN predicate silently drops the patient's own record. Both were
            // observed against a real Redis Stack; see MedRagRetrievalIntegrationTest.
            assertThat(query)
                    .contains("tenant\\-rag\\-a")
                    .contains("dept\\-cardio")
                    .contains("patient\\-rag\\-1")
                    .doesNotContain(TENANT_WITH_HYPHEN)
                    .doesNotContain(DEPT_WITH_HYPHEN)
                    .doesNotContain(PATIENT_WITH_HYPHEN);
        }

        @Test
        @DisplayName("the IN predicate of a hyphenated patient scope escapes every operand")
        void hyphenatedInPredicateIsEscaped() {
            String query = toRedisQuery(MedRetrievalFilters.scope(
                    MedDocumentScope.ofPatient(TENANT_WITH_HYPHEN, DEPT_WITH_HYPHEN, PATIENT_WITH_HYPHEN)));

            assertThat(query)
                    .contains("patient\\-rag\\-1")
                    .contains(MedDocumentScope.SHARED_PATIENT_TAG);
        }

        @Test
        @DisplayName("an ordinary identifier still produces the plain query")
        void plainScopeQueryIsUnchanged() {
            String query = toRedisQuery(
                    MedRetrievalFilters.scope(MedDocumentScope.ofPatient(TENANT, DEPT, PATIENT)));

            assertThat(query).contains(TENANT).contains(DEPT).contains(PATIENT);
        }
    }
}
