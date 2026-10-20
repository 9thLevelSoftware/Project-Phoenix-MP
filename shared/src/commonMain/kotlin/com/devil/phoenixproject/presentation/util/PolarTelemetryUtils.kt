package com.devil.phoenixproject.presentation.util

import com.devil.phoenixproject.domain.model.PolarRrBeat
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Computes rolling RMSSD (Root Mean Square of Successive Differences) in milliseconds
 * over a sliding time window (default 60 seconds, min 30 seconds recommended).
 *
 * Successive differences are computed on adjacent [PolarRrBeat] entries that fall within
 * [currentTimeMs - windowMs, currentTimeMs]. Beats separated by excessive time gaps (> 3500ms)
 * or with non-physiological intervals (< 250ms or > 2500ms) are excluded from difference pairs
 * to prevent dropped-beat artifact inflation.
 *
 * @param beats Ordered list of [PolarRrBeat] events.
 * @param windowMs Time window in milliseconds (default 60,000ms = 60s).
 * @param currentTimeMs Reference timestamp for the window edge.
 * @return RMSSD in milliseconds, or null if fewer than 2 consecutive valid beats are available.
 */
fun computeRollingRmssd(
    beats: List<PolarRrBeat>,
    windowMs: Long = 60_000L,
    currentTimeMs: Long,
): Double? {
    if (beats.size < 2) return null

    val windowStart = currentTimeMs - windowMs
    val windowBeats = beats.filter { it.timestampMs in windowStart..currentTimeMs }
    if (windowBeats.size < 2) return null

    var sumSquaredDiffs = 0.0
    var count = 0

    for (i in 0 until windowBeats.size - 1) {
        val b1 = windowBeats[i]
        val b2 = windowBeats[i + 1]

        // Validate individual RR intervals (250ms = 240 BPM, 2500ms = 24 BPM)
        if (b1.rrIntervalMs !in 250.0..2500.0 || b2.rrIntervalMs !in 250.0..2500.0) {
            continue
        }

        // Validate inter-beat time gap (beats should be contiguous within reasonable tolerance)
        val timeGap = b2.timestampMs - b1.timestampMs
        if (timeGap !in 200..3500) {
            continue
        }

        val diff = b2.rrIntervalMs - b1.rrIntervalMs
        sumSquaredDiffs += diff * diff
        count++
    }

    if (count == 0) return null
    return sqrt(sumSquaredDiffs / count)
}

/**
 * Format milliseconds to a clean integer string representation.
 */
fun formatRrMs(rrMs: Double?): String = rrMs?.let { "${it.roundToInt()} ms" } ?: "—"

/**
 * Format RMSSD to a clean integer string representation.
 */
fun formatRmssdMs(rmssd: Double?): String = rmssd?.let { "${it.roundToInt()} ms" } ?: "—"
