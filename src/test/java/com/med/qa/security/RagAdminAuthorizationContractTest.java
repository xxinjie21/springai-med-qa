package com.med.qa.security;

import com.med.qa.controller.RagAdminController;
import com.med.qa.rag.MedDocumentRequest;
import com.med.qa.rag.MedDocumentScope;
import com.med.qa.rag.MedDocumentService;
import com.med.qa.rag.MedRetrievalQuery;
import com.med.qa.rag.MedRetrievalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Cross-component contract tests of the RAG administration authorization chain (D42).
 *
 * <h2>Why this class exists</h2>
 * <p>The 2026-09-25 review found that 1389 green tests had missed three P0 defects and diagnosed the
 * cause: every test exercised <em>one component</em>, so nothing asserted that the components were
 * actually wired to each other. P0-3 is the RAG variant of that failure — {@link RequestIdentityGuard}
 * was correct and thoroughly unit tested, and {@link MedDocumentScope} was correct and thoroughly unit
 * tested, yet {@link RagAdminController} built its scope straight from the request body and never called
 * the guard, so any staff key could index into, search and physically delete another department's
 * vectors. No single-component test could notice.</p>
 *
 * <p>This class therefore drives the <strong>real</strong> production chain over HTTP and asserts the
 * contract between its links rather than any single link's behaviour:</p>
 * <ol>
 *   <li>{@link ApiKeyAuthFilter} resolves the {@code X-API-Key} header into a {@link MedPrincipal};</li>
 *   <li>{@link DeptScopeInterceptor} enforces the staff role declared by the controller;</li>
 *   <li>the controller resolves the effective scope through the real {@link RequestIdentityGuard};</li>
 *   <li>only then do {@link MedDocumentService} / {@link MedRetrievalService} run — and the assertions
 *       observe <em>which</em> coordinates reached them.</li>
 * </ol>
 *
 * <p>Only the two RAG services are mocked: they are the Redis-backed links, irrelevant to the contract
 * under test, and mocking them is what makes the coordinates observable. The whole class stays offline —
 * no Redis, no MySQL, no embedding endpoint.</p>
 *
 * <h2>What a refusal looks like on the wire</h2>
 * <p>Two different shapes, and both are deliberate. A refusal raised by the interceptor (wrong role,
 * or a department id carried in the request envelope) is written as a real HTTP {@code 403}. A refusal
 * raised <em>inside</em> the handler — an identity claim that contradicts the API key — is a
 * {@link com.med.qa.common.exception.BizException} and therefore follows the project-wide convention
 * that {@code GlobalExceptionHandler} answers with HTTP {@code 200} plus the business code
 * {@code 40300} in the {@code ApiResult} envelope. The assertions below spell that out explicitly
 * because the authoritative signal for a caller is the business code, not the status line.</p>
 *
 * <p>{@code med.audit.enabled=false} keeps the audited endpoints from reaching MySQL, and
 * {@code med.alert.enabled=false} keeps the scheduled index monitor from instantiating the lazily wired
 * Jedis client on a long-lived cached context.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "med.rate-limit.enabled=false",
        "med.audit.enabled=false",
        "med.alert.enabled=false",
        "med.security.enabled=true",
        "med.security.require-auth=true",
        "med.security.keys.staffkey.tenant-id=hosp-1",
        "med.security.keys.staffkey.dept-id=dept-cardio",
        "med.security.keys.staffkey.role=STAFF",
        "med.security.keys.patientkey.tenant-id=hosp-1",
        "med.security.keys.patientkey.dept-id=dept-cardio",
        "med.security.keys.patientkey.role=PATIENT",
        "med.security.keys.patientkey.patient-id=pat-77"
})
class RagAdminAuthorizationContractTest {

    private static final String STAFF_KEY = "staffkey";

    private static final String PATIENT_KEY = "patientkey";

    private static final String INGEST = "/api/rag/documents/ingest";

    private static final String DELETE = "/api/rag/documents/delete";

    private static final String SEARCH = "/api/rag/documents/search";

    /** Business code of {@link com.med.qa.common.exception.ErrorCode#FORBIDDEN}. */
    private static final String FORBIDDEN_CODE = "\"code\":40300";

