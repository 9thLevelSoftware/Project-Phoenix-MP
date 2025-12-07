package com.devil.phoenixproject.ui.theme

import androidx.compose.ui.graphics.Color

// Light Theme Colors
val ColorOnLightBackground = Color(0xFF0F172A)   // Slate-900 like text
val ColorLightSurface = Color(0xFFFFFFFF)        // White surface
val ColorOnLightSurface = Color(0xFF111827)      // Dark text on surface
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

// TopAppBar Colors (darker for better contrast)
val TopAppBarDark = Color(0xFF1A0E26)           // Very dark purple for dark mode header
val TopAppBarLight = Color(0xFF4A2F8A)          // Darker purple for light mode header

// Text Colors
val TextPrimary = Color(0xFFFFFFFF)             // Pure white text
val TextSecondary = Color(0xFFE0E0E0)           // Light grey text
val TextTertiary = Color(0xFFB0B0B0)            // Medium grey text
val TextDisabled = Color(0xFF707070)            // Disabled text

// Status Colors
val ErrorRed = Color(0xFFF44336)                // Error states

// 2025 Material Design Expressive Surface Container Roles (Dark Mode)
// These create depth through tonal variation rather than shadows
val SurfaceDimDark = Color(0xFF141218)
val SurfaceBrightDark = Color(0xFF3B383E)
val SurfaceContainerLowestDark = Color(0xFF0F0D13)
val SurfaceContainerLowDark = Color(0xFF1D1B20)
val SurfaceContainerDark = Color(0xFF211F26)        // Main background for screens
val SurfaceContainerHighDark = Color(0xFF2B2930)    // Card background
val SurfaceContainerHighestDark = Color(0xFF36343B) // Modal/Float background

// 2025 Material Design Expressive Surface Container Roles (Light Mode)
val SurfaceDimLight = Color(0xFFDED8E1)
val SurfaceBrightLight = Color(0xFFFDF8FF)
val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
val SurfaceContainerLowLight = Color(0xFFF7F2FA)
val SurfaceContainerLight = Color(0xFFF3EDF7)       // Main background for screens
val SurfaceContainerHighLight = Color(0xFFECE6F0)   // Card background
val SurfaceContainerHighestLight = Color(0xFFE6E0E9) // Modal/Float background
