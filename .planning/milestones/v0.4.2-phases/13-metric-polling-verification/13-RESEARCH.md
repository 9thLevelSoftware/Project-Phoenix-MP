# Phase 13: MetricPollingEngine Verification - Research

**Researched:** 2026-02-15
**Domain:** Formal verification of Phase 11 (MetricPollingEngine) success criteria -- process gap closure
**Confidence:** HIGH

## Summary

Phase 13 is a pure verification phase, not a code-writing phase. The v0.4.2 milestone audit (2026-02-16) found that Phase 11 (MetricPollingEngine) was fully executed -- both plans completed, 18 unit tests passing, KableBleRepository reduced by 445 lines, integration confirmed by Phase 12 -- but never received a formal VERIFICATION.md. This leaves 3 requirements (POLL-01, POLL-02, POLL-03) in a "substantively satisfied but unverified" state.

The verification task is straightforward: examine the existing code, tests, and summaries to produce evidence-backed verification of Phase 11's success criteria. No code changes should be needed. The primary challenge is following the established verification format (used by Phases 5-10, 12) and providing concrete line-number evidence for each truth and requirement.

**Primary recommendation:** Generate a single VERIFICATION.md following the exact format of existing verifications (e.g., Phase 10's 10-VERIFICATION.md), mapping POLL-01/02/03 requirements to specific code locations and test results.

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| N/A | N/A | This is a verification-only phase | No code changes or dependencies needed |

### Supporting
| Tool | Purpose | When to Use |
|------|---------|-------------|
| `grep` / code search | Find specific line numbers for evidence | Locating exact implementation of each truth |
| `./gradlew :shared:testDebugUnitTest` | Confirm all tests still pass | Mandatory verification step |
| `./gradlew :androidApp:assembleDebug` | Confirm full build | Build health check |
| `git log` / `git show` | Verify commits from Phase 11 execution | Commit-level evidence |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Manual verification | Automated verification script | Not worth it for a one-time gap closure -- manual is more thorough |

## Architecture Patterns

### Verification Report Structure (from established precedent)

All existing VERIFICATION.md files in this project follow the same structure. The verifier for Phase 13 MUST follow this exact pattern:

```markdown
---
phase: 11-metric-polling-engine
verified: [timestamp]
status: passed/failed
score: X/Y must-haves verified
---

# Phase 11: MetricPollingEngine Verification Report

**Phase Goal:** [from ROADMAP.md]
**Verified:** [timestamp]
**Status:** [passed/failed]

## Goal Achievement

### Observable Truths
[Table: truth statement | status | evidence with line numbers]

### Required Artifacts
[Table: artifact | expected | status | details]

### Key Link Verification
[Table: from | to | via | status | details]

### Requirements Coverage
[Table: POLL-01/02/03 | status | evidence]

### Anti-Patterns Found
[Any issues discovered]

### Human Verification Required
[Hardware tests etc.]

### Gaps Summary
[Blockers or warnings]

## Technical Verification Details
[Deep-dive evidence]

## Success Criteria Check
[Map each criterion from ROADMAP.md]
```

### What Must Be Verified (Observable Truths from Plans)

**From Plan 01 (MetricPollingEngine creation):**
1. MetricPollingEngine manages 4 independent polling Jobs (monitor, diagnostic, heuristic, heartbeat)
2. stopMonitorOnly cancels only monitor job while diagnostic, heuristic, and heartbeat remain active (Issue #222)
3. stopAll cancels all 4 jobs, nulls references, and resets diagnostic counters
4. restartAll conditionally restarts only inactive jobs (no duplicate loops)
5. Consecutive timeout threshold triggers onConnectionLost callback (POLL-03)
6. Monitor polling mutex prevents concurrent monitor loops

**From Plan 02 (KableBleRepository delegation):**
1. KableBleRepository delegates all polling lifecycle to pollingEngine inline property
2. startObservingNotifications splits cleanly: notifications stay, polling delegates to engine
3. disconnect() and cleanupExistingConnection() cancel polling via pollingEngine.stopAll()
4. stopPolling(), stopMonitorPollingOnly(), restartDiagnosticPolling(), startActiveWorkoutPolling(), restartMonitorPolling() delegate to engine
5. BleRepository interface is completely unchanged
6. Workout CONFIG commands followed by diagnostic reads work correctly
7. enableHandleDetection() still calls engine for polling restart

### Requirements to Verify

| Requirement | What to Verify | Where to Find Evidence |
|-------------|---------------|----------------------|
| POLL-01 | MetricPollingEngine manages all 4 polling loops (monitor, diagnostic, heuristic, heartbeat) | `MetricPollingEngine.kt` lines 139-145 (`startAll`), line 52-55 (4 Job references), lines 156-227 (monitor), 232-269 (diagnostic), 274-311 (heuristic), 318-339 (heartbeat) |
| POLL-02 | stopMonitorOnly preserves diagnostic and heartbeat polling (Issue #222) | `MetricPollingEngine.kt` lines 386-394 (`stopMonitorOnly` cancels only `monitorPollingJob`), `MetricPollingEngineTest.kt` lines 109-162 (4 tests proving selective cancellation) |
| POLL-03 | Timeout disconnect after MAX_CONSECUTIVE_TIMEOUTS (5) works correctly | `MetricPollingEngine.kt` lines 200-205 (threshold check + `onConnectionLost`), `BleConstants.kt` line 139 (`MAX_CONSECUTIVE_TIMEOUTS = 5`), `MetricPollingEngineTest.kt` lines 260-303 (3 timeout tests) |

### Key Links to Verify

| From | To | Via | What to Check |
|------|-----|-----|--------------|
| KableBleRepository | MetricPollingEngine | `private val pollingEngine = MetricPollingEngine(...)` | Line 99-113 in KableBleRepository.kt |
| KableBleRepository | pollingEngine.startAll | `startObservingNotifications()` | Delegation exists in startObservingNotifications |
| KableBleRepository | pollingEngine.stopAll | `stopPolling()` override | Line 259 |
| KableBleRepository | pollingEngine.stopMonitorOnly | `stopMonitorPollingOnly()` override | Line 261 |
| KableBleRepository | pollingEngine.restartAll | `startActiveWorkoutPolling()` | Line 256 |
| KableBleConnectionManager | pollingEngine | Constructor injection | Line 122 |
| MetricPollingEngine | BleOperationQueue | `bleQueue.read { ... }` | Lines 183, 244, 283 |
| MetricPollingEngine | MonitorDataProcessor | `monitorProcessor.process()` | Line 463 |
| MetricPollingEngine | HandleStateDetector | `handleDetector.processMetric()` | Line 464 |
| MetricPollingEngine | BleConstants.Timing | Interval/timeout constants | Lines 131, 182, 201, 243, 258, 283, 300, 323, 326 |

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Verification format | A custom format | The established VERIFICATION.md format from Phases 5-12 | Consistency with existing audit, planner expects this format |
| Line number evidence | Approximate descriptions | Exact line numbers from current source | Milestone audit specifically flagged missing formal verification |

**Key insight:** This phase is about process rigor, not code quality. The code is already verified functionally (18 tests, integration confirmed). The deliverable is a formal document with traceable evidence.

## Common Pitfalls

### Pitfall 1: Verifying Against Stale Line Numbers
**What goes wrong:** Phase 12 modified KableBleRepository after Phase 11. Line numbers from Phase 11 SUMMARYs may be wrong.
**Why it happens:** Phase 12 extracted KableBleConnectionManager, moving more code out of KableBleRepository. The file went from ~1384 lines (after Phase 11) to ~394 lines (after Phase 12).
**How to avoid:** Verify line numbers against the CURRENT source files, not the SUMMARY references. Read MetricPollingEngine.kt (unchanged since Phase 11) and KableBleRepository.kt (changed by Phase 12) fresh.
**Warning signs:** Line numbers in VERIFICATION.md don't match when you `grep` for them.

### Pitfall 2: Missing the KableBleConnectionManager Delegation
**What goes wrong:** Phase 12 introduced KableBleConnectionManager which now sits between KableBleRepository and MetricPollingEngine for some operations. Verifier only checks KableBleRepository -> pollingEngine links.
**Why it happens:** Phase 11 Plan 02 originally wired KableBleRepository directly to pollingEngine. Phase 12 moved some of those call sites to KableBleConnectionManager.
**How to avoid:** Check BOTH KableBleRepository.kt AND KableBleConnectionManager.kt for pollingEngine references. The connectionManager also receives pollingEngine as a constructor parameter (line 122 of KableBleRepository.kt).
**Warning signs:** Missing links in verification that should be there.

### Pitfall 3: Skipping Test Execution
**What goes wrong:** Verifier documents tests exist but doesn't actually run them.
**Why it happens:** Assumption that "they passed before, they still pass."
**How to avoid:** Run `./gradlew :shared:testDebugUnitTest` and record the actual result. Phase 12 changes could have introduced regressions.
**Warning signs:** "Tests pass" claim without a build output timestamp.

### Pitfall 4: Confusing "Verification" with "Re-implementation"
**What goes wrong:** Verifier starts writing new tests or modifying code.
**Why it happens:** Finding something that could be improved and scope-creeping into code changes.
**How to avoid:** This phase's ONLY deliverable is VERIFICATION.md. If issues are found, they should be documented as gaps, not fixed in-line.
**Warning signs:** Any `git diff` showing code changes in .kt files.

## Code Examples

### Evidence Collection Pattern (what the verifier should do)

```bash
# 1. Verify MetricPollingEngine exists and has all 4 loops
grep -n "fun start.*Polling\|fun startHeartbeat\|fun startAll" MetricPollingEngine.kt

# 2. Verify stopMonitorOnly preserves other jobs (Issue #222)
grep -n "fun stopMonitorOnly" MetricPollingEngine.kt
# Should show: ONLY monitorPollingJob?.cancel() and monitorPollingJob = null

# 3. Verify POLL-03 timeout disconnect
grep -n "MAX_CONSECUTIVE_TIMEOUTS\|onConnectionLost" MetricPollingEngine.kt

# 4. Verify delegation in KableBleRepository
grep -n "pollingEngine\." KableBleRepository.kt

# 5. Verify delegation in KableBleConnectionManager
grep -n "pollingEngine\." KableBleConnectionManager.kt

# 6. Verify tests exist and cover requirements
grep -n "@Test" MetricPollingEngineTest.kt | wc -l  # Should be 18

# 7. Run tests
./gradlew :shared:testDebugUnitTest
```

### MetricPollingEngine Source Evidence Map

Key locations in `MetricPollingEngine.kt` (532 lines, unchanged since Phase 11):

| Feature | Lines | What's There |
|---------|-------|-------------|
| 4 Job references | 52-55 | `monitorPollingJob`, `diagnosticPollingJob`, `heuristicPollingJob`, `heartbeatJob` |
| Monitor Mutex | 58 | `monitorPollingMutex = Mutex()` |
| Timeout counter | 67 | `consecutiveTimeouts` |
| startAll() | 139-145 | Calls all 4 start methods |
| startMonitorPolling() | 156-227 | Monitor loop with timeout disconnect |
| startDiagnosticPolling() | 232-269 | Diagnostic loop |
| startHeuristicPolling() | 274-311 | Heuristic loop |
| startHeartbeat() | 318-339 | Heartbeat loop |
| stopAll() | 345-380 | Cancels all 4 jobs, resets state |
| stopMonitorOnly() | 386-394 | Cancels ONLY monitor job |
| restartAll() | 401-427 | Conditional restart |
| POLL-03 check | 200-205 | `consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS` -> `onConnectionLost()` |

### MetricPollingEngineTest Coverage Map

| Test Category | Count | Lines | What's Covered |
|---------------|-------|-------|---------------|
| Job Lifecycle | 4 | 44-102 | startAll starts 4 jobs, stopAll cancels 4 jobs, stopAll resets counters, startMonitorPolling replaces previous |
| Issue #222 Partial Stop | 4 | 108-162 | stopMonitorOnly cancels monitor, preserves diagnostic, preserves heartbeat, preserves heuristic |
| Conditional Restart | 5 | 170-252 | restartAll starts monitor unconditionally, skips active diagnostic, restarts inactive diagnostic, skips active heartbeat, restarts inactive heartbeat |
| POLL-03 Timeout | 3 | 260-303 | Triggers at MAX threshold, successful read resets counter, does NOT trigger at MAX-1 |
| Diag+Heartbeat Restart | 2 | 310-344 | Starts both if inactive, skips both if active |
| **Total** | **18** | | |

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| No formal verification for Phase 11 | Milestone audit flagged the gap | 2026-02-16 | Created Phase 13 as gap closure |
| SUMMARY self-checks as "good enough" | Formal VERIFICATION.md with line-number evidence | v0.4.2 audit process | 3 requirements need formal status |

## Open Questions

1. **Has MetricPollingEngine.kt been modified since Phase 11?**
   - What we know: Phase 12 modified KableBleRepository.kt and created KableBleConnectionManager.kt, but MetricPollingEngine.kt should be unchanged.
   - What's unclear: Need to confirm with `git log` on the file.
   - Recommendation: Verifier should run `git log --oneline MetricPollingEngine.kt` to confirm no post-Phase-11 changes.

2. **Are all 18 tests still passing after Phase 12 changes?**
   - What we know: Phase 11 SUMMARYs confirm tests passed at that time. Phase 12 SUMMARY confirms tests passed after its changes.
   - What's unclear: Current test state needs fresh execution.
   - Recommendation: Mandatory `./gradlew :shared:testDebugUnitTest` during verification.

3. **Should KableBleConnectionManager delegation be part of Phase 11 verification or Phase 12?**
   - What we know: Phase 12 introduced KableBleConnectionManager which receives `pollingEngine` as a constructor param. Phase 11 Plan 02 wired KableBleRepository -> pollingEngine. Phase 12 moved some sites to connectionManager -> pollingEngine.
   - What's unclear: Whether POLL-01/02/03 require verifying the connectionManager path.
   - Recommendation: Verify the pollingEngine API (POLL-01/02/03 are about the engine's behavior, not the caller). Mention connectionManager integration as supplementary evidence but don't make it a blocker.

## Sources

### Primary (HIGH confidence)
- `MetricPollingEngine.kt` (532 lines) -- Direct source inspection
- `MetricPollingEngineTest.kt` (345 lines) -- 18 tests covering all requirements
- `KableBleRepository.kt` (394 lines) -- Delegation wiring
- `KableBleConnectionManager.kt` -- Additional delegation wiring (Phase 12)
- `BleConstants.kt` -- Timing constants (MAX_CONSECUTIVE_TIMEOUTS=5, intervals)
- `11-01-PLAN.md`, `11-02-PLAN.md` -- Must-haves and truths to verify
- `11-01-SUMMARY.md`, `11-02-SUMMARY.md` -- Self-check results from execution
- `v0.4.2-MILESTONE-AUDIT.md` -- Gap analysis that created Phase 13
- `10-VERIFICATION.md`, `09-01-VERIFICATION.md` -- Format precedent

### Secondary (MEDIUM confidence)
- `ROADMAP.md` -- Phase 11 and 13 definitions and success criteria

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - No stack needed (verification only)
- Architecture: HIGH - Established VERIFICATION.md format with 8 precedent files
- Pitfalls: HIGH - Clear from examining Phase 12 changes and milestone audit

**Research date:** 2026-02-15
**Valid until:** 2026-03-15 (stable -- verification format unlikely to change)
