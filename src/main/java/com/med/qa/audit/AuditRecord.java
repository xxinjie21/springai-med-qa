package com.med.qa.audit;

import com.med.qa.domain.enums.AuditOutcome;
import com.med.qa.security.MedPrincipal;
import com.med.qa.security.MedRole;
import org.springframework.lang.Nullable;

/**
 * Immutable description of one audited medical operation, handed by {@link AuditAspect} to
 * {@link com.med.qa.service.AuditService} for persistence.
 *
 * <p>The value object is the boundary between "observing the call" and "storing the evidence": the
 * aspect knows the join point, the service knows the table, and neither needs the other's vocabulary.
 * It normalizes at construction time so a half-populated entry can never reach storage — the scope
 * and operator segments fall back to {@link #ANONYMOUS} instead of {@code null} (a request that
 * reached an audited method without authentication is itself worth recording), while empty optional
 * text collapses to {@code null} so the column stays genuinely empty rather than holding
 * {@code ""}.</p>
 *
 * @param tenantId      hospital/tenant id, {@link #ANONYMOUS} when unauthenticated
 * @param deptId        department id, {@link #ANONYMOUS} when unauthenticated
 * @param operatorId    acting subject id, {@link #ANONYMOUS} when unauthenticated
 * @param operatorRole  role of the acting subject, or {@code null} when unauthenticated
 * @param action        stable action code, never blank
 * @param resourceType  type of the touched resource, or {@code null}
 * @param resourceId    id of the touched resource, or {@code null}
 * @param outcome       whether the operation succeeded, never {@code null}
 * @param errorCode     business error code of a failure, or {@code null} on success
 * @param latencyMillis wall-clock duration of the call in milliseconds, never negative
 * @param message       description on success or failure summary, or {@code null}
 */
