package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.HrmBeatSample
import com.devil.phoenixproject.domain.model.PolarEcgSegment
import com.devil.phoenixproject.domain.model.PolarRecovery

interface HrmTelemetryRepository {
    suspend fun saveTelemetry(
        sessionId: String,
        beats: List<HrmBeatSample>,
        ecgs: List<PolarEcgSegment>,
        recovery: PolarRecovery? = null,
    )

    suspend fun pruneRawEcg()
}
