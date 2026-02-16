# Phase 3: UI Composable Decomposition - Research

**Researched:** 2026-02-13
**Domain:** Kotlin Multiplatform Compose UI file decomposition (pure refactoring)
**Confidence:** HIGH

## Summary

Phase 3 is a pure file-organization refactoring with zero behavioral changes. Two oversized Compose files -- `HistoryAndSettingsTabs.kt` (2,750 lines) and `WorkoutTab.kt` (2,840 lines) -- need to be split into focused, navigable files. These are by far the largest files in the presentation layer; the next-largest screen file is 1,161 lines, and most are 500-900 lines.

Both files live in `com.devil.phoenixproject.presentation.screen` within `shared/src/commonMain/`. Because all new files stay in the same package, no import changes are needed at call sites for public composables. Private composables that move to new files must become `internal` or public. The existing codebase already demonstrates the "one major composable per file" pattern (e.g., `RoutinesTab.kt`, `InsightsTab.kt`, `ExercisesTab.kt`), so this is a matter of following established convention.

**Primary recommendation:** Extract in two plans: (1) split `HistoryAndSettingsTabs.kt` into `HistoryTab.kt` and `SettingsTab.kt`, (2) decompose `WorkoutTab.kt` by pulling dialogs and shared cards to their own files. Both plans are independent and can be done in either order.

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Compose Multiplatform | 1.7.1 | UI framework | Project standard, KMP |
| Material3 | (bundled with Compose 1.7.1) | Design system | Project standard |
| Kotlin | 2.0.21 | Language | Project standard |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Koin Compose | 4.0.0 | DI injection in composables | `koinInject()` used in SettingsTab |

### Alternatives Considered
None -- this is pure file reorganization, no new dependencies.

## Architecture Patterns

### Current Project Structure (presentation layer)
```
shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/
  screen/          # Tab-level and screen-level composables (one per file typically)
  components/      # Reusable UI components (dialogs, cards, widgets)
  navigation/      # NavGraph.kt
  manager/         # State management (Phase 2 output)
  util/            # Window size classes, etc.
  viewmodel/       # ViewModels
```

### Pattern 1: One Major Composable Per File (Established)
**What:** Each top-level screen/tab composable gets its own file, named after the composable.
**When to use:** Always for public @Composable functions that represent a tab or screen.
**Evidence:** `RoutinesTab.kt`, `InsightsTab.kt`, `ExercisesTab.kt`, `BadgesScreen.kt`, `AnalyticsScreen.kt` all follow this pattern.

### Pattern 2: Private Helpers Stay Co-located OR Move to Components
**What:** Private helper composables that serve only one parent can stay in the parent file. Shared helpers go to `components/`.
**When to use:** If a composable is used by multiple screens, it belongs in `components/`. If used by only one screen, it can stay in the screen file or be extracted if the file is still too large.
**Evidence:** `components/` directory has 58 files of focused, reusable components. Several dialogs already live there (`ConnectionErrorDialog.kt`, `EditProfileDialog.kt`, etc.).

### Pattern 3: Dialogs in Separate Files
**What:** Dialog composables get their own files, either in `screen/` (if screen-specific) or `components/` (if reusable).
**Evidence:** `ExerciseEditDialog.kt` (in `screen/`), `ConnectionErrorDialog.kt` (in `components/`), `DateRangePickerDialog.kt` (in `components/`).

### Anti-Patterns to Avoid
- **Moving private helpers and changing their visibility unnecessarily:** If a helper is only used by one composable and the file is a reasonable size after the split, keep them together.
- **Creating overly granular files:** A 50-line card composable does not need its own file if it is only used by one parent.
- **Breaking same-package assumptions:** All files MUST remain in `com.devil.phoenixproject.presentation.screen` package. New files in this package are automatically discovered by the Kotlin compiler.
- **Changing function signatures during extraction:** This is a move-only refactoring. Do not rename parameters, change types, or modify behavior.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Visual regression testing | Custom screenshot comparisons | @Preview functions + manual comparison | Project has no screenshot testing infrastructure; @Preview is sufficient |
| Import management | Manual import fixup | IDE auto-import / compiler errors | Same-package visibility means minimal import changes needed |

**Key insight:** This phase is purely mechanical file extraction. The risk is NOT in the approach but in accidentally changing behavior. The verification strategy matters more than the implementation technique.

## Common Pitfalls

