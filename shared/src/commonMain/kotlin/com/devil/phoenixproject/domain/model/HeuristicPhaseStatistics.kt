package com.devil.phoenixproject.domain.model

data class HeuristicPhaseStatistics(
    val kgAvg: Float,
    val kgMax: Float,
    val velAvg: Float,
    val velMax: Float,
    val wattAvg: Float,
    val wattMax: Float,
    // Deviation fields (Var = E[X^2] - E[X]^2) for statistical tracking (Phase 3f)
    val kgDeviation: Float = 0f,
    val velDeviation: Float = 0f,
    val wattDeviation: Float = 0f
)
