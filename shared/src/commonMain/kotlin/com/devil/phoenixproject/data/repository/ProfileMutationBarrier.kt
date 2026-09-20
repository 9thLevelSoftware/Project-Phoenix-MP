package com.devil.phoenixproject.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Coordinates profile ownership mutations with sync. */
class ProfileMutationBarrier {
    private val mutex = Mutex()

    suspend fun <T> withExclusive(block: suspend () -> T): T = mutex.withLock { block() }
}

data class ProfileRecoveryActivity(
    val workoutActive: Boolean = false,
    val assessmentActive: Boolean = false,
    val recoveryReserved: Boolean = false,
) {
    val isBusy: Boolean get() = workoutActive || assessmentActive
}

/**
 * Process-local activity signal used for the pre-lock and post-lock recovery checks.
 * The profile mutation barrier supplies the serialization boundary; this class only
 * records whether a user-owned activity is in progress.
 */
class ProfileRecoveryActivityTracker {
    private val _activity = MutableStateFlow(ProfileRecoveryActivity())
    val activity: StateFlow<ProfileRecoveryActivity> = _activity.asStateFlow()

    fun setWorkoutActive(active: Boolean): Boolean = updateActivity { current ->
        if (active && current.recoveryReserved) null else current.copy(workoutActive = active)
    }

    fun setAssessmentActive(active: Boolean): Boolean = updateActivity { current ->
        if (active && current.recoveryReserved) null else current.copy(assessmentActive = active)
    }

    /** Atomically excludes new workout/assessment starts while recovery owns its reservation. */
    fun tryReserveRecovery(): Boolean = updateActivity { current ->
        if (current.isBusy || current.recoveryReserved) null else current.copy(recoveryReserved = true)
    }

    fun releaseRecoveryReservation() {
        _activity.update { it.copy(recoveryReserved = false) }
    }

    fun isBusy(): Boolean = _activity.value.isBusy

    private inline fun updateActivity(
        transform: (ProfileRecoveryActivity) -> ProfileRecoveryActivity?,
    ): Boolean {
        while (true) {
            val current = _activity.value
            val updated = transform(current) ?: return false
            if (_activity.compareAndSet(current, updated)) return true
        }
    }
}
