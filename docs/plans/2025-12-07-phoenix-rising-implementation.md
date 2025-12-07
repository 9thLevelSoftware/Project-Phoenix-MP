# Phoenix Rising Theme Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the purple/teal theme with Phoenix Rising (Orange/Slate/Teal) color system.

**Architecture:** Update Color.kt with new palette, update Theme.kt color scheme mappings, add DataColors object for semantic chart colors, clean up Android-specific Theme.kt.

**Tech Stack:** Compose Multiplatform, Material 3, Kotlin

---

## Task 1: Add DataColors Object

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/DataColors.kt`

**Step 1: Create DataColors.kt with semantic chart colors**

```kotlin
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

    /** Intensity/effort metrics - Amber */
    val Intensity = Color(0xFFF59E0B)

    /** Heart rate / cardio data - Red (use with icons for accessibility) */
    val HeartRate = Color(0xFFEF4444)

    /** Time-based metrics - Emerald */
    val Duration = Color(0xFF10B981)

    /** Strength PRs / 1RM estimates - Violet */
    val OneRepMax = Color(0xFF8B5CF6)

    /** Power output / wattage - Cyan */
    val Power = Color(0xFF06B6D4)
}
```

**Step 2: Verify file compiles**

Run: `cd .worktrees/phoenix-rising-theme && ./gradlew :shared:compileKotlinMetadata --quiet`
Expected: No errors

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/DataColors.kt
git commit -m "feat(theme): add DataColors object for semantic chart colors"
```

---

## Task 2: Update Color.kt with Phoenix Palette

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/Color.kt`

**Step 1: Replace entire Color.kt content**

```kotlin
package com.devil.phoenixproject.ui.theme

import androidx.compose.ui.graphics.Color

// ==============================================================================
// THEME: PHOENIX RISING
// Concept: High energy activity (Fire) grounded by solid structure (Ash/Slate)
// ==============================================================================

// --- CORE BRAND COLORS ---
// Primary: "Phoenix Flame" - Used for FABs, Main Actions, Active States
val PhoenixOrangeLight = Color(0xFFD94600)  // Deep energetic orange (light mode)
val PhoenixOrangeDark = Color(0xFFFFB590)   // Soft peach-orange (dark mode)

// Secondary: "Ember Gold" - Used for Secondary Actions, Toggles
val EmberYellowLight = Color(0xFF6A5F00)    // Olive gold (light mode)
val EmberYellowDark = Color(0xFFE2C446)     // Bright gold (dark mode)

// Tertiary: "Cooling Ash" - Used for accents to balance the heat
val AshBlueLight = Color(0xFF006684)        // Deep teal (light mode)
val AshBlueDark = Color(0xFF6ED2FF)         // Electric cyan (dark mode)

// --- SLATE NEUTRALS (Tinted Blue-Grey) ---
// 2025 Trend: Tinted neutrals instead of pure grey
val Slate950 = Color(0xFF020617)  // Almost black, blue-tinted (OLED friendly)
val Slate900 = Color(0xFF0F172A)  // Deep background
val Slate800 = Color(0xFF1E293B)  // Card background
val Slate700 = Color(0xFF334155)  // Border/Divider
val Slate400 = Color(0xFF94A3B8)  // Subtext
val Slate200 = Color(0xFFE2E8F0)  // Light mode surfaces
val Slate50 = Color(0xFFF8FAFC)   // Light mode background

// --- SIGNAL COLORS (Status) ---
// Intentionally NOT orange to avoid confusion with primary
val SignalSuccess = Color(0xFF22C55E)  // Green
val SignalError = Color(0xFFEF4444)    // Red
val SignalWarning = Color(0xFFF59E0B)  // Amber

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
```

**Step 2: Verify file compiles**

Run: `cd .worktrees/phoenix-rising-theme && ./gradlew :shared:compileKotlinMetadata --quiet`
Expected: No errors (may have unused variable warnings, that's OK)

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/Color.kt
git commit -m "feat(theme): replace purple/teal palette with Phoenix Rising colors"
```

