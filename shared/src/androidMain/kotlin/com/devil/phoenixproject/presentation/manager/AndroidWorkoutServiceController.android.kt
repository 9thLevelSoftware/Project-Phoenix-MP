package com.devil.phoenixproject.presentation.manager

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger

class AndroidWorkoutServiceController(
    context: Context,
) : WorkoutServiceController {
    private val appContext = context.applicationContext
    private val log = Logger.withTag("AndroidWorkoutServiceController")
    private val serviceClassName = "${appContext.packageName}.service.WorkoutForegroundService"

    @Volatile
    private var isRunning = false

    /** startForegroundService was accepted, and the service has not reported back yet. */
    @Volatile
    private var awaitingForeground = false

    init {
        foregroundOutcomeListener = ::onForegroundOutcome
    }

    override fun showOrUpdate(snapshot: WorkoutServiceSnapshot) {
        val intent = buildIntent(snapshot)
        try {
            if (isRunning) {
                appContext.startService(intent)
            } else {
                // Set before the call so a promote result delivered on this thread,
                // before startForegroundService returns, still counts.
                awaitingForeground = true
                ContextCompat.startForegroundService(appContext, intent)
            }
        } catch (e: Exception) {
            // A dead service makes this throw while isRunning stays true, so later
            // updates keep calling startService and never promote a new FGS.
            isRunning = false
            awaitingForeground = false
            log.e(e) { "Failed to sync workout foreground service" }
        }
    }

    override fun stop() {
        val shouldStop = isRunning || awaitingForeground
        isRunning = false
        awaitingForeground = false
        if (!shouldStop) return

        try {
            appContext.startService(
                Intent()
                    .setClassName(appContext, serviceClassName)
                    .setAction(WorkoutServiceProtocol.ACTION_STOP),
            )
        } catch (e: Exception) {
            log.e(e) { "Failed to stop workout foreground service" }
        }
    }

    /**
     * Success is the only way into the running state. A failed promote clears it
     * so the next update calls startForegroundService again.
     */
    private fun onForegroundOutcome(promoted: Boolean) {
        if (!promoted) {
            isRunning = false
            awaitingForeground = false
            return
        }
        if (!awaitingForeground) return
        awaitingForeground = false
        isRunning = true
    }

    companion object {
        @Volatile
        private var foregroundOutcomeListener: ((Boolean) -> Unit)? = null

        /** In-process promote result from the workout foreground service. No IPC. */
        fun reportForegroundOutcome(promoted: Boolean) {
            foregroundOutcomeListener?.invoke(promoted)
        }
    }

    private fun buildIntent(snapshot: WorkoutServiceSnapshot): Intent = Intent()
        .setClassName(appContext, serviceClassName)
        .setAction(WorkoutServiceProtocol.ACTION_SYNC)
        .putExtra(WorkoutServiceProtocol.EXTRA_PHASE, snapshot.phase.name)
        .putExtra(WorkoutServiceProtocol.EXTRA_WORKOUT_MODE, snapshot.workoutModeName)
        .putExtra(WorkoutServiceProtocol.EXTRA_EXERCISE_NAME, snapshot.exerciseName)
        .putExtra(WorkoutServiceProtocol.EXTRA_NEXT_EXERCISE_NAME, snapshot.nextExerciseName)
        .putExtra(WorkoutServiceProtocol.EXTRA_CURRENT_SET, snapshot.currentSet ?: -1)
        .putExtra(WorkoutServiceProtocol.EXTRA_TOTAL_SETS, snapshot.totalSets ?: -1)
        .putExtra(WorkoutServiceProtocol.EXTRA_COMPLETED_REPS, snapshot.completedReps ?: -1)
        .putExtra(WorkoutServiceProtocol.EXTRA_TARGET_REPS, snapshot.targetReps ?: -1)
        .putExtra(WorkoutServiceProtocol.EXTRA_SECONDS_REMAINING, snapshot.secondsRemaining ?: -1)
}
