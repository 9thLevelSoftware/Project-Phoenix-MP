# Tablet Display Issues

This document contains GitHub issues to be created for tablet display problems affecting both Android tablets and iPads.

---

## Issue 1: Missing WindowSizeClass Implementation

**Title:** `[Tablet] Missing WindowSizeClass implementation for responsive layouts`

**Labels:** `tablet`, `ui`, `enhancement`, `priority:critical`

### Problem

The codebase has **no WindowSizeClass implementation**, which is the foundational mechanism for differentiating phone vs tablet layouts in Compose Multiplatform.

### Platforms Affected

- ✅ Android tablets
- ✅ iPads (iOS)

### Current Behavior

All screens render identically on phones and tablets, leading to:
- Wasted screen space on tablets
- Components that are too small relative to screen size
- No adaptive layouts for different form factors

### Expected Behavior

Implement responsive layout classes to enable:
- **Compact** (phones < 600dp) - Current single-column layouts
- **Medium** (tablets 600-840dp) - Adapted layouts with larger components
- **Expanded** (tablets > 840dp) - Two-column layouts, side panels

### Suggested Implementation

1. Add window size class dependency to `shared/build.gradle.kts`
2. Create `presentation/util/WindowSizeUtils.kt` with platform-specific implementations
3. Provide size class context at app root (`EnhancedMainScreen.kt` for Android, SwiftUI equivalent for iOS)
4. Use size class to branch layout logic in screens

### Priority

🔴 **Critical** - This is the foundation for fixing all other tablet issues.

---

## Issue 2: Chart Components Have Hardcoded Heights

**Title:** `[Tablet] Chart components use hardcoded heights that don't scale`

**Labels:** `tablet`, `ui`, `charts`

### Problem

All chart components use fixed `dp` heights that don't adapt to tablet screen sizes.

### Platforms Affected

- ✅ Android tablets
- ✅ iPads (iOS)

### Affected Files

| File | Height | Location |
|------|--------|----------|
| `charts/RadarChart.kt` | 320.dp | ~Line 76 |
| `charts/GaugeChart.kt` | 200.dp | ~Lines 71, 172 |
| `charts/AreaChart.kt` | 200.dp | ~Line 84 |
| `charts/ComboChart.kt` | 200.dp | ~Line 129 |
| `charts/CircleChart.kt` | 280.dp | ~Line 80 |
| `charts/VolumeTrendChart.kt` | 200.dp | modifier param |
| `components/VolumeHistoryChart.kt` | 200.dp | ~Line 35 |

### Current Behavior

Charts appear small and cramped on tablets, wasting available screen space.

### Expected Behavior

Charts should scale appropriately based on:
- Available container height
- Screen size class (Compact/Medium/Expanded)
- Aspect ratio preservation where appropriate

### Suggested Fix

1. Replace fixed heights with `weight()` modifiers where charts are in columns
2. Use `BoxWithConstraints` to calculate proportional heights
3. Accept height as a parameter with sensible defaults based on size class

### Priority

🟡 **High** - Charts are a primary visual element of the analytics experience.

---

## Issue 3: InsightCards Have Hardcoded Heights

**Title:** `[Tablet] InsightCards.kt uses hardcoded card heights`

**Labels:** `tablet`, `ui`, `analytics`

### Problem

`InsightCards.kt` contains multiple insight/analytics cards with hardcoded heights that don't scale on tablets.

### Platforms Affected

- ✅ Android tablets
- ✅ iPads (iOS)

### Affected Components (in InsightCards.kt)

| Card Component | Height | Line |
|----------------|--------|------|
| MuscleBalanceRadarCard | 300.dp | ~372 |
| ConsistencyGaugeCard | 250.dp | ~423 |
| VolumeVsIntensityCard | 300.dp | ~488 |
| TotalVolumeCard | 300.dp | ~538 |
| WorkoutModeDistributionCard | 280.dp | ~583 |

### Current Behavior

On tablets, insight cards appear cramped with wasted space around them. The fixed heights mean cards don't utilize available screen real estate.

### Expected Behavior

Cards should:
- Scale to 40-50% of available height on tablets
- Support two-column layouts on larger tablets
- Maintain proportional sizing relative to content

### Priority

🟡 **High** - Insight cards are core to the analytics tab user experience.

---

## Issue 4: HomeScreen Tablet Layout Issues

**Title:** `[Tablet] HomeScreen has multiple hardcoded sizes`

**Labels:** `tablet`, `ui`, `home`

### Problem

`HomeScreen.kt` contains several hardcoded dimensions that cause layout issues on tablets.

### Platforms Affected

- ✅ Android tablets
- ✅ iPads (iOS)

### Affected Areas

| Component | Size | Line | Issue |
|-----------|------|------|-------|
| FAB spacer | `height(180.dp)` | ~136 | Fixed spacer doesn't adapt |
| Hero card image | `size(200.dp)` | ~330 | Image too small on tablets |
| ActiveCycleHero card | `height(180.dp)` | ~319 | Fixed card height |

### Current Behavior

