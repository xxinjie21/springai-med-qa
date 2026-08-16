/**
 * Declarative authorization annotations (D22).
 *
 * <p>{@link com.med.qa.security.annotation.RequireDept} marks a handler as department-scoped;
 * {@link com.med.qa.security.annotation.DeptIdSource} selects where the requested department id is read
 * from. Both are pure metadata — the enforcement point is
 * {@code com.med.qa.security.DeptScopeInterceptor}.</p>
 */
package com.med.qa.security.annotation;
