package com.devil.phoenixproject.util

import platform.Foundation.NSRecursiveLock

/**
 * iOS implementation uses a single global NSRecursiveLock instead of per-object locks.
 *
 * Rationale: Per-object locking via WeakReference map causes memory leaks on K/N
 * because WeakReference<NSRecursiveLock> prevents garbage collection of the lock map entries.
 *
 * Trade-off: All withPlatformLock callers are serialized against each other.
 * This is acceptable for beta because BLE commands and DB writes are already
 * serialized by their respective layers. Monitor for jank if adding high-frequency callers.
 *
 * NSRecursiveLock (rather than NSLock) is chosen so that nested calls from the same
 * thread do not deadlock -- e.g., a BLE write callback that triggers a DB write
 * within the same call stack.
 *
 * TODO(v0.9.0): Investigate @Synchronized annotation or K/N 2.x weak reference improvements.
 */
@PublishedApi
internal val globalLock = NSRecursiveLock()

actual inline fun <T> withPlatformLock(lock: Any, block: () -> T): T {
    globalLock.lock()
    try {
        return block()
    } finally {
        globalLock.unlock()
    }
}
