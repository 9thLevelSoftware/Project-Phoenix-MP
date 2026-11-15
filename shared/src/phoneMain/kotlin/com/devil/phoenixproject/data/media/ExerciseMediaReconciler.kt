package com.devil.phoenixproject.data.media

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ExerciseRepository

private val log = Logger.withTag("ExerciseMediaReconciler")

/**
 * Phone-only, reference-aware startup reconciliation for exercise media
 * (issue #1358, SD8).
 *
 * Called from the existing startup cleanup effect in `App.kt` (next to
 * `deleteDatabaseExportArchive`) after the DB is available. There is NO
 * commonMain sweep and NO tvOS stub — tvOS has no media code at all.
 *
 * Never throws, never blocks startup (all work is best-effort and silent).
 */
class ExerciseMediaReconciler(
    private val repository: ExerciseRepository,
    private val mediaStore: ExerciseMediaStore,
) {
    /**
     * Runs the DB snapshot AND the sweep serialized against every live
     * import/commit operation (merge-gate finding B), receiving the REAL active
     * session ids (staging sessions plus in-flight commit exercise ids) from
     * [sessionRegistry] — never an empty hand-supplied set.
     */
    suspend fun reconcile(
        sessionRegistry: ExerciseMediaSessionRegistry = ExerciseMediaSessionRegistry.shared,
    ) {
        try {
            sessionRegistry.withSweep { activeSessions ->
                val liveExerciseIds = repository.getAllExerciseIds().toSet()
                val referencedUrls = repository.getAllImageUrls().toSet()
                mediaStore.reconcile(
                    liveExerciseIds = liveExerciseIds,
                    referencedUrls = referencedUrls,
                    activeSessions = activeSessions,
                )
            }
        } catch (e: Exception) {
            log.w(e) { "Startup media reconcile failed (ignored)" }
        }
    }
}
