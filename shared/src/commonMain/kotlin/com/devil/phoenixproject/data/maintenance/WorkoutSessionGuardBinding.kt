package com.devil.phoenixproject.data.maintenance

import kotlin.concurrent.Volatile

/**
 * Process-local binding for the G0 session guard (I3, issue #1425). `WorkoutCoordinator` is
 * owned by `DefaultWorkoutSessionManager`, which `MainViewModel` constructs outside Koin, so
 * the live probe (`coordinator::isInWorkoutSessionNow`) is registered at startup and resolved
 * lazily by the compaction service. The default fails closed (true = a workout may be active):
 * until a probe is bound, cleanup is conservatively blocked rather than risking a mid-workout
 * delete.
 */
object WorkoutSessionGuardBinding {
    @Volatile
    var isInWorkoutSession: () -> Boolean = { true }

    fun bind(probe: () -> Boolean) {
        isInWorkoutSession = probe
    }
}
