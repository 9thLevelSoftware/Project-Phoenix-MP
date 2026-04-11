---
phase: 28-integration-validation
plan: 02
subsystem: sync-testing
tags: [testing, sync-manager, token-storage, integration-tests]
dependency_graph:
  requires: []
  provides: [sync-manager-test-coverage, token-expiry-test-coverage, fake-portal-api-client, fake-sync-repository]
  affects: [shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalApiClient.kt]
tech_stack:
  added: []
  patterns: [open-class-for-test-subclassing, real-settings-in-tests, fake-api-client-pattern]
key_files:
  created:
    - shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/SyncManagerTest.kt
    - shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalTokenStorageTest.kt
    - shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakePortalApiClient.kt
    - shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeSyncRepository.kt
  modified:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalApiClient.kt
    - shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeGamificationRepository.kt
decisions:
  - Made PortalApiClient open (class + 4 methods) for test subclassing rather than extracting an interface, minimizing production code changes
  - Used real PortalTokenStorage with MapSettings for tests rather than faking it, giving true integration coverage of settings-backed state
  - Updated FakeGamificationRepository.saveRpgProfile to capture profile for assertion (was no-op)
metrics:
  duration: 249s
  completed: "2026-03-02T23:58:00Z"
  tasks: 5
  files_created: 4
  files_modified: 2
  test_cases: 27
---

# Phase 28 Plan 02: SyncManager + PortalTokenStorage Integration Tests Summary

SyncManager integration tests (20 cases) and PortalTokenStorage auth edge case tests (7 cases) using real MapSettings-backed token storage with fake API client and repositories.

## Tasks Completed

| # | Task | Commit | Key Changes |
|---|------|--------|-------------|
| 1 | Make PortalApiClient open | cc518d9e | Added `open` to class + signIn, signUp, pushPortalPayload, pullPortalPayload |
| 2 | Create FakePortalApiClient | d262160f | Test double extending PortalApiClient with configurable results and call counters |
| 3 | Create FakeSyncRepository | 10a83255 | Implements SyncRepository with configurable returns and merge call captures |
| 4 | Create SyncManagerTest | 12f3e9e2 | 20 test cases covering auth, push/pull, errors, timestamps |
| 5 | Create PortalTokenStorageTest | f734084a | 7 test cases covering token expiry buffer, clearAuth, saveGoTrueAuth |

## Test Coverage

### SyncManagerTest (20 cases)

**Auth state (4):**
- Initial state is Idle
- No token -> NotAuthenticated without calling push
- Login stores auth and returns PortalUser
- Logout clears auth and sets NotAuthenticated

**Push success flow (4):**
- Sync pushes local changes and returns Success
- Payload has correct deviceId and platform
- ISO 8601 syncTime parsed to epoch millis
- Empty local data still sends push

**Pull success flow (5):**
- Merges routines from pull response
- Merges badges from pull response
- Merges gamification stats from pull response
- Saves RPG attributes from pull response
- Empty pull response skips all merge calls

**Error handling (4):**
- Push 401 -> NotAuthenticated state
- Push non-401 -> Error state with message
- Pull failure is non-fatal, uses push syncTime
- Push failure does not call pull

**Timestamp management (3):**
- Sync updates lastSyncTimestamp in token storage
- Uses pull syncTime when pull succeeds
- Falls back to push syncTime when pull fails

### PortalTokenStorageTest (7 cases)

**Token expiry (4):**
- isTokenExpired true when expired
- isTokenExpired true within 60-second buffer (SC-3 proactive refresh trigger)
- isTokenExpired false when >60s remaining
- isTokenExpired true when no token stored

**Auth persistence (3):**
- clearAuth preserves deviceId and lastSyncTimestamp
- saveGoTrueAuth stores all fields correctly
- hasToken returns false after clearAuth

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing test functionality] FakeGamificationRepository.saveRpgProfile was no-op**
- **Found during:** Task 4 (SyncManagerTest writing)
- **Issue:** FakeGamificationRepository.saveRpgProfile() was a no-op, preventing assertion on RPG profile merge during pull
- **Fix:** Added `savedRpgProfile` capture field and updated `reset()` method
- **Files modified:** shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeGamificationRepository.kt
- **Commit:** 12f3e9e2 (included with SyncManagerTest commit)

**2. [Rule 3 - Blocking] PortalApiClient.kt changed between plan creation and execution**
- **Found during:** Task 1
- **Issue:** PortalApiClient had `syncBaseUrl` parameter and legacy sync endpoints removed since plan was written
- **Fix:** Applied open modifier to current file shape (2-parameter constructor, no legacy endpoints)
- **Files modified:** shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalApiClient.kt
- **Commit:** cc518d9e

## Decisions Made

1. **Open class over interface extraction**: Made PortalApiClient `open` with 4 `open` methods rather than extracting an interface. This is the minimum-diff approach -- 5 lines changed, zero risk of breaking existing code.

2. **Real PortalTokenStorage in tests**: Used real `PortalTokenStorage(MapSettings())` rather than faking it. This tests the actual settings key management, expiry buffer logic, and state flow emissions -- catching integration bugs a fake would miss.

3. **FakeGamificationRepository enhancement**: Added `savedRpgProfile` capture field to enable RPG attribute merge assertions. The prior no-op prevented testing the pull flow's RPG save path.

## Self-Check: PASSED

All 6 files verified present. All 5 task commits verified in git log.
