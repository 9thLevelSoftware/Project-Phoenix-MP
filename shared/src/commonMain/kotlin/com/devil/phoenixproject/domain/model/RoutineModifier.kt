package com.devil.phoenixproject.domain.model

/** One-shot launch-time routine modifier options. */
enum class RoutineModifierType {
    ACTIVE_RECOVERY,
    HEAVY_DELOAD,
}

/**
 * Modifier selected immediately before starting a routine.
 *
 * This model is intentionally not persisted on [Routine]; it describes a temporary
 * transform applied to the resolved routine loaded into the current workout flow.
 */
data class AppliedRoutineModifier(
    val type: RoutineModifierType,
    val percent: Int,
) {
    init {
        require(percent in VALID_PERCENT_RANGE) { "Routine modifier percent must be between 1 and 100" }
    }

    companion object {
        val selectablePercents = listOf(50, 55, 60)
        private val VALID_PERCENT_RANGE = 1..100
    }
}