- FAB positioning is off on tablets
- Hero card images appear proportionally small
- Cards don't utilize available screen height

### Expected Behavior

- FAB spacer should be proportional to screen height
- Hero images should scale (max ~40% of card width on tablets)
- Cards should adapt height based on content and screen size

### Priority

🟡 **High** - Home screen is the first impression of the app.

---

## Issue 5: AnalyticsScreen Tablet Issues

**Title:** `[Tablet] AnalyticsScreen has non-scaling UI elements`

**Labels:** `tablet`, `ui`, `analytics`

### Problem

`AnalyticsScreen.kt` has hardcoded UI element sizes.

### Platforms Affected

- ✅ Android tablets
- ✅ iPads (iOS)

### Affected Areas

| Element | Size | Line |
|---------|------|------|
| Tab indicator | `height(8.dp)` | ~240 |
| FAB icon | `size(28.dp)` | ~383 |
| FAB corner radius | `RoundedCornerShape(28.dp)` | ~374 |

### Current Behavior

Tab indicators and FAB appear proportionally small on tablets.

### Expected Behavior

These elements should scale based on screen size class for better touch targets and visual balance.

### Priority

🟢 **Medium** - Functional but not optimal.

---

## Issue 6: ProfileSidePanel Fixed Width

**Title:** `[Tablet] ProfileSidePanel has fixed 200dp width`

**Labels:** `tablet`, `ui`, `profile`

### Problem

`ProfileSidePanel.kt` uses hardcoded dimensions that don't adapt to tablets.

### Platforms Affected

- ✅ Android tablets
- ✅ iPads (iOS)

### Affected Areas

| Element | Size | Line |
|---------|------|------|
| Panel width | `PANEL_WIDTH = 200.dp` | ~34 |
| Handle width | `HANDLE_WIDTH = 24.dp` | ~36 |
| Handle height | `HANDLE_HEIGHT = 48.dp` | ~37 |

### Current Behavior

On a 10" tablet, the profile panel is only ~33% of the screen width, appearing narrow and underutilized.

### Expected Behavior

- Panel should be 25-30% of screen width on tablets (but capped at reasonable max)
- Handle sizes should scale proportionally
- Consider permanent side panel on expanded tablets (landscape)

### Priority

🟢 **Medium** - Affects profile experience on tablets.

---

## Issue 7: Bottom Sheets and Modals Height Constraints

**Title:** `[Tablet] Bottom sheets/modals have restrictive max heights`

**Labels:** `tablet`, `ui`, `modals`

### Problem

Bottom sheets and modals are capped at phone-sized heights.

### Platforms Affected

- ✅ Android tablets
- ✅ iPads (iOS)

### Affected Files

| File | Constraint | Issue |
|------|------------|-------|
| `WorkoutTab.kt` | `heightIn(max = 400.dp)` (~Line 1235) | Caps modal height |
| `AddDaySheet.kt` | `heightIn(max = 400.dp)` | Caps sheet height |

### Current Behavior

On tablets, bottom sheets and modals appear cramped, not using available vertical space.

### Expected Behavior

- Use dynamic constraints: `coerceAtMost(screenHeight * 0.8f)` or similar
- Remove fixed max heights or make them screen-relative
- Consider full-height side sheets on landscape tablets

### Priority

🟢 **Medium** - Affects workout creation flows.

---

## Issue 8: Workout HUD/Display Sizing

**Title:** `[Tablet] Workout HUD metric displays don't scale`

**Labels:** `tablet`, `ui`, `workout`

### Problem

Active workout metric displays use fixed sizes that appear small on tablets.

### Platforms Affected

- ✅ Android tablets
- ✅ iPads (iOS)

### Affected Files

| File | Size | Line |
|------|------|------|
| `WorkoutHud.kt` | `size(200.dp)` | ~414 |
| `WorkoutTabAlt.kt` | `size(200.dp)` | ~720 |

### Current Behavior

During active workouts on tablets, the metric displays (force gauge, position indicator, etc.) are too small relative to available screen space.

### Expected Behavior

- Metric displays should scale based on available space
- On tablets, displays should maximize screen utilization
- Consider side-by-side layout for metrics on landscape tablets

### Priority

🟡 **High** - Active workout is the core user experience.

---

## Summary

| Issue | Priority | Category |
|-------|----------|----------|
| WindowSizeClass | 🔴 Critical | Foundation |
| Chart Heights | 🟡 High | Charts |
| InsightCards Heights | 🟡 High | Analytics |
| HomeScreen Layout | 🟡 High | Home |
| Workout HUD Sizing | 🟡 High | Workout |
| AnalyticsScreen Elements | 🟢 Medium | Analytics |
| ProfileSidePanel Width | 🟢 Medium | Profile |
| Bottom Sheet Heights | 🟢 Medium | Modals |

### Recommended Fix Order

1. **WindowSizeClass** - Must be done first as foundation
2. **Chart Components** - High visibility, affects multiple screens
3. **InsightCards** - Works with chart fixes
4. **HomeScreen** - First impression
5. **Workout HUD** - Core UX
6. Remaining medium-priority items
