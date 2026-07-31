/**
 * ShardingSphere-JDBC sharding plugins. Contains only the algorithm class that maps a session id to
 * {@code med_message_{crc32(session_id) % 16}}; SQL routing, rewriting and result merging are done
 * by the framework.
 */
package com.med.qa.memory.sharding;