### Pitfall 1: Duplicate ExercisePickerDialog
**What goes wrong:** `WorkoutTab.kt` line 2651 defines its own `ExercisePickerDialog` with a DIFFERENT signature than `components/ExercisePicker.kt` line 102. Both are public and in different packages, so they coexist without conflict currently.
**Why it happens:** The WorkoutTab version is simpler (3 params: `exerciseRepository`, `onDismiss`, `onExerciseSelected`). The components version is feature-rich (custom exercises, favorites, video playback, full-screen mode, 9 params).
**How to avoid:** When extracting, keep the WorkoutTab version as a private/internal helper within the dialog file OR rename it to avoid confusion. Do NOT accidentally merge them -- they serve different purposes.
**Warning signs:** Compile error about ambiguous references if both are imported into the same file.

### Pitfall 2: SetSummaryCard is Cross-Referenced
**What goes wrong:** `SetSummaryCard` (defined in `WorkoutTab.kt` line 1483) is called from BOTH `WorkoutTab.kt` AND `HistoryAndSettingsTabs.kt`. Moving it to a workout-specific file would break the history tab.
**Why it happens:** It is a genuinely shared component that ended up in the wrong file.
**How to avoid:** Extract `SetSummaryCard` (plus its private helpers `SummaryStatCard`, `SummaryForceCard`, `EchoPhaseBreakdownCard`, `PhaseStatColumn`) to its own file first, before splitting either parent file. Or place it in a shared location like `components/`.
**Warning signs:** Compile error in `HistoryAndSettingsTabs.kt` after moving `SetSummaryCard`.

### Pitfall 3: Private Utility Functions Shared Across Composables
**What goes wrong:** `WorkoutTab.kt` has private utility functions at the bottom (`formatFloat`, `Float.pow`, `formatReps`) that may be used by multiple composables within the file.
**Why it happens:** These are file-scoped private helpers that become invisible when composables move to new files.
**How to avoid:** Before splitting, audit which composables use which private helpers. Either: (a) duplicate the helper in both files if small, (b) make it `internal` and keep in a shared location, or (c) move to a `util` file.
**Warning signs:** Compile error "unresolved reference" for private functions after extraction.

### Pitfall 4: Private Visibility Must Change
**What goes wrong:** Several composables in both files are `private` (`WorkoutSetupCard`, `ErrorCard`, `WorkoutPausedCard`, `CompletedCard`, `ActiveWorkoutCard`, `CompletedSetsSection`, `DiscoModeUnlockDialog`). If extracted to their own files, they cannot remain `private`.
**Why it happens:** Kotlin `private` at file level means the function is only visible within that file.
**How to avoid:** Change `private` to `internal` (module-visible) when extracting. Since these are only used within the `shared` module, `internal` is appropriate. Alternatively, keep them in the same file as their sole caller.
**Warning signs:** Compile error "cannot access private function" from the parent composable file.

### Pitfall 5: HistoryAndSettingsTabs.kt Utility Functions
**What goes wrong:** `HistoryAndSettingsTabs.kt` has private utilities at the bottom (`formatTimestamp`, `formatRelativeTimestamp`, `formatDuration`) used by the history cards. After splitting, `SettingsTab.kt` does not need them, but `HistoryTab.kt` does.
**Why it happens:** These are file-scoped utilities that naturally belong with the history composables.
**How to avoid:** Move them into `HistoryTab.kt` alongside the history composables.

### Pitfall 6: Import Bloat After Split
**What goes wrong:** The monolithic files have large import blocks serving ALL composables. After splitting, each new file inherits a superset of imports it does not need.
**Why it happens:** Lazy copy of the full import block to each new file.
**How to avoid:** After extraction, run the Kotlin formatter / optimize imports to remove unused imports. This is cosmetic but keeps files clean.

## Code Examples

### Extracting a Public Composable to a New File (Same Package)

```kotlin
// NEW FILE: HistoryTab.kt
package com.devil.phoenixproject.presentation.screen

// Only imports needed by HistoryTab and its helpers
import ...

@Composable
fun HistoryTab(
    // Same signature, same body -- unchanged
)

// Helper composables that HistoryTab calls:
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutHistoryCard(...)

@Composable
private fun CompletedSetsSection(...)

@Composable
fun GroupedRoutineCard(...)

@Composable
fun WorkoutSessionCard(...)

@Composable
fun MetricItem(...)

@Composable
fun EnhancedMetricItem(...)

// Utility functions used by history composables
private fun formatTimestamp(timestamp: Long): String { ... }
private fun formatRelativeTimestamp(timestamp: Long): String { ... }
private fun formatDuration(millis: Long): String { ... }
```

### Changing Visibility for Extracted Private Composables

