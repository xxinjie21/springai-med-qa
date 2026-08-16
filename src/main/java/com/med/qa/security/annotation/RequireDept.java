package com.med.qa.security.annotation;

import com.med.qa.security.MedRole;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a handler may only be invoked inside the caller's own department (D22).
 *
 * <h2>Semantics</h2>
 * <p>A handler carrying this annotation is rejected with {@code 403} unless all of the following hold:</p>
 * <ol>
 *   <li>the request is authenticated — an anonymous request never passes, the check fails closed;</li>
 *   <li>the caller's {@link MedRole} is contained in {@link #roles()}, when that list is non-empty;</li>
 *   <li>the department id carried by the request equals the caller's own department id.</li>
 * </ol>
 *
 * <p>Rule 3 is skipped when {@code required() == false} <em>and</em> the request carries no department id
 * at all — that combination exists for endpoints whose scope travels inside the JSON body (streaming
 * consultation, RAG ingestion), where the interceptor must not consume the body. If such a request
 * <em>does</em> carry a department id in its envelope, the id is still verified: relaxing the requirement
 * must never open a bypass. Body-borne scopes remain protected by the service layer
 * ({@code PatientAccessGuard}) and by the tenant/department metadata filters of the RAG retrieval path.</p>
 *
 * <h2>Placement</h2>
 * <p>May be placed on a controller class (applies to every handler of that class) or on a single handler
 * method, which overrides the class-level declaration. Enforcement lives in
 * {@code com.med.qa.security.DeptScopeInterceptor}; the annotation itself carries no behaviour.</p>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface RequireDept {

    /**
     * Name of the request parameter / URI template variable / header carrying the department id.
     *
     * @return parameter name, {@code "deptId"} by default
     */
    String param() default "deptId";

    /**
     * Where the department id is read from.
     *
     * @return the lookup strategy, {@link DeptIdSource#AUTO} by default
     */
    DeptIdSource source() default DeptIdSource.AUTO;

    /**
     * Roles allowed to invoke the handler.
     *
     * @return allowed roles; an empty array (the default) admits every authenticated role
     */
    MedRole[] roles() default {};

    /**
     * Whether the request must carry a department id.
     *
     * @return {@code true} (the default) to reject a request whose envelope has no department id;
     *         {@code false} for body-borne scopes, where only authentication and role are checked
     */
    boolean required() default true;
}