    /** Business code of {@link com.med.qa.common.exception.ErrorCode#BAD_REQUEST}. */
    private static final String BAD_REQUEST_CODE = "\"code\":40000";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MedDocumentService documentService;

    @MockBean
    private MedRetrievalService retrievalService;

    private MvcResult post(String path, String apiKey, String body) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (apiKey != null) {
            request = request.header("X-API-Key", apiKey);
        }
        return mockMvc.perform(request).andReturn();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    @Nested
    @DisplayName("authentication and role are enforced before the handler runs")
    class PreDispatchGates {

        @Test
        @DisplayName("a missing key is rejected by the authentication filter with 401")
        void missingKeyIsUnauthorized() throws Exception {
            MvcResult result = post(INGEST, null, "{\"documents\":[]}");

            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            verifyNoInteractions(documentService);
        }

        @Test
        @DisplayName("an unknown key is rejected by the authentication filter with 401")
        void unknownKeyIsUnauthorized() throws Exception {
            MvcResult result = post(INGEST, "not-a-key", "{\"documents\":[]}");

            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            verifyNoInteractions(documentService);
        }

        @Test
        @DisplayName("a patient key is refused with a real 403 by the department-scope interceptor")
        void patientKeyIsForbidden() throws Exception {
            MvcResult result = post(INGEST, PATIENT_KEY, "{\"documents\":[]}");

            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(body(result)).contains(FORBIDDEN_CODE);
            verifyNoInteractions(documentService);
        }
    }

    @Nested
    @DisplayName("ingestion is bound to the API key's scope")
    class Ingestion {

        @Test
        @DisplayName("a body without identity claims is indexed in the principal's department scope")
        void claimsAreOptional() throws Exception {
            when(documentService.ingestAll(anyList())).thenReturn(List.of("doc-1"));

            MvcResult result = post(INGEST, STAFF_KEY,
                    "{\"documents\":[{\"text\":\"triage protocol\"}]}");

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(result)).contains("\"ingested\":1");
            ArgumentCaptor<List<MedDocumentRequest>> captor = ArgumentCaptor.forClass(List.class);
            verify(documentService).ingestAll(captor.capture());
            assertThat(captor.getValue()).extracting(MedDocumentRequest::getScope)
                    .containsExactly(MedDocumentScope.ofDepartment("hosp-1", "dept-cardio"));
        }

        @Test
        @DisplayName("a staff key may still index a document for one of its own patients")
        void staffMayNameItsOwnPatient() throws Exception {
            when(documentService.ingestAll(anyList())).thenReturn(List.of("doc-1"));

            post(INGEST, STAFF_KEY,
                    "{\"documents\":[{\"text\":\"discharge summary\",\"patientId\":\"pat-77\"}]}");

            ArgumentCaptor<List<MedDocumentRequest>> captor = ArgumentCaptor.forClass(List.class);
            verify(documentService).ingestAll(captor.capture());
            assertThat(captor.getValue()).extracting(MedDocumentRequest::getScope)
                    .containsExactly(MedDocumentScope.ofPatient("hosp-1", "dept-cardio", "pat-77"));
        }

