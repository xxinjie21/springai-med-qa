/**
 * Distributed locking for consultation sessions.
 *
 * <p>Concurrency control is delegated entirely to Redisson's {@code RLock} (Redis based, with
 * automatic watchdog lease renewal). This package only contributes the key schema, the externalized
 * timings and the failure translation into the project's {@code ErrorCode} vocabulary — no locking
 * primitive (SETNX, Lua release script, reentrancy bookkeeping) is implemented here.</p>
 */
package com.med.qa.memory.lock;