public record AuditRecord(String tenantId,
                          String deptId,
                          String operatorId,
                          @Nullable MedRole operatorRole,
                          String action,
                          @Nullable String resourceType,
                          @Nullable String resourceId,
                          AuditOutcome outcome,
                          @Nullable Integer errorCode,
                          long latencyMillis,
                          @Nullable String message) {

    /**
     * Placeholder written into the scope/operator columns when the audited call carried no
     * authenticated principal. A literal marker keeps the columns {@code NOT NULL} and, unlike a
     * dropped row, makes the anonymous attempt visible to a reviewer.
     */
    public static final String ANONYMOUS = "__anonymous__";

    /**
     * Operator id recorded for staff principals. A staff API key authenticates a department role
     * rather than a natural person, so the department (in {@code deptId}) plus this marker is the
     * whole truth the authentication layer knows — inventing a per-user id here would fabricate
     * evidence.
     */
    public static final String STAFF_OPERATOR = "__staff__";

    /**
     * Normalizes and validates the entry.
     *
     * @throws IllegalArgumentException if {@code action} is blank, {@code outcome} is {@code null} or
     *                                  {@code latencyMillis} is negative
     */
    public AuditRecord {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action must not be blank");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
        if (latencyMillis < 0) {
            throw new IllegalArgumentException("latencyMillis must not be negative");
        }
        action = action.trim();
        tenantId = orAnonymous(tenantId);
        deptId = orAnonymous(deptId);
        operatorId = orAnonymous(operatorId);
        resourceType = orNull(resourceType);
        resourceId = orNull(resourceId);
        message = orNull(message);
    }

    /**
     * Starts building an entry for the given action.
     *
     * @param action stable action code, must not be blank
     * @return a builder pre-set to {@link AuditOutcome#SUCCESS} and zero latency
     * @throws IllegalArgumentException if {@code action} is blank
     */
    public static Builder builder(String action) {
        return new Builder(action);
    }

    /**
     * Whether this entry was produced by an unauthenticated call.
     *
     * @return {@code true} when no principal was bound to the audited request
     */
    public boolean isAnonymous() {
        return operatorRole == null && ANONYMOUS.equals(operatorId);
    }

    /**
     * Whether this entry records a successful operation.
     *
     * @return {@code true} when the outcome is {@link AuditOutcome#SUCCESS}
     */
    public boolean isSuccessful() {
        return outcome.isSuccess();
    }

    private static String orAnonymous(@Nullable String value) {
        return value == null || value.isBlank() ? ANONYMOUS : value.trim();
    }

    @Nullable
    private static String orNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Mutable builder of {@link AuditRecord}. Not thread-safe; one instance serves one audited call.
     */
    public static final class Builder {

        private final String action;

        @Nullable
        private String tenantId;

        @Nullable
        private String deptId;

        @Nullable
        private String operatorId;

        @Nullable
        private MedRole operatorRole;

        @Nullable
        private String resourceType;

        @Nullable
        private String resourceId;

        private AuditOutcome outcome = AuditOutcome.SUCCESS;

        @Nullable
        private Integer errorCode;

        private long latencyMillis;

        @Nullable
        private String message;

        private Builder(String action) {
            if (action == null || action.isBlank()) {
                throw new IllegalArgumentException("action must not be blank");
            }
            this.action = action;
        }

        /**
         * Copies scope, role and operator id from the authenticated caller.
         *
         * <p>A {@code null} principal leaves the entry anonymous, which is exactly what an
         * unauthenticated call should look like in the trail.</p>
         *
         * @param principal authenticated caller, or {@code null} when the request had none
         * @return this builder
         */
        public Builder principal(@Nullable MedPrincipal principal) {
            if (principal == null) {
                return this;
            }
            this.tenantId = principal.tenantId();
            this.deptId = principal.deptId();
            this.operatorRole = principal.role();
            String patientId = principal.patientId();
            this.operatorId = patientId != null && !patientId.isBlank() ? patientId : STAFF_OPERATOR;
            return this;
        }

        /**
         * Overrides the tenant id.
         *
         * @param tenantId hospital/tenant id, or {@code null} to keep it anonymous
         * @return this builder
         */
        public Builder tenantId(@Nullable String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        /**
         * Overrides the department id.
         *
         * @param deptId department id, or {@code null} to keep it anonymous
         * @return this builder
         */
        public Builder deptId(@Nullable String deptId) {
            this.deptId = deptId;
            return this;
        }

        /**
         * Overrides the acting subject id.
         *
         * @param operatorId acting subject id, or {@code null} to keep it anonymous
         * @return this builder
         */
        public Builder operatorId(@Nullable String operatorId) {
            this.operatorId = operatorId;
            return this;
        }

        /**
         * Overrides the acting subject role.
         *
         * @param operatorRole caller role, or {@code null} when unauthenticated
         * @return this builder
         */
        public Builder operatorRole(@Nullable MedRole operatorRole) {
            this.operatorRole = operatorRole;
            return this;
        }

        /**
         * Sets the touched resource.
         *
         * @param resourceType resource type, or {@code null}
         * @param resourceId   resource id, or {@code null}
         * @return this builder
         */
        public Builder resource(@Nullable String resourceType, @Nullable String resourceId) {
            String normalizedType =
                    (resourceType == null || resourceType.isBlank()) ? null : resourceType.trim();
            this.resourceType = normalizedType;
            // An untyped resource has no meaningful id, so a blank type collapses the id too.
            this.resourceId = (normalizedType == null
                    || resourceId == null || resourceId.isBlank()) ? null : resourceId.trim();
            return this;
        }

        /**
         * Marks the operation successful, optionally with a human description.
         *
         * @param description reviewer-facing description, or {@code null}
         * @return this builder
         */
        public Builder success(@Nullable String description) {
            this.outcome = AuditOutcome.SUCCESS;
            this.errorCode = null;
            this.message = description;
            return this;
        }

        /**
         * Marks the operation failed.
         *
         * @param errorCode business error code of the failure, or {@code null} when unclassified
         * @param message   failure summary, or {@code null}
         * @return this builder
         */
        public Builder failure(@Nullable Integer errorCode, @Nullable String message) {
            this.outcome = AuditOutcome.FAILURE;
            this.errorCode = errorCode;
            this.message = message;
            return this;
        }

        /**
         * Sets the measured duration of the audited call.
         *
         * @param latencyMillis wall-clock duration in milliseconds, must not be negative
         * @return this builder
         * @throws IllegalArgumentException if {@code latencyMillis} is negative
         */
        public Builder latencyMillis(long latencyMillis) {
            if (latencyMillis < 0) {
                throw new IllegalArgumentException("latencyMillis must not be negative");
            }
            this.latencyMillis = latencyMillis;
            return this;
        }

        /**
         * Builds the immutable entry.
         *
         * @return the normalized audit entry
         */
        public AuditRecord build() {
            return new AuditRecord(tenantId, deptId, operatorId, operatorRole, action, resourceType,
                    resourceId, outcome, errorCode, latencyMillis, message);
        }
    }
}
