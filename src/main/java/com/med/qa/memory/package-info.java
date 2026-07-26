/**
 * Memory layer: Spring AI {@code ChatMemory} backed by a custom {@code ChatMemoryRepository}.
 * Uses ShardingSphere-JDBC (crc32 % 16 table sharding), Spring Data Redis cache-aside,
 * Protobuf serialization and Redisson RLock for distributed session locking.
 */
package com.med.qa.memory;
