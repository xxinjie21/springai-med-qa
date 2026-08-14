package com.med.qa.service;

import com.med.qa.domain.enums.SessionStatus;
import org.springframework.lang.Nullable;

import java.util.Objects;

/**
 * Immutable description of one consultation-session listing request.
 *
 * <p>The tenant and department segments are mandatory: a listing that is not narrowed to a
 * department could return sessions of another hospital, which the isolation contract of this service
 * forbids. Narrowing further to a patient or to a lifecycle status is optional.</p>
 *
 * <p>{@code size} is deliberately nullable — {@code null} means "apply the configured default", which
 * only {@link MedChatSessionService} knows. Pages are one-based, matching what an API client sends.</p>
 */
public final class SessionPageQuery {

    private final String tenantId;

    private final String deptId;

    private final String patientId;

    private final SessionStatus status;

    private final int page;

    private final Integer size;

    private SessionPageQuery(Builder builder) {
        this.tenantId = builder.tenantId;
        this.deptId = builder.deptId;
        this.patientId = builder.patientId;
        this.status = builder.status;
        this.page = builder.page;
        this.size = builder.size;
    }

    /**
     * Starts a query for the sessions of one department.
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
     * Returns the patient this listing is narrowed to.
     *
     * @return the patient id, or {@code null} to list the whole department
     */
    @Nullable
    public String patientId() {
        return patientId;
    }

    /**
     * Returns the lifecycle status this listing is narrowed to.
     *
     * @return the status, or {@code null} to list every status
     */
    @Nullable
    public SessionStatus status() {
        return status;
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
        if (!(o instanceof SessionPageQuery that)) {
            return false;
        }
        return page == that.page
                && Objects.equals(tenantId, that.tenantId)
                && Objects.equals(deptId, that.deptId)
                && Objects.equals(patientId, that.patientId)
                && status == that.status
                && Objects.equals(size, that.size);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, deptId, patientId, status, page, size);
    }

    @Override
    public String toString() {
        return "SessionPageQuery{tenantId='" + tenantId + "', deptId='" + deptId
                + "', patientId='" + patientId + "', status=" + status
                + ", page=" + page + ", size=" + size + '}';
    }

    /** Fluent builder of {@link SessionPageQuery}. */
    public static final class Builder {

        private final String tenantId;

        private final String deptId;

        private String patientId;

        private SessionStatus status;

        private int page = 1;

        private Integer size;

        private Builder(String tenantId, String deptId) {
            requireText(tenantId, "tenantId");
            requireText(deptId, "deptId");
            this.tenantId = tenantId;
            this.deptId = deptId;
        }

        /**
         * Narrows the listing to one patient.
         *
         * @param patientId patient id, or {@code null}/blank to list the whole department
         * @return this builder
         */
        public Builder patientId(@Nullable String patientId) {
            this.patientId = (patientId == null || patientId.isBlank()) ? null : patientId;
            return this;
        }

        /**
         * Narrows the listing to one lifecycle status.
         *
         * @param status the status, or {@code null} to list every status
         * @return this builder
         */
        public Builder status(@Nullable SessionStatus status) {
            this.status = status;
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
         */
        public SessionPageQuery build() {
            return new SessionPageQuery(this);
        }

        private static void requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
        }
    }
}
