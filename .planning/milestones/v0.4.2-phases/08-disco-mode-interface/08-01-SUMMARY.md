---
phase: 08-disco-mode-interface
plan: 01
subsystem: ble
tags: [kotlin, coroutines, kermit, ble, interface-design, dependency-inversion]

# Dependency graph
requires:
  - phase: 07-ble-operation-queue
    provides: BleOperationQueue extraction pattern (class not object, callback-based)
provides:
  - DiscoMode.kt self-contained module with callback-based BLE command sending
  - setLastColorSchemeIndex() on BleRepository interface
  - Issue #144 resolved (no concrete cast in SettingsManager)
affects: [02-led-biofeedback]

# Tech tracking
tech-stack:
  added: []
  patterns: [callback-based module extraction, interface method with default no-op]

key-files:
  created:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/DiscoMode.kt
  modified:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/BleRepository.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/KableBleRepository.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/SettingsManager.kt
    - shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeBleRepository.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/simulator/SimulatorBleRepository.kt

key-decisions:
  - "Callback-based design: DiscoMode takes suspend (ByteArray) -> Unit to avoid circular dependency"
  - "Interface method with default no-op: setLastColorSchemeIndex() won't break existing implementations"
  - "Connection guard stays in KableBleRepository (not DiscoMode): keeps DiscoMode decoupled from BLE state"

patterns-established:
  - "Callback extraction: Module accepts lambda for parent operations, avoids circular imports"
  - "Interface extension with default: Add methods with no-op default to avoid breaking implementors"

# Metrics
duration: 6min
completed: 2026-02-15
---

# Phase 8 Plan 01: DiscoMode + Interface Summary

**DiscoMode extracted as callback-based module, setLastColorSchemeIndex added to BleRepository interface, Issue #144 concrete cast eliminated**

## Performance

- **Duration:** 6 min
- **Started:** 2026-02-15T20:53:14Z
- **Completed:** 2026-02-15T20:59:42Z
- **Tasks:** 3
- **Files modified:** 6

## Accomplishments
- Extracted disco mode easter egg from KableBleRepository into self-contained DiscoMode.kt module (99 lines)
- Added setLastColorSchemeIndex() to BleRepository interface with default no-op implementation
- Fixed Issue #144: SettingsManager no longer casts to concrete KableBleRepository type
- Updated all 3 BleRepository implementations (Kable, Fake, Simulator)

## Task Commits

Each task was committed atomically:

1. **Task 1: Create DiscoMode.kt with callback-based design** - `7bac93ce` (feat)
2. **Task 2: Add setLastColorSchemeIndex to interface and update implementations** - `50b19428` (refactor)
3. **Task 3: Fix SettingsManager concrete cast (Issue #144)** - `58c9397d` (fix)

## Files Created/Modified
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/DiscoMode.kt` - Self-contained disco mode easter egg with start/stop/setLastColorSchemeIndex
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/BleRepository.kt` - Added setLastColorSchemeIndex() with default no-op
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/KableBleRepository.kt` - Delegates disco operations to DiscoMode instance, removed inline disco logic
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/SettingsManager.kt` - Uses interface method instead of concrete cast
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeBleRepository.kt` - Added setLastColorSchemeIndex() override
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/simulator/SimulatorBleRepository.kt` - Added setLastColorSchemeIndex() override

## Decisions Made
- **Callback design over direct reference:** DiscoMode takes `suspend (ByteArray) -> Unit` instead of referencing KableBleRepository, preventing circular dependency and enabling easy testing
- **Connection guard in KableBleRepository:** The `peripheral == null` check stays in KableBleRepository.startDiscoMode(), not in DiscoMode itself, keeping DiscoMode decoupled from BLE connection state
- **Interface default implementation:** setLastColorSchemeIndex() uses a no-op default body in the interface, so FakeBleRepository and SimulatorBleRepository don't strictly need overrides (but we added explicit ones for clarity)

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- DiscoMode module is ready for Phase 02-led-biofeedback which may reuse LED control patterns
- BleRepository interface is stable with the new method
- All builds and tests pass
- Zero `as? KableBleRepository` casts remain in codebase

## Self-Check: PASSED

- [x] DiscoMode.kt exists at expected path
- [x] BleRepository.kt exists at expected path
- [x] 08-01-SUMMARY.md exists at expected path
- [x] Commit 7bac93ce found (Task 1)
- [x] Commit 50b19428 found (Task 2)
- [x] Commit 58c9397d found (Task 3)

---
*Phase: 08-disco-mode-interface*
*Completed: 2026-02-15*
