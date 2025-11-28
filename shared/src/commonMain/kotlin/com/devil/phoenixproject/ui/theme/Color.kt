package com.devil.phoenixproject.ui.theme

import androidx.compose.ui.graphics.Color

// Background Colors
val BackgroundBlack = Color(0xFF000000)          // Pure black background
val BackgroundDarkGrey = Color(0xFF121212)       // Dark grey surface
val SurfaceDarkGrey = Color(0xFF1E1E1E)         // Elevated surfaces
val CardBackground = Color(0xFF252525)           // Card backgrounds

// Light Theme Colors
val ColorLightBackground = Color(0xFFF8F9FB)     // Soft light background
val ColorOnLightBackground = Color(0xFF0F172A)   // Slate-900 like text
val ColorLightSurface = Color(0xFFFFFFFF)        // White surface
val ColorOnLightSurface = Color(0xFF111827)      // Dark text on surface
val ColorLightSurfaceVariant = Color(0xFFF3F4F6) // Light gray surface variant
val ColorOnLightSurfaceVariant = Color(0xFF6B7280) // Gray-500 text

// Purple Accent Colors - Material 3 Expressive
// Dark Mode: Desaturated (~30% less saturated) to reduce eye strain and "vibration" effect
// Light Mode: Vibrant/saturated for energy and branding
val PrimaryPurpleDark = Color(0xFF8B5CF6)       // Desaturated purple for dark mode (was #9333EA)
val SecondaryPurpleDark = Color(0xFF7C3AED)     // Desaturated deeper purple for dark mode
val TertiaryPurpleDark = Color(0xFFA78BFA)      // Soft purple for dark mode highlights
val PurpleAccentDark = Color(0xFF8B5CF6)        // Desaturated accent for dark mode

// Light mode uses blue/teal (popular fitness app colors, great contrast)
val PrimaryBlueLight = Color(0xFF06B6D4)        // Teal/cyan for light mode - modern, fresh
val SecondaryBlueLight = Color(0xFF0891B2)      // Deeper teal for light mode
val TertiaryBlueLight = Color(0xFF22D3EE)       // Bright cyan for light mode highlights
val BlueAccentLight = Color(0xFF06B6D4)         // Teal accent for light mode

// TopAppBar Colors (darker for better contrast)
val TopAppBarDark = Color(0xFF1A0E26)           // Very dark purple for dark mode header
val TopAppBarLight = Color(0xFF4A2F8A)          // Darker purple for light mode header

// Text Colors
val TextPrimary = Color(0xFFFFFFFF)             // Pure white text
val TextSecondary = Color(0xFFE0E0E0)           // Light grey text
val TextTertiary = Color(0xFFB0B0B0)            // Medium grey text
val TextDisabled = Color(0xFF707070)            // Disabled text

// Status Colors
val SuccessGreen = Color(0xFF4CAF50)            // Success states
val ErrorRed = Color(0xFFF44336)                // Error states
val WarningOrange = Color(0xFFFF9800)           // Warning states
val InfoBlue = Color(0xFF2196F3)                // Info states

// Legacy colors (kept for compatibility) - default to dark mode variants
val Purple80 = PrimaryPurpleDark
val PurpleGrey80 = SecondaryPurpleDark
val Pink80 = TertiaryPurpleDark
val Purple40 = PurpleAccentDark
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)