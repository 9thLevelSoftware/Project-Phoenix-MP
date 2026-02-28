---
phase: 22-ghost-racing
verified: 2026-02-28T21:00:00Z
status: passed
score: 18/18 must-haves verified
re_verification: false
human_verification:
  - test: "Ghost overlay renders dual vertical bars during active set"
    expected: "YOU (blue/primary) and BEST (grey/muted) bars rise proportionally to velocity; bars update per rep"
    why_human: "Composable rendering and animation cannot be verified programmatically"
  - test: "Verdict badge transitions correctly after each rep"
    expected: "AHEAD (green), BEHIND (red), TIED (amber), or NEW BEST (green) badge appears within ~200ms of rep completion"
    why_human: "50ms biomechanics delay + StateFlow timing requires live workout session to observe"
  - test: "Ghost overlay does not appear for free-tier users"
    expected: "No ghost overlay visible when account is free tier; overlay appears immediately after upgrading"
    why_human: "Subscription tier gating requires real account state"
  - test: "SetSummaryCard ghost delta section renders after set completion"
    expected: "vs Personal Best card with FASTER/SLOWER/MATCHED verdict, mm/s delta, and rep breakdown (X ahead, Y behind, Z beyond)"
    why_human: "Card visibility gated on ghostSetSummary being non-null, requires a completed set with ghost session loaded"
---

# Phase 22: Ghost Racing Verification Report

**Phase Goal:** Users can race against their personal best during a set, with real-time visual comparison and post-set velocity delta summary
**Verified:** 2026-02-28T21:00:00Z
**Status:** PASSED
**Re-verification:** No -- initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | GhostRacingEngine.findBestSession() selects highest avgMcvMmS within weight tolerance | VERIFIED | GhostRacingEngine.kt:42-46 -- filters by tolerance, returns maxByOrNull; 4 passing tests |
| 2 | GhostRacingEngine.compareRep() returns AHEAD/BEHIND/TIED with 5% tolerance | VERIFIED | GhostRacingEngine.kt:56-65 -- deltaPercent logic with TIED_TOLERANCE_PERCENT=5f; 4 passing tests |
| 3 | GhostRacingEngine.computeSetDelta() aggregates per-rep deltas correctly | VERIFIED | GhostRacingEngine.kt:75-99 -- BEYOND excluded from delta, 4 passing tests confirm counts/verdicts |
| 4 | BEYOND verdict emitted when current rep index exceeds ghost rep count | VERIFIED | ActiveSessionEngine.kt:576-583 -- ghostRepIndex bounds check with GhostVerdict.BEYOND emission |
| 5 | selectBestGhostSession SQL query exists and matches spec | VERIFIED | VitruvianDatabase.sq:1672 -- exact query with exerciseId, mode, ABS weight tolerance, ORDER BY avgMcvMmS DESC LIMIT 1 |
| 6 | Ghost session pre-loaded at workout start (no DB reads during active set) | VERIFIED | ActiveSessionEngine.kt:1403-1436 -- scope.launch non-blocking pre-load in startWorkout; all comparison logic reads from coordinator._ghostSession.value |
| 7 | Per-rep AHEAD/BEHIND/TIED/BEYOND verdict emitted via StateFlow after each rep | VERIFIED | ActiveSessionEngine.kt:551-583 -- comparison constructed, ghostRepComparisons.add(), _latestGhostVerdict.value = comparison |
| 8 | Ghost comparisons accumulate for end-of-set summary | VERIFIED | ActiveSessionEngine.kt:2149-2155 -- ghostRepComparisons.toList() passed to GhostRacingEngine.computeSetDelta() before clear |
| 9 | Ghost state resets between sets (comparisons cleared, verdict nulled) | VERIFIED | ActiveSessionEngine.kt:2153-2155 -- ghostRepComparisons.clear() + _latestGhostVerdict.value = null after set completion |
| 10 | Just Lift mode (null exerciseId) skips ghost loading | VERIFIED | ActiveSessionEngine.kt:1406 -- `if (exerciseId != null && exerciseId.isNotBlank() && !isBodyweight)` gate |
| 11 | WorkoutState.SetSummary includes ghostSetSummary field | VERIFIED | Models.kt:92 -- `val ghostSetSummary: GhostSetSummary? = null` on SetSummary data class |
| 12 | Two vertical progress bars render with YOU/BEST labels | VERIFIED | GhostRacingOverlay.kt:52-70 -- Row with two VerticalProgressBar composables; labels "YOU" and "BEST" |
| 13 | AHEAD/BEHIND/TIED/BEYOND verdict badge displays after each rep | VERIFIED | GhostRacingOverlay.kt:75-101 -- verdict badge chip with WCAG colors + text label |
| 14 | Ghost overlay renders conditionally in WorkoutHud overlay zone | VERIFIED | WorkoutHud.kt:338-357 -- `if (ghostSession != null)` guard; GhostRacingOverlay() invocation confirmed |
| 15 | Ghost overlay gated to Phoenix+ tier | VERIFIED | ActiveWorkoutScreen.kt:104-105 -- gatedGhostSession = if (hasProAccess) ghostSession else null |
| 16 | End-of-set summary shows velocity delta with ahead/behind counts | VERIFIED | SetSummaryCard.kt:311-395 -- ghost delta section with overallVerdict, avgDeltaMcvMmS, repsAhead/repsBehind/repsBeyondGhost |
| 17 | All semantic colors use AccessibilityTheme (WCAG Phase 17 mandate) | VERIFIED | GhostRacingOverlay.kt:40-42, SetSummaryCard.kt:349-351 -- success/error/warning from AccessibilityTheme.colors |
| 18 | Ghost state threading chain: ViewModel -> ActiveWorkoutScreen -> WorkoutUiState -> WorkoutTab -> WorkoutHud | VERIFIED | MainViewModel.kt:174,177; WorkoutUiState.kt:83-84; WorkoutTab.kt:127-128,199-200,243-244; ActiveWorkoutScreen.kt:71-72,371-372 |

