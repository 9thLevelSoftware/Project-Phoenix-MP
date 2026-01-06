# Tablet Responsive Layout Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement responsive layouts for Android tablets and iPads across 22 identified UI issues, ensuring phone layouts remain unchanged.

**Architecture:** WindowSizeClass-based responsive design using Compose Multiplatform. All changes use a wrapper/modifier pattern that preserves existing phone behavior while enabling tablet-specific scaling. The pattern `when { maxWidth > 840.dp -> expanded; maxWidth > 600.dp -> medium; else -> currentValue }` ensures backward compatibility.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform 1.10.0, Material 3, expect/actual for platform-specific WindowSizeClass detection.

---

## Phase 1: Foundation - WindowSizeClass Infrastructure

### Task 1.1: Create WindowSizeClass Utility

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/WindowSizeClass.kt`

**Step 1: Create the util directory and WindowSizeClass file**

```kotlin
package com.devil.phoenixproject.presentation.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Represents the size class of the window for responsive layouts.
 * Based on Material 3 window size class breakpoints.
 */
enum class WindowWidthSizeClass {
    /** Phones in portrait (< 600dp) */
    Compact,
    /** Small tablets, phones in landscape (600-840dp) */
    Medium,
    /** Large tablets, desktops (> 840dp) */
    Expanded
}

enum class WindowHeightSizeClass {
    /** Short screens (< 480dp) */
    Compact,
    /** Medium height (480-900dp) */
    Medium,
    /** Tall screens (> 900dp) */
    Expanded
}

data class WindowSizeClass(
    val widthSizeClass: WindowWidthSizeClass,
    val heightSizeClass: WindowHeightSizeClass,
    val widthDp: Dp,
    val heightDp: Dp
) {
    val isTablet: Boolean
        get() = widthSizeClass != WindowWidthSizeClass.Compact

    val isExpandedTablet: Boolean
        get() = widthSizeClass == WindowWidthSizeClass.Expanded
}

/**
 * CompositionLocal for accessing WindowSizeClass throughout the app.
 * Defaults to Compact (phone) if not provided.
 */
val LocalWindowSizeClass = compositionLocalOf {
    WindowSizeClass(
        widthSizeClass = WindowWidthSizeClass.Compact,
        heightSizeClass = WindowHeightSizeClass.Medium,
        widthDp = 400.dp,
        heightDp = 800.dp
    )
}

/**
 * Calculate WindowSizeClass from screen dimensions.
 */
fun calculateWindowSizeClass(widthDp: Dp, heightDp: Dp): WindowSizeClass {
    val widthClass = when {
        widthDp < 600.dp -> WindowWidthSizeClass.Compact
        widthDp < 840.dp -> WindowWidthSizeClass.Medium
        else -> WindowWidthSizeClass.Expanded
    }

    val heightClass = when {
        heightDp < 480.dp -> WindowHeightSizeClass.Compact
        heightDp < 900.dp -> WindowHeightSizeClass.Medium
        else -> WindowHeightSizeClass.Expanded
    }

    return WindowSizeClass(
        widthSizeClass = widthClass,
        heightSizeClass = heightClass,
        widthDp = widthDp,
        heightDp = heightDp
    )
}
```

**Step 2: Verify file created**

Run: `ls shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/`
Expected: `WindowSizeClass.kt`

**Step 3: Build to verify no compilation errors**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/
git commit -m "feat(tablet): add WindowSizeClass utility for responsive layouts

Closes #66"
```

---

