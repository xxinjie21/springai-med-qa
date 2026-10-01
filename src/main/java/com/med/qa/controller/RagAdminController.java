package com.med.qa.controller;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.common.result.ApiResult;
import com.med.qa.controller.dto.RagDeleteRequest;
import com.med.qa.controller.dto.RagDeleteResponse;
import com.med.qa.controller.dto.RagIngestItem;
import com.med.qa.controller.dto.RagIngestRequest;
import com.med.qa.controller.dto.RagIngestResponse;
import com.med.qa.controller.dto.RagSearchPreviewItem;
import com.med.qa.controller.dto.RagSearchPreviewRequest;
import com.med.qa.controller.dto.RagSearchPreviewResponse;
import com.med.qa.rag.MedDocumentRequest;
import com.med.qa.rag.MedDocumentScope;
import com.med.qa.rag.MedDocumentService;
import com.med.qa.rag.MedRetrievalQuery;
import com.med.qa.rag.MedRetrievalService;
import com.med.qa.security.MedCallerScope;
import com.med.qa.security.MedPrincipal;
import com.med.qa.security.MedRole;
import com.med.qa.security.MedSecurityContext;
import com.med.qa.security.RequestIdentityGuard;
import com.med.qa.audit.annotation.MedAudit;
import com.med.qa.common.ratelimit.annotation.RateLimit;
import com.med.qa.security.annotation.RequireDept;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.document.Document;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Administrative REST surface for the medical RAG corpus.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code POST /api/rag/documents/ingest} — index one or more documents.</li>
 *   <li>{@code POST /api/rag/documents/delete} — remove the documents of one isolation scope.</li>
 *   <li>{@code POST /api/rag/documents/search} — run a tag-scoped similarity search and preview the
 *       matched documents.</li>
 * </ul>
 *
 * <h2>Design boundaries</h2>
 * <p>Every request is validated at the boundary and translated into the existing, tested RAG
 * services ({@link MedDocumentService}, {@link MedRetrievalService}); this controller contains no
 * embedding, vector math, Top-K or filtering logic of its own. Documents are narrowed exclusively by
 * the {@code tenant_id} / {@code dept_id} / {@code patient_id} tags of their scope — the query text
 * is never parsed. A malformed or under-specified request is rejected with
 * {@link ErrorCode#BAD_REQUEST}; embedding or index failures propagate as storage / LLM errors from
 * the services below.</p>
 *
 * <h2>Authorization: the API key decides the scope, the body only claims it (D42)</h2>
 * <p>Every endpoint on this surface is staff-only — {@link RequireDept} with {@code roles = STAFF}
 * refuses a patient principal and any anonymous caller before the handler runs — and every endpoint
 * resolves its isolation scope through the same {@link RequestIdentityGuard} the streaming
 * consultation path uses. The tenant, department and patient that reach
 * {@link MedDocumentService} and {@link MedRetrievalService} are therefore always the authenticated
 * {@link MedPrincipal}'s, never the ones a caller typed into the JSON body:</p>
 * <ul>
 *   <li>a claim that matches the principal is accepted (the client may still state its intent);</li>
 *   <li>an absent claim falls back to the principal's own value;</li>
 *   <li>a claim that contradicts the principal is refused with {@code 403} — a caller cannot index
 *       into, search or delete another tenant's or department's corpus.</li>
 * </ul>
 * <p>Before D42 all three handlers built their scope straight from the request body, so any staff key
 * could read, write and physically delete another department's vectors simply by naming it — P0-3 of
 * the 2026-09-25 review. The controller also re-checks the staff role itself rather than relying only
 * on the interceptor, because {@code med.security.dept-scope.enabled=false} removes the
 * {@link RequireDept} enforcement while leaving authentication intact; the surface fails closed
 * either way.</p>
 *
 * <p>Deletion deserves its own note: the id-based mode is gone. See
 * {@link MedDocumentService#deleteByScope} for why it could not be made scope-safe, and
 * {@link RagDeleteRequest} for why a department-wide delete must be confirmed explicitly.</p>
 */
@RestController
@RequestMapping("/api/rag")
@RequireDept(roles = MedRole.STAFF, required = false)
@Tag(name = "RAG Administration", description = "Staff-only management of the medical RAG corpus: "
        + "document ingestion, scope deletion and tag-scoped similarity-search preview. The tenant, "
        + "department and patient that drive the isolation tags come from the authenticated API key; "
        + "identity fields in the body are only cross-checked against it. The query text is never parsed.")
public class RagAdminController {

    private final MedDocumentService documentService;

    private final MedRetrievalService retrievalService;

    private final RequestIdentityGuard identityGuard;

    /**
     * Creates the controller.
     *
     * @param documentService ingestion / deletion service, must not be {@code null}
     * @param retrievalService tag-scoped similarity search service, must not be {@code null}
     * @param identityGuard   reconciles the request body's identity claims with the authenticated
     *                        principal, must not be {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public RagAdminController(MedDocumentService documentService,
                              MedRetrievalService retrievalService,
                              RequestIdentityGuard identityGuard) {
        this.documentService = Objects.requireNonNull(documentService, "documentService must not be null");
        this.retrievalService = Objects.requireNonNull(retrievalService, "retrievalService must not be null");
        this.identityGuard = Objects.requireNonNull(identityGuard, "identityGuard must not be null");
    }

    /**
     * Indexes a batch of medical documents into the RAG vector store.
     *
     * <p>Each item is indexed in the caller's own scope. An item that names another tenant or
     * department is refused with {@code 403} before anything is written, so a batch is never partially
     * indexed into a scope the caller is not entitled to.</p>
     *
     * @param request documents to ingest, must not be {@code null} and must carry at least one item
     * @return the number of indexed documents and their store identifiers
     * @throws BizException {@link ErrorCode#BAD_REQUEST} on a missing/empty batch or an invalid item,
     *                      {@link ErrorCode#FORBIDDEN} on a claim that contradicts the API key
     */
    @MedAudit(action = "RAG_DOCUMENT_INGEST", resourceType = "RAG_DOCUMENT")
    @RateLimit(rate = 10, durationSeconds = 1)
    @PostMapping("/documents/ingest")
    @Operation(summary = "Ingest documents into the RAG corpus",
            description = "Indexes one or more medical documents (verbatim) tagged with the caller's "
                    + "tenant/dept/patient scope. Identity fields in the body are optional consistency "
                    + "claims; a contradiction with the API key is refused with 403. Staff only.")
    public ApiResult<RagIngestResponse> ingest(@RequestBody @Nullable RagIngestRequest request) {
        if (request == null || request.documents() == null || request.documents().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "ingest request must contain at least one document");
        }
        MedPrincipal principal = requireStaffPrincipal();
        List<MedDocumentRequest> requests = new ArrayList<>(request.documents().size());
        for (RagIngestItem item : request.documents()) {
            requests.add(toMedDocumentRequest(item, principal));
        }
        List<String> ids = documentService.ingestAll(requests);
        return ApiResult.ok(new RagIngestResponse(ids.size(), ids));
    }

    /**
     * Removes every document of one isolation scope from the RAG vector store.
     *
     * <p>The scope is the caller's own: the body's identity fields are consistency claims only, and a
     * contradiction is refused with {@code 403}. A patient-scoped deletion removes just that patient's
     * documents; a department-wide deletion also removes the department's shared guidelines and
     * therefore has to be confirmed with {@code confirmDepartmentWide = true}.</p>
     *
     * @param request deletion target, must not be {@code null}
     * @return a receipt naming the scope that was deleted
     * @throws BizException {@link ErrorCode#BAD_REQUEST} when a department-wide deletion was not
     *                      confirmed, {@link ErrorCode#FORBIDDEN} on a claim that contradicts the API key
     */
    @MedAudit(action = "RAG_DOCUMENT_DELETE", resourceType = "RAG_DOCUMENT", target = "#request.patientId")
    @PostMapping("/documents/delete")
    @Operation(summary = "Delete the documents of an isolation scope",
            description = "Removes every document of the caller's tenant/dept scope (or of one patient "
                    + "within it). Identity fields in the body are optional consistency claims; a "
                    + "contradiction with the API key is refused with 403. A department-wide deletion "
                    + "must be confirmed with confirmDepartmentWide=true. Staff only.")
    public ApiResult<RagDeleteResponse> delete(@RequestBody @Nullable RagDeleteRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "delete request must not be null");
        }
        MedDocumentScope scope = toDocumentScope(resolveScope(requireStaffPrincipal(),
                request.tenantId(), request.deptId(), request.patientId()));
        if (!scope.isPatientScoped() && !request.confirmDepartmentWide()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "deleting the whole department scope removes its shared clinical guidelines too; "
                            + "set confirmDepartmentWide=true to accept that, or name a patientId");
        }
        documentService.deleteByScope(scope);
        return ApiResult.ok(new RagDeleteResponse(scope.toString(), scope.isPatientScoped()));
    }

    /**
     * Runs a tag-scoped similarity search and returns a preview of the matched documents.
     *
     * <p>The query text is embedded verbatim; {@code topK} and {@code similarityThreshold} default to
     * the configured guard rails when omitted. The documents the search may reach are bounded by the
     * caller's own tenant/department tags, so a preview can never show another department's corpus.</p>
     *
     * @param request search to run, must not be {@code null} and must carry non-blank text
     * @return the matched documents, best first
     * @throws BizException {@link ErrorCode#BAD_REQUEST} on a missing text or an invalid query
     *                      combination, {@link ErrorCode#FORBIDDEN} on a claim that contradicts the
     *                      API key
     */
    @MedAudit(action = "RAG_DOCUMENT_SEARCH", resourceType = "RAG_DOCUMENT")
    @PostMapping("/documents/search")
    @Operation(summary = "Preview a tag-scoped similarity search",
            description = "Runs a vector search scoped to the caller's tenant/dept (optionally narrowed "
                    + "to one patient) and returns the matched documents best first. The query text is "
                    + "embedded verbatim; topK and similarity threshold fall back to the configured "
                    + "guard rails when omitted. Identity fields in the body are optional consistency "
                    + "claims; a contradiction with the API key is refused with 403. Staff only.")
    public ApiResult<RagSearchPreviewResponse> searchPreview(
            @RequestBody @Nullable RagSearchPreviewRequest request) {
        if (request == null || !StringUtils.hasText(request.text())) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "search request must contain non-blank text");
        }
        MedDocumentScope scope = toDocumentScope(resolveScope(requireStaffPrincipal(),
                request.tenantId(), request.deptId(), request.patientId()));
        boolean includeShared = request.includeSharedDocuments() == null || request.includeSharedDocuments();
        MedRetrievalQuery.Builder queryBuilder = MedRetrievalQuery.builder(request.text(), scope)
                .includeSharedDocuments(includeShared);
        if (request.topK() != null) {
            queryBuilder.topK(request.topK());
        }
        if (request.similarityThreshold() != null) {
            queryBuilder.similarityThreshold(request.similarityThreshold());
        }
        MedRetrievalQuery query;
        try {
            query = queryBuilder.build();
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, ex.getMessage(), ex);
        }
        List<Document> documents = retrievalService.search(query);
        List<RagSearchPreviewItem> items = documents.stream()
                .map(document -> new RagSearchPreviewItem(
                        document.getId(), document.getScore(), document.getText(), document.getMetadata()))
                .toList();
        return ApiResult.ok(new RagSearchPreviewResponse(items.size(), items));
    }

    /**
     * Returns the authenticated staff principal, refusing anyone else.
     *
     * <p>{@link RequireDept} already enforces the role, but it is skipped when
     * {@code med.security.dept-scope.enabled=false} while {@link com.med.qa.security.ApiKeyAuthFilter}
     * keeps authenticating — a configuration in which a patient key would otherwise reach these
     * handlers. Re-checking here costs nothing and makes the surface fail closed on its own, which is
     * the same reasoning that moved the streaming path's authorization into the service layer.</p>
     *
     * @return the authenticated staff principal, never {@code null}
     * @throws BizException {@link ErrorCode#FORBIDDEN} when the request is anonymous or the caller is
     *                      not staff
     */
    private static MedPrincipal requireStaffPrincipal() {
        MedPrincipal principal = MedSecurityContext.getPrincipal();
        if (principal == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "authentication required");
        }
        if (!principal.isStaff()) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "the RAG administration surface is staff-only");
        }
        return principal;
    }

    /**
     * Resolves the effective identity triple from the principal and the body's claims.
     *
     * @param principal       the authenticated staff principal, must not be {@code null}
     * @param claimedTenantId tenant the body names, or {@code null} / blank for none
     * @param claimedDeptId   department the body names, or {@code null} / blank for none
     * @param claimedPatientId patient the body names, or {@code null} / blank for none
     * @return the effective scope, never {@code null}
     * @throws BizException {@link ErrorCode#FORBIDDEN} when a claim contradicts the principal
     */
    private MedCallerScope resolveScope(MedPrincipal principal,
                                        @Nullable String claimedTenantId,
                                        @Nullable String claimedDeptId,
                                        @Nullable String claimedPatientId) {
        return identityGuard.resolve(principal, claimedTenantId, claimedDeptId, claimedPatientId);
    }

    /**
     * Translates the effective identity triple into the isolation scope of the vector index.
     *
     * @param scope the effective caller scope, must not be {@code null}
     * @return the matching document scope, never {@code null}
     * @throws BizException {@link ErrorCode#BAD_REQUEST} when a tag violates the scope contract (a
     *                      comma or whitespace in an identifier, which RediSearch cannot index safely)
     */
    private static MedDocumentScope toDocumentScope(MedCallerScope scope) {
        try {
            if (scope.patientId() != null) {
                return MedDocumentScope.ofPatient(scope.tenantId(), scope.deptId(), scope.patientId());
            }
            return MedDocumentScope.ofDepartment(scope.tenantId(), scope.deptId());
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    /**
     * Translates one inbound ingest item into the internal request, validating the caller input and
     * binding the item to the caller's own scope.
     *
     * @param item      item submitted through the API, must not be {@code null}
     * @param principal the authenticated staff principal, must not be {@code null}
     * @return the validated internal request, never {@code null}
     * @throws BizException {@link ErrorCode#BAD_REQUEST} on a blank text or an invalid scope tag,
     *                      {@link ErrorCode#FORBIDDEN} on a claim that contradicts the API key
     */
    private MedDocumentRequest toMedDocumentRequest(RagIngestItem item, MedPrincipal principal) {
        if (item == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "document item must not be null");
        }
        if (!StringUtils.hasText(item.text())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "document text must not be blank");
        }
        MedDocumentScope scope = toDocumentScope(resolveScope(principal,
                item.tenantId(), item.deptId(), item.patientId()));
        try {
            return new MedDocumentRequest(item.id(), item.text(), scope, item.metadata());
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