---

## Task 3: Update Theme.kt Color Scheme Mappings

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/Theme.kt`

**Step 1: Replace DarkColorScheme**

Replace the existing `DarkColorScheme` definition (lines 11-49) with:

```kotlin
private val DarkColorScheme = darkColorScheme(
    // Primary (Orange)
    primary = Primary80,
    onPrimary = Primary20,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,

    // Secondary (Gold)
    secondary = Secondary80,
    onSecondary = Secondary20,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,

    // Tertiary (Teal - cool accent)
    tertiary = Tertiary80,
    onTertiary = Tertiary20,
    tertiaryContainer = AshBlueLight,
    onTertiaryContainer = Color.White,

    // Backgrounds & Surfaces (Slate scale)
    background = SurfaceContainerDark,
    onBackground = OnSurfaceDark,

    surface = SurfaceContainerDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceContainerHighDark,
    onSurfaceVariant = OnSurfaceVariantDark,

    // Surface container roles
    surfaceDim = SurfaceDimDark,
    surfaceBright = SurfaceContainerHighestDark,
    surfaceContainerLowest = Slate950,
    surfaceContainerLow = Slate900,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,

    // Status
    error = SignalError,
    onError = Color.White,

    outline = Slate700,
    outlineVariant = Slate400
)
```

**Step 2: Replace LightColorScheme**

Replace the existing `LightColorScheme` definition (lines 51-89) with:

```kotlin
private val LightColorScheme = lightColorScheme(
    // Primary (Orange)
    primary = PhoenixOrangeLight,
    onPrimary = Color.White,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,

    // Secondary (Gold)
    secondary = EmberYellowLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE06F).copy(alpha = 0.3f),
    onSecondaryContainer = Secondary20,

    // Tertiary (Teal)
    tertiary = AshBlueLight,
    onTertiary = Color.White,
    tertiaryContainer = AshBlueDark.copy(alpha = 0.2f),
    onTertiaryContainer = AshBlueLight,

    // Backgrounds & Surfaces
    background = SurfaceContainerLight,
    onBackground = Slate900,

    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = SurfaceContainerHighLight,
    onSurfaceVariant = Slate700,

    // Surface container roles
    surfaceDim = SurfaceDimLight,
    surfaceBright = SurfaceBrightLight,
    surfaceContainerLowest = SurfaceContainerLowestLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,

    // Status
    error = SignalError,
    onError = Color.White,

    outline = Slate400,
    outlineVariant = Slate200
)
```

**Step 3: Add Color import if missing**

Ensure this import exists at the top:
```kotlin
import androidx.compose.ui.graphics.Color
```

**Step 4: Verify build compiles**

Run: `cd .worktrees/phoenix-rising-theme && ./gradlew :shared:compileKotlinMetadata --quiet`
Expected: No errors

**Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/Theme.kt
git commit -m "feat(theme): update color scheme mappings to Phoenix Rising"
```

---

## Task 4: Clean Up Android Theme.kt

**Files:**
- Modify: `androidApp/src/main/kotlin/com/devil/phoenixproject/ui/theme/Theme.kt`

**Step 1: Replace entire file to delegate to shared theme**

```kotlin
package com.devil.phoenixproject.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.devil.phoenixproject.ui.theme.ThemeMode as SharedThemeMode
import com.devil.phoenixproject.ui.theme.VitruvianTheme as SharedVitruvianTheme

/**
 * Android-specific theme wrapper.
 * Delegates to shared theme and adds platform-specific status bar coloring.
 */
@Composable
fun VitruvianTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color disabled - breaks brand identity
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val themeMode = if (darkTheme) SharedThemeMode.DARK else SharedThemeMode.LIGHT

    SharedVitruvianTheme(themeMode = themeMode) {
        val colorScheme = MaterialTheme.colorScheme
        val view = LocalView.current

        if (!view.isInEditMode) {
            SideEffect {
                val window = (view.context as Activity).window
                window.statusBarColor = colorScheme.surface.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }

        content()
    }
}
```

