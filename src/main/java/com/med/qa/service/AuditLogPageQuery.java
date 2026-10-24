package com.med.qa.service;

import com.med.qa.domain.enums.AuditOutcome;
import org.springframework.lang.Nullable;

import java.util.Objects;

/**
 * Immutable description of one audit-trail review request.
 *
 * <p>The tenant and department segments are mandatory: a review that is not narrowed to a department
 * could surface the operations of another hospital, which the isolation contract of this service
 * forbids — the endpoint is staff-only, but "staff" still means staff of exactly one department.
 * Narrowing further to an operator, an action, an outcome and/or a creation-time window is optional.</p>
 *
 * <p>The time window is half-inclusive: {@code createdFrom} is included and {@code createdTo} is
 * excluded, the same convention the retention trim uses for its cutoff, so a review bounded by
 * {@code [from, to)} never double-counts or drops a row stamped exactly at a bound. A window whose
 * lower bound is after its upper bound is rejected at build time rather than silently returning
 * nothing.</p>
 *
 * <p>{@code size} is deliberately nullable — {@code null} means "apply the configured default", which
 * only {@link AuditLogService} knows. Pages are one-based, matching what an API client sends.</p>
 */
public final class AuditLogPageQuery {

    private final String tenantId;

    private final String deptId;

    private final String operatorId;

    private final String action;

    private final AuditOutcome outcome;

    private final Long createdFrom;

    private final Long createdTo;

    private final int page;

    private final Integer size;

    private AuditLogPageQuery(Builder builder) {
        this.tenantId = builder.tenantId;
        this.deptId = builder.deptId;
        this.operatorId = builder.operatorId;
        this.action = builder.action;
        this.outcome = builder.outcome;
        this.createdFrom = builder.createdFrom;
        this.createdTo = builder.createdTo;
        this.page = builder.page;
        this.size = builder.size;
    }

    /**
     * Starts a query for the audit trail of one department.
     *
     * @param tenantId hospital/tenant id, must not be blank
     * @param deptId   department id, must not be blank
     * @return a builder pre-filled with the mandatory scope
     * @throws IllegalArgumentException if a segment is {@code null} or blank
     */
    public static Builder builder(String tenantId, String deptId) {
        return new Builder(tenantId, deptId);
    }

    public String tenantId() {
        return tenantId;
    }

    public String deptId() {
        return deptId;
    }

    /**
     * Returns the operator this review is narrowed to.
     *
     * @return the operator id, or {@code null} for every operator of the department
     */
    @Nullable
    public String operatorId() {
        return operatorId;
    }

    /**
     * Returns the action code this review is narrowed to.
     *
     * @return the action code, or {@code null} for every action
     */
    @Nullable
    public String action() {
        return action;
    }

    /**
     * Returns the outcome this review is narrowed to.
     *
     * @return the outcome, or {@code null} for successes and failures alike
     */
    @Nullable
    public AuditOutcome outcome() {
        return outcome;
    }

    /**
     * Returns the inclusive lower bound of the time window.
     *
     * @return epoch milliseconds, or {@code null} for no lower bound
     */
    @Nullable
    public Long createdFrom() {
        return createdFrom;
    }

    /**
     * Returns the exclusive upper bound of the time window.
     *
     * @return epoch milliseconds, or {@code null} for no upper bound
     */
    @Nullable
    public Long createdTo() {
        return createdTo;
    }

    /**
     * Returns the requested one-based page number.
     *
     * @return page number, always {@code >= 1}
     */
    public int page() {
        return page;
    }

