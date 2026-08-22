package com.med.qa.controller.dto;

import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.enums.SessionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;

/**
 * Read-side view of a consultation session, shaped for the REST API.
 *
 * <p>The DTO deliberately mirrors the storage fields one-to-one (it is the public projection of
 * {@link ChatSessionDO}); the numeric {@link SessionStatus} is surfaced as its enum name so clients do
 * not have to hard-code the internal codes. No clinical content lives on a session, so nothing here is
 * sensitive enough to require masking.</p>
 */
public record SessionResponse(
        @Schema(description = "consultation session identifier", example = "sess-9f2c1a")
        String sessionId,
        @Schema(description = "hospital / tenant identifier", example = "t-1001")
        String tenantId,
        @Schema(description = "department identifier", example = "dept-cardio")
        String deptId,
        @Schema(description = "patient identifier", example = "pat-7731")
        String patientId,
        @Schema(description = "optional display title, may be null", example = "Post-op follow-up")
        @Nullable String title,
        @Schema(description = "lifecycle status: ACTIVE, CLOSED or ARCHIVED")
        SessionStatus status,
        @Schema(description = "creation time, epoch millis", example = "1718000000000")
        long createdAt,
        @Schema(description = "last update time, epoch millis", example = "1718003600000")
        long updatedAt) {

    /**
     * Builds the API view of a persisted session entity.
     *
     * @param session the session, must not be {@code null}
     * @return the API view, never {@code null}
     * @throws IllegalArgumentException if {@code session} is {@code null}
     */
    public static SessionResponse from(ChatSessionDO session) {
        if (session == null) {
            throw new IllegalArgumentException("session must not be null");
        }
        return new SessionResponse(
                session.getSessionId(),
                session.getTenantId(),
                session.getDeptId(),
                session.getPatientId(),
                session.getTitle(),
                session.getStatus(),
                session.getCreatedAt(),
                session.getUpdatedAt());
    }
}
