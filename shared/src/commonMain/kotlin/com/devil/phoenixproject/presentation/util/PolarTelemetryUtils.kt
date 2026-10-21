package com.devil.phoenixproject.presentation.util

import com.devil.phoenixproject.domain.model.PolarRrBeat
import com.devil.phoenixproject.util.PolarTelemetryUtils

/**
 * Computes rolling RMSSD (Root Mean Square of Successive Differences) in milliseconds
 * over a sliding time window (default 60 seconds).
 * Filters beats by the sliding window and delegates to canonical [PolarTelemetryUtils.calculateRmssdFromBeats].
 */
fun computeRollingRmssd(
    beats: List<PolarRrBeat>,
    windowMs: Long = 60_000L,
    currentTimeMs: Long,
): Double? {
    if (beats.size < 2) return null
    val windowStart = currentTimeMs - windowMs
    val windowBeats = beats.filter { it.timestampMs in windowStart..currentTimeMs }
    return PolarTelemetryUtils.calculateRmssdFromBeats(windowBeats)
}

/**
 * Format milliseconds to a clean integer string representation.
 */
fun formatRrMs(rrMs: Double?): String = PolarTelemetryUtils.formatRrMs(rrMs)

/**
 * Format RMSSD to a clean integer string representation.
 */
fun formatRmssdMs(rmssd: Double?): String = PolarTelemetryUtils.formatRmssdMs(rmssd)

