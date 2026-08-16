/**
 * API-key authentication, patient-session ownership (D21) and declarative department-scope
 * authorization (D22).
 *
 * <p>The {@link com.med.qa.security.ApiKeyAuthFilter} resolves an {@code X-API-Key} header into a
 * {@link com.med.qa.security.MedPrincipal} through the {@link com.med.qa.security.MedApiKeyRegistry}, and
 * binds it to the {@link com.med.qa.security.MedSecurityContext}. Two guards then consume that principal
 * at different granularities:</p>
 * <ul>
 *   <li>{@link com.med.qa.security.PatientAccessGuard} — row level, at the service layer: a patient may
 *       only reach the sessions that belong to their own patient id.</li>
 *   <li>{@link com.med.qa.security.DeptScopeGuard} — endpoint level, driven by
 *       {@link com.med.qa.security.annotation.RequireDept} and applied by
 *       {@link com.med.qa.security.DeptScopeInterceptor} before the handler runs, so a cross-department
 *       call is refused with {@code 403} without touching MySQL, Redis, the vector index or the LLM.</li>
 * </ul>
 */
package com.med.qa.security;
