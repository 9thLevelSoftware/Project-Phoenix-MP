# Phase 18: HUD Customization - Research

**Researched:** 2026-02-28
**Domain:** Compose Multiplatform HorizontalPager dynamic page filtering, preference-backed UI configuration, string-key persistence
**Confidence:** HIGH

## Summary

This phase adds preset-based HUD page visibility control to the workout screen. The current `WorkoutHud` composable uses a `HorizontalPager` with a hardcoded `pageCount = 3` and a `when(page)` block mapping indices 0/1/2 to ExecutionPage, InstructionPage, and StatsPage. The implementation requires: (1) defining an enum/sealed class for HUD page identifiers with stable string keys, (2) defining three presets (Essential, Biomechanics, Full) that map to subsets of those pages, (3) persisting the selected preset as a string in `multiplatform-settings` through the existing `UserPreferences -> PreferencesManager -> SettingsManager` pipeline, (4) filtering the pager's page list at composition time based on the active preset, and (5) adding a preset selector UI to the SettingsTab Accessibility section.

The codebase has strong precedent for this exact pattern. The `colorBlindModeEnabled` setting added in Phase 17 follows the identical pipeline: field in `UserPreferences`, key/getter/setter in `PreferencesManager`, delegation in `SettingsManager`, delegation in `MainViewModel`, wiring in `NavGraph.kt`, and UI in `SettingsTab`. The HUD preset follows the same path but stores a string (preset name) instead of a boolean.

The `HorizontalPager` from `androidx.compose.foundation.pager` supports dynamic `pageCount` natively. The key architectural decision is filtering the page list before passing it to the pager, not hiding pages after they render. This avoids index-mapping bugs and keeps the pager's internal state consistent.

**Primary recommendation:** Define `HudPage` as a Kotlin enum with `key: String` property (stable persistence), define `HudPreset` as an enum mapping to `List<HudPage>`, store the preset name as a string via `settings.putString()`, and filter the `when(page)` block using a `visiblePages` list derived from the active preset. The Execution page is hardcoded as always-present in every preset definition.

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| BOARD-04 | User can configure which HUD pages are visible during workouts via Settings (preset-based: Essential, Biomechanics, Full) | HudPreset enum with string-key persistence, SettingsTab preset selector, WorkoutHud dynamic pager filtering, UserPreferences pipeline |
</phase_requirements>

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Compose Multiplatform | 1.7.1 | `HorizontalPager`, `rememberPagerState`, composable filtering | Already in project; WorkoutHud already uses HorizontalPager |
| multiplatform-settings (russhwolf) | (in project) | Persist HUD preset selection as string | Already used for all user preferences; `putString`/`getStringOrNull` pattern proven for enum-as-string storage (see `KEY_WEIGHT_UNIT`) |
| Material 3 | (bundled) | SegmentedButton or RadioButton group for preset selection UI | Already the app's design system |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Koin | 4.0.0 | DI for PreferencesManager, SettingsManager | Already wired; no new modules needed |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Enum-based presets | Per-page toggle booleans | Presets are simpler UX, fewer settings keys, and explicitly scoped in REQUIREMENTS.md; per-page toggles are over-engineered for v0.5.1 |
| String key persistence | Integer index persistence | String keys survive enum reordering and are explicitly required by success criteria #3 |
| Filtering page list before pager | Conditional rendering inside pager | Filtering gives correct `pageCount` to pager state; conditional rendering causes invisible pages and broken dot indicators |

## Architecture Patterns

### Recommended File Additions/Modifications
```
shared/src/commonMain/kotlin/com/devil/phoenixproject/
├── domain/model/
│   ├── HudModels.kt (NEW)           # HudPage enum, HudPreset enum
│   └── UserPreferences.kt           # Add hudPreset field
├── data/preferences/
│   └── PreferencesManager.kt        # Add KEY_HUD_PRESET, getter/setter
├── presentation/manager/
│   └── SettingsManager.kt           # Add hudPreset StateFlow + setter
├── presentation/viewmodel/
│   └── MainViewModel.kt             # Delegate hudPreset to SettingsManager
├── presentation/screen/
│   ├── WorkoutHud.kt                # Accept visiblePages param, filter pager
│   ├── WorkoutTab.kt                # Pass visiblePages from state
│   ├── WorkoutUiState.kt            # Add hudPreset or visiblePages field
│   ├── SettingsTab.kt               # Add HUD preset selector UI
│   └── NavGraph.kt                  # Wire hudPreset from viewModel
└── (test files)
    ├── FakePreferencesManager.kt    # Add setHudPreset + fix missing setColorBlindModeEnabled
    └── SettingsManagerTest.kt       # Add hudPreset tests
```

