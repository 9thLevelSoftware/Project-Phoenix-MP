package com.devil.phoenixproject.domain.premium

import com.devil.phoenixproject.domain.model.RepMetricData
import com.devil.phoenixproject.domain.model.RepQualityScore
import com.devil.phoenixproject.domain.model.QualityTrend
import com.devil.phoenixproject.domain.model.SetQualitySummary

/**
 * Scores each rep 0-100 based on four weighted components.
 *
 * Stateful: accumulates per-set baselines for ROM and velocity consistency.
 * Call [reset] between sets.
 */
class RepQualityScorer {

    fun scoreRep(repData: RepMetricData): RepQualityScore {
        TODO("Not yet implemented")
    }

    fun getSetSummary(): SetQualitySummary {
        TODO("Not yet implemented")
    }

    fun getTrend(): QualityTrend {
        TODO("Not yet implemented")
    }

    fun reset() {
        TODO("Not yet implemented")
    }
}
