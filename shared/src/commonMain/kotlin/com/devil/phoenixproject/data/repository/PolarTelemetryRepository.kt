package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.PolarBeatSample
import com.devil.phoenixproject.domain.model.PolarEcgSegment
import com.devil.phoenixproject.domain.model.PolarRecovery

interface PolarTelemetryRepository {
    suspend fun saveTelemetry(
        sessionId: String,
        beats: List<PolarBeatSample>,
        ecgs: List<PolarEcgSegment>,
        recovery: PolarRecovery? = null,
    )

    suspend fun pruneRawEcg()
}
