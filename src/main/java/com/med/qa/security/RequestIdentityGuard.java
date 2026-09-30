package com.med.qa.security;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Reconciles the authenticated principal with the identity a request body claims, and refuses the
 * request when the two disagree (D41).
 *
 * <h2>The rule</h2>
 * <p>Authority comes from the API key, never from the payload. A body that names a tenant, a department
 * or a patient is treated as the caller <em>asserting</em> who it is: when the assertion matches the
 * {@link MedPrincipal} the request proceeds with the principal's identity, and when it does not the
 * request is refused with {@link ErrorCode#FORBIDDEN}. Omitting the fields is allowed — the principal
 * already carries everything needed — so a client cannot widen or redirect its own scope by editing
 * JSON, and an honest client keeps working whether or not it repeats the identity.</p>
 *
 * <p>Before D41 the streaming consultation path read the tenant / department / patient straight out of
 * the body, which made patient A able to read and write patient B's transcript by filling in B's
 * identifiers. This guard is the single place where that class of mistake is now caught, and it is
 * called before anything touches MySQL, Redis, the vector index or the model.</p>
 *
 * <h2>Staff versus patient</h2>
 * <p>A {@link MedRole#PATIENT} principal may only ever act for itself: a body claiming another patient
 * is refused, and a patient principal that carries no patient id at all is refused as well rather than
 * being silently widened into a department-wide scope. A {@link MedRole#STAFF} principal is
 * department-scoped, so it may name any patient of its own department (that is how a clinician opens a
 * patient's consultation) — but it still cannot leave its tenant or department.</p>
 *
 * <p>The guard performs no IO, reads no thread-local state and holds no state of its own: the principal
 * and the claims are both parameters, which keeps it exhaustively unit testable and safe to call on any
 * thread.</p>
 */
@Service
public class RequestIdentityGuard {

    /**
     * Resolves the effective scope of a call, refusing a claim that contradicts the principal.
     *
     * @param principal        the authenticated caller, or {@code null} for an anonymous request
     * @param claimedTenantId  tenant the body names, or {@code null} / blank when it names none
     * @param claimedDeptId    department the body names, or {@code null} / blank when it names none
     * @param claimedPatientId patient the body names, or {@code null} / blank when it names none
     * @return the effective scope, never {@code null}
     * @throws BizException {@link ErrorCode#FORBIDDEN} when the caller is anonymous, when a claimed field
     *                      contradicts the principal, or when a patient principal carries no patient id
     */
    public MedCallerScope resolve(@Nullable MedPrincipal principal,
                                  @Nullable String claimedTenantId,
                                  @Nullable String claimedDeptId,
                                  @Nullable String claimedPatientId) {
        if (principal == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "authentication required");
        }
        assertClaimMatches(principal.getTenantId(), claimedTenantId, "tenant");
        assertClaimMatches(principal.getDeptId(), claimedDeptId, "department");
        if (principal.isPatient()) {
            String ownPatientId = principal.getPatientId();
            if (!StringUtils.hasText(ownPatientId)) {
                // Fail closed: a patient principal without a patient id could otherwise be widened into a
                // department-wide scope, which is exactly the escalation this guard exists to prevent.
                throw new BizException(ErrorCode.FORBIDDEN,
                        "the authenticated patient principal carries no patient id");
            }
            assertClaimMatches(ownPatientId, claimedPatientId, "patient");
            return MedCallerScope.of(principal);
        }
        return MedCallerScope.of(principal).withPatientId(claimedPatientId);
    }

    /**
     * Refuses a claim that names something other than what the principal is authenticated for.
     *
     * <p>An absent or blank claim is not a contradiction — it simply asserts nothing, and the principal's
     * value stands. Surrounding whitespace is ignored, because a padded identifier is a transport
     * artefact, not an attempt to address another tenant.</p>
     *
     * @param authenticated the value the principal is authenticated for, never blank
     * @param claimed       the value the request body names, or {@code null} / blank for none
     * @param field         human-readable field name, used in the rejection message
     * @throws BizException {@link ErrorCode#FORBIDDEN} when the claim contradicts the principal
     */
    private static void assertClaimMatches(String authenticated, @Nullable String claimed, String field) {
        if (!StringUtils.hasText(claimed)) {
            return;
        }
        if (!authenticated.equals(claimed.trim())) {
            throw new BizException(ErrorCode.FORBIDDEN, field + " mismatch");
        }
    }
}
