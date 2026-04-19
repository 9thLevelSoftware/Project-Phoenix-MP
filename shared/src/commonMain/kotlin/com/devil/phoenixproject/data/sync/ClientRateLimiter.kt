package com.devil.phoenixproject.data.sync

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.util.KmpUtils.currentTimeMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sliding-window rate limiter for mobile sync operations (Phase 4.2, audit
 * item #9). Mirrors the server-enforced rate limits at
 * `phoenix-portal/supabase/functions/mobile-sync-push|pull` so a
 * misbehaving client fails fast locally instead of burning an Edge Function
 * invocation only to receive HTTP 429.
 *
 * In-memory only. A process restart wipes the window, so the server
 * remains authoritative. The persistent SQLDelight-backed variant (with a
 * `SyncRateLog` table) is tracked as a follow-up; the in-memory tracker is
 * sufficient to catch the common case of a buggy retry loop without
 * expanding schema surface in this phase.
 *
 * Thread safety: internal state protected by a coroutine-aware Mutex so
 * concurrent `SyncManager.push`/`pull` calls cannot race.
 */
class ClientRateLimiter(
    private val windowMillis: Long = SyncConfig.RATE_LIMIT_WINDOW_MS,
) {
    private val mutex = Mutex()
    private val attempts: MutableMap<String, ArrayDeque<Long>> = mutableMapOf()

    /**
     * Atomically check whether an operation is allowed within the current
     * window. If allowed, records the attempt and returns true. If denied,
     * returns false without mutating the window (caller decides whether to
     * retry later or surface the limit to the user).
     *
     * @param operation opaque key, e.g. "push" or "pull"
     * @param limit maximum attempts permitted within windowMillis
     */
    suspend fun tryAcquire(operation: String, limit: Int): Boolean = mutex.withLock {
        val now = currentTimeMillis()
        val cutoff = now - windowMillis
        val window = attempts.getOrPut(operation) { ArrayDeque() }
        // Drop timestamps that fell out of the sliding window.
        while (window.isNotEmpty() && window.first() < cutoff) {
            window.removeFirst()
        }
        return@withLock if (window.size < limit) {
            window.addLast(now)
            true
        } else {
            Logger.w("ClientRateLimiter") {
                "Denied $operation: ${window.size}/$limit used in last ${windowMillis}ms."
            }
            false
        }
    }

    /** Wipe all recorded attempts. Intended for test fixtures only. */
    suspend fun resetForTest() = mutex.withLock { attempts.clear() }
}

/**
 * Typed outcome returned by rate-limited sync entry points when the client
 * refuses to issue a request. Callers can translate to UI state (show a
 * "try again in a moment" banner) or silently defer to the next trigger.
 */
sealed class SyncRateLimitOutcome {
    data object Allowed : SyncRateLimitOutcome()
    data class Denied(val operation: String, val limitPerMinute: Int) : SyncRateLimitOutcome()
}
