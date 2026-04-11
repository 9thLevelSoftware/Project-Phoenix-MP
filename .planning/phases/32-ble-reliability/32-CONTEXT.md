# Phase 32: BLE Reliability — Context

## Phase Goal
Fix the BLE connection chain so devices can connect, reconnect, and maintain stable sessions. This phase addresses 8 audit findings (2 BLOCKER, 4 HIGH, 2 MEDIUM) identified in the 2026-03-23 beta-readiness audit.

## Requirements
- BLE-01: Fix connectToDevice() dead StateFlow (B1 BLOCKER)
- BLE-02: Wire auto-reconnect flow consumer (B2 BLOCKER)
- BLE-03: Cancel stale peripheral state observer on reconnect (H2 HIGH)
- BLE-04: Add error handling to onDeviceReady() (H3 HIGH)
- BLE-05: Add scan timeout to startScanning() (H4 HIGH)
- BLE-06: Fix BLE permission denied loop on Android 11+ (H10 HIGH)
- BLE-07: Add .catch{} to permanent init collectors (M1 MEDIUM)
- BLE-08: Increase CONNECTION_RETRY_DELAY_MS to 1500ms (M2 MEDIUM)

## Audit Source
`.planning/exploration-beta-readiness-audit.md` — full findings with file paths and line numbers.

## Key Files (read before modifying)
| File | Lines | Role |
|------|-------|------|
| `shared/.../presentation/manager/BleConnectionManager.kt` | 263 | High-level connection facade — Plans 1 |
| `shared/.../data/repository/KableBleRepository.kt` | 391 | Thin facade delegating to BLE modules — Plan 1 (read-only reference) |
| `shared/.../data/ble/KableBleConnectionManager.kt` | 1107 | Core connection lifecycle — Plan 2 |
| `shared/.../presentation/components/BlePermissionHandler.android.kt` | 269 | Permission gate — Plan 2 |
| `shared/.../presentation/manager/ActiveSessionEngine.kt` | 3265 | Workout orchestration — Plan 3 |
| `shared/.../util/BleConstants.kt` | 224 | Protocol constants — Plan 3 |

## Architecture Notes
- BLE connection flow: `BleConnectionManager` → `KableBleRepository` → `KableBleConnectionManager` → Kable `Peripheral`
- `KableBleConnectionManager` owns the Peripheral reference exclusively
- Reconnection: `KableBleConnectionManager` emits `ReconnectionRequest` → `KableBleRepository._reconnectionRequested` → NOBODY COLLECTS (B2)
- Guards: `wasEverConnected` and `isExplicitDisconnect` flags prevent spurious reconnects
- ActiveSessionEngine has 8 init-block collectors running in parallel with no `.catch{}`

## Existing Decisions
- Guided + Deep Analysis workflow (plan approval before execution)
- H8 (SavedStateHandle) deferred to v0.9.0

## Plan Structure
| Plan | Wave | Findings | Agent |
|------|------|----------|-------|
| 32-01: BLE Connection Foundation | 1 | B1, B2 | Senior Developer |
| 32-02: State Machine Hardening | 2 | H2, H3, H4, H10 | Senior Developer |
| 32-03: Error Resilience | 2 | M1, M2 | Senior Developer |

Wave 2 plans are independent and can execute in parallel.
