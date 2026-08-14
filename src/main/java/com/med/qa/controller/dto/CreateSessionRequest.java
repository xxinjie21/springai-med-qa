package com.med.qa.controller.dto;

import org.springframework.lang.Nullable;

/**
 * Inbound request to open a new consultation session.
 *
 * <p>Only the identity segments are mandatory: a session exists within exactly one tenant, department
 * and patient. The {@code title} is a display convenience and may be omitted — a blank title is stored
 * as {@code null} by the service.</p>
 */
public record CreateSessionRequest(
        String tenantId,
        String deptId,
        String patientId,
        @Nullable String title) {

    /**
     * Validates the caller-supplied identity segments.
     *
     * <p>Designed to be called at the controller boundary and translated into a
     * {@code 400 BAD_REQUEST}; it throws the framework-neutral {@link IllegalArgumentException} so the
     * controller can attach the right error code without leaking validation logic into the DTO.</p>
     *
     * @throws IllegalArgumentException if a mandatory identity segment is {@code null} or blank
     */
    public void validate() {
        requireText(tenantId, "tenantId");
        requireText(deptId, "deptId");
        requireText(patientId, "patientId");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
