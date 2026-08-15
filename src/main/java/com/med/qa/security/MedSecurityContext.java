package com.med.qa.security;

import org.springframework.lang.Nullable;

/**
 * Per-request holder of the authenticated {@link MedPrincipal}.
 *
 * <p>{@link ApiKeyAuthFilter} populates this context at the start of a request and clears it in a
 * {@code finally} block, so the principal is available to the service layer (which performs the actual
 * ownership checks via {@link PatientAccessGuard}) without threading it through every method signature.
 * The holder is a {@code ThreadLocal}; web requests are handled on a single thread, so the value set by
 * the filter is visible to the controller and service it dispatches to.</p>
 */
public final class MedSecurityContext {

    private static final ThreadLocal<MedPrincipal> PRINCIPAL = new ThreadLocal<>();

    private MedSecurityContext() {
    }

    /**
     * Binds the current request's principal, or clears it when {@code principal} is {@code null}.
     *
     * @param principal authenticated caller, or {@code null} to reset the context
     */
    public static void setPrincipal(@Nullable MedPrincipal principal) {
        if (principal == null) {
            PRINCIPAL.remove();
        } else {
            PRINCIPAL.set(principal);
        }
    }

    /**
     * Returns the current request's principal.
     *
     * @return the principal, or {@code null} when the request is unauthenticated
     */
    @Nullable
    public static MedPrincipal getPrincipal() {
        return PRINCIPAL.get();
    }

    /**
     * Alias of {@link #getPrincipal()} used by the service layer.
     *
     * @return the principal, or {@code null} when the request is unauthenticated
     */
    @Nullable
    public static MedPrincipal getCurrent() {
        return PRINCIPAL.get();
    }

    /**
     * Removes any principal bound to the current thread. Called by the filter after the chain returns.
     */
    public static void clear() {
        PRINCIPAL.remove();
    }
}
