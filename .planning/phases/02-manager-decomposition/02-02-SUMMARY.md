---
phase: 02-manager-decomposition
plan: 02
subsystem: presentation
tags: [kotlin, sharedflow, circular-dependency, refactoring, ble]

# Dependency graph
requires:
  - phase: 02-manager-decomposition
    plan: 01
    provides: "WorkoutCoordinator as shared state bus with internal fields for sub-manager access"
provides:
  - "Circular dependency between DWSM and BleConnectionManager eliminated"
  - "bleErrorEvents SharedFlow on WorkoutCoordinator for one-way BLE error propagation"
  - "Simplified MainViewModel construction (no two-step wiring)"
affects: [02-03, 02-04]

# Tech tracking
tech-stack:
  added: []
  patterns: [sharedflow-error-propagation, one-way-dependency-via-events]

key-files:
  created: []
  modified:
    - "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutCoordinator.kt"
    - "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt"
    - "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/BleConnectionManager.kt"
    - "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt"
    - "shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/DWSMTestHarness.kt"
    - "shared/src/androidUnitTest/kotlin/com/devil/phoenixproject/presentation/manager/BleConnectionManagerTest.kt"

key-decisions:
  - "WorkoutStateProvider interface retained: BleConnectionManager still needs it for connection-loss detection during workouts"
  - "setConnectionError() public method kept on BleConnectionManager for potential external callers"
  - "bleErrorEvents uses tryEmit() (non-suspending) in DWSM catch blocks to avoid blocking error handlers"

patterns-established:
  - "SharedFlow error propagation: sub-managers emit errors to coordinator, consumers collect from coordinator"
  - "One-way dependency: DWSM -> coordinator -> BleConnectionManager (no back-reference)"

# Metrics
duration: 4min
completed: 2026-02-13
---

# Phase 2 Plan 02: Eliminate DWSM-BleConnectionManager Circular Dependency Summary

**Circular lateinit var replaced with SharedFlow-based bleErrorEvents on WorkoutCoordinator for one-way BLE error propagation from DWSM to BleConnectionManager**

## Performance

- **Duration:** 4 min
- **Started:** 2026-02-13T20:44:49Z
- **Completed:** 2026-02-13T20:49:06Z
- **Tasks:** 1 (5 atomic steps)
- **Files modified:** 6

## Accomplishments
- Eliminated `lateinit var bleConnectionManager` from DefaultWorkoutSessionManager
- Added `bleErrorEvents: MutableSharedFlow<String>` to WorkoutCoordinator as one-way error channel
- BleConnectionManager now collects errors from coordinator SharedFlow in its init block
- MainViewModel construction simplified: no init block, no `.also{}` wiring step
- All 358+ tests pass (38 characterization + 5 BleConnectionManager + existing)

## Task Commits

Each step was committed atomically:

1. **Step 1: Add bleErrorEvents to WorkoutCoordinator** - `8ff45148` (refactor)
2. **Step 2: Replace lateinit with SharedFlow emission in DWSM** - `2283050d` (refactor)
3. **Step 3: BleConnectionManager collects bleErrorEvents** - `9584eabc` (refactor)
4. **Step 4: Simplify MainViewModel construction** - `34a20d33` (refactor)
5. **Step 5: Update test harness and BleConnectionManagerTest** - `92826966` (refactor)

## Files Created/Modified
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutCoordinator.kt` - Added `_bleErrorEvents` MutableSharedFlow and public `bleErrorEvents` getter
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt` - Removed `lateinit var bleConnectionManager`, replaced 2 `setConnectionError()` calls with `coordinator._bleErrorEvents.tryEmit()`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/BleConnectionManager.kt` - Added `bleErrorEvents: SharedFlow<String>` constructor parameter, collector in init block
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt` - Removed init block with circular wiring, pass `coordinator.bleErrorEvents` to BleConnectionManager
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/DWSMTestHarness.kt` - Removed `.also{}` wiring, pass `coordinator.bleErrorEvents` to BleConnectionManager constructor
- `shared/src/androidUnitTest/kotlin/com/devil/phoenixproject/presentation/manager/BleConnectionManagerTest.kt` - Added `MutableSharedFlow()` parameter to all 5 BleConnectionManager constructor calls

## Decisions Made
- **WorkoutStateProvider retained**: The interface is still needed by BleConnectionManager to detect connection loss during active workouts -- only the BACK-reference (DWSM->BleConnectionManager) was removed
- **setConnectionError() kept**: Public method still on BleConnectionManager for potential direct callers outside the SharedFlow pattern
- **tryEmit() over emit()**: Used non-suspending `tryEmit()` in DWSM catch blocks since they run in coroutine contexts where suspending is possible but tryEmit is safer and sufficient with the buffer configuration

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] BleConnectionManagerTest constructor signature mismatch**
- **Found during:** Task 1, Step 5 (running tests after harness update)
- **Issue:** 5 BleConnectionManager constructor calls in BleConnectionManagerTest.kt didn't include the new `bleErrorEvents` parameter, causing compilation failure
- **Fix:** Added `MutableSharedFlow()` as the bleErrorEvents parameter to all 5 constructor calls, added import for `MutableSharedFlow`
- **Files modified:** BleConnectionManagerTest.kt
- **Verification:** All tests compile and pass
- **Committed in:** 92826966

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** Plan didn't mention BleConnectionManagerTest.kt since it focused on the main DWSM test harness. The fix was trivial -- adding the new parameter to existing constructor calls.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Circular dependency eliminated; DWSM no longer holds any reference to BleConnectionManager
- ActiveSessionEngine extraction (Plan 03) can proceed cleanly -- BLE error emission via coordinator is already in place
- RoutineFlowManager extraction (Plan 03/04) unaffected by this change

---
*Phase: 02-manager-decomposition*
*Completed: 2026-02-13*
