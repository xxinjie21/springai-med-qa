/**
 * Redis cache layer of the conversation memory module.
 *
 * <p>Backed by Spring Data Redis (Lettuce). The cache holds the recent message window of a
 * consultation session under the key mandated by the unified medical storage specification
 * (ROADMAP section 4): {@code med:chat:{tenant_id}:{dept_id}:{session_id}}. Values are the same
 * Protobuf payloads persisted in MySQL, so a record written by this service and one written by the
 * heterogeneous Python middleware are byte-identical.</p>
 *
 * <p>MySQL remains the source of truth; this package only accelerates reads.</p>
 */
package com.med.qa.memory.cache;
