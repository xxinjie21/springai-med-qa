/**
 * API-key authentication and patient-session ownership enforcement (D21).
 *
 * <p>The {@link ApiKeyAuthFilter} resolves an {@code X-API-Key} header into a {@link MedPrincipal}
 * through the {@link MedApiKeyRegistry}, and binds it to the {@link MedSecurityContext}. The
 * {@link PatientAccessGuard} then enforces, at the service layer, that a patient may only reach the
 * sessions that belong to their own patient id.</p>
 */
package com.med.qa.security;
