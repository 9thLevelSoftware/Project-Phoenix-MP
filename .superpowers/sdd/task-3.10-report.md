# Task 3.10 — ExpressiveCard Adoption: Inventory & Conversion Report

## Summary

| Metric | Value |
|--------|-------|
| Total tappable-card sites found | 19 |
| Converted to ExpressiveCard | 16 |
| Skipped | 3 |
| Static cards surveyed (no action) | 9+ |

---

## Complete Inventory

| # | File | Line | Screen / Component | Tap action | Verdict | Reason |
|---|------|------|--------------------|------------|---------|--------|
| 1 | `components/InsightCards.kt` | 971 | `NextBadgeProgressCard` | Navigate to Badges screen | **CONVERT** | Tappable info card; `Card(modifier...clickable)` → `ExpressiveCard(onClick)`; default colors+shape dropped |
| 2 | `components/cycle/RestDayRow.kt` | 20 | `RestDayRow` (inside `SwipeableCycleItem`) | Edit rest day config | **SKIP** | Card is always rendered inside `SwipeableCycleItem` (swipe-to-dismiss container); press-scale animation would fight the swipe gesture |
| 3 | `components/cycle/UnifiedCycleCreationSheet.kt` | 140 | `TemplateCard` | Select cycle template | **CONVERT** | `Card(modifier...clickable)` → `ExpressiveCard(onClick)`; non-default colors+shape passed through |
| 4 | `components/cycle/WorkoutDayRow.kt` | 22 | `WorkoutDayRow` (inside `SwipeableCycleItem`) | Edit workout day | **SKIP** | Same reason as RestDayRow — exclusively used inside `SwipeableCycleItem` swipe container |
| 5 | `components/UpNextWidget.kt` | 410 | Up-next dashboard widget | Start workout / view cycles | **CONVERT** | `Card(onClick = { ... })` — semantically meaningful `primaryContainer` color passed through; `shape = medium` is default (dropped) |
| 6 | `screen/AssessmentWizardScreen.kt` | 175 | Assessment exercise-select step | Select exercise for 1RM assessment | **CONVERT** | `Card(onClick = ...)` — non-default `surfaceContainerHigh` colors + `shapes.small` passed through |
| 7 | `screen/BadgesScreen.kt` | 402 | `BadgeCard` | Open badge detail | **CONVERT** | `Card(modifier...clickable)` → `ExpressiveCard(onClick)`; dynamic colors (isEarned: surfaceVariant vs surface) and dynamic border passed through; existing `.scale(scale).alpha(alpha)` modifiers for isEarned state preserved on the modifier param (compound scale is intentional: earned-state visual × press-scale) |
| 8 | `screen/CycleReviewScreen.kt` | 135 | `CycleReviewDayCard` | Expand/collapse cycle day details | **CONVERT** | `Card(modifier...clickable(enabled = canExpand))` → `ExpressiveCard(onClick, enabled = canExpand)`; dynamic colors (isRestDay: surfaceContainerHigh vs surfaceContainer) passed through; `clickable` import removed |
| 9 | `screen/ExerciseDetailScreen.kt` | 724 | `SessionHistoryRow` | Expand/collapse session history entry | **CONVERT** | `Card(onClick = ...)` — non-default `surfaceContainerLow` colors + `shapes.small` passed through |
| 10 | `screen/ExercisesTab.kt` | 194 | `ExerciseSummaryRow` | Navigate to exercise detail | **CONVERT** | `Card(modifier...clickable)` → `ExpressiveCard(onClick)`; non-default `surfaceContainerLow` colors + `shapes.small` passed through |
| 11 | `screen/ExternalActivitiesScreen.kt` | 129 | `ExternalActivityItem` | Expand/collapse external activity | **CONVERT** | `Card(onClick = ...)` — default colors dropped; non-default `elevation = 4.dp` (vs default 8.dp) and `border = 1.dp outlineVariant` (vs default 2.dp) passed through |
| 12 | `screen/ExternalIntegrationScreens.kt` | 474 | `HubCard` | Navigate to integration hub section | **CONVERT** | `Card(onClick = onClick)` — default colors dropped; non-default `shapes.extraSmall` and `border = 1.dp outlineVariant` passed through |
| 13 | `screen/ExternalIntegrationScreens.kt` | 557 | `EntityCard` (onClick != null branch) | Navigate to entity detail | **CONVERT** | `Card(onClick = onClick)` in the non-null branch of `EntityCard`; null branch (static) stays as `Card()`; non-default `shapes.extraSmall` + `border = 1.dp` passed through |
| 14 | `screen/HistoryTab.kt` | 281 | Single-exercise session card | Expand/collapse session | **CONVERT** | `Card(onClick = ...)` — default colors+shape+elevation dropped; non-default `border = BorderStroke(2.dp, primary.copy(alpha=0.2f))` passed through; `.shadow(8.dp, ...)` preserved on modifier |
| 15 | `screen/HistoryTab.kt` | 810 | Daily-routine session card | Expand/collapse session | **CONVERT** | Same treatment as site 14 |
| 16 | `screen/ModeConfirmationScreen.kt` | 263 | Exercise config card in routine setup | Open exercise config modal | **CONVERT** | `Card(modifier...clickable)` → `ExpressiveCard(onClick)`; non-default `surfaceContainer` colors + `shapes.small` passed through; `clickable` import removed |
| 17 | `screen/RoutinePickerDialog.kt` | 76 | Routine list item in picker dialog | Select routine | **CONVERT** | `Card(modifier...clickable)` → `ExpressiveCard(onClick)`; non-default `surfaceContainerHigh` colors + `shapes.small` passed through; `clickable` import removed |
| 18 | `screen/RoutinesTab.kt` | 1057 | `RoutineCard` | Expand/collapse or toggle selection | **SKIP** | Uses `combinedClickable(onClick, onLongClick)` for long-press selection mode; `ExpressiveCard` only accepts `onClick` — converting would lose the `onLongClick` handler. Would require ExpressiveCard API extension (out of scope). |
| 19 | `screen/TrainingCyclesScreen.kt` | 1048 | `CycleCard` | Expand/collapse cycle | **CONVERT** | `Card(modifier...clickable)` → `ExpressiveCard(onClick)`; dynamic colors (isActive: primaryContainer vs surfaceContainerHigh) + dynamic border (isActive: primary vs null) passed through; `clickable` import removed |

