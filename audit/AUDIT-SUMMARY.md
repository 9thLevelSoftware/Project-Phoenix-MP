# Project Phoenix - Full Application Audit Summary

**Date:** 2026-03-31  
**Auditor:** Automated code analysis (Factory AI)  
**Branch:** `mvp` (commit `01ecf8ae`)  
**Scope:** Full codebase - shared KMP module, Android app, iOS platform code, Supabase sync layer  
**Type:** Audit-only (no fixes applied)

---

## Audit Reports

| Report | File | Findings |
|--------|------|----------|
| Database & Migrations | [`database-migrations-audit.md`](database-migrations-audit.md) | 4 Critical, 6 High |
| BLE Communication | [`ble-communication-audit.md`](ble-communication-audit.md) | 3 High, 12 Medium, 10 Low |
| UI & State Management | [`ui-state-management-audit.md`](ui-state-management-audit.md) | 4 Critical, 6 High, 10 Medium, 6 Low |
| Sync Layer | [`sync-layer-audit.md`](sync-layer-audit.md) | 3 High, 12 Medium, 11 Low |
| Architecture & Testing | [`architecture-testing-audit.md`](architecture-testing-audit.md) | 2 High, 7 Medium, 3 Low |

---

## Consolidated Severity Counts

| Severity | Database | BLE | UI/State | Sync | Arch/Test | **Total** |
|----------|----------|-----|----------|------|-----------|-----------|
| Critical | 4 | 0 | 4 | 0 | 0 | **8** |
| High | 6 | 3 | 6 | 3 | 2 | **20** |
| Medium | — | 12 | 10 | 12 | 7 | **41** |
| Low | — | 10 | 6 | 11 | 3 | **30** |
| **Total** | **10** | **25** | **26** | **26** | **12** | **99** |

---

## Top 10 Critical & High Priority Issues for Resolution Sprint

### Critical (Fix Immediately)

1. **[DB-C001] `.sqm` vs `MigrationStatements.kt` divergence** — Migrations 3, 4, 9, 10 have fundamentally different behavior in the `.sqm` files (Copy-Drop-Recreate) vs the resilient fallback (just ALTER TABLE ADD COLUMN). This is the root cause of platform-specific schema shape differences between Android and iOS, directly explaining why iOS crashes and Android loses data on migrations.

2. **[DB-C002] Migration 24 resilient fallback lacks transaction wrapping** — The DROP TABLE + RENAME sequence for EarnedBadge runs without a transaction, risking badge data loss if the rename fails after the drop.

3. **[DB-C003] MigrationManager runs data migrations asynchronously with no completion signal** — Data cleanup, normalization, and PR repair run fire-and-forget on `Dispatchers.IO`, creating race conditions between migration logic and user writes on app startup.

4. **[DB-C004] iOS `foreignKeyConstraints = false` at driver level** — FK constraints are disabled during migration on iOS, allowing orphaned rows that would be rejected on Android, causing schema behavior divergence.

5. **[UI-C001] LinkAccountViewModel leaks CoroutineScope** — Creates its own unmanaged CoroutineScope without cleanup. Each navigation to the screen creates a new leaked scope with running coroutines.

6. **[UI-C003] MainViewModel god-object (18 parameters, ~150+ public methods)** — Extreme coupling makes testing difficult and any change ripples through the entire delegation surface.

7. **[UI-C004] Thread-unsafe mutable state in WorkoutCoordinator** — `@Volatile` fields used for cross-coroutine visibility but compound read-modify-write operations are not atomic, causing potential race conditions in auto-stop timing.

8. **[UI-C002] ViewModels not registered in Koin** — `ExerciseLibraryViewModel` and `ExerciseConfigViewModel` bypass dependency injection entirely.

### High (Fix in Sprint 1)

9. **[ARCH-H001] iOS token storage uses plain NSUserDefaults** — Auth tokens (JWT, refresh, email) stored unencrypted. Android uses EncryptedSharedPreferences with Keystore. Significant security asymmetry.

10. **[ARCH-H002] ~30,000 lines of UI code with zero test coverage** — All screen composables, navigation flows, and most components have no automated tests. Regressions are caught only through manual testing.

---

## Build System Status

| Check | Status | Notes |
|-------|--------|-------|
| `shared:allTests` | PASS | All unit tests pass |
| `shared:testAndroidHostTest` | PASS | 80+ test files, all passing |
| `shared:validateSchemaManifest` | PASS | 284 columns across 29 tables validated |
| `shared:verifyMigrations` | **FAIL** | SQLite JDBC native library loading error (`UnsatisfiedLinkError`) — migration chain correctness is NOT being validated by the build system |
| `androidApp:testDebugUnitTest` | NOT RUN IN CI | Only smoke test exists |
| iOS tests | COMPILE ONLY | No `iosSimulatorArm64` target; tests never execute |

---

## Recommended Resolution Sprint Priorities

### Sprint 1: Database & Migration Hardening (Highest Priority)
The user's primary pain point — iOS crashes and Android data loss on migration — traces directly to the `.sqm` vs `MigrationStatements.kt` divergence (DB-C001). Fix this first:
- Reconcile all divergent migration paths to produce identical schema shapes
- Wrap all resilient fallback operations in transactions (DB-C002)
- Make data migrations synchronous with completion signaling (DB-C003)
- Fix the `verifyMigrations` Gradle task so migration correctness is validated in CI
- Enable foreign key constraints consistently across platforms (DB-C004)

### Sprint 2: Security & Stability
- Implement iOS Keychain storage for auth tokens (ARCH-H001)
- Fix CoroutineScope leaks in LinkAccountViewModel (UI-C001)
- Fix thread-unsafe state in WorkoutCoordinator (UI-C004)
- Add centralized CoroutineExceptionHandler (ARCH-M003)
- Fix ViewModel lifecycle mismatches (UI-H001, UI-H002)

### Sprint 3: BLE Reliability
- Persist workout data on unexpected disconnect (BLE-H002)
- Fix auto-reconnect suppression (BLE-H001)
- Add timeouts to disconnect operations (BLE-M001)
- Implement iOS background BLE support (BLE-M011)
- Fix unsynchronized discoveredAdvertisements map (BLE-M010)

### Sprint 4: Sync Layer Hardening
- Unify conflict resolution strategy across entity types (documented in sync report)
- Make batched push atomic to prevent partial sync state
- Add proper session expiry notification to UI
- Implement exponential backoff on failures

### Sprint 5: Testing & CI Infrastructure
- Add iOS simulator target for test execution
- Run Android app tests in CI
- Add code coverage reporting (Kover)
- Enforce spotless formatting in CI
- Begin adding UI tests for critical flows

---

## Methodology

Each audit domain was investigated by a specialized code analysis agent that:
1. Read all source files in the domain (including platform-specific implementations)
2. Analyzed code for defects, race conditions, error handling gaps, and anti-patterns
3. Cross-referenced with existing test files to identify coverage gaps
4. Produced findings with specific file/location references and severity ratings

The Gradle build and test suite were executed to establish baseline pass/fail status. The SQLDelight migration verification task failure was discovered during this process.

All findings reference specific source files. No documentation or existing audit reports were consulted — this is a from-scratch code-only analysis.
