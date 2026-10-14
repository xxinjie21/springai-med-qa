/**
 * Tests for the background job run ledger in {@code com.med.qa.job}.
 *
 * <p>The suite drives the ledger through a real {@code SimpleMeterRegistry} rather than a mocked one, so
 * what is asserted is the meter a Prometheus scrape would actually read -- name, label set and value --
 * instead of the fact that some method was called. The two contracts that carry the most weight are that
 * a freshly registered job is not immediately reported as stale (otherwise every rule would fire for the
 * whole {@code initial-delay} after each restart) and that a second registration cannot re-seed the
 * timestamps (otherwise re-registering a job would be a way to make a stalled one look alive).</p>
 */
package com.med.qa.job;