### Task 1.2: Integrate WindowSizeClass into EnhancedMainScreen

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt`

**Step 1: Add imports at top of file**

Add after existing imports (around line 46):

```kotlin
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.calculateWindowSizeClass
```

**Step 2: Wrap Scaffold with BoxWithConstraints and CompositionLocalProvider**

Find the `Scaffold(` call (around line 132) and wrap it:

```kotlin
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthDp = maxWidth
        val heightDp = maxHeight
        val windowSizeClass = calculateWindowSizeClass(widthDp, heightDp)

        CompositionLocalProvider(LocalWindowSizeClass provides windowSizeClass) {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                // ... rest of existing Scaffold code
```

Don't forget to close the BoxWithConstraints and CompositionLocalProvider at the end of the composable.

**Step 3: Add BoxWithConstraints import**

Add to imports:
```kotlin
import androidx.compose.foundation.layout.BoxWithConstraints
```

**Step 4: Build to verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt
git commit -m "feat(tablet): integrate WindowSizeClass into EnhancedMainScreen

Provides LocalWindowSizeClass to all child composables for responsive layouts."
```

---

## Phase 2: Low-Risk Fixes - Bottom Sheets and Dialogs

### Task 2.1: Fix RoutinePickerDialog Max Height (#87)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/RoutinePickerDialog.kt`

**Step 1: Add imports**

```kotlin
import androidx.compose.foundation.layout.BoxWithConstraints
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
```

**Step 2: Replace fixed heightIn constraint**

Find `.heightIn(max = 600.dp)` and replace with responsive version:

```kotlin
BoxWithConstraints {
    val maxDialogHeight = (maxHeight * 0.8f).coerceIn(400.dp, 800.dp)

    Column(
        modifier = Modifier
            .heightIn(max = maxDialogHeight)
            // ... rest of existing modifiers
    ) {
        // existing content
    }
}
```

**Step 3: Build to verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/RoutinePickerDialog.kt
git commit -m "feat(tablet): make RoutinePickerDialog height responsive

Uses 80% of screen height, capped between 400-800dp.
Closes #87"
```

---

### Task 2.2: Fix Bottom Sheet Max Heights (#72)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/AddDaySheet.kt`

**Step 1: Update WorkoutTab.kt**

Find `.heightIn(max = 400.dp)` (around line 1235) and wrap with BoxWithConstraints:

```kotlin
BoxWithConstraints {
    val maxSheetHeight = (maxHeight * 0.8f).coerceIn(300.dp, 600.dp)

    Column(
        modifier = Modifier
            .heightIn(max = maxSheetHeight)
            // ... rest of existing modifiers
    ) {
        // existing content
    }
}
```

**Step 2: Update AddDaySheet.kt similarly**

Find and replace `.heightIn(max = 400.dp)` with the same responsive pattern.

**Step 3: Build to verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/AddDaySheet.kt
git commit -m "feat(tablet): make bottom sheet heights responsive

Sheets now use 80% of screen height, capped appropriately.
Closes #72"
```

---

### Task 2.3: Fix AutoStopOverlay Dialog Width (#74)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/AutoStopOverlay.kt`

**Step 1: Add imports**

```kotlin
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
```

**Step 2: Replace fixed widthIn constraints**

Find `.widthIn(max = 280.dp)` (lines 60 and 199) and replace:

```kotlin
val windowSizeClass = LocalWindowSizeClass.current
val dialogMaxWidth = when (windowSizeClass.widthSizeClass) {
    WindowWidthSizeClass.Expanded -> 400.dp
    WindowWidthSizeClass.Medium -> 340.dp
    WindowWidthSizeClass.Compact -> 280.dp
}

// Then use:
.widthIn(max = dialogMaxWidth)
```

**Step 3: Build to verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/AutoStopOverlay.kt
git commit -m "feat(tablet): make AutoStopOverlay dialog width responsive

Dialog scales from 280dp (phone) to 400dp (tablet).
Closes #74"
```

---

## Phase 3: Chart Components - High Visibility

### Task 3.1: Create Responsive Chart Height Utility

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/WindowSizeClass.kt`

**Step 1: Add chart scaling helper functions**

Append to WindowSizeClass.kt:

```kotlin
/**
 * Responsive dimension helpers for common UI patterns.
 */
object ResponsiveDimensions {

    /**
     * Calculate responsive chart height based on window size.
     * @param baseHeight The phone-sized height (compact)
     * @param mediumMultiplier Scale factor for medium tablets (default 1.25)
     * @param expandedMultiplier Scale factor for large tablets (default 1.5)
     */
    @Composable
    fun chartHeight(
        baseHeight: Dp,
        mediumMultiplier: Float = 1.25f,
        expandedMultiplier: Float = 1.5f
    ): Dp {
        val windowSizeClass = LocalWindowSizeClass.current
        return when (windowSizeClass.widthSizeClass) {
            WindowWidthSizeClass.Expanded -> baseHeight * expandedMultiplier
            WindowWidthSizeClass.Medium -> baseHeight * mediumMultiplier
            WindowWidthSizeClass.Compact -> baseHeight
        }
    }

    /**
     * Calculate max width for cards to prevent over-stretching on tablets.
     * Returns null for phones (use full width), or a max width for tablets.
     */
    @Composable
    fun cardMaxWidth(): Dp? {
        val windowSizeClass = LocalWindowSizeClass.current
        return when (windowSizeClass.widthSizeClass) {
            WindowWidthSizeClass.Expanded -> 600.dp
            WindowWidthSizeClass.Medium -> 500.dp
            WindowWidthSizeClass.Compact -> null  // No max, use full width
        }
    }

    /**
     * Calculate responsive component size (for gauges, HUDs, etc.)
     */
    @Composable
    fun componentSize(baseSize: Dp): Dp {
        val windowSizeClass = LocalWindowSizeClass.current
        return when (windowSizeClass.widthSizeClass) {
            WindowWidthSizeClass.Expanded -> baseSize * 1.6f
            WindowWidthSizeClass.Medium -> baseSize * 1.3f
            WindowWidthSizeClass.Compact -> baseSize
        }
    }
}

// Extension for Dp multiplication
private operator fun Dp.times(factor: Float): Dp = (this.value * factor).dp
```

**Step 2: Build to verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/WindowSizeClass.kt
git commit -m "feat(tablet): add ResponsiveDimensions utility for chart scaling"
```

---

### Task 3.2: Fix RadarChart Height (#67 - Part 1)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/charts/RadarChart.kt`

**Step 1: Add import**

```kotlin
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions
```

**Step 2: Replace fixed height**

Find `.height(320.dp)` (around line 76) and replace:

```kotlin
val chartHeight = ResponsiveDimensions.chartHeight(baseHeight = 320.dp)

BoxWithConstraints(
    modifier = modifier
        .fillMaxWidth()
        .height(chartHeight)
        .padding(24.dp)
) {
```

**Step 3: Build to verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/charts/RadarChart.kt
git commit -m "feat(tablet): make RadarChart height responsive

Scales from 320dp (phone) to 480dp (expanded tablet).
Partial fix for #67"
```

---

### Task 3.3: Fix GaugeChart Height (#67 - Part 2)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/charts/GaugeChart.kt`

**Step 1: Add import**

```kotlin
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions
```

**Step 2: Replace fixed heights**

Find all `.height(200.dp)` and `.height(250.dp)` occurrences and replace with:

```kotlin
val chartHeight = ResponsiveDimensions.chartHeight(baseHeight = 200.dp)
// or for 250.dp base:
val chartHeight = ResponsiveDimensions.chartHeight(baseHeight = 250.dp)
```

**Step 3: Build to verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/charts/GaugeChart.kt
git commit -m "feat(tablet): make GaugeChart height responsive

Partial fix for #67"
```

---

### Task 3.4: Fix AreaChart Height (#67 - Part 3)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/charts/AreaChart.kt`

**Step 1: Add import and replace fixed heights**

Same pattern as previous charts. Replace `.height(200.dp)` and `.height(280.dp)`.

**Step 2: Build to verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/charts/AreaChart.kt
git commit -m "feat(tablet): make AreaChart height responsive

Partial fix for #67"
```

---

### Task 3.5: Fix ComboChart Height (#67 - Part 4)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/charts/ComboChart.kt`

Same pattern. Replace `.height(200.dp)`.

**Step 1: Apply responsive height pattern**

**Step 2: Build to verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/charts/ComboChart.kt
git commit -m "feat(tablet): make ComboChart height responsive

Partial fix for #67"
```

---

### Task 3.6: Fix CircleChart Height (#67 - Part 5)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/charts/CircleChart.kt`

Same pattern. Replace `.height(280.dp)`.

**Step 1: Apply responsive height pattern**

**Step 2: Build to verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/charts/CircleChart.kt
git commit -m "feat(tablet): make CircleChart height responsive

Partial fix for #67"
```

---

### Task 3.7: Fix VolumeTrendChart (#67 - Part 6, #85)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/charts/VolumeTrendChart.kt`

**Step 1: Add imports**

```kotlin
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
```

**Step 2: Replace fixed height**

Replace `.height(200.dp)` with responsive version.

**Step 3: Fix column width (#85)**

Find `.width(50.dp)` (around line 82) and replace:

```kotlin
val windowSizeClass = LocalWindowSizeClass.current
val columnWidth = when (windowSizeClass.widthSizeClass) {
    WindowWidthSizeClass.Expanded -> 80.dp
    WindowWidthSizeClass.Medium -> 65.dp
    WindowWidthSizeClass.Compact -> 50.dp
}
```

**Step 4: Build to verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/charts/VolumeTrendChart.kt
git commit -m "feat(tablet): make VolumeTrendChart responsive

- Height scales with screen size
- Column width scales appropriately
Closes #67, #85"
```

---

## Phase 4: InsightCards - Card Width Constraints

### Task 4.1: Add Card Wrapper to InsightsTab (#68)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/InsightsTab.kt`

**Step 1: Add imports**

```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions
```

**Step 2: Create wrapper composable for cards**

Add this helper inside the file:

```kotlin
@Composable
private fun ResponsiveCardWrapper(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val maxWidth = ResponsiveDimensions.cardMaxWidth()

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = if (maxWidth != null) {
                Modifier.widthIn(max = maxWidth).fillMaxWidth()
            } else {
                Modifier.fillMaxWidth()
            }
        ) {
            content()
        }
    }
}
```

**Step 3: Wrap each card with ResponsiveCardWrapper**

Replace each card item, for example:

```kotlin
// Before:
item {
    MuscleBalanceRadarCard(
        personalRecords = prs,
        exerciseRepository = exerciseRepository,
        modifier = Modifier.fillMaxWidth()
    )
}

// After:
item {
    ResponsiveCardWrapper {
        MuscleBalanceRadarCard(
            personalRecords = prs,
            exerciseRepository = exerciseRepository,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
```

Apply to all card items: ThisWeekSummaryCard, MuscleBalanceRadarCard, ConsistencyGaugeCard, VolumeVsIntensityCard, TotalVolumeCard, WorkoutModeDistributionCard.

**Step 4: Build to verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/InsightsTab.kt
git commit -m "feat(tablet): add responsive card wrapper to InsightsTab

Cards now have max-width on tablets to prevent over-stretching.
Closes #68"
```

---

## Phase 5: HomeScreen Layout

### Task 5.1: Fix HomeScreen Hardcoded Sizes (#69)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/HomeScreen.kt`

**Step 1: Add imports**

```kotlin
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
```

**Step 2: Replace FAB spacer height**

Find `.height(180.dp)` for the FAB spacer (around line 136) and replace:

```kotlin
val windowSizeClass = LocalWindowSizeClass.current
val fabSpacerHeight = when (windowSizeClass.widthSizeClass) {
    WindowWidthSizeClass.Expanded -> 240.dp
    WindowWidthSizeClass.Medium -> 210.dp
    WindowWidthSizeClass.Compact -> 180.dp
}
Spacer(modifier = Modifier.height(fabSpacerHeight))
```

**Step 3: Replace hero card image size**

Find `.size(200.dp)` for hero image (around line 330) and replace:

```kotlin
val heroImageSize = when (windowSizeClass.widthSizeClass) {
    WindowWidthSizeClass.Expanded -> 280.dp
    WindowWidthSizeClass.Medium -> 240.dp
    WindowWidthSizeClass.Compact -> 200.dp
}
```

**Step 4: Replace ActiveCycleHero height**

Find `.height(180.dp)` for ActiveCycleHero (around line 319) and replace:

```kotlin
val heroCardHeight = when (windowSizeClass.widthSizeClass) {
    WindowWidthSizeClass.Expanded -> 240.dp
    WindowWidthSizeClass.Medium -> 210.dp
    WindowWidthSizeClass.Compact -> 180.dp
}
```

**Step 5: Build to verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/HomeScreen.kt
git commit -m "feat(tablet): make HomeScreen dimensions responsive

- FAB spacer scales with screen size
- Hero card image scales appropriately
- ActiveCycleHero height adapts to tablets
Closes #69"
```

---

## Phase 6: Workout HUD and Active Workout

### Task 6.1: Fix WorkoutHud Metric Display Size (#73)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutHud.kt`

**Step 1: Add imports**

```kotlin
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions
```

**Step 2: Replace fixed size**

Find `.size(200.dp)` (around line 414) and replace:

```kotlin
val hudSize = ResponsiveDimensions.componentSize(baseSize = 200.dp)
// Use: .size(hudSize)
```

**Step 3: Fix button height (#86 partial)**

Find `.height(64.dp)` (around line 214) and make responsive:

```kotlin
val windowSizeClass = LocalWindowSizeClass.current
val buttonHeight = when (windowSizeClass.widthSizeClass) {
    WindowWidthSizeClass.Expanded -> 80.dp
    WindowWidthSizeClass.Medium -> 72.dp
    WindowWidthSizeClass.Compact -> 64.dp
}
```

**Step 4: Build to verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutHud.kt
git commit -m "feat(tablet): make WorkoutHud responsive

- Metric display scales with screen size
- Button heights adapt to tablets
Closes #73, partial #86"
```

---

### Task 6.2: Fix WorkoutTabAlt Sizes (#73 continued, #86)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTabAlt.kt`

**Step 1: Apply same patterns as WorkoutHud**

Replace `.size(200.dp)` (around line 720) and `.height(64.dp)` (around line 222) with responsive versions.

**Step 2: Build to verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTabAlt.kt
git commit -m "feat(tablet): make WorkoutTabAlt responsive

Closes #86"
```

---

### Task 6.3: Fix CountdownCard Size and Font (#78)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/CountdownCard.kt`

**Step 1: Add imports**

```kotlin
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
import androidx.compose.ui.unit.sp
```

**Step 2: Replace fixed dimensions**

Find `.height(220.dp)` and `.size(220.dp)` and make responsive:

```kotlin
val windowSizeClass = LocalWindowSizeClass.current
val countdownSize = when (windowSizeClass.widthSizeClass) {
    WindowWidthSizeClass.Expanded -> 320.dp
    WindowWidthSizeClass.Medium -> 270.dp
    WindowWidthSizeClass.Compact -> 220.dp
}

val countdownFontSize = when (windowSizeClass.widthSizeClass) {
    WindowWidthSizeClass.Expanded -> 120.sp
    WindowWidthSizeClass.Medium -> 105.sp
    WindowWidthSizeClass.Compact -> 90.sp
}
```

**Step 3: Build to verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/CountdownCard.kt
git commit -m "feat(tablet): make CountdownCard responsive

- Countdown circle scales from 220dp to 320dp
- Font size scales from 90sp to 120sp
Closes #78"
```

---

## Phase 7: Grid and List Layouts

### Task 7.1: Fix BadgesScreen Grid Columns (#80)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/BadgesScreen.kt`

**Step 1: Add imports**

```kotlin
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
```

**Step 2: Replace fixed grid columns**

Find `GridCells.Fixed(3)` (around line 114) and replace:

```kotlin
val windowSizeClass = LocalWindowSizeClass.current
val gridColumns = when (windowSizeClass.widthSizeClass) {
    WindowWidthSizeClass.Expanded -> 6
    WindowWidthSizeClass.Medium -> 4
    WindowWidthSizeClass.Compact -> 3
}

LazyVerticalGrid(
    columns = GridCells.Fixed(gridColumns),
    // ... rest of params
)
```

**Step 3: Build to verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/BadgesScreen.kt
git commit -m "feat(tablet): make BadgesScreen grid responsive

Grid columns: 3 (phone) -> 4 (medium) -> 6 (expanded)
Closes #80"
```

---

## Phase 8: Remaining Medium Priority Items

### Task 8.1: Fix AnalyticsScreen Elements (#70)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/AnalyticsScreen.kt`

**Step 1: Add imports**

```kotlin
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
```

**Step 2: Make tab indicator and FAB responsive**

Find `.height(8.dp)` for tab indicator (around line 240):

```kotlin
val windowSizeClass = LocalWindowSizeClass.current
val tabIndicatorHeight = when (windowSizeClass.widthSizeClass) {
    WindowWidthSizeClass.Expanded -> 12.dp
    WindowWidthSizeClass.Medium -> 10.dp
    WindowWidthSizeClass.Compact -> 8.dp
}
```

Find `.size(28.dp)` for FAB icon (around line 383):

```kotlin
val fabIconSize = when (windowSizeClass.widthSizeClass) {
    WindowWidthSizeClass.Expanded -> 36.dp
    WindowWidthSizeClass.Medium -> 32.dp
    WindowWidthSizeClass.Compact -> 28.dp
}
```

**Step 3: Build to verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/AnalyticsScreen.kt
git commit -m "feat(tablet): make AnalyticsScreen UI elements responsive

Closes #70"
```

---

### Task 8.2: Fix ProfileSidePanel Width (#71)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSidePanel.kt`

**Step 1: Add imports and replace constants**

Find `PANEL_WIDTH = 200.dp` (around line 34) and make it a composable calculation:

```kotlin
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass

// Inside the composable:
val windowSizeClass = LocalWindowSizeClass.current
val panelWidth = when (windowSizeClass.widthSizeClass) {
    WindowWidthSizeClass.Expanded -> 280.dp
    WindowWidthSizeClass.Medium -> 240.dp
    WindowWidthSizeClass.Compact -> 200.dp
}
```

**Step 2: Build to verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSidePanel.kt
git commit -m "feat(tablet): make ProfileSidePanel width responsive

Panel scales from 200dp to 280dp on tablets.
Closes #71"
```

---

### Task 8.3: Fix AnimatedActionButton Heights (#81)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/AnimatedActionButton.kt`

**Step 1: Add imports**

```kotlin
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
```

**Step 2: Replace all fixed button heights**

Find all `.height(64.dp)` (around lines 257, 308, 327) and replace with responsive:

```kotlin
val windowSizeClass = LocalWindowSizeClass.current
val buttonHeight = when (windowSizeClass.widthSizeClass) {
    WindowWidthSizeClass.Expanded -> 80.dp
    WindowWidthSizeClass.Medium -> 72.dp
    WindowWidthSizeClass.Compact -> 64.dp
}
```

**Step 3: Build to verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/AnimatedActionButton.kt
git commit -m "feat(tablet): make AnimatedActionButton heights responsive

Closes #81"
```

---

### Task 8.4: Fix WorkoutTab Weight Labels (#84)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt`

**Step 1: Replace fixed width labels**

Find `.width(50.dp)` (around lines 1133, 1163) and replace:

```kotlin
val windowSizeClass = LocalWindowSizeClass.current
val labelWidth = when (windowSizeClass.widthSizeClass) {
    WindowWidthSizeClass.Expanded -> 80.dp
    WindowWidthSizeClass.Medium -> 65.dp
    WindowWidthSizeClass.Compact -> 50.dp
}
```

**Step 2: Build to verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt
git commit -m "feat(tablet): make WorkoutTab weight labels responsive

Closes #84"
```

---

## Phase 9: Lower Priority Items

### Task 9.1: Fix ExerciseEditDialog Label Widths (#76)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseEditDialog.kt`

**Step 1: Replace fixed widths with wrapContentWidth or responsive values**

Find `.width(60.dp)`, `.width(100.dp)`, `.width(120.dp)` and use flexible layouts:

```kotlin
// Instead of fixed width, use weight-based layout:
Row(modifier = Modifier.fillMaxWidth()) {
    Text(
        "Label:",
        modifier = Modifier.weight(0.3f)
    )
    TextField(
        modifier = Modifier.weight(0.7f),
        // ...
    )
}
```

**Step 2: Build to verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseEditDialog.kt
git commit -m "feat(tablet): make ExerciseEditDialog use flexible layouts

Closes #76"
```

---

### Task 9.2: Fix CycleEditorScreen Label Width (#75)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/CycleEditorScreen.kt`

Same pattern as ExerciseEditDialog. Replace `.width(120.dp)` with flexible layout.

**Step 1: Apply flexible layout pattern**

**Step 2: Build to verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/CycleEditorScreen.kt
git commit -m "feat(tablet): make CycleEditorScreen labels flexible

Closes #75"
```

---

### Task 9.3: Fix DayCountPickerScreen Padding (#77)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/DayCountPickerScreen.kt`

**Step 1: Make padding responsive**

Find `.padding(horizontal = 24.dp)` and replace:

```kotlin
val windowSizeClass = LocalWindowSizeClass.current
val horizontalPadding = when (windowSizeClass.widthSizeClass) {
    WindowWidthSizeClass.Expanded -> 48.dp
    WindowWidthSizeClass.Medium -> 36.dp
    WindowWidthSizeClass.Compact -> 24.dp
}
```

**Step 2: Build to verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/DayCountPickerScreen.kt
git commit -m "feat(tablet): make DayCountPickerScreen padding responsive

Closes #77"
```

---

### Task 9.4: Fix ConnectionLogsScreen Preview Height (#79)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ConnectionLogsScreen.kt`

**Step 1: Replace fixed height**

Find `.height(200.dp)` (around line 221) and replace:

```kotlin
BoxWithConstraints {
    val previewHeight = (maxHeight * 0.4f).coerceIn(150.dp, 400.dp)

    Surface(
        modifier = Modifier.height(previewHeight)
        // ...
    )
}
```

**Step 2: Build to verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ConnectionLogsScreen.kt
git commit -m "feat(tablet): make ConnectionLogsScreen preview height responsive

Closes #79"
```

---

### Task 9.5: Fix HistoryAndSettingsTabs Skeleton Height (#82)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/HistoryAndSettingsTabs.kt`

**Step 1: Make skeleton height responsive**

Find `.height(100.dp)` (around line 2158) and replace:

```kotlin
val windowSizeClass = LocalWindowSizeClass.current
val skeletonHeight = when (windowSizeClass.widthSizeClass) {
    WindowWidthSizeClass.Expanded -> 140.dp
    WindowWidthSizeClass.Medium -> 120.dp
    WindowWidthSizeClass.Compact -> 100.dp
}
```

**Step 2: Build to verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/HistoryAndSettingsTabs.kt
git commit -m "feat(tablet): make HistoryAndSettingsTabs skeleton responsive

Closes #82"
```

---

### Task 9.6: Fix ShimmerEffect Dimensions (#83)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ShimmerEffect.kt`

**Step 1: Add responsive helper**

At top of file, add a helper function:

```kotlin
@Composable
private fun responsiveDimension(base: Dp): Dp {
    val windowSizeClass = LocalWindowSizeClass.current
    return when (windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Expanded -> base * 1.4f
        WindowWidthSizeClass.Medium -> base * 1.2f
        WindowWidthSizeClass.Compact -> base
    }
}
```

**Step 2: Replace all hardcoded dimensions**

Apply `responsiveDimension()` to all hardcoded widths/heights (160.dp, 120.dp, 80.dp, 100.dp, 50.dp, 140.dp).

**Step 3: Build to verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ShimmerEffect.kt
git commit -m "feat(tablet): make ShimmerEffect dimensions responsive

Closes #83"
```

---

## Phase 10: Final Verification

### Task 10.1: Full Build Verification

**Step 1: Clean and full build**

Run: `./gradlew clean :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 2: Run unit tests**

Run: `./gradlew :androidApp:testDebugUnitTest`
Expected: All tests pass

**Step 3: Build Android APK**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 4: Final commit with issue references**

```bash
git add -A
git commit -m "feat(tablet): complete responsive layout implementation

All 22 tablet display issues resolved:
- #66: WindowSizeClass foundation
- #67: Chart heights
- #68: InsightCards
- #69: HomeScreen
- #70: AnalyticsScreen
- #71: ProfileSidePanel
- #72: Bottom sheets
- #73: WorkoutHud
- #74: AutoStopOverlay
- #75: CycleEditorScreen
- #76: ExerciseEditDialog
- #77: DayCountPickerScreen
- #78: CountdownCard
- #79: ConnectionLogsScreen
- #80: BadgesScreen grid
- #81: AnimatedActionButton
- #82: HistoryAndSettingsTabs
- #83: ShimmerEffect
- #84: WorkoutTab labels
- #85: VolumeTrendChart
- #86: Button heights
- #87: RoutinePickerDialog

Phone layouts remain unchanged. Tablet layouts scale appropriately."
```

---

## Testing Checklist

Before marking complete, manually verify on:

- [ ] Phone (< 600dp width) - All layouts unchanged
- [ ] Small tablet portrait (600-840dp) - Moderate scaling
- [ ] Large tablet portrait (> 840dp) - Full scaling
- [ ] Large tablet landscape - Side-by-side where appropriate
- [ ] Charts maintain proper aspect ratios
- [ ] Cards don't stretch beyond readable widths
- [ ] Touch targets remain accessible
- [ ] No layout clipping or overflow

---

## Summary

| Phase | Issues | Risk | Tasks |
|-------|--------|------|-------|
| 1. Foundation | #66 | None | 1.1-1.2 |
| 2. Bottom Sheets | #72, #74, #87 | Low | 2.1-2.3 |
| 3. Charts | #67, #85 | Medium | 3.1-3.7 |
| 4. InsightCards | #68 | Medium | 4.1 |
| 5. HomeScreen | #69 | Medium | 5.1 |
| 6. Workout | #73, #78, #86 | Medium | 6.1-6.3 |
| 7. Grids | #80 | Medium | 7.1 |
| 8. Medium Priority | #70, #71, #81, #84 | Low | 8.1-8.4 |
| 9. Low Priority | #75-77, #79, #82-83 | Low | 9.1-9.6 |
| 10. Verification | All | None | 10.1 |
