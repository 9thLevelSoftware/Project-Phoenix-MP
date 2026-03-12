package com.devil.phoenixproject.domain.model

/**
 * Centralized safety policy for all machine command pathways.
 *
 * Vendors can provide a custom implementation when integrating alternative hardware,
 * but all start/adjust/stop operations must pass these checks before command emission.
 */
interface SafetyPolicy {
    val minLoadKgPerCable: Float
    val maxLoadKgPerCable: Float
    val commandTimeoutMs: Long
    val emergencyStopSendsImmediateStop: Boolean
    val sessionWatchdogIntervalMs: Long
    val sessionWatchdogTimeoutMs: Long

    fun evaluateStart(params: WorkoutParameters, state: WorkoutState): SafetyDecision
    fun evaluateAdjust(currentParams: WorkoutParameters, requestedWeightKg: Float, state: WorkoutState): SafetyDecision
    fun evaluateStop(state: WorkoutState, emergency: Boolean): SafetyDecision
}

data class SafetyDecision(
    val allowed: Boolean,
    val reason: String? = null,
    val approvedWeightKg: Float? = null
)

class DefaultSafetyPolicy : SafetyPolicy {
    override val minLoadKgPerCable: Float = 0f
    override val maxLoadKgPerCable: Float = 110f
    override val commandTimeoutMs: Long = 2_000L
    override val emergencyStopSendsImmediateStop: Boolean = true
    override val sessionWatchdogIntervalMs: Long = 1_000L
    override val sessionWatchdogTimeoutMs: Long = 8_000L

    override fun evaluateStart(params: WorkoutParameters, state: WorkoutState): SafetyDecision {
        if (state !is WorkoutState.Initializing && state !is WorkoutState.Idle && state !is WorkoutState.SetReady) {
            return SafetyDecision(false, "Start denied: workout state=$state")
        }

        if (params.weightPerCableKg !in minLoadKgPerCable..maxLoadKgPerCable) {
            return SafetyDecision(false, "Start denied: requested load ${params.weightPerCableKg}kg is outside policy limits")
        }

        return SafetyDecision(true)
    }

    override fun evaluateAdjust(
        currentParams: WorkoutParameters,
        requestedWeightKg: Float,
        state: WorkoutState
    ): SafetyDecision {
        val allowedStates = state is WorkoutState.Active ||
            state is WorkoutState.Resting ||
            state is WorkoutState.SetSummary ||
            state is WorkoutState.Idle

        if (!allowedStates) {
            return SafetyDecision(false, "Adjust denied: workout state=$state")
        }

        return SafetyDecision(
            allowed = true,
            approvedWeightKg = requestedWeightKg.coerceIn(minLoadKgPerCable, maxLoadKgPerCable)
        )
    }

    override fun evaluateStop(state: WorkoutState, emergency: Boolean): SafetyDecision {
        if (state is WorkoutState.Idle || state is WorkoutState.Completed) {
            return SafetyDecision(false, "Stop ignored: state=$state")
        }

        if (emergency && !emergencyStopSendsImmediateStop) {
            return SafetyDecision(false, "Emergency stop denied by policy")
        }

        return SafetyDecision(true)
    }
}
