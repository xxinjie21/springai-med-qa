package com.med.qa.controller;

import com.med.qa.audit.annotation.MedAudit;
import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.common.result.ApiResult;
import com.med.qa.common.result.PageResult;
import com.med.qa.controller.dto.AuditLogResponse;
import com.med.qa.domain.entity.AuditLogDO;
import com.med.qa.domain.enums.AuditOutcome;
import com.med.qa.security.MedRole;
import com.med.qa.security.annotation.DeptIdSource;
import com.med.qa.security.annotation.RequireDept;
import com.med.qa.service.AuditLogPageQuery;
import com.med.qa.service.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST surface for reviewing the medical operation audit trail (D56).
 *
 * <h2>Why this endpoint exists</h2>
 * <p>The trail has been writable since D23 and trimmable since D55, but the two read statements of
 * {@code AuditLogMapper} had no production caller until this controller: a hospital could not answer
 * "who touched this session last week" without querying the database by hand. This surface is that
 * missing call site, and it is the only one — the service carries a mandatory tenant/department pair
 * into every statement, so no review can escape the caller's own department.</p>
 *
 * <h2>Scope handling</h2>
 * <p>The listing is staff-only: {@link RequireDept} with {@code roles = STAFF} rejects a patient key
 * outright, and the interceptor additionally verifies that the requested {@code deptId} equals the
 * caller's own department (D22), so the trail of another department is a {@code 403} before the
 * handler body runs. Reading the trail is itself an audited operation
 * ({@link MedAudit} {@code AUDIT_LOG_LIST}), because an audit review that leaves no trace is not
 * evidence.</p>
 *
 * <h2>Validation</h2>
 * <p>A blank scope or a malformed page (non-positive page/size, a size above the configured maximum,
 * an inverted time window) is rejected with {@link ErrorCode#BAD_REQUEST}, and a MySQL failure with
 * {@link ErrorCode#STORAGE_ERROR} — the same two shapes every other read endpoint of this service
 * uses.</p>
 */
@RestController
@RequestMapping("/api/audit/logs")
@Tag(name = "Audit Review", description = "Staff-only review of the medical operation audit trail, "
        + "scoped to the caller's own department. Reading the trail is itself audited.")
public class AuditLogController {

    private final AuditLogService auditLogService;

    /**
     * Creates the controller.
     *
     * @param auditLogService audit review service, must not be {@code null}
     * @throws NullPointerException if {@code auditLogService} is {@code null}
     */
    @Autowired
    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * Lists one page of audit entries of a department, newest first.
     *
     * <p>The listing may be narrowed to one operator, one action code, one outcome and/or a
     * half-inclusive creation-time window ({@code [createdFrom, createdTo)}, epoch milliseconds). A
     * page past the end returns an empty page carrying the real total rather than an error.</p>
     *
     * @param tenantId    hospital/tenant id, must not be blank
     * @param deptId      department id, must not be blank; must equal the caller's own department
     * @param operatorId  optional operator filter, or {@code null} for every operator
     * @param action      optional action-code filter, or {@code null} for every action
     * @param outcome     optional outcome filter, or {@code null} for successes and failures alike
     * @param createdFrom optional inclusive lower bound on {@code created_at}, epoch millis
     * @param createdTo   optional exclusive upper bound on {@code created_at}, epoch millis
     * @param page        one-based page number, defaults to {@code 1}
     * @param size        requested page size, or {@code null} to apply the configured default
     * @return the requested page of audit entries
     * @throws BizException {@link ErrorCode#BAD_REQUEST} on a blank scope, a malformed page, an
     *                      inverted time window or a size above the configured maximum;
     *                      {@link ErrorCode#STORAGE_ERROR} on a MySQL failure
     */
    @GetMapping
    @RequireDept(source = DeptIdSource.QUERY, roles = MedRole.STAFF)
    @MedAudit(action = "AUDIT_LOG_LIST", resourceType = "AUDIT_LOG")
    @Operation(summary = "Review the audit trail",
            description = "Returns one page of a department's audit entries, newest first. May be "
                    + "narrowed to one operator, one action, one outcome and/or a half-inclusive "
                    + "creation-time window; an out-of-range page yields an empty page with the "
                    + "real total. Staff-only, and reading the trail is itself audited.")
    public ApiResult<PageResult<AuditLogResponse>> list(
            @Parameter(description = "hospital / tenant id") @RequestParam String tenantId,
            @Parameter(description = "department id") @RequestParam String deptId,
            @Parameter(description = "optional operator filter")
            @RequestParam(required = false) @Nullable String operatorId,
            @Parameter(description = "optional action-code filter")
            @RequestParam(required = false) @Nullable String action,
            @Parameter(description = "optional outcome filter")
            @RequestParam(required = false) @Nullable AuditOutcome outcome,
            @Parameter(description = "optional inclusive lower bound on created_at, epoch millis")
            @RequestParam(required = false) @Nullable Long createdFrom,
            @Parameter(description = "optional exclusive upper bound on created_at, epoch millis")
            @RequestParam(required = false) @Nullable Long createdTo,
            @Parameter(description = "one-based page number") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "requested page size, or null for the configured default")
            @RequestParam(required = false) @Nullable Integer size) {
        requireScope(tenantId, deptId);
        AuditLogPageQuery query;
        try {
            query = AuditLogPageQuery.builder(tenantId, deptId)
                    .operatorId(operatorId)
                    .action(action)
                    .outcome(outcome)
                    .createdFrom(createdFrom)
                    .createdTo(createdTo)
                    .page(page)
                    .size(size)
                    .build();
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, ex.getMessage(), ex);
        }
        PageResult<AuditLogDO> result = auditLogService.review(query);
        List<AuditLogResponse> records = result.records().stream()
                .map(AuditLogResponse::from)
                .toList();
        PageResult<AuditLogResponse> response = PageResult.of(
                result.page(), result.size(), result.total(), records);
        return ApiResult.ok(response);
    }

    private static void requireScope(String tenantId, String deptId) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(deptId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "tenantId and deptId must not be blank");
        }
    }
}