        @Test
        @DisplayName("an item claiming another tenant is refused and nothing is written")
        void foreignTenantIsRefused() throws Exception {
            MvcResult result = post(INGEST, STAFF_KEY,
                    "{\"documents\":[{\"text\":\"triage protocol\",\"tenantId\":\"hosp-2\"}]}");

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(result)).contains(FORBIDDEN_CODE);
            verifyNoInteractions(documentService);
        }

        @Test
        @DisplayName("an item claiming another department is refused and nothing is written")
        void foreignDepartmentIsRefused() throws Exception {
            MvcResult result = post(INGEST, STAFF_KEY,
                    "{\"documents\":[{\"text\":\"triage protocol\",\"deptId\":\"dept-onco\"}]}");

            assertThat(body(result)).contains(FORBIDDEN_CODE);
            verifyNoInteractions(documentService);
        }

        @Test
        @DisplayName("a foreign claim on the second item stops the whole batch")
        void foreignClaimOnALaterItemStopsTheBatch() throws Exception {
            MvcResult result = post(INGEST, STAFF_KEY, "{\"documents\":["
                    + "{\"text\":\"fine\"},"
                    + "{\"text\":\"not fine\",\"deptId\":\"dept-onco\"}]}");

            assertThat(body(result)).contains(FORBIDDEN_CODE);
            verifyNoInteractions(documentService);
        }
    }

    @Nested
    @DisplayName("search preview is bounded by the API key's scope")
    class SearchPreview {

        @Test
        @DisplayName("a body without identity claims searches the principal's department")
        void scopeComesFromThePrincipal() throws Exception {
            when(retrievalService.search(any(MedRetrievalQuery.class))).thenReturn(List.of());

            MvcResult result = post(SEARCH, STAFF_KEY, "{\"text\":\"aspirin dose\"}");

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            ArgumentCaptor<MedRetrievalQuery> captor = ArgumentCaptor.forClass(MedRetrievalQuery.class);
            verify(retrievalService).search(captor.capture());
            assertThat(captor.getValue().getScope())
                    .isEqualTo(MedDocumentScope.ofDepartment("hosp-1", "dept-cardio"));
        }

        @Test
        @DisplayName("a query claiming another department is refused and never searches")
        void foreignDepartmentIsRefused() throws Exception {
            MvcResult result = post(SEARCH, STAFF_KEY,
                    "{\"text\":\"aspirin dose\",\"deptId\":\"dept-onco\"}");

            assertThat(body(result)).contains(FORBIDDEN_CODE);
            verifyNoInteractions(retrievalService);
        }
    }

    @Nested
    @DisplayName("deletion is scope-only and bound to the API key")
    class Deletion {

        @Test
        @DisplayName("a patient-scoped delete reaches the service with the principal's coordinates")
        void patientScopeIsDeleted() throws Exception {
            MvcResult result = post(DELETE, STAFF_KEY, "{\"patientId\":\"pat-77\"}");

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(result)).contains("\"patientScoped\":true");
            verify(documentService).deleteByScope(
                    MedDocumentScope.ofPatient("hosp-1", "dept-cardio", "pat-77"));
        }

        @Test
        @DisplayName("an unconfirmed department-wide delete is refused and deletes nothing")
        void departmentWideNeedsConfirmation() throws Exception {
            MvcResult result = post(DELETE, STAFF_KEY, "{}");

            assertThat(body(result)).contains(BAD_REQUEST_CODE);
            verifyNoInteractions(documentService);
        }

        @Test
        @DisplayName("a confirmed department-wide delete reaches the service with the principal's scope")
        void confirmedDepartmentWideIsDeleted() throws Exception {
            MvcResult result = post(DELETE, STAFF_KEY, "{\"confirmDepartmentWide\":true}");

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(result)).contains("\"patientScoped\":false");
            verify(documentService).deleteByScope(
                    MedDocumentScope.ofDepartment("hosp-1", "dept-cardio"));
        }

        @Test
        @DisplayName("a delete claiming another department is refused even when confirmed")
        void foreignDepartmentIsRefused() throws Exception {
            MvcResult result = post(DELETE, STAFF_KEY,
                    "{\"deptId\":\"dept-onco\",\"confirmDepartmentWide\":true}");

            assertThat(body(result)).contains(FORBIDDEN_CODE);
            verifyNoInteractions(documentService);
        }

        @Test
        @DisplayName("a delete claiming another tenant is refused")
        void foreignTenantIsRefused() throws Exception {
            MvcResult result = post(DELETE, STAFF_KEY,
                    "{\"tenantId\":\"hosp-2\",\"confirmDepartmentWide\":true}");

            assertThat(body(result)).contains(FORBIDDEN_CODE);
            verifyNoInteractions(documentService);
        }

        @Test
        @DisplayName("there is no id-based delete left to abuse")
        void noIdBasedDeleteExists() throws Exception {
            // The pre-D42 payload shape. The field is unknown now, so Jackson ignores it and the request
            // degrades to an unconfirmed department-wide delete — refused, not silently widened.
            MvcResult result = post(DELETE, STAFF_KEY, "{\"ids\":[\"doc-of-another-department\"]}");

            assertThat(body(result)).contains(BAD_REQUEST_CODE);
            verifyNoInteractions(documentService);
        }
    }
}
