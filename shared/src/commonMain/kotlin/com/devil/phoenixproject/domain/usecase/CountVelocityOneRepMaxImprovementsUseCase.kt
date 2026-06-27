package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.VelocityOneRepMaxEntity

/** Counts cumulative velocity-1RM improvements (>= prior best x 1.025) across all exercises. */
class CountVelocityOneRepMaxImprovementsUseCase {
    operator fun invoke(passingEstimates: List<VelocityOneRepMaxEntity>): Int {
        var improvements = 0
        passingEstimates.groupBy { it.exerciseId }.forEach { (_, rows) ->
            var best: Float? = null
            rows.sortedBy { it.computedAt }.forEach { row ->
                val prior = best
                if (prior != null && row.estimatedPerCableKg >= prior * IMPROVEMENT_FACTOR) {
                    improvements++
                    best = row.estimatedPerCableKg
                } else if (prior == null || row.estimatedPerCableKg > prior) {
                    best = row.estimatedPerCableKg
                }
            }
        }
        return improvements
    }

    companion object {
        const val IMPROVEMENT_FACTOR = 1.025f
    }
}
