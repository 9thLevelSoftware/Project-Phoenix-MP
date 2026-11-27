package com.devil.phoenixproject.data.maintenance

import com.devil.phoenixproject.database.MetricSample
import com.devil.phoenixproject.domain.model.WorkoutMetric

/**
 * Maps persisted [MetricSample] rows to archival [WorkoutMetric]s for the issue #1425
 * planner (I1). There is no existing MetricSample -> WorkoutMetric mapper in the repo, and
 * `position`/`positionB` are nullable while [WorkoutMetric] positions are not. A NULL
 * position anywhere makes the stationary baseline untrustworthy (a run of NULLs coalesced to
 * 0 would look like a perfect stationary prefix), so the whole set must be preserved: return
 * null and let the planner record `NULL_POSITION`. Never coalesce NULL to 0f.
 */
internal fun MetricSample.toArchivalWorkoutMetricOrNull(): WorkoutMetric? {
    val archivalPositionA = position ?: return null
    val archivalPositionB = positionB ?: return null
    return WorkoutMetric(
        timestamp = timestamp,
        // The policy ignores load; a missing load is only payload noise.
        loadA = load?.toFloat() ?: 0f,
        loadB = loadB?.toFloat() ?: 0f,
        positionA = archivalPositionA.toFloat(),
        positionB = archivalPositionB.toFloat(),
        velocityA = velocity ?: 0.0,
        velocityB = velocityB ?: 0.0,
        status = status.toInt(),
        heartRateBpm = heartRateBpm?.toInt(),
        rrIntervalMs = rrIntervalMs?.toFloat(),
        torsoPitchDeg = torsoPitchDeg?.toFloat(),
        torsoRollDeg = torsoRollDeg?.toFloat(),
        dynamicAccMg = dynamicAccMg?.toInt(),
        chestAccXMg = chestAccXMg?.toInt(),
        chestAccYMg = chestAccYMg?.toInt(),
        chestAccZMg = chestAccZMg?.toInt(),
    )
}