### Pattern 1: HudPage + HudPreset Enums
**What:** Define page identifiers as an enum with stable string keys, and presets as an enum mapping to page lists.
**When to use:** Any time UI configuration options need persistence with forward-compatible keys.
**Example:**
```kotlin
// domain/model/HudModels.kt
enum class HudPage(val key: String) {
    EXECUTION("execution"),
    INSTRUCTION("instruction"),
    STATS("stats");

    companion object {
        fun fromKey(key: String): HudPage? = entries.find { it.key == key }
    }
}

enum class HudPreset(val key: String, val pages: List<HudPage>) {
    ESSENTIAL("essential", listOf(HudPage.EXECUTION)),
    BIOMECHANICS("biomechanics", listOf(HudPage.EXECUTION, HudPage.STATS)),
    FULL("full", listOf(HudPage.EXECUTION, HudPage.INSTRUCTION, HudPage.STATS));

    companion object {
        fun fromKey(key: String): HudPreset = entries.find { it.key == key } ?: FULL
    }
}
```
**Key constraint:** `EXECUTION` is present in every preset (success criteria #4).

### Pattern 2: Preference Pipeline (Identical to colorBlindModeEnabled)
**What:** String-based setting flowing through UserPreferences -> PreferencesManager -> SettingsManager -> MainViewModel -> NavGraph -> SettingsTab.
**When to use:** Every new user-configurable setting in this codebase.
**Example:**
```kotlin
// UserPreferences.kt
data class UserPreferences(
    // ... existing fields ...
    val hudPreset: String = HudPreset.FULL.key  // Default: all pages visible
)

// PreferencesManager.kt
private const val KEY_HUD_PRESET = "hud_preset"
// In loadPreferences():
hudPreset = settings.getStringOrNull(KEY_HUD_PRESET) ?: HudPreset.FULL.key
// Setter:
suspend fun setHudPreset(preset: String) {
    settings.putString(KEY_HUD_PRESET, preset)
    updateAndEmit { copy(hudPreset = preset) }
}

// SettingsManager.kt
val hudPreset: StateFlow<String> = userPreferences
    .map { it.hudPreset }
    .stateIn(scope, SharingStarted.Eagerly, HudPreset.FULL.key)

fun setHudPreset(preset: String) {
    scope.launch { preferencesManager.setHudPreset(preset) }
}
```

### Pattern 3: Dynamic Pager Filtering
**What:** Compute `visiblePages` list from preset, use its size as `pageCount`, and index into the filtered list inside `when(page)`.
**When to use:** When HorizontalPager content is determined by user configuration.
**Example:**
```kotlin
// In WorkoutHud composable:
val visiblePages = remember(hudPreset) {
    HudPreset.fromKey(hudPreset).pages
}
val pagerState = rememberPagerState(pageCount = { visiblePages.size })

HorizontalPager(state = pagerState, ...) { pageIndex ->
    when (visiblePages[pageIndex]) {
        HudPage.EXECUTION -> ExecutionPage(...)
        HudPage.INSTRUCTION -> InstructionPage(...)
        HudPage.STATS -> StatsPage(...)
    }
}
```

### Pattern 4: Settings UI (SegmentedButton Row)
**What:** Material 3 `SingleChoiceSegmentedButtonRow` for preset selection in the Accessibility/Display section.
**When to use:** Small fixed set of mutually exclusive options.
**Example:**
```kotlin
SingleChoiceSegmentedButtonRow {
    HudPreset.entries.forEachIndexed { index, preset ->
        SegmentedButton(
            selected = currentPreset == preset.key,
            onClick = { onHudPresetChange(preset.key) },
            shape = SegmentedButtonDefaults.itemShape(index, HudPreset.entries.size)
        ) {
            Text(preset.displayName)
        }
    }
}
```

### Anti-Patterns to Avoid
- **Storing integer page indices instead of string keys:** Integer indices break when pages are reordered or new pages are added. The success criteria explicitly require string keys.
- **Hiding pages inside the pager via `if` blocks:** This creates invisible "ghost" pages where the user can swipe to empty content. Always filter the list before constructing the pager.
- **Using `pageCount` as a separate parameter disconnected from the page list:** Keep the page list as the single source of truth; derive `pageCount` from `list.size`.
- **Making Execution page removable:** Every preset must include `HudPage.EXECUTION`. Enforce this in the enum definition, not with runtime checks.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Preset persistence | Custom file-based config | `multiplatform-settings` `putString`/`getStringOrNull` | Already in project, handles Android SharedPreferences + iOS NSUserDefaults |
| Segmented button | Custom toggle row | Material 3 `SingleChoiceSegmentedButtonRow` | Built-in accessibility, ripple, theming |
| Pager | Custom swipe gesture handler | `HorizontalPager` from foundation.pager | Already in use, handles state, animations, gestures |
| Dynamic page filtering | Custom pager adapter | Filtered `List<HudPage>` indexed by pager | Compose pagers are composable-driven, not adapter-driven |

**Key insight:** This feature is a thin configuration layer on top of an already-working pager. The total new code is ~100 lines of domain models, ~30 lines of settings pipeline, ~20 lines of HUD filtering, and ~40 lines of settings UI.

## Common Pitfalls

### Pitfall 1: PagerState Key Mismatch on Preset Change
**What goes wrong:** When the user changes preset while a workout is active (unlikely but possible via Settings), the pager state's `pageCount` changes but `currentPage` might exceed the new count.
**Why it happens:** `rememberPagerState` caches the current page index. If the user was on page 2 (StatsPage in Full preset) and switches to Essential (1 page), the index is out of bounds.
**How to avoid:** Key the pager state on the preset value so it resets when the preset changes: `rememberPagerState(key1 = hudPreset) { visiblePages.size }`. Alternatively, since users are unlikely to change presets during an active workout, accept that the page resets to 0 on preset change.
**Warning signs:** Crash or blank page when switching presets.

### Pitfall 2: Missing FakePreferencesManager Override
**What goes wrong:** Adding `setHudPreset` to the `PreferencesManager` interface without implementing it in `FakePreferencesManager` causes compile errors in tests.
**Why it happens:** The fake in `commonTest` and `androidTest` both implement the interface.
**How to avoid:** Update both `FakePreferencesManager` files (commonTest and androidTest) when adding new interface methods.
**Warning signs:** Test compilation failures.
**Pre-existing issue:** `FakePreferencesManager` in `commonTest` is already missing `setColorBlindModeEnabled()` from Phase 17. This must be fixed alongside the new `setHudPreset` addition.

### Pitfall 3: Forgetting to Thread hudPreset Through Full Pipeline
**What goes wrong:** The preset selection works in Settings but the WorkoutHud doesn't react.
**Why it happens:** The pipeline has 6 layers: UserPreferences -> PreferencesManager -> SettingsManager -> MainViewModel -> WorkoutUiState -> WorkoutTab -> WorkoutHud. Missing any layer breaks the chain.
**How to avoid:** Follow the exact pattern used for `colorBlindModeEnabled` (Phase 17) or `enableVideoPlayback` as a checklist. The pipeline files are:
  1. `UserPreferences.kt` - add field
  2. `PreferencesManager.kt` - add interface method + implementation
  3. `SettingsManager.kt` - add StateFlow + setter
  4. `MainViewModel.kt` - add delegation
  5. `WorkoutUiState.kt` - add field
  6. `WorkoutTab.kt` - pass to WorkoutHud
  7. `WorkoutHud.kt` - accept and use
  8. `NavGraph.kt` - wire SettingsTab
  9. `SettingsTab.kt` - add UI
**Warning signs:** Setting persists but HUD doesn't change.

### Pitfall 4: Pager Dot Indicator Not Updating
**What goes wrong:** The dot indicator at the bottom of WorkoutHud still shows 3 dots regardless of preset.
**Why it happens:** The indicator uses `repeat(pagerState.pageCount)` which should auto-update, but if a separate hardcoded `3` was used somewhere, it won't match.
**How to avoid:** Verify the indicator uses `pagerState.pageCount` (it currently does at line 194 of WorkoutHud.kt) and that `pagerState` is created with the dynamic count.
**Warning signs:** Extra dots with no corresponding pages.

## Code Examples

### Current WorkoutHud Pager (Lines 87-183 of WorkoutHud.kt)
```kotlin
// CURRENT: Hardcoded 3 pages
val pagerState = rememberPagerState(pageCount = { 3 })
// ...
HorizontalPager(state = pagerState, ...) { page ->
    when (page) {
        0 -> ExecutionPage(...)
        1 -> InstructionPage(...)
        2 -> StatsPage(...)
    }
}
```

### Target: Dynamic Page Filtering
```kotlin
// TARGET: Preset-filtered pages
val visiblePages = remember(hudPreset) {
    HudPreset.fromKey(hudPreset).pages
}
val pagerState = rememberPagerState(pageCount = { visiblePages.size })
// ...
HorizontalPager(state = pagerState, ...) { pageIndex ->
    when (visiblePages[pageIndex]) {
        HudPage.EXECUTION -> ExecutionPage(...)
        HudPage.INSTRUCTION -> InstructionPage(...)
        HudPage.STATS -> StatsPage(...)
    }
}
```

### Current Settings Pipeline Pattern (colorBlindModeEnabled as Reference)
```kotlin
// 1. UserPreferences.kt
val colorBlindModeEnabled: Boolean = false

// 2. PreferencesManager.kt
private const val KEY_COLOR_BLIND_MODE = "color_blind_mode_enabled"
override suspend fun setColorBlindModeEnabled(enabled: Boolean) {
    settings.putBoolean(KEY_COLOR_BLIND_MODE, enabled)
    updateAndEmit { copy(colorBlindModeEnabled = enabled) }
}

// 3. SettingsManager.kt
val colorBlindModeEnabled: StateFlow<Boolean> = userPreferences
    .map { it.colorBlindModeEnabled }
    .stateIn(scope, SharingStarted.Eagerly, false)
fun setColorBlindModeEnabled(enabled: Boolean) {
    scope.launch { preferencesManager.setColorBlindModeEnabled(enabled) }
}

// 4. MainViewModel.kt
val colorBlindModeEnabled: StateFlow<Boolean> get() = settingsManager.colorBlindModeEnabled
fun setColorBlindModeEnabled(enabled: Boolean) = settingsManager.setColorBlindModeEnabled(enabled)

// 5. NavGraph.kt (Settings route)
colorBlindModeEnabled = userPreferences.colorBlindModeEnabled,
onColorBlindModeChange = { viewModel.setColorBlindModeEnabled(it) },
```

### HUD Preset Descriptions for Settings UI
```kotlin
// Display names and descriptions for each preset
val presetInfo = mapOf(
    HudPreset.ESSENTIAL to ("Essential" to "Rep counter and force gauge only"),
    HudPreset.BIOMECHANICS to ("Biomechanics" to "Execution + live velocity/force stats"),
    HudPreset.FULL to ("Full" to "All pages including exercise video")
)
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Hardcoded pager pageCount | Dynamic pageCount from filtered list | Compose Foundation 1.5+ | `rememberPagerState` accepts lambda `pageCount` that re-evaluates on recomposition |
| Adapter-based pagers (ViewPager2) | Composable-based HorizontalPager | Compose Foundation 1.4+ | No adapter needed; page content is a composable lambda |
| SharedPreferences (Android-only) | multiplatform-settings | Already in project | Cross-platform persistence for KMP |

## Pre-Existing Issues Found

### 1. FakePreferencesManager Missing setColorBlindModeEnabled
**File:** `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakePreferencesManager.kt`
**Issue:** The `PreferencesManager` interface includes `setColorBlindModeEnabled(enabled: Boolean)` (added in Phase 17), but `FakePreferencesManager` does not override it. This means tests that use `FakePreferencesManager` would fail to compile if they invoke `setColorBlindModeEnabled`.
**Fix:** Add the missing override alongside the new `setHudPreset` method during this phase.

### 2. androidTest FakePreferencesManager May Also Be Missing
**File:** `androidApp/src/androidTest/kotlin/com/devil/phoenixproject/testutil/FakePreferencesManager.kt`
**Issue:** Likely has the same gap. Must be checked and updated in parallel.

## Open Questions

1. **Preset selector placement in SettingsTab**
   - What we know: The Accessibility section (Phase 17) currently only contains the color-blind toggle. HUD customization is display-related.
   - What's unclear: Should the HUD preset go in the existing Accessibility section, a new "Workout Display" section, or alongside the existing video playback toggle?
   - Recommendation: Create a new "Workout HUD" or "Workout Display" subsection within Settings, placed near the video playback toggle since both control workout display. The Accessibility section should stay focused on accessibility concerns.

2. **Preset default value**
   - What we know: The requirement says users can "control which HUD pages are visible." No default is specified.
   - What's unclear: Should new users see all pages (Full) by default, or start with Essential?
   - Recommendation: Default to `FULL` so existing users see no change in behavior after the update. Users who want fewer pages opt in to Essential or Biomechanics.

## Sources

### Primary (HIGH confidence)
- **Codebase analysis** - WorkoutHud.kt (lines 87-207): Current HorizontalPager with hardcoded 3-page setup
- **Codebase analysis** - PreferencesManager.kt: Established `putString`/`getStringOrNull` pattern for enum persistence (WeightUnit)
- **Codebase analysis** - SettingsManager.kt: StateFlow + delegation pattern for all user preferences
- **Codebase analysis** - NavGraph.kt (lines 298-345): Complete wiring pattern for Settings -> ViewModel -> UI
- **Codebase analysis** - Phase 17 research and implementation: Identical pipeline for colorBlindModeEnabled

### Secondary (MEDIUM confidence)
- **Compose Foundation docs** - HorizontalPager `pageCount` parameter accepts a lambda that re-evaluates dynamically

### Tertiary (LOW confidence)
- None

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - All libraries already in use; no new dependencies
- Architecture: HIGH - Exact pattern replicated from Phase 17 (colorBlindModeEnabled pipeline) and existing pager
- Pitfalls: HIGH - All identified from direct codebase analysis; pre-existing FakePreferencesManager gap confirmed

**Research date:** 2026-02-28
**Valid until:** 2026-03-28 (stable domain, no fast-moving external dependencies)