    /**
     * Returns the requested page size.
     *
     * @return page size, or {@code null} when the configured default should apply
     */
    @Nullable
    public Integer size() {
        return size;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AuditLogPageQuery that)) {
            return false;
        }
        return page == that.page
                && Objects.equals(tenantId, that.tenantId)
                && Objects.equals(deptId, that.deptId)
                && Objects.equals(operatorId, that.operatorId)
                && Objects.equals(action, that.action)
                && outcome == that.outcome
                && Objects.equals(createdFrom, that.createdFrom)
                && Objects.equals(createdTo, that.createdTo)
                && Objects.equals(size, that.size);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, deptId, operatorId, action, outcome, createdFrom, createdTo,
                page, size);
    }

    @Override
    public String toString() {
        return "AuditLogPageQuery{tenantId='" + tenantId + "', deptId='" + deptId
                + "', operatorId='" + operatorId + "', action='" + action + "', outcome=" + outcome
                + ", createdFrom=" + createdFrom + ", createdTo=" + createdTo
                + ", page=" + page + ", size=" + size + '}';
    }

    /** Fluent builder of {@link AuditLogPageQuery}. */
    public static final class Builder {

        private final String tenantId;

        private final String deptId;

        private String operatorId;

        private String action;

        private AuditOutcome outcome;

        private Long createdFrom;

        private Long createdTo;

        private int page = 1;

        private Integer size;

        private Builder(String tenantId, String deptId) {
            requireText(tenantId, "tenantId");
            requireText(deptId, "deptId");
            this.tenantId = tenantId;
            this.deptId = deptId;
        }

        /**
         * Narrows the review to one operator.
         *
         * @param operatorId operator id, or {@code null}/blank for every operator
         * @return this builder
         */
        public Builder operatorId(@Nullable String operatorId) {
            this.operatorId = (operatorId == null || operatorId.isBlank()) ? null : operatorId;
            return this;
        }

        /**
         * Narrows the review to one action code.
         *
         * @param action action code, or {@code null}/blank for every action
         * @return this builder
         */
        public Builder action(@Nullable String action) {
            this.action = (action == null || action.isBlank()) ? null : action;
            return this;
        }

        /**
         * Narrows the review to one outcome.
         *
         * @param outcome the outcome, or {@code null} for successes and failures alike
         * @return this builder
         */
        public Builder outcome(@Nullable AuditOutcome outcome) {
            this.outcome = outcome;
            return this;
        }

        /**
         * Sets the inclusive lower bound of the time window.
         *
         * @param createdFrom epoch milliseconds, must not be negative; {@code null} removes the bound
         * @return this builder
         * @throws IllegalArgumentException if the bound is negative
         */
        public Builder createdFrom(@Nullable Long createdFrom) {
            if (createdFrom != null && createdFrom < 0) {
                throw new IllegalArgumentException("createdFrom must not be negative but was "
                        + createdFrom);
            }
            this.createdFrom = createdFrom;
            return this;
        }

        /**
         * Sets the exclusive upper bound of the time window.
         *
         * @param createdTo epoch milliseconds, must not be negative; {@code null} removes the bound
         * @return this builder
         * @throws IllegalArgumentException if the bound is negative
         */
        public Builder createdTo(@Nullable Long createdTo) {
            if (createdTo != null && createdTo < 0) {
                throw new IllegalArgumentException("createdTo must not be negative but was "
                        + createdTo);
            }
            this.createdTo = createdTo;
            return this;
        }

        /**
         * Sets the one-based page number.
         *
         * @param page page number, must be {@code >= 1}
         * @return this builder
         * @throws IllegalArgumentException if {@code page < 1}
         */
        public Builder page(int page) {
            if (page < 1) {
                throw new IllegalArgumentException("page must be >= 1 but was " + page);
            }
            this.page = page;
            return this;
        }

        /**
         * Sets the requested page size.
         *
         * @param size page size, must be {@code >= 1} when present; {@code null} defers to the
         *             configured default
         * @return this builder
         * @throws IllegalArgumentException if a present {@code size} is not positive
         */
        public Builder size(@Nullable Integer size) {
            if (size != null && size < 1) {
                throw new IllegalArgumentException("size must be >= 1 but was " + size);
            }
            this.size = size;
            return this;
        }

        /**
         * Builds the immutable query.
         *
         * @return the query, never {@code null}
         * @throws IllegalArgumentException if both time bounds are present and the window is empty
         *                                  ({@code createdFrom > createdTo})
         */
        public AuditLogPageQuery build() {
            if (createdFrom != null && createdTo != null && createdFrom > createdTo) {
                throw new IllegalArgumentException("createdFrom (" + createdFrom
                        + ") must not be after createdTo (" + createdTo + ")");
            }
            return new AuditLogPageQuery(this);
        }

        private static void requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
        }
    }
}
