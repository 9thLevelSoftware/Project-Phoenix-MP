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

    override fun showOrUpdate(snapshot: WorkoutServiceSnapshot) {
        try {
            ContextCompat.startForegroundService(appContext, buildIntent(snapshot))
        } catch (e: Exception) {
            log.e(e) { "Failed to sync workout foreground service" }
        }
    }

    override fun stop() {
        try {
            // Idle snapshots and cleanup call stop() when the service was never
            // started, and again after WorkoutForegroundService has already
            // stopSelf()'d. stopService leaves a missing service alone and
            // removes the foreground notification when the service is up.
            appContext.stopService(
                Intent().setClassName(appContext, serviceClassName),
            )
        } catch (e: Exception) {
            log.e(e) { "Failed to stop workout foreground service" }
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
