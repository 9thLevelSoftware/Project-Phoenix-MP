package com.devil.phoenixproject.domain.model

/**
 * Verdict for a single rep comparison against a ghost session.
 * AHEAD = faster than ghost, BEHIND = slower, TIED = within tolerance,
 * BEYOND = current rep exceeds ghost session rep count (no ghost data to compare).
 */
enum class GhostVerdict { AHEAD, BEHIND, TIED, BEYOND }

/**
 * Full ghost session data including per-rep velocities for live comparison.
 * Loaded from DB once at workout start, held in memory for the duration of the set.
 */
data class GhostSession(
    val sessionId: String,
    val exerciseName: String,
    val weightPerCableKg: Float,
    val workingReps: Int,
    val avgMcvMmS: Float,
    val repVelocities: List<Float>,       // Indexed by repNumber-1
    val repPeakPositions: List<Float>      // Indexed by repNumber-1
)

/**
 * Result of comparing a single rep against the ghost session's corresponding rep.
 */
data class GhostRepComparison(
    val repNumber: Int,
    val currentMcvMmS: Float,
    val ghostMcvMmS: Float,
    val deltaMcvMmS: Float,   // Positive = faster than ghost
    val verdict: GhostVerdict
)

/**
 * Aggregate summary of all rep comparisons within a set.
 */
data class GhostSetSummary(
    val totalDeltaMcvMmS: Float,
    val avgDeltaMcvMmS: Float,
    val repsCompared: Int,
    val repsAhead: Int,
    val repsBehind: Int,
    val repsBeyondGhost: Int,
    val overallVerdict: GhostVerdict
)

/**
 * Lightweight DB-result type for ghost session candidate selection.
 * Contains only the columns needed for matching logic, before loading full rep data.
 */
data class GhostSessionCandidate(
    val id: String,
    val exerciseName: String?,
    val weightPerCableKg: Float,
    val workingReps: Int,
    val avgMcvMmS: Float
)