---

## Sites NOT in inventory (excluded by brief or structural reason)

| File | Reason for exclusion |
|------|---------------------|
| `components/ExpressiveComponents.kt:54` | Internal `Card()` call inside `ExpressiveCard` itself — IS the implementation |
| `components/ExerciseRowInSuperset.kt:46` | Explicitly excluded by brief (Task 3.2 normalized; uses `combinedClickable`) |
| `components/ExerciseRowWithConnector.kt:142` | Explicitly excluded by brief (Task 3.2 normalized; Card is static, `combinedClickable` on inner Row) |
| `components/EquipmentRackSelectionCard.kt:101` | `.clickable` is on an inner `Row` header, not on the `Card` itself — Card is static |
| `components/BiomechanicsHistoryCard.kt:337` | `.clickable` is on an inner `Column`, not on the `Card` itself — Card is static |
| `screen/SettingsTab.kt` (13 sites) | All Cards are static info/section containers; one `.clickable` is on an inner `Row` (Dominatrix easter egg) |
| `components/ExercisePicker.kt:599` | Static card used as video player container — no onClick |

---

## KDoc Convention Block (added to ExpressiveComponents.kt)

```
 * ## Card usage convention
 * - **Interactive cards** (navigates, expands, selects): use [ExpressiveCard].
 *   Provides press-scale spring animation, consistent 2dp border, and 8dp elevation.
 * - **Static / informational cards** (no tap action): use plain `Card(
 *   colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
 *   shape = MaterialTheme.shapes.medium,
 * )`.
```
