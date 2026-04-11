# Phase 35: iOS Platform Parity — Context

## Phase Goal
Fix iOS-specific runtime failures and document platform feature gaps. 6 findings (2 BLOCKER, 4 HIGH).

## Requirements
- IOS-01: Update CURRENT_SCHEMA_VERSION to 22 (B7 BLOCKER)
- IOS-02: Add formScore column to iOS manual schema (B8 BLOCKER)
- IOS-03: Implement ConnectivityChecker with NWPathMonitor (H12 HIGH)
- IOS-04: Document/improve withPlatformLock global lock (H13 HIGH)
- IOS-05: Gate Form Check feature behind platform check on iOS (H14 HIGH)
- IOS-06: Verify no Vico chart imports leak to commonMain/iosMain (H15 HIGH)

## Key Files
| File | Role | Plans |
|------|------|-------|
| `shared/src/iosMain/.../data/local/DriverFactory.ios.kt` | iOS database driver | 1 |
| `shared/src/iosMain/.../util/ConnectivityChecker.ios.kt` | Network check stub | 2 |
| `shared/src/iosMain/.../util/Locking.ios.kt` | Platform lock | 2 |
| `shared/src/commonMain/.../presentation/screen/` | UI screens | 3 |
| `shared/build.gradle.kts` | Dependencies | 3 |

## Plan Structure
| Plan | Wave | Findings | Agent |
|------|------|----------|-------|
| 35-01: iOS Database Blockers | 1 | B7, B8 | Senior Developer |
| 35-02: iOS Infrastructure | 2 | H12, H13 | Senior Developer |
| 35-03: Feature Gating | 2 | H14, H15 | Senior Developer |
