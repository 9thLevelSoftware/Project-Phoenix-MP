package com.devil.phoenixproject.ui.theme

import androidx.compose.ui.graphics.Color

// ==============================================================================
// THEME: PHOENIX RISING
// Concept: High energy activity (Fire) grounded by solid structure (Ash/Slate)
// ==============================================================================

// --- CORE BRAND COLORS ---
// Primary: "Phoenix Flame" - Used for FABs, Main Actions, Active States
val PhoenixOrangeLight = Color(0xFFE65100) // Deep energetic orange (light mode)
val PhoenixOrangeDark = Color(0xFFFF9149) // Vibrant orange (dark mode) - was too pink/salmon

// Fire gradient colors for Just Lift button
val FlameOrange = Color(0xFFFF6B00) // Core flame orange
val FlameYellow = Color(0xFFFFAB00) // Inner flame yellow
val FlameRed = Color(0xFFE64A19) // Outer flame red-orange

// Secondary: "Ember Gold" - Used for Secondary Actions, Toggles
val EmberYellowLight = Color(0xFF6A5F00) // Olive gold (light mode)
val EmberYellowDark = Color(0xFFE2C446) // Bright gold (dark mode)

// Tertiary: "Cooling Ash" - Used for accents to balance the heat
val AshBlueLight = Color(0xFF006684) // Deep teal (light mode)
val AshBlueDark = Color(0xFF6ED2FF) // Electric cyan (dark mode)

// --- SLATE NEUTRALS (Tinted Blue-Grey) ---
// 2025 Trend: Tinted neutrals instead of pure grey
val Slate950 = Color(0xFF020617) // Almost black, blue-tinted (OLED friendly)
val Slate900 = Color(0xFF0F172A) // Deep background
val Slate800 = Color(0xFF1E293B) // Card background
val Slate700 = Color(0xFF334155) // Border/Divider
val Slate400 = Color(0xFF94A3B8) // Subtext
val Slate200 = Color(0xFFE2E8F0) // Light mode surfaces
val Slate50 = Color(0xFFF8FAFC) // Light mode background

// --- SIGNAL COLORS (Status) ---
// Intentionally NOT orange to avoid confusion with primary
val SignalSuccess = Color(0xFF22C55E) // Green
val SignalError = Color(0xFFEF4444) // Red
val SignalWarning = Color(0xFFF59E0B) // Amber

// --- MATERIAL 3 DARK MODE TOKENS ---
val Primary80 = PhoenixOrangeDark
val Primary20 = Color(0xFF4C1400)
val PrimaryContainerDark = Color(0xFF702300)
val OnPrimaryContainerDark = Color(0xFFFFDBCF)

val Secondary80 = EmberYellowDark
val Secondary20 = Color(0xFF373100)
val SecondaryContainerDark = Color(0xFF4F4700)
val OnSecondaryContainerDark = Color(0xFFFFE06F)

val Tertiary80 = AshBlueDark
val Tertiary20 = Color(0xFF003546)

// --- MATERIAL 3 LIGHT MODE TOKENS ---
val PrimaryContainerLight = Color(0xFFFFDBCF)
val OnPrimaryContainerLight = Color(0xFF380D00)

// --- SURFACE CONTAINERS (Dark Mode) ---
// Using Slate scale for depth without opacity hacks
val SurfaceDimDark = Slate950
val SurfaceContainerDark = Slate900
val SurfaceContainerHighDark = Slate800
val SurfaceContainerHighestDark = Slate700
val OnSurfaceDark = Slate200
val OnSurfaceVariantDark = Slate400

// --- SURFACE CONTAINERS (Light Mode) ---
val SurfaceDimLight = Color(0xFFDED8E1)
val SurfaceBrightLight = Color(0xFFFDF8FF)
val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
val SurfaceContainerLowLight = Color(0xFFF7F2FA)
val SurfaceContainerLight = Slate50
val SurfaceContainerHighLight = Slate200
val SurfaceContainerHighestLight = Color(0xFFE6E0E9)
