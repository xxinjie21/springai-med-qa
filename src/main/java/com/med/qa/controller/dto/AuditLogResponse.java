package com.med.qa.controller.dto;

import com.med.qa.domain.entity.AuditLogDO;
import com.med.qa.domain.enums.AuditOutcome;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;

/**
 * Read-side view of one audit-trail entry, shaped for the REST API.
 *
 * <p>The DTO mirrors {@link AuditLogDO} field for field — it is the public projection of the stored
 * row. No clinical payload lives on an audit entry (identifiers and outcomes only, per the V3
 * migration), so nothing here requires masking; the caller has already been narrowed to their own
 * department by the {@code @RequireDept} interceptor.</p>
 */
public record AuditLogResponse(
        @Schema(description = "audit entry identifier", example = "b3f1c2a4-...")
        String auditId,
        @Schema(description = "hospital / tenant identifier", example = "t-1001")
        String tenantId,
        @Schema(description = "department identifier", example = "dept-cardio")
        String deptId,
        @Schema(description = "acting subject identifier", example = "staff-42")
        String operatorId,
        @Schema(description = "role of the acting subject, may be null", example = "STAFF")
        @Nullable String operatorRole,
        @Schema(description = "stable action code", example = "SESSION_CLOSE")
        String action,
        @Schema(description = "resource type, may be null", example = "SESSION")
        @Nullable String resourceType,
        @Schema(description = "resource identifier, may be null", example = "sess-9f2c1a")
        @Nullable String resourceId,
        @Schema(description = "outcome: SUCCESS or FAILURE")
        AuditOutcome outcome,
        @Schema(description = "business error code of a failure, null on success", example = "40400")
        @Nullable Integer errorCode,
        @Schema(description = "wall-clock duration of the audited call in milliseconds",
                example = "37")
        long latencyMillis,
        @Schema(description = "description or failure message, may be null")
        @Nullable String message,
        @Schema(description = "audit time, epoch millis", example = "1718000000000")
        long createdAt) {

    /**
     * Builds the API view of a persisted audit entry.
     *
     * @param entry the stored entry, must not be {@code null}
     * @return the API view, never {@code null}
     * @throws IllegalArgumentException if {@code entry} is {@code null}
     */
    public static AuditLogResponse from(AuditLogDO entry) {
        if (entry == null) {
            throw new IllegalArgumentException("entry must not be null");
        }
        return new AuditLogResponse(
                entry.getAuditId(),
                entry.getTenantId(),
                entry.getDeptId(),
                entry.getOperatorId(),
                entry.getOperatorRole(),
                entry.getAction(),
                entry.getResourceType(),
                entry.getResourceId(),
                entry.getOutcome(),
                entry.getErrorCode(),
                entry.getLatencyMillis(),
                entry.getMessage(),
                entry.getCreatedAt());
    }
}
