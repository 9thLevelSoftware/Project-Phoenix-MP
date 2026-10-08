package com.devil.phoenixproject.ui.theme

import androidx.compose.ui.graphics.Color

// ==============================================================================
// THEME: PHOENIX RISING
// Concept: High energy activity (Fire) grounded by solid structure (Ash/Slate)
// ==============================================================================

// --- CORE BRAND COLORS ---
// Primary: "Phoenix Flame" - Used for FABs, Main Actions, Active States
internal val PhoenixOrangeLight = Color(0xFFE65100) // Deep energetic orange (light mode)
private val PhoenixOrangeDark = Color(0xFFFF9149) // Vibrant orange (dark mode) - was too pink/salmon

// Fire gradient colors for Just Lift button
val FlameOrange = Color(0xFFFF6B00) // Core flame orange
val FlameYellow = Color(0xFFFFAB00) // Inner flame yellow
val FlameRed = Color(0xFFE64A19) // Outer flame red-orange

// Secondary: "Ember Gold" - Used for Secondary Actions, Toggles
internal val EmberYellowLight = Color(0xFF6A5F00) // Olive gold (light mode)
private val EmberYellowDark = Color(0xFFE2C446) // Bright gold (dark mode)

// Tertiary: "Cooling Ash" - Used for accents to balance the heat
internal val AshBlueLight = Color(0xFF006684) // Deep teal (light mode)
internal val AshBlueDark = Color(0xFF6ED2FF) // Electric cyan (dark mode)

// --- SLATE NEUTRALS (Tinted Blue-Grey) ---
// 2025 Trend: Tinted neutrals instead of pure grey
internal val Slate950 = Color(0xFF020617) // Almost black, blue-tinted (OLED friendly)
val Slate900 = Color(0xFF0F172A) // Deep background
internal val Slate800 = Color(0xFF1E293B) // Card background
internal val Slate700 = Color(0xFF334155) // Border/Divider
internal val Slate400 = Color(0xFF94A3B8) // Subtext
internal val Slate200 = Color(0xFFE2E8F0) // Light mode surfaces
val Slate50 = Color(0xFFF8FAFC) // Light mode background

// --- BRAND ACCENT ---
val ForgeGreen = Color(0xFF10B981) // Phoenix Forge Green — brand success/calibration tint

// --- SIGNAL COLORS (Status) ---
// Intentionally NOT orange to avoid confusion with primary
internal val SignalSuccess = Color(0xFF22C55E) // Green
internal val SignalError = Color(0xFFEF4444) // Red
internal val SignalWarning = Color(0xFFF59E0B) // Amber

// --- MATERIAL 3 DARK MODE TOKENS ---
internal val Primary80 = PhoenixOrangeDark
internal val Primary20 = Color(0xFF4C1400)
internal val PrimaryContainerDark = Color(0xFF702300)
internal val OnPrimaryContainerDark = Color(0xFFFFDBCF)

internal val Secondary80 = EmberYellowDark
internal val Secondary20 = Color(0xFF373100)
internal val SecondaryContainerDark = Color(0xFF4F4700)
internal val OnSecondaryContainerDark = Color(0xFFFFE06F)

internal val Tertiary80 = AshBlueDark
internal val Tertiary20 = Color(0xFF003546)

// --- MATERIAL 3 LIGHT MODE TOKENS ---
internal val PrimaryContainerLight = Color(0xFFFFDBCF)
internal val OnPrimaryContainerLight = Color(0xFF380D00)

// --- SURFACE CONTAINERS (Dark Mode) ---
// Using Slate scale for depth without opacity hacks
internal val SurfaceDimDark = Slate950
internal val SurfaceContainerDark = Slate900
internal val SurfaceContainerHighDark = Slate800
internal val SurfaceContainerHighestDark = Slate700
internal val OnSurfaceDark = Slate200
internal val OnSurfaceVariantDark = Slate400

// --- SURFACE CONTAINERS (Light Mode) ---
internal val SurfaceDimLight = Color(0xFFDED8E1)
internal val SurfaceBrightLight = Color(0xFFFDF8FF)
internal val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
internal val SurfaceContainerLowLight = Color(0xFFF7F2FA)
internal val SurfaceContainerLight = Slate50
internal val SurfaceContainerHighLight = Slate200
internal val SurfaceContainerHighestLight = Color(0xFFE6E0E9)
