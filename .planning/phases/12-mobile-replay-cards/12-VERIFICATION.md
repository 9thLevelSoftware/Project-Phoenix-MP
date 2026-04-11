---
phase: 12-mobile-replay-cards
verified: 2026-02-15T17:46:04Z
status: passed
score: 4/4 must-haves verified
re_verification:
  previous_status: gaps_found
  previous_score: 3/4
  gaps_closed:
    - "Force arrays in RepMetricData are populated from real MetricSample data during rep capture"
  gaps_remaining: []
  regressions: []
human_verification:
  - test: "Start a workout session and complete 3-5 reps of any exercise"
    expected: "After completing the session, navigate to History tab, expand the session, and verify rep cards appear with non-flat force curve sparklines"
    why_human: "Requires running app with BLE device to generate real MetricSample data during workout"
  - test: "Verify force sparkline visual appearance matches design"
    expected: "Sparkline should be 40dp tall, use primary color for line, tertiary color for peak marker, with smooth curve shape"
    why_human: "Visual appearance verification requires human judgment"
  - test: "Verify rep timing accuracy"
    expected: "Concentric and eccentric durations should reflect actual rep tempo (not hardcoded 1:2 ratio)"
    why_human: "Timing accuracy depends on real-time data capture during workflow"
---

# Phase 12: Mobile Replay Cards Verification Report

**Phase Goal:** Users can review any past session with per-rep detail including force curves and timing breakdowns  
**Verified:** 2026-02-15T17:46:04Z  
**Status:** passed  
**Re-verification:** Yes — after gap closure (Plan 12-03)

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Session detail screen displays a scrollable list of rep cards showing each rep in the set | ✓ VERIFIED | RepDetailsSection integrated in HistoryTab lines 367, 560-594, 887 |
| 2 | Each rep card renders a mini force curve sparkline drawn with Canvas | ✓ VERIFIED | ForceSparkline.kt (96 lines) with Canvas drawing; RepReplayCard.kt line 101-104 invokes ForceSparkline with combinedForceData |
| 3 | Rep cards display peak force in kg plus concentric and eccentric durations in seconds | ✓ VERIFIED | RepReplayCard.kt lines 52-56 calculate peak force and format durations as seconds |
| 4 | Rep boundaries are detected from position data using valley detection with smoothing | ✓ VERIFIED | RepBoundaryDetector.kt implements 5-sample smoothing, 10mm prominence valley detection; ActiveSessionEngine.kt line 575 calls detectBoundaries() |

**Score:** 4/4 truths verified

### Re-Verification Summary

**Gap Closed (Plan 12-03):** Force arrays in RepMetricData are now populated from real MetricSample data

**Previous Issue:** Lines 578-587 of ActiveSessionEngine.kt created RepMetricData with empty floatArrayOf()

**Fix Applied (commit a610eca9):**
- Added RepBoundaryDetector import and instance (line 18, private property)
- Replaced stub scoreCurrentRep() with phase-segmented metric extraction (lines 554-673)
- Uses rep boundary timestamps for accurate metric window (not arbitrary takeLast)
- Calls detectBoundaries() to find concentric/eccentric split (line 575)
- Populates concentricLoadsA/B and eccentricLoadsA/B with real force values (lines 591-592, 602-603)
- Calculates actual peak/average force, power, ROM, velocity from segmented data

**Verification:**
- Import verified: `import com.devil.phoenixproject.domain.replay.RepBoundaryDetector` (line 18)
- detectBoundaries() call verified: line 575
- Force array population verified: lines 591-592, 602-603 map loadA/loadB from phase-segmented metrics
- Tests pass: `./gradlew :shared:testDebugUnitTest --tests "*RepBoundaryDetectorTest*"` (BUILD SUCCESSFUL)

