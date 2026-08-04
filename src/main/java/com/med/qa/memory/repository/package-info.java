/**
 * Two-tier conversation memory repository.
 *
 * <p>Composes the Redis window cache ({@code com.med.qa.memory.cache}) with the sharded MySQL
 * store ({@code com.med.qa.mapper}) into a single cache-aside facade used by the service layer:
 * reads are served from Redis and fall back to MySQL with an automatic back-fill, writes go to
 * MySQL first and are then mirrored into the cache.</p>
 */
package com.med.qa.memory.repository;