```kotlin
// BEFORE (in WorkoutTab.kt):
@Composable
private fun WorkoutSetupCard(...) { ... }

// AFTER (in WorkoutCards.kt, still same package):
@Composable
internal fun WorkoutSetupCard(...) { ... }
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Monolithic "God files" with all composables | One composable per file, shared components in `components/` | Compose convention from inception | Better navigability, IDE performance, code review |

**Deprecated/outdated:**
- Nothing specific -- this is a refactoring phase with no library/API changes.

## Key File Inventory

### HistoryAndSettingsTabs.kt (2,750 lines) Decomposition Map

| Composable | Lines | Visibility | Used By | Destination |
|------------|-------|------------|---------|-------------|
| `HistoryTab` | 56-158 | public | AnalyticsScreen | **HistoryTab.kt** |
| `WorkoutHistoryCard` | 160-483 | public | HistoryTab | **HistoryTab.kt** |
| `CompletedSetsSection` | 484-580 | private | WorkoutHistoryCard | **HistoryTab.kt** |
| `GroupedRoutineCard` | 581-962 | public | HistoryTab | **HistoryTab.kt** |
| `WorkoutSessionCard` | 963-1011 | public | GroupedRoutineCard | **HistoryTab.kt** |
| `MetricItem` | 1012-1027 | public | WorkoutHistoryCard, WorkoutSessionCard | **HistoryTab.kt** |
| `SettingsTab` | 1029-2565 | public | NavGraph | **SettingsTab.kt** |
| `DiscoModeUnlockDialog` | 2570-2695 | private | SettingsTab | **SettingsTab.kt** |
| `formatTimestamp` | 2697-2703 | private | History composables | **HistoryTab.kt** |
| `formatRelativeTimestamp` | 2704-2708 | private | (unused, future) | **HistoryTab.kt** |
| `EnhancedMetricItem` | 2709-2743 | public | History composables | **HistoryTab.kt** |
| `formatDuration` | 2744-2750 | private | History composables | **HistoryTab.kt** |

**Estimated sizes after split:**
- `HistoryTab.kt`: ~1,180 lines (lines 56-1027 + 2697-2750)
- `SettingsTab.kt`: ~1,570 lines (lines 1029-2695)

### WorkoutTab.kt (2,840 lines) Decomposition Map

| Composable | Lines | Visibility | Used By | Destination |
|------------|-------|------------|---------|-------------|
| `WorkoutTab` (state-holder) | 52-116 | public | Navigation | **WorkoutTab.kt** (keep) |
| `WorkoutTab` (main) | 117-543 | public | Above overload | **WorkoutTab.kt** (keep) |
| `WorkoutSetupCard` | 544-592 | private | WorkoutTab | **WorkoutTab.kt** (keep, small) |
| `ErrorCard` | 593-642 | private | WorkoutTab | **WorkoutTab.kt** (keep, small) |
| `WorkoutPausedCard` | 643-724 | private | WorkoutTab | **WorkoutTab.kt** (keep, small) |
| `CompletedCard` | 725-848 | private | WorkoutTab | **WorkoutTab.kt** (keep) |
| `ActiveWorkoutCard` | 848-888 | private | WorkoutTab | **WorkoutTab.kt** (keep, small) |
| `ConnectionCard` | 889-1055 | public | WorkoutTab | Could extract, but self-contained |
| `RepCounterCard` | 1056-1135 | public | WorkoutTab | Could extract |
| `LiveMetricsCard` | 1136-1252 | public | WorkoutTab | Could extract |
| `VerticalCablePositionBar` | 1253-1353 | public | WorkoutTab | Could extract |
| `CurrentExerciseCard` | 1354-1481 | public | WorkoutTab | Could extract |
| `SetSummaryCard` | 1483-1778 | public | WorkoutTab, HistoryTab | **MUST extract** (shared) |
| `SummaryStatCard` | 1779-1844 | private | SetSummaryCard | Follows SetSummaryCard |
| `SummaryForceCard` | 1845-1910 | private | SetSummaryCard | Follows SetSummaryCard |
| `EchoPhaseBreakdownCard` | 1911-1998 | private | SetSummaryCard | Follows SetSummaryCard |
| `PhaseStatColumn` | 1999-2040 | private | EchoPhaseBreakdownCard | Follows SetSummaryCard |
| `WorkoutSetupDialog` | 2041-2471 | public | WorkoutTab | **WorkoutSetupDialog.kt** |
| `ModeSubSelectorDialog` | 2476-2648 | public | WorkoutSetupDialog | **ModeSubSelectorDialog.kt** |
| `ExercisePickerDialog` | 2649-2798 | public | WorkoutSetupDialog | **WorkoutSetupDialog.kt** (local to setup flow) |
| `formatFloat` | 2800-2810 | private | SetSummaryCard, others | Follows users |
| `Float.pow` | 2812-2816 | private | formatFloat | Follows formatFloat |
| `formatReps` | 2822-2839 | private | CompletedCard | **WorkoutTab.kt** (keep) |

**Recommended extraction groups:**
1. `SetSummaryCard` + helpers -> `SetSummaryCard.kt` (shared component, ~560 lines)
2. `WorkoutSetupDialog` + `ExercisePickerDialog` (simple) -> `WorkoutSetupDialog.kt` (~760 lines)
3. `ModeSubSelectorDialog` -> `ModeSubSelectorDialog.kt` (~172 lines)
4. Remaining `WorkoutTab.kt` -> ~1,350 lines (manageable)

## Dependency Graph (Critical Cross-References)

```
HistoryAndSettingsTabs.kt
  HistoryTab -> WorkoutHistoryCard -> CompletedSetsSection, MetricItem
                                   -> SetSummaryCard (from WorkoutTab.kt!)
             -> GroupedRoutineCard -> WorkoutSessionCard -> MetricItem
                                  -> SetSummaryCard (from WorkoutTab.kt!)
  SettingsTab -> DiscoModeUnlockDialog

