## Task 3.3 Report: Echo-Level Pill Selector Unification

### What Was Done

Created `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/EchoLevelPillSelector.kt` as the single shared component.

Deleted the three private copies:
- `RestTimerEchoLevelSelector` from `RestTimerCard.kt` (~line 667–723)
- `SetReadyEchoLevelSelector` from `SetReadyScreen.kt` (~line 1017–1072)
- `OverviewEchoLevelSelector` from `RoutineOverviewScreen.kt` (~line 776–832)

Updated each call site to use `EchoLevelPillSelector(...)` with matching parameter signatures. Cleaned up now-unused imports (`rest_echo_level` from RestTimerCard and RoutineOverviewScreen; `echoLevelLabel` from RoutineOverviewScreen). Updated the guard test that was asserting localization patterns against `RoutineOverviewScreen.kt` (they now live in `EchoLevelPillSelector.kt`).

---

### Drift Diff: RoutineOverview vs RestTimer

| Attribute | RestTimerCard | RoutineOverviewScreen |
|---|---|---|
| Section label text | `stringResource(Res.string.rest_echo_level)` ✓ | `stringResource(Res.string.rest_echo_level)` ✓ |
| Label letterSpacing | standalone `letterSpacing = 1.sp` param ✓ | standalone `letterSpacing = 1.sp` param ✓ |
| Pill text | `level.displayName` (hardcoded English) ✗ | `echoLevelLabel(level)` (localized) ✓ |
| Row/pill structure | `Surface(onClick=...)` | `Surface(onClick=...)` |

**Behavioral drift confirmed:** RoutineOverviewScreen already used `echoLevelLabel(level)` for pill labels (localized string resources), while RestTimerCard used `level.displayName` (hardcoded enum values "Hard", "Harder", "Hardest", "Epic"). The canonical adopts `echoLevelLabel(level)` from RoutineOverview — this is the correct approach and fixes a latent localization bug in RestTimerCard as well as the overt one in SetReadyScreen.

SetReady had two additional divergences:
1. Hardcoded `"ECHO LEVEL"` label string (localization bug) — dead
2. `letterSpacing` via `.copy(letterSpacing = 1.sp)` on the style instead of as a standalone Text param — canonicalized to standalone param

---

### A11y Pattern: What Was Mirrored and From Where

Source: `components/ModeSelector.kt` (Phase 1 hardened implementation).

ModeSelector pattern:
1. Builds a `modeNames: Map<ProgramMode, String>` at the top of the composable using `stringResource(...)` calls — resolves all names up-front for locale-awareness.
2. Adds `.selectableGroup()` to the Row modifier.
3. Per pill: uses `Box(Modifier.weight(1f).clip(...).background(...).semantics(mergeDescendants=true){ role=Role.RadioButton; selected=isSelected; contentDescription=resolvedName }.clickable{...}.padding(...))` instead of `Surface(onClick=...)`.
4. `contentDescription` uses the pre-resolved localized name (not `displayName`).

`EchoLevelPillSelector` mirrors this exactly:
- `levelNames: Map<EchoLevel, String>` built from `echoLevelLabel(EchoLevel.HARD/HARDER/HARDEST/EPIC)` — `echoLevelLabel` is the domain-layer equivalent of ModeSelector's inline `stringResource(...)` calls, already mapping each level to the right string resource.
- `.selectableGroup()` on Row.
- Per pill: `Box + clip + background + semantics(mergeDescendants=true){ role=Role.RadioButton; selected=isSelected; contentDescription=levelName } + clickable + padding`.
- `displayName` is never announced raw; the fallback `?: level.displayName` in `levelNames[level] ?: level.displayName` is only for defensive null-safety since the map is exhaustive.

The Surface+onClick pattern from the three copies was replaced by Box+clickable to match ModeSelector exactly.

---

### Design-System Constraints

- `RoundedCornerShape(Spacing.medium)` (outer container) and `RoundedCornerShape(Spacing.small)` (pills): kept as-is — Spacing token references do not match the ratchet regex `RoundedCornerShape(\d+\.dp`. `MaterialTheme.shapes.*` equivalents don't exist at these exact sizes (Spacing.medium=16dp, Spacing.small=8dp; shapes.small=12dp, shapes.extraSmall=8dp — pills at 8dp could use `MaterialTheme.shapes.extraSmall`, but outer at 16dp has no exact match), so Spacing tokens were retained to avoid visual regressions.
- No raw N.dp radii introduced.
- No hardcoded color values introduced (all use colorScheme tokens).

---

### Verification Summary

- `./gradlew :androidApp:assembleDebug`: BUILD SUCCESSFUL (56s)
- `./gradlew :shared:testAndroidHostTest` (full suite, run twice): BUILD SUCCESSFUL, 2296 tests, 0 failures
- One intermittent failure observed on first run (`DWSMRoutineFlowTest.enterRoutineOverview_setsOverviewState`): confirmed pre-existing flaky coroutine timing test — passes in isolation and on clean re-run; unrelated to this change.

---

### Concerns

**Pre-existing flaky test:** `DWSMRoutineFlowTest.enterRoutineOverview_setsOverviewState` is intermittently failing due to coroutine timing (it passes when run in isolation). Not introduced by this change, but worth noting. The test creates/cleans up a `DWSMTestHarness` in a coroutine test — suspect the `advanceUntilIdle()` call doesn't fully settle before `harness.cleanup()` in some test-runner execution orders.

**Import ordering:** The new `EchoLevelPillSelector` import in `RestTimerCard.kt` is positioned in the middle of the resource imports block (it replaced `rest_echo_level` in-place). Kotlin compiles correctly regardless of import order; IDE will re-sort on next `Optimize Imports`.