**Score:** 18/18 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `shared/.../domain/model/GhostModels.kt` | 5 domain types | VERIFIED | 59 lines; exports GhostSession, GhostVerdict, GhostRepComparison, GhostSetSummary, GhostSessionCandidate |
| `shared/.../domain/premium/GhostRacingEngine.kt` | Stateless engine object | VERIFIED | 100 lines; `object GhostRacingEngine` with findBestSession, compareRep, computeSetDelta -- no DI, no suspend |
| `shared/.../domain/premium/GhostRacingEngineTest.kt` | 80+ lines TDD tests | VERIFIED | 197 lines; 12 test cases covering all 3 engine functions |
| `shared/.../database/VitruvianDatabase.sq` | selectBestGhostSession query | VERIFIED | Line 1672 -- query present with exerciseId, mode, weight tolerance parameters |
| `shared/.../manager/WorkoutCoordinator.kt` | Ghost state fields | VERIFIED | Lines 328-335 -- _ghostSession, _latestGhostVerdict StateFlows, ghostRepComparisons mutableListOf |
| `shared/.../manager/ActiveSessionEngine.kt` | Ghost lifecycle | VERIFIED | Pre-load:1403-1436, compare:551-583, summarize:2149-2155, reset:2153-2155, cleanup:1332-1334 |
| `shared/.../repository/WorkoutRepository.kt` | findBestGhostSession interface | VERIFIED | Line 82 -- suspend fun with exerciseId, mode, weightPerCableKg, weightToleranceKg params |
| `shared/.../repository/SqlDelightWorkoutRepository.kt` | findBestGhostSession implementation | VERIFIED | Lines 785-808 -- calls selectBestGhostSession with correct SQLDelight param names (exerciseId, mode, weightPerCableKg, value_) |
| `shared/.../domain/model/Models.kt` | ghostSetSummary on SetSummary | VERIFIED | Line 92 -- `val ghostSetSummary: GhostSetSummary? = null` |
| `shared/.../components/GhostRacingOverlay.kt` | 60+ lines composable | VERIFIED | 151 lines; GhostRacingOverlay + private VerticalProgressBar helper |
| `shared/.../screen/WorkoutHud.kt` | GhostRacingOverlay invocation | VERIFIED | Line 347 -- GhostRacingOverlay() call inside `if (ghostSession != null)` |
| `shared/.../screen/SetSummaryCard.kt` | Ghost velocity delta section | VERIFIED | Lines 311-395 -- ghostSetSummary?.let block with verdict, delta, rep breakdown |
| `shared/.../screen/WorkoutUiState.kt` | Ghost state fields | VERIFIED | Lines 83-84 -- ghostSession, latestGhostVerdict |
| `shared/.../screen/WorkoutTab.kt` | Ghost state threading | VERIFIED | Lines 127-128, 199-200, 243-244 -- both WorkoutTab overloads thread ghost state |
| `shared/.../screen/ActiveWorkoutScreen.kt` | Ghost state collection + tier gate | VERIFIED | Lines 71-72, 104-105, 371-372 -- collectAsState, hasProAccess gate, parameter passing |
| `shared/.../viewmodel/MainViewModel.kt` | Ghost state delegation | VERIFIED | Lines 174, 177 -- ghostSession and latestGhostVerdict delegated from WorkoutCoordinator |
| `shared/.../testutil/FakeWorkoutRepository.kt` | findBestGhostSession stub | VERIFIED | Line 186 -- returns null by default |
| `androidApp/.../testutil/FakeWorkoutRepository.kt` | findBestGhostSession stub | VERIFIED | Line 185 -- returns null by default |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| GhostRacingEngineTest.kt | GhostRacingEngine.kt | Direct function calls | WIRED | `GhostRacingEngine.findBestSession`, `compareRep`, `computeSetDelta` all called in 12 test cases |
| GhostRacingEngine.kt | GhostModels.kt | Return types | WIRED | Returns GhostVerdict, GhostRepComparison, GhostSetSummary; imports confirmed at lines 3-6 |
| ActiveSessionEngine.kt | WorkoutRepository.kt | findBestGhostSession() | WIRED | Line 1408 -- `workoutRepository.findBestGhostSession(...)` in startWorkout coroutine |
| ActiveSessionEngine.kt | GhostRacingEngine.kt | compareRep() + computeSetDelta() | WIRED | Line 568 compareRep in rep block; line 2150 computeSetDelta in set completion |
| ActiveSessionEngine.kt | WorkoutCoordinator.kt | coordinator state fields | WIRED | Lines 552, 570, 576, 581 -- _ghostSession.value, _latestGhostVerdict.value, ghostRepComparisons used throughout |
| ActiveSessionEngine.kt | BiomechanicsRepository.kt | getRepBiomechanics() | WIRED | Line 1419 -- `biomechanicsRepository.getRepBiomechanics(candidate.id)` in pre-load coroutine |
| WorkoutHud.kt | GhostRacingOverlay.kt | Composable invocation | WIRED | Line 347 -- GhostRacingOverlay() called with all 4 required params |
| WorkoutHud.kt | WorkoutCoordinator.kt | ghostSession/latestGhostVerdict | WIRED | Via parameter chain (threading verified across MainViewModel -> ActiveWorkoutScreen -> WorkoutUiState -> WorkoutTab -> WorkoutHud) |
| SetSummaryCard.kt | GhostModels.kt | GhostSetSummary rendering | WIRED | Line 311 -- `summary.ghostSetSummary?.let { ghost -> }` with GhostVerdict enum used |
| GhostRacingOverlay.kt | AccessibilityTheme | WCAG-safe colors | WIRED | Lines 40-42 -- successColor, errorColor, warningColor from AccessibilityTheme.colors |

