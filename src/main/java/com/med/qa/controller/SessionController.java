package com.med.qa.controller;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.common.result.ApiResult;
import com.med.qa.common.result.PageResult;
import com.med.qa.config.MedSessionProperties;
import com.med.qa.controller.dto.CreateSessionRequest;
import com.med.qa.controller.dto.SessionResponse;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.enums.SessionStatus;
import com.med.qa.common.ratelimit.annotation.RateLimit;
import com.med.qa.security.annotation.DeptIdSource;
import com.med.qa.security.annotation.RequireDept;
import com.med.qa.service.MedChatSessionService;
import com.med.qa.service.SessionPageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST surface for the consultation session lifecycle: creation, lookup, closing, archiving and paged
 * listing.
 *
 * <h2>Scope handling</h2>
 * <p>Every operation takes the {@code tenantId} / {@code deptId} scope from the request. The scope is
 * validated for non-blankness here and authorized declaratively by {@link RequireDept} (D22): a caller
 * asking for another department is refused with {@code 403} before the handler body runs. The
 * {@link MedChatSessionService} then enforces the row-level isolation semantics (a session requested
 * through the wrong department is reported as absent, never as "exists elsewhere") and the patient
 * ownership rule. This controller performs no session row, message or lock mutation of its own — all of
 * that lives behind the service.</p>
 *
 * <p>{@link #create(CreateSessionRequest)} carries its scope inside the JSON body, which an interceptor
 * must not consume; it is therefore annotated with {@code required = false}, so only authentication and
 * role are checked up front and the body scope is authorized by the service layer.</p>
 *
 * <h2>Validation</h2>
 * <p>Malformed or under-specified requests are rejected with {@link ErrorCode#BAD_REQUEST}. The service
 * methods throw the same error code for policy violations (a title that is too long, a page size that
 * is too large, an archived session that cannot be closed), so a single handler boundary keeps every
 * failure a clean {@code 400} rather than a {@code 500}.</p>
 */
@RestController
@RequestMapping("/api/sessions")
@EnableConfigurationProperties(MedSessionProperties.class)
@Tag(name = "Session Lifecycle", description = "Consultation session creation, lookup, close, archive "
        + "and paged listing. Every operation is scoped to a tenant/department and authorized by the "
        + "@RequireDept interceptor and the patient-ownership guard.")
public class SessionController {

    private final MedChatSessionService sessionService;

    /**
     * Creates the controller.
     *
     * @param sessionService session lifecycle service, must not be {@code null}
     * @throws NullPointerException if {@code sessionService} is {@code null}
     */
    @Autowired
    public SessionController(MedChatSessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * Opens a new consultation session.
     *
     * @param request session to create, must not be {@code null} and must carry a non-blank
     *               {@code tenantId} / {@code deptId} / {@code patientId}
     * @return the created session in its {@link SessionStatus#ACTIVE} state
     * @throws BizException {@link ErrorCode#BAD_REQUEST} on a null or malformed request
     */
    @PostMapping
    @RequireDept(required = false)
    @RateLimit(rate = 20, durationSeconds = 1)
    @Operation(summary = "Open a new consultation session",
            description = "Creates a session in the ACTIVE state within exactly one tenant, department "
                    + "and patient. The title is optional.")
    public ApiResult<SessionResponse> create(@RequestBody @Nullable CreateSessionRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "create session request must not be null");
        }
        try {
            request.validate();
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, ex.getMessage(), ex);
        }
        ChatSessionDO session = sessionService.createSession(
                request.tenantId(), request.deptId(), request.patientId(), request.title());
        return ApiResult.ok(SessionResponse.from(session));
    }

    /**
     * Loads one session of the given department.
     *
     * @param sessionId consultation session id, must not be blank
     * @param tenantId hospital/tenant id, must not be blank
     * @param deptId   department id, must not be blank
     * @return the session, or a {@code 404}-mapped {@link ErrorCode#NOT_FOUND} when absent
     * @throws BizException {@link ErrorCode#BAD_REQUEST} when the scope is blank
     */
    @GetMapping("/{sessionId}")
    @RequireDept(source = DeptIdSource.QUERY)
    @Operation(summary = "Get one session",
            description = "Loads a single session by id within its tenant/department. An unknown or "
                    + "out-of-scope session is reported as absent, never as existing elsewhere.")
    public ApiResult<SessionResponse> get(@Parameter(description = "consultation session id") @PathVariable String sessionId,
                                         @Parameter(description = "hospital / tenant id") @RequestParam String tenantId,
                                         @Parameter(description = "department id") @RequestParam String deptId) {
        requireScope(tenantId, deptId);
        ChatSessionDO session = sessionService.getSession(tenantId, deptId, sessionId);
        return ApiResult.ok(SessionResponse.from(session));
    }

    /**
     * Closes an active session so it stops accepting messages.
     *
     * @param sessionId consultation session id, must not be blank
     * @param tenantId hospital/tenant id, must not be blank
     * @param deptId   department id, must not be blank
     * @return the session in its closed state
     * @throws BizException {@link ErrorCode#BAD_REQUEST} on a blank scope or an unsupported transition,
     *                      {@link ErrorCode#NOT_FOUND} when unknown,
     *                      {@link ErrorCode#SESSION_LOCKED} when busy
     */
    @PostMapping("/{sessionId}/close")
    @RequireDept(source = DeptIdSource.QUERY)
    @Operation(summary = "Close a session",
            description = "Marks an ACTIVE session CLOSED so it stops accepting messages. Idempotent; "
                    + "an archived session cannot be closed.")
    public ApiResult<SessionResponse> close(@Parameter(description = "consultation session id") @PathVariable String sessionId,
                                           @Parameter(description = "hospital / tenant id") @RequestParam String tenantId,
                                           @Parameter(description = "department id") @RequestParam String deptId) {
        requireScope(tenantId, deptId);
        ChatSessionDO session = sessionService.closeSession(tenantId, deptId, sessionId);
        return ApiResult.ok(SessionResponse.from(session));
    }

    /**
     * Archives a session as cold data, evicting its cached message window.
     *
     * @param sessionId consultation session id, must not be blank
     * @param tenantId hospital/tenant id, must not be blank
     * @param deptId   department id, must not be blank
     * @return the session in its archived state
     * @throws BizException {@link ErrorCode#BAD_REQUEST} on a blank scope,
     *                      {@link ErrorCode#NOT_FOUND} when unknown,
     *                      {@link ErrorCode#SESSION_LOCKED} when busy
     */
    @PostMapping("/{sessionId}/archive")
    @RequireDept(source = DeptIdSource.QUERY)
    @Operation(summary = "Archive a session",
            description = "Marks a session ARCHIVED as cold data, evicting its cached message window. "
                    + "Idempotent.")
    public ApiResult<SessionResponse> archive(@Parameter(description = "consultation session id") @PathVariable String sessionId,
                                             @Parameter(description = "hospital / tenant id") @RequestParam String tenantId,
                                             @Parameter(description = "department id") @RequestParam String deptId) {
        requireScope(tenantId, deptId);
        ChatSessionDO session = sessionService.archiveSession(tenantId, deptId, sessionId);
        return ApiResult.ok(SessionResponse.from(session));
    }

    /**
     * Lists one page of sessions of a department, newest first.
     *
     * <p>The listing may be narrowed to one patient and/or one lifecycle status. An out-of-range page
     * (past the end) returns an empty page carrying the real total rather than an error.</p>
     *
     * @param tenantId  hospital/tenant id, must not be blank
     * @param deptId    department id, must not be blank
     * @param patientId optional patient filter, or {@code null} for the whole department
     * @param status    optional lifecycle-status filter, or {@code null} for every status
     * @param page      one-based page number, defaults to {@code 1}
     * @param size      requested page size, or {@code null} to apply the configured default
     * @return the requested page of sessions
     * @throws BizException {@link ErrorCode#BAD_REQUEST} on a blank scope, a non-positive page, or a
     *                      page size that is too large
     */
    @GetMapping
    @RequireDept(source = DeptIdSource.QUERY)
    @Operation(summary = "List sessions",
            description = "Returns one page of a department's sessions, newest first. May be narrowed "
                    + "to one patient and/or one lifecycle status; an out-of-range page yields an empty "
                    + "page with the real total.")
    public ApiResult<PageResult<SessionResponse>> list(
            @Parameter(description = "hospital / tenant id") @RequestParam String tenantId,
            @Parameter(description = "department id") @RequestParam String deptId,
            @Parameter(description = "optional patient filter") @RequestParam(required = false) @Nullable String patientId,
            @Parameter(description = "optional lifecycle-status filter") @RequestParam(required = false) @Nullable SessionStatus status,
            @Parameter(description = "one-based page number") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "requested page size, or null for the configured default") @RequestParam(required = false) @Nullable Integer size) {
        requireScope(tenantId, deptId);
        SessionPageQuery query;
        try {
            query = SessionPageQuery.builder(tenantId, deptId)
                    .patientId(patientId)
                    .status(status)
                    .page(page)
                    .size(size)
                    .build();
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, ex.getMessage(), ex);
        }
        PageResult<ChatSessionDO> result = sessionService.pageSessions(query);
        List<SessionResponse> records = result.records().stream()
                .map(SessionResponse::from)
                .toList();
        PageResult<SessionResponse> response = PageResult.of(
                result.page(), result.size(), result.total(), records);
        return ApiResult.ok(response);
    }

    private static void requireScope(String tenantId, String deptId) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(deptId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "tenantId and deptId must not be blank");
        }
    }
}