**Regressions:** None detected

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| RepBoundaryDetector.kt | Valley-based rep boundary detection | ✓ VERIFIED | 7530 bytes; exports RepBoundary, RepBoundaryDetector |
| RepBoundaryDetectorTest.kt | Comprehensive test coverage | ✓ VERIFIED | 13 @Test methods; all passing |
| ForceSparkline.kt | Canvas-based force curve | ✓ VERIFIED | 96 lines; Canvas drawing with path and peak marker |
| RepReplayCard.kt | Rep card with metrics | ✓ VERIFIED | 159 lines; combines force data (line 42), displays metrics (lines 52-56) |
| HistoryTab.kt (modified) | Rep list integration | ✓ VERIFIED | RepDetailsSection lines 560-594; RepReplayCard invoked line 586 |
| ActiveSessionEngine.kt (modified) | Production wiring | ✓ VERIFIED | RepBoundaryDetector import/instance; scoreCurrentRep() lines 554-673 |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| RepBoundaryDetector | MetricSample position data | FloatArray of positions | ✓ WIRED | detectBoundaries signature; ActiveSessionEngine line 575 |
| RepReplayCard | RepMetricData | data class parameter | ✓ WIRED | repData parameter line 36; combinedForceData line 42 |
| ForceSparkline | FloatArray force data | force parameter | ✓ WIRED | forceData: FloatArray parameter; RepReplayCard line 101 |
| HistoryTab | RepMetricRepository.getRepMetrics | LaunchedEffect fetch | ✓ WIRED | koinInject; getRepMetrics call |
| RepBoundaryDetector | ActiveSessionEngine | production usage | ✓ WIRED | Import line 18; detectBoundaries() call line 575 |
| concentricLoadsA | WorkoutMetric.loadA | phase-segmented extraction | ✓ WIRED | Line 591: concentricMetrics.map { it.loadA } |
| eccentricLoadsA | WorkoutMetric.loadA | phase-segmented extraction | ✓ WIRED | Line 602: eccentricMetrics.map { it.loadA } |

### Requirements Coverage

| Requirement | Status | Details |
|-------------|--------|---------|
| REPLAY-01 (rep boundary detection) | ✓ SATISFIED | Algorithm implemented, tested, and wired into production |
| REPLAY-02 (force curve visualization) | ✓ SATISFIED | UI renders correctly AND receives real force arrays from capture pipeline |
| REPLAY-03 (timing metrics) | ✓ SATISFIED | Concentric/eccentric durations calculated from actual timestamp ranges and displayed in seconds |
| REPLAY-04 (scrollable rep list) | ✓ SATISFIED | RepDetailsSection renders Column with spacedBy(Spacing.xsmall) |

### Anti-Patterns Found

No blocker anti-patterns found in modified files. The `return null` in ActiveSessionEngine is a valid null-safety pattern, not an empty implementation.

### Human Verification Required

#### 1. Workout Replay Integration Test

**Test:** Start a workout session with BLE device, complete 3-5 reps, end session, navigate to History tab, expand the session  
**Expected:** Rep cards appear with non-flat force curve sparklines showing realistic concentric/eccentric force curves  
**Why human:** Requires running app with BLE device to generate real MetricSample data. Check logcat for "Rep quality scored: rep=*, concentricSamples=*, eccentricSamples=*" with concentricSamples > 0.

#### 2. Force Sparkline Visual Design

**Test:** View rep cards in expanded session with actual force data  
**Expected:** Sparkline is 40dp tall, primary color line (1.5dp stroke), tertiary color peak marker (3dp radius), smooth curve  
**Why human:** Visual appearance and design compliance require human aesthetic judgment

#### 3. Rep Timing Accuracy

**Test:** Perform reps with varying tempos (fast concentric, slow eccentric), verify displayed durations  
**Expected:** Concentric and eccentric durations accurately reflect actual rep tempo, not hardcoded 1:2 ratio  
**Why human:** Timing accuracy verification requires comparing displayed values against actual performed tempo

---

_Verified: 2026-02-15T17:46:04Z_  
_Verifier: Claude (gsd-verifier)_  
_Gap Closure: Plan 12-03 (commit a610eca9)_