### Requirements Coverage

| Requirement | Description | Source Plans | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| GHOST-01 | User can race against their best matching local session during a set (Phoenix+ tier) | 22-01, 22-02 | SATISFIED | selectBestGhostSession SQL query + findBestGhostSession() repository method + ActiveSessionEngine pre-load + hasProAccess tier gate in ActiveWorkoutScreen |
| GHOST-02 | Two vertical progress bars show current vs. ghost cable position in real-time | 22-03 | SATISFIED | GhostRacingOverlay.kt dual VerticalProgressBar composables (YOU/BEST), rendered in WorkoutHud Box overlay zone when ghostSession != null |
| GHOST-03 | Per-rep AHEAD/BEHIND verdict displayed based on velocity comparison | 22-01, 22-02, 22-03 | SATISFIED | GhostRacingEngine.compareRep() with 5% tolerance -> GhostVerdict emitted to _latestGhostVerdict -> GhostRacingOverlay verdict badge |
| GHOST-04 | End-of-set summary shows total velocity delta vs. ghost | 22-01, 22-03 | SATISFIED | GhostRacingEngine.computeSetDelta() -> SetSummary.ghostSetSummary -> SetSummaryCard ghost delta section with verdict, avgDeltaMcvMmS, rep breakdown |

All 4 requirements SATISFIED. No orphaned requirements found -- all GHOST-0x IDs are claimed by plans and evidenced in code.

