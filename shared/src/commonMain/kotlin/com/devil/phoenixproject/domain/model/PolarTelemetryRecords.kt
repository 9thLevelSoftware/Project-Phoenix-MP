package com.devil.phoenixproject.domain.model

data class PolarBeatSample(
    val id: Long,
    val sessionId: String,
    val timestampMs: Long,
    val phase: String,
    val hrBpm: Long,
    val rrMs: Double,
    val rmssdMs: Double?,
    val torsoPitchDeg: Double?,
    val torsoRollDeg: Double?,
    val dynamicAccMg: Long?,
)

data class PolarEcgSegment(
    val id: Long,
    val sessionId: String,
    val sensorTimestampNs: Long,
    val wallClockTimestampMs: Long,
    val phase: String,
    val samplesUvCsv: String,
)

data class PolarMetricSample(
    val position: Double?,
    val positionB: Double?,
    val torsoPitchDeg: Double?,
    val torsoRollDeg: Double?,
)

data class PolarRecovery(
    val hrr60Bpm: Int?,
    val hrr300Bpm: Int?,
)
