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
import com.med.qa.security.MedRole;
import com.med.qa.security.annotation.RequireDept;
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
 *   <li>{@code POST /api/rag/documents/delete} — remove documents by id or by isolation scope.</li>
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
 * <h2>Authorization</h2>
 * <p>The whole corpus surface is staff-only: {@link RequireDept} with {@code roles = STAFF} refuses a
 * patient principal (and any anonymous call) with {@code 403} before the handler runs, so a patient can
 * never ingest, delete or preview department documents. The isolation scope itself travels inside the JSON
 * body, which an interceptor must not consume, hence {@code required = false}: the department/patient
 * narrowing keeps being enforced by the metadata tags of the RAG services below. Audit is layered on by a
 * later iteration.</p>
 */
@RestController
@RequestMapping("/api/rag")
@RequireDept(roles = MedRole.STAFF, required = false)
public class RagAdminController {

    private final MedDocumentService documentService;

    private final MedRetrievalService retrievalService;

    /**
     * Creates the controller.
     *
     * @param documentService ingestion / deletion service, must not be {@code null}
     * @param retrievalService tag-scoped similarity search service, must not be {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public RagAdminController(MedDocumentService documentService, MedRetrievalService retrievalService) {
        this.documentService = Objects.requireNonNull(documentService, "documentService must not be null");
        this.retrievalService = Objects.requireNonNull(retrievalService, "retrievalService must not be null");
    }

    /**
     * Indexes a batch of medical documents into the RAG vector store.
     *
     * @param request documents to ingest, must not be {@code null} and must carry at least one item
     * @return the number of indexed documents and their store identifiers
     * @throws BizException {@link ErrorCode#BAD_REQUEST} on a missing/empty batch or an invalid item
     */
    @PostMapping("/documents/ingest")
    public ApiResult<RagIngestResponse> ingest(@RequestBody @Nullable RagIngestRequest request) {
        if (request == null || request.documents() == null || request.documents().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "ingest request must contain at least one document");
        }
        List<MedDocumentRequest> requests = new ArrayList<>(request.documents().size());
        for (RagIngestItem item : request.documents()) {
            requests.add(toMedDocumentRequest(item));
        }
        List<String> ids = documentService.ingestAll(requests);
        return ApiResult.ok(new RagIngestResponse(ids.size(), ids));
    }

    /**
     * Removes documents from the RAG vector store by identifier or by isolation scope.
     *
     * <p>When both identifiers and a scope are supplied, the identifiers take precedence.</p>
     *
     * @param request deletion target, must not be {@code null} and must specify ids or a
     *               {@code tenantId} / {@code deptId} scope
     * @return a receipt describing what was deleted
     * @throws BizException {@link ErrorCode#BAD_REQUEST} when neither ids nor a scope are present
     */
    @PostMapping("/documents/delete")
    public ApiResult<RagDeleteResponse> delete(@RequestBody @Nullable RagDeleteRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "delete request must not be null");
        }
        boolean hasIds = request.ids() != null && !request.ids().isEmpty();
        boolean hasScope = StringUtils.hasText(request.tenantId()) && StringUtils.hasText(request.deptId());
        if (!hasIds && !hasScope) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "delete request must specify ids or a tenant/dept scope");
        }
        if (hasIds) {
            documentService.deleteByIds(request.ids());
            return ApiResult.ok(new RagDeleteResponse(true, request.ids(), null));
        }
        MedDocumentScope scope = toScope(request.tenantId(), request.deptId(), request.patientId());
        documentService.deleteByScope(scope);
        return ApiResult.ok(new RagDeleteResponse(false, null, scope.toString()));
    }

    /**
     * Runs a tag-scoped similarity search and returns a preview of the matched documents.
     *
     * <p>The query text is embedded verbatim; {@code topK} and {@code similarityThreshold} default to
     * the configured guard rails when omitted.</p>
     *
     * @param request search to run, must not be {@code null} and must carry non-blank text plus a
     *               {@code tenantId} / {@code deptId} scope
     * @return the matched documents, best first
     * @throws BizException {@link ErrorCode#BAD_REQUEST} on a missing text or scope
     */
    @PostMapping("/documents/search")
    public ApiResult<RagSearchPreviewResponse> searchPreview(
            @RequestBody @Nullable RagSearchPreviewRequest request) {
        if (request == null || !StringUtils.hasText(request.text())) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "search request must contain non-blank text");
        }
        if (!StringUtils.hasText(request.tenantId()) || !StringUtils.hasText(request.deptId())) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "search request must specify tenantId and deptId");
        }
        MedDocumentScope scope = toScope(request.tenantId(), request.deptId(), request.patientId());
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
     * Translates one inbound ingest item into the internal request, validating the caller input.
     *
     * @param item item submitted through the API, must not be {@code null}
     * @return the validated internal request, never {@code null}
     * @throws BizException {@link ErrorCode#BAD_REQUEST} on a blank text, a missing
     *                      {@code tenantId}/{@code deptId}, or an invalid scope tag
     */
    private MedDocumentRequest toMedDocumentRequest(RagIngestItem item) {
        if (item == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "document item must not be null");
        }
        if (!StringUtils.hasText(item.text())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "document text must not be blank");
        }
        if (!StringUtils.hasText(item.tenantId()) || !StringUtils.hasText(item.deptId())) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "document must specify tenantId and deptId");
        }
        MedDocumentScope scope = toScope(item.tenantId(), item.deptId(), item.patientId());
        try {
            return new MedDocumentRequest(item.id(), item.text(), scope, item.metadata());
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    /**
     * Builds an isolation scope from raw caller tags, mapping a tag error onto a bad request.
     *
     * @param tenantId hospital / tenant identifier
     * @param deptId   department identifier
     * @param patientId patient identifier, or {@code null} for a department-wide scope
     * @return the validated scope, never {@code null}
     * @throws BizException {@link ErrorCode#BAD_REQUEST} when a tag violates the scope contract
     */
    private MedDocumentScope toScope(String tenantId, String deptId, @Nullable String patientId) {
        try {
            if (StringUtils.hasText(patientId)) {
                return MedDocumentScope.ofPatient(tenantId, deptId, patientId);
            }
            return MedDocumentScope.ofDepartment(tenantId, deptId);
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