### Anti-Patterns Found

None. No TODO/FIXME/PLACEHOLDER comments in ghost racing files. No stub implementations. No hardcoded return null/empty beyond the FakeWorkoutRepository (which is correct by design).

**Notable deviations handled correctly:**
- ProgramMode.displayName used instead of .name (sealed class, no .name property) -- deviation caught and fixed in Plan 02
- SQLDelight generates `value_` for 4th positional parameter -- deviation caught and fixed in Plan 02
- Ghost state threaded through full parameter chain rather than direct coordinator collection in WorkoutHud -- deviation caught and fixed in Plan 03 (follows Phase 19 pattern)
- MaterialTheme.colorScheme used for non-semantic colors (primary, onSurface, onSurfaceVariant) since AccessibilityColors only has semantic status colors -- deviation caught and fixed in Plan 03

### Human Verification Required

#### 1. Ghost Overlay Real-Time Rendering

**Test:** Start a workout with an exercise that has prior sessions in the DB (same exercise, mode, within 5kg weight). Complete 3-5 reps.
**Expected:** Two vertical bars (YOU and BEST) appear in the left side of the workout HUD shortly after workout start. Bars animate proportionally to velocity. Verdict badge updates after each rep.
**Why human:** Composable rendering, animation, and StateFlow timing (50ms delay for biomechanics) cannot be verified programmatically.

#### 2. BEYOND Verdict Visual

**Test:** Complete more reps than the ghost session had (e.g., ghost had 5 reps, do 6+).
**Expected:** On rep 6+, the verdict badge shows "NEW BEST" in green instead of AHEAD/BEHIND/TIED.
**Why human:** Requires a live workout session with a known ghost session rep count.

#### 3. Phoenix+ Tier Gate

**Test:** Open a workout on a free-tier account with an exercise that has ghost data.
**Expected:** No ghost overlay appears during the set. Upgrade to Phoenix+, repeat -- overlay appears.
**Why human:** Subscription tier state requires real account configuration.

#### 4. SetSummaryCard Ghost Delta After Set Completion

**Test:** Complete a set with a ghost session loaded. Review the set summary card.
**Expected:** A "vs Personal Best" card appears with overall verdict (FASTER/SLOWER/MATCHED), average delta in mm/s (e.g., "+12.5 mm/s avg"), and per-rep breakdown (e.g., "3 ahead", "1 behind").
**Why human:** Requires completed set with ghost comparisons accumulated; card visibility is gated on ghostSetSummary being non-null.

## Verification Summary

All 18 observable truths verified. All 4 GHOST requirements satisfied. No anti-patterns detected. The implementation is complete and correctly wired across all three layers:

1. **Domain Layer (Plan 01):** GhostRacingEngine stateless computation object with TDD coverage (12 tests), GhostModels.kt with 5 types, selectBestGhostSession SQL query.

2. **Lifecycle Layer (Plan 02):** Ghost session pre-loaded non-blocking at workout start, per-rep comparison emitting to StateFlow, set summary computation with BEYOND exclusion, clean state reset between sets while session persists across multi-set workouts.

3. **UI Layer (Plan 03):** GhostRacingOverlay composable with dual VerticalProgressBar helpers and verdict badge, full state threading chain (WorkoutCoordinator -> MainViewModel -> ActiveWorkoutScreen with Phoenix+ gate -> WorkoutUiState -> WorkoutTab -> WorkoutHud), SetSummaryCard ghost delta section with KMP-safe number formatting.

The phase goal -- "Users can race against their personal best during a set, with real-time visual comparison and post-set velocity delta summary" -- is fully achieved.

---
_Verified: 2026-02-28T21:00:00Z_
_Verifier: Claude (gsd-verifier)_