**Step 2: Verify Android app compiles**

Run: `cd .worktrees/phoenix-rising-theme && ./gradlew :androidApp:compileDebugKotlin --quiet`
Expected: No errors

**Step 3: Commit**

```bash
git add androidApp/src/main/kotlin/com/devil/phoenixproject/ui/theme/Theme.kt
git commit -m "refactor(android): delegate theme to shared module, disable dynamic color"
```

---

## Task 5: Update VolumeHistoryChart to Use DataColors

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/VolumeHistoryChart.kt`

**Step 1: Update import and default color**

Add import:
```kotlin
import com.devil.phoenixproject.ui.theme.DataColors
```

Change line 24-25 from:
```kotlin
    barColor: Color = MaterialTheme.colorScheme.primary
```
to:
```kotlin
    barColor: Color = DataColors.Volume
```

**Step 2: Verify compiles**

Run: `cd .worktrees/phoenix-rising-theme && ./gradlew :shared:compileKotlinMetadata --quiet`
Expected: No errors

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/VolumeHistoryChart.kt
git commit -m "refactor(charts): use DataColors.Volume in VolumeHistoryChart"
```

---

## Task 6: Update VolumeTrendChart to Use DataColors

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/charts/VolumeTrendChart.kt`

**Step 1: Update import and bar color**

Add import after line 24:
```kotlin
import com.devil.phoenixproject.ui.theme.DataColors
```

Change line 54 from:
```kotlin
    val barColor = MaterialTheme.colorScheme.tertiary
```
to:
```kotlin
    val barColor = DataColors.Volume
```

**Step 2: Verify compiles**

Run: `cd .worktrees/phoenix-rising-theme && ./gradlew :shared:compileKotlinMetadata --quiet`
Expected: No errors

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/charts/VolumeTrendChart.kt
git commit -m "refactor(charts): use DataColors.Volume in VolumeTrendChart"
```

---

## Task 7: Build and Visual Verification

**Step 1: Full Android build**

Run: `cd .worktrees/phoenix-rising-theme && ./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 2: Run Android unit tests**

Run: `cd .worktrees/phoenix-rising-theme && ./gradlew :androidApp:testDebugUnitTest`
Expected: All tests pass

**Step 3: Manual visual check (if device available)**

Run: `cd .worktrees/phoenix-rising-theme && ./gradlew :androidApp:installDebug`

Check:
- [ ] Dark mode: Orange primary, Slate backgrounds
- [ ] Light mode: Deep orange primary, light backgrounds
- [ ] FABs and buttons are orange
- [ ] Charts use blue for volume data

**Step 4: Commit verification note**

```bash
git commit --allow-empty -m "chore: verify Phoenix Rising theme builds and tests pass"
```

---

## Task 8: Search for Remaining Hardcoded Colors

**Step 1: Grep for hardcoded Color values**

Run: `cd .worktrees/phoenix-rising-theme && grep -rn "Color(0x" shared/src/commonMain/kotlin --include="*.kt" | grep -v "/theme/" | head -20`

Expected: Review any results - if colors are for specific UI elements (not theme), they may be OK. If they should use theme colors, note for future cleanup.

**Step 2: Document findings (if any)**

If significant hardcoded colors found, create a follow-up task. Otherwise, proceed.

---

## Summary

After completing all tasks:
- Phoenix Rising theme is active
- Orange primary, Slate backgrounds, Teal accents
- DataColors available for semantic chart colors
- Android app uses shared theme (no dynamic color)
- Volume charts use consistent DataColors.Volume blue

**Merge when ready:**
```bash
git checkout main
git merge feature/phoenix-rising-theme
git branch -d feature/phoenix-rising-theme
git worktree remove .worktrees/phoenix-rising-theme
```
