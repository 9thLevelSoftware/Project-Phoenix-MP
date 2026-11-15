package com.devil.phoenixproject.data.media

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared lifecycle mechanism coordinating live media import/commit sessions
 * with the cleanup snapshot/sweep (issue #1358, merge-gate finding B).
 *
 * The startup sweep must never observe a stale snapshot while an editor is
 * staging or committing: a delayed sweep used to delete live staging dirs and
 * just-moved, not-yet-referenced commit files. Both sides now serialize through
 * ONE mutex — the sweep holds it across its DB snapshot AND its deletes, while
 * staging/commit work holds it across their file operations — and the sweep is
 * additionally told the exact live session ids (staging sessions plus in-flight
 * commit exercise ids) so active work survives even a later sweep.
 *
 * A session stays registered from its first import until it is released
 * ([releaseSession]) after a successful save or Cancel/dismiss, so staged files
 * are protected for the whole dialog lifetime, not just during a copy.
 */
class ExerciseMediaSessionRegistry {

    private val mutex = Mutex()
    private val liveSessions = mutableSetOf<String>()
    private val inFlightCommits = mutableSetOf<String>()

    /**
     * Live session ids: staging sessions plus in-flight commit exercise ids.
     * Passed to the store's reconcile so those paths survive the sweep.
     */
    suspend fun activeSessions(): Set<String> = mutex.withLock { liveSessions + inFlightCommits }

    /**
     * Runs [block] (a staging/copy operation) serialized against any sweep and
     * registers [sessionId] as live. The registration intentionally OUTLIVES
     * the block — staged files stay protected until [releaseSession] — because
     * the draft sits in the staging dir between pick and Save.
     */
    suspend fun <T> withActiveSession(sessionId: String, block: suspend () -> T): T =
        mutex.withLock {
            liveSessions.add(sessionId)
            block()
        }

    /**
     * Runs [block] (files-move + row delta of a Save) serialized against any
     * sweep, protecting BOTH the staging session and the target exercise dir
     * (whose just-moved files are not row-referenced yet).
     */
    suspend fun <T> withActiveCommit(sessionId: String, exerciseId: String, block: suspend () -> T): T =
        mutex.withLock {
            liveSessions.add(sessionId)
            inFlightCommits.add(exerciseId)
            try {
                block()
            } finally {
                inFlightCommits.remove(exerciseId)
            }
        }

    /** End a session's protection (Save success or Cancel/dismiss). */
    suspend fun releaseSession(sessionId: String) {
        mutex.withLock { liveSessions.remove(sessionId) }
    }

    /**
     * Runs the cleanup snapshot+sweep in [block], serialized against every live
     * staging/commit operation and given the current live session ids.
     */
    suspend fun <T> withSweep(block: suspend (activeSessions: Set<String>) -> T): T =
        mutex.withLock { block(liveSessions + inFlightCommits) }

    companion object {
        /** Process-wide registry shared by both dialog entry points and the startup sweep. */
        val shared = ExerciseMediaSessionRegistry()
    }
}
