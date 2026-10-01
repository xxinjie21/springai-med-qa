package com.med.qa.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;

/**
 * Payload of the RAG admin delete endpoint (D42).
 *
 * <h2>The scope is claimed here and authorised against the API key</h2>
 * <p>{@code tenantId} / {@code deptId} / {@code patientId} are <strong>consistency claims</strong>: the
 * deletion runs in the scope of the authenticated principal the API key resolved to, and these fields
 * are cross-checked against it by {@link com.med.qa.security.RequestIdentityGuard}. A claim that
 * contradicts the principal is refused with {@code 403}; an absent claim simply falls back to the
 * principal's own value. Before D42 these fields <em>were</em> the authority, so any staff key could
 * delete another department's vectors just by naming that department.</p>
 *
 * <h2>Why a department-wide delete has to be confirmed</h2>
 * <p>Removing one patient's documents is narrow: only that patient is affected. Removing a whole
 * department takes its shared clinical guidelines with it, so every consultation in that department
 * afterwards answers from a corpus that no longer contains its protocols. The capability therefore
 * demands a deliberate act rather than a default — when no {@code patientId} is named the caller must
 * also set {@code confirmDepartmentWide} to {@code true}, otherwise the request is rejected with
 * {@code 400}. This mirrors the two-switch idiom D38 uses for a destructive index rebuild.</p>
 *
 * <p>There is deliberately no {@code ids} field any more. See
 * {@link com.med.qa.rag.MedDocumentService#deleteByScope} for why an id-based delete cannot be made
 * scope-safe with this index schema.</p>
 *
 * @param tenantId  hospital / tenant identifier the caller claims to act for, or {@code null} to use the
 *                  principal's own
 * @param deptId    department identifier the caller claims to act for, or {@code null} to use the
 *                  principal's own
 * @param patientId patient whose documents are removed, or {@code null} / blank for the department-wide
 *                  scope
 * @param confirmDepartmentWide whether the caller deliberately accepts a department-wide deletion; must
 *                  be {@code true} whenever no patient id is named
 */
public record RagDeleteRequest(
        @Schema(description = "tenant the caller claims to act for; optional, must match the API key",
                example = "t-1001") @Nullable String tenantId,
        @Schema(description = "department the caller claims to act for; optional, must match the API key",
                example = "dept-cardio") @Nullable String deptId,
        @Schema(description = "patient whose documents are removed, or null for the whole department",
                example = "pat-7731") @Nullable String patientId,
        @Schema(description = "must be true when no patientId is given, because the whole department "
                + "corpus (shared guidelines included) is removed", example = "false")
        boolean confirmDepartmentWide) {
}
