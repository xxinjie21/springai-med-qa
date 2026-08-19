package com.med.qa.audit.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method whose invocation must leave a medical operation audit trail.
 *
 * <p>Every annotated call is intercepted by {@code AuditAspect}, which records who performed the
 * operation (the authenticated principal), what was performed ({@link #action()} /
 * {@link #resourceType()}), which object was touched ({@link #target()}), how long it took and
 * whether it succeeded. The row is written to {@code med_audit_log} after the method returns or
 * throws, so a failed attempt is auditable as well — in a hospital deployment the rejected access is
 * usually the interesting one.</p>
 *
 * <h2>Why an annotation instead of explicit calls</h2>
 * <p>Audit statements sprinkled through the service layer rot: a new early-return or a rethrow that
 * skips the logging line silently creates a blind spot. Declaring the intent on the method keeps the
 * "which operations are auditable" decision reviewable in one glance and moves the mechanics
 * (latency measurement, principal lookup, failure classification, truncation, persistence) into a
 * single aspect.</p>
 *
 * <h2>Clinical-content safety</h2>
 * <p>The audit trail deliberately stores identifiers and outcomes only — never message bodies,
 * prompts or retrieved documents. There is no "record arguments" switch on purpose: an audit table
 * that mirrors consultation content would duplicate protected health information into a second,
 * differently governed store.</p>
 *
 * <h2>Target expressions</h2>
 * <p>{@link #target()} is a SpEL expression evaluated by Spring's own expression engine against the
 * method arguments, exactly like the key expressions of {@code @Cacheable}. Arguments are addressable
 * by name ({@code #sessionId}) or position ({@code #a0}), and {@code #result} holds the return value
 * ({@code null} when the method threw). A blank expression means the operation has no single target
 * id. An expression that cannot be evaluated never breaks the business call: the aspect records the
 * entry with an empty target instead.</p>
 *
 * <p>Example — audit the closing of a consultation session:</p>
 * <pre>{@code
 * @MedAudit(action = "SESSION_CLOSE", resourceType = "SESSION", target = "#sessionId")
 * public ChatSessionDO closeSession(String tenantId, String deptId, String sessionId) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MedAudit {

    /**
     * Stable action code of the audited operation, e.g. {@code SESSION_CLOSE}.
     *
     * <p>Codes are the query dimension of the audit table (and of any downstream compliance report),
     * so they are written as screaming snake case constants rather than free prose and must not be
     * blank.</p>
     *
     * @return the action code, never blank
     */
    String action();

    /**
     * Type of the touched resource, e.g. {@code SESSION} or {@code RAG_DOCUMENT}.
     *
     * @return the resource type, or an empty string when the action is not bound to a resource kind
     */
    String resourceType() default "";

    /**
     * SpEL expression resolving the id of the touched resource, e.g. {@code #sessionId} or
     * {@code #result.sessionId}.
     *
     * @return the target expression, or an empty string when the operation has no single target
     */
    String target() default "";

    /**
     * Human-readable description stored alongside a successful entry, for reviewers reading the
     * trail without the source at hand.
     *
     * @return the description, or an empty string to store none
     */
    String description() default "";
}
