package com.devil.phoenixproject.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Semantic colors for data visualization (charts, graphs).
 * Designed to be colorblind-safe with distinct luminance values.
 * These do NOT change with light/dark mode.
 */
object DataColors {
    /** Training volume trends - Blue */
    val Volume = Color(0xFF3B82F6)

    /** Intensity/effort metrics - Yellow (distinct from SignalWarning amber) */
    val Intensity = Color(0xFFEAB308)

    /** Heart rate / cardio data - Rose (distinct from SignalError red) */
    val HeartRate = Color(0xFFF43F5E)

    /** Time-based metrics - Emerald */
    val Duration = Color(0xFF10B981)

    /** Strength PRs / 1RM estimates - Violet */
    val OneRepMax = Color(0xFF8B5CF6)

    /** Power output / wattage - Cyan */
    val Power = Color(0xFF06B6D4)

    // Workout metrics chart colors (for WorkoutMetricsDetailChart)

    /** Load A (left cable) - Indigo-blue (distinct from Volume blue) */
    val LoadA = Color(0xFF2563EB)

    /** Load B (right cable) - Orange */
    val LoadB = Color(0xFFF97316)

    /** Position A (left cable position) - Green */
    val PositionA = Color(0xFF22C55E)

    /** Position B (right cable position) - Purple */
    val PositionB = Color(0xFFA855F7)
}
