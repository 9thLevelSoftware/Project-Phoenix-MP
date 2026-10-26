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
}