WorkoutTab.kt
  WorkoutTab -> WorkoutSetupCard, ErrorCard, WorkoutPausedCard,
                CompletedCard, ActiveWorkoutCard, ConnectionCard,
                RepCounterCard, LiveMetricsCard, VerticalCablePositionBar,
                CurrentExerciseCard, SetSummaryCard,
                WorkoutSetupDialog -> ExercisePickerDialog (simple),
                                     ModeSubSelectorDialog
```

**Critical dependency:** `SetSummaryCard` MUST be extracted BEFORE splitting `HistoryAndSettingsTabs.kt`, because both tabs reference it and it currently lives in `WorkoutTab.kt`.

## Open Questions

1. **WorkoutTab card extraction granularity**
   - What we know: After extracting dialogs and SetSummaryCard, WorkoutTab.kt drops to ~1,350 lines. The remaining cards (ConnectionCard, RepCounterCard, LiveMetricsCard, etc.) are each 80-170 lines and only used by WorkoutTab.
   - What's unclear: Is 1,350 lines acceptable, or should more cards be extracted?
   - Recommendation: 1,350 lines is within the range of other screen files (e.g., ExerciseEditBottomSheet is 1,161). Accept it for now. Further extraction can be a follow-up if desired.

2. **Duplicate ExercisePickerDialog disposition**
   - What we know: Two different `ExercisePickerDialog` functions exist -- simple (WorkoutTab) and rich (components/ExercisePicker.kt).
   - What's unclear: Should the simple one be consolidated into the rich one, or kept separate?
   - Recommendation: Out of scope for Phase 3 (which is strictly move-only). Keep the simple version as-is in the extracted `WorkoutSetupDialog.kt`. Flag for future cleanup.

3. **Preview file updates**
   - What we know: `WorkoutTabPreviews.kt` (in androidMain) exists and references composables from WorkoutTab.kt.
   - What's unclear: Whether moving composables breaks preview resolution.
   - Recommendation: Since previews import from the same package, they should continue to work. Verify after extraction.

## Execution Order Recommendation

```
Plan 03-01: HistoryAndSettingsTabs Split
  Step 1: Extract SetSummaryCard + helpers from WorkoutTab.kt -> SetSummaryCard.kt
  Step 2: Extract HistoryTab + history composables -> HistoryTab.kt
  Step 3: Extract SettingsTab + DiscoModeUnlockDialog -> SettingsTab.kt
  Step 4: Delete HistoryAndSettingsTabs.kt
  Step 5: Verify build compiles, run tests

Plan 03-02: WorkoutTab Dialog Decomposition
  Step 1: Extract WorkoutSetupDialog + simple ExercisePickerDialog -> WorkoutSetupDialog.kt
  Step 2: Extract ModeSubSelectorDialog -> ModeSubSelectorDialog.kt
  Step 3: Clean up WorkoutTab.kt imports
  Step 4: Verify build compiles, run tests
```

**SetSummaryCard must be extracted in Plan 03-01 Step 1** because Plan 03-01 Steps 2-4 depend on it being available outside WorkoutTab.kt.

## Sources

### Primary (HIGH confidence)
- Direct codebase analysis of all files in `presentation/screen/` and `presentation/components/`
- Line-by-line grep of all cross-references between target files
- Build configuration analysis (no explicit file references in Gradle)

### Secondary (MEDIUM confidence)
- Compose Multiplatform 1.7.1 same-package visibility rules (standard Kotlin behavior)
- KMP source set auto-discovery (verified: no Gradle file enumerations)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - No new libraries, pure refactoring
- Architecture: HIGH - Following established patterns already in the codebase
- Pitfalls: HIGH - Identified through direct code analysis of cross-references and visibility modifiers

**Research date:** 2026-02-13
**Valid until:** Indefinite (no external dependency changes involved)
