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
}
