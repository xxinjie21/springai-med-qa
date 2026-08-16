package com.med.qa.security.annotation;

/**
 * Where {@link RequireDept} reads the requested department id from.
 *
 * <p>The department id is always taken from the <em>request envelope</em> (query string, URI template
 * variable or header) and never from the request body: a {@code HandlerInterceptor} runs before argument
 * resolution, so consuming the body there would break every downstream {@code @RequestBody} binding. For
 * body-borne scopes the annotation is declared with {@code required = false}, which downgrades the check
 * to authentication plus role, and the service layer keeps enforcing isolation.</p>
 */
public enum DeptIdSource {

    /** Try the query string first, then the URI template variables, then the header. */
    AUTO,

    /** Read the department id from a query/form parameter. */
    QUERY,

    /** Read the department id from a URI template variable, e.g. {@code /api/depts/{deptId}/...}. */
    PATH,

    /** Read the department id from a request header. */
    HEADER
}
