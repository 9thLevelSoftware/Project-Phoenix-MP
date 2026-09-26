# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) and other coding agents (`AGENTS.md` points here) when working with code in this repository.

## Working Rules

- BEFORE ANYTHING ELSE: check the portal sibling `../Phoenix-portal` (Edge Functions in `supabase/functions/`, schema in `supabase/migrations/`) for how a feature is implemented on the other side before troubleshooting or changing it. Mobile and portal share a parity-critical contract (see Sync Architecture below).
- Spawned agents must stay within this project's tools and skills (`.agents/skills/`: `agent-browser`, `update-phoenix-version`); don't pull in tooling from unrelated projects.
- All weight fields are **per cable** unless the name says *Total*. Never treat a machine total (200/220 kg) as a per-cable value.
- Never use `INSERT OR REPLACE` on a table with FK children. REPLACE deletes the old row, and `ON DELETE CASCADE` children (e.g. of `WorkoutSession`, `Exercise`) go with it. Use `INSERT OR IGNORE` + `UPDATE` instead.

## Project Overview

Kotlin Multiplatform app for controlling Phoenix Trainer workout machines via BLE. Community rescue project to keep machines functional after company bankruptcy.

## Build Commands

Every Gradle invocation configures `androidApp`, which fails without Supabase credentials. For test-only or local builds pass `-Pskip.supabase.check=true`. For a build that can sync, set `supabase.url` and `supabase.anon.key` in `local.properties` (or env `SUPABASE_URL` / `SUPABASE_ANON_KEY`).

```bash
# Android debug APK
./gradlew -Pskip.supabase.check=true :androidApp:assembleDebug

# Tests (the test suites CI runs). The shared suite holds most tests; don't stop at androidApp.
./gradlew -Pskip.supabase.check=true :shared:testAndroidHostTest :androidApp:testDebugUnitTest --continue
./gradlew -Pskip.supabase.check=true :shared:testAndroidHostTest --tests '*SchemaParityTest*'   # one class

# Schema manifest check (also runs automatically before SQLDelight codegen)
./gradlew -Pskip.supabase.check=true :shared:validateSchemaManifest

# iOS (macOS only; verified by the CI iOS workflows, not on Windows/Linux)
./gradlew -Pskip.supabase.check=true :shared:linkReleaseFrameworkIosArm64 :shared:generateComposeResClass :shared:iosArm64ProcessResources
./gradlew -Pskip.supabase.check=true :shared:assembleXCFramework
```

There is no `:shared:testDebugUnitTest`. Gradle runs can modify `gradle.properties` or create `gradle/gradle-daemon-jvm.properties`; don't commit either.

## Architecture

### Module Structure
- **shared/** - Kotlin Multiplatform library with business logic
- **androidApp/** - Android application (Compose, Min SDK 26)
- **iosApp/** - iOS application (SwiftUI + shared framework)

### Shared Module Source Sets
```
shared/src/
├── commonMain/     # Cross-platform code (domain models, interfaces, database)
├── androidMain/    # Android implementations (Android SQLite driver, EncryptedSharedPreferences, Health Connect)
└── iosMain/        # iOS implementations (Native SQLite driver, Keychain settings, HealthKit)
```

### Key Patterns
- **expect/actual** for platform-specific implementations (see `Platform.kt`)
- **Clean Architecture**: domain models in `domain/model/`; repository interfaces in `data/repository/` (e.g. `BleRepository.kt`); the BLE implementation in `data/ble/`
- **Koin** for dependency injection
- **SQLDelight** for type-safe multiplatform database
- **Coroutines + Flow** for async operations and reactive streams

### BLE Architecture
- Library: **Kable** (Kotlin Multiplatform), used from `commonMain` (`data/ble/KableBleConnectionManager.kt`). Android uses a vendored, patched Kable core in `third_party/kable-core-android-patched`.
- UUIDs and timeouts live in `util/BleConstants.kt`:
  - UART service `6e400001-b5a3-f393-e0a9-e50e24dcca9e`; the app **writes** commands to `6e400002-…` (`NUS_RX_CHAR_UUID_STRING`). No UART notify characteristic is used.
  - Telemetry arrives on the trainer's own characteristics: sample/monitor `90e991a6-…` (polled), reps `8308f2a6-…` (notify), mode `67d0dae0-…`, plus version, cable left/right, diagnostic and others listed there.
  - `CONNECTION_TIMEOUT_MS = 15000`, `SCAN_TIMEOUT_MS = 30000`, `GATT_OPERATION_TIMEOUT_MS = 5000`.
- Device-name filters (case-insensitive, `KableBleConnectionManager.kt`): the main scan accepts `Vee_`, `VIT` or `Phoenix`; scan-and-connect and the scan dedupe checks accept only `Vee_`/`VIT`.

### Database Schema
SQLDelight schema at `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/PhoenixDatabase.sq` (~48 tables) with migrations `migrations/1.sqm` … `N.sqm`. Core tables include `WorkoutSession`, `MetricSample`, `PersonalRecord`, `Exercise`, `Routine`/`RoutineExercise`. See "Schema changes" below before touching any of it.

### Domain Models
Located in `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/`:
- **Models.kt**: `WorkoutMode` (6 subtypes; Just Lift is the `isJustLift` flag, not a mode), `ConnectionState`, `WorkoutMetric`, `WorkoutSession`
- **Exercise.kt**: `Exercise`, `ExerciseCategory`, `ExerciseCableIntent` (muscle groups are plain strings; there is no `MuscleGroup` type)
- **Routine.kt**: `Routine`, `RoutineExercise`, `RoutineGroup`, `Superset`, `RoutineItem`, `WarmupSet`
- **Gamification.kt**, **RpgModels.kt**, **BiomechanicsModels.kt**, **TrainingCycleModels.kt**: gamification, RPG scoring, velocity zones, cycles

### Constants
`util/Constants.kt` contains:
- Weight limits, **per cable**: `MIN_WEIGHT_KG = 0`, `MAX_WEIGHT_KG = 100` (safe default max), `MAX_WEIGHT_PER_CABLE_KG = 110` (Trainer+ hardware ceiling)
- Weight increment options: `[0.5, 1, 2.5, 5]` kg (`WEIGHT_INCREMENT_OPTIONS_KG`, default 0.5) and `[0.1, 0.5, 1, 2.5, 5]` lb
- `APP_VERSION` and one-rep max formulas (`OneRepMaxCalculator`: Brzycki, Epley, hybrid `estimate()`)

BLE timeouts are in `util/BleConstants.kt` (see above), not here.

## Tech Stack Versions
See `gradle/libs.versions.toml` (single source of truth; don't copy versions into docs). Android min SDK 26; iOS deployment target 15.0.

## Hardware Support
- **Phoenix V-Form Trainer** (VIT-200): 200 kg total = 100 kg per cable
- **Phoenix Trainer+**: 220 kg total = 110 kg per cable

## Schema changes
1. Add `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/N.sqm` (N = highest existing + 1). Keep it additive (API 26 SQLite 3.18: no `DROP COLUMN`, no `ON CONFLICT DO UPDATE`).
2. Mirror the change in `PhoenixDatabase.sq` so a fresh install matches an upgraded one.
3. Add the matching entries in `data/local/SchemaManifest.kt` (`manifestTables`, `manifestColumns` heal ops, `manifestIndexes`). iOS relies on this post-open heal.
4. Add entry `N` to `getMigrationStatements` in `data/local/MigrationStatements.kt` (the resilient-migration fallback replays it when `Schema.migrate` throws). Keep entries contiguous with the `.sqm` files and backfill any missing N.
5. Verify: `:shared:validateSchemaManifest` (runs automatically before codegen) and `:shared:testAndroidHostTest --tests '*SchemaParityTest*'`.

The schema version is derived from the migration files (highest `N.sqm` + 1). Do not assign `version` inside the SQLDelight database block in `shared/build.gradle.kts`; that property is not a schema version and sets the Gradle project version instead.

## Releasing
1. Bump the version with the `update-phoenix-version` skill (`.agents/skills/update-phoenix-version/`). It keeps Android `versionName`, `Constants.APP_VERSION` and both iOS `MARKETING_VERSION` values aligned.
2. Merge to `main`, then dispatch `.github/workflows/release-all.yml` from `main`.
3. Among its gates, `release-all` refuses to run if Android `versionName` and the iOS `MARKETING_VERSION`s differ or the tag `v<version>` already exists (read the workflow for the current full set). It does not check `Constants.APP_VERSION`, so rely on the skill for that.
4. It creates the GitHub release `v<version>`, then calls `android-release-apk.yml`, `ios-release-ipa.yml`, `android-playstore.yml` and `ios-testflight.yml` (each can be skipped by an input).
5. Required repo secrets, by group: release (`RELEASE_PAT`); Supabase (`SUPABASE_URL`, `SUPABASE_ANON_KEY`); Android signing (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`); Play (`GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`); Apple signing (`BUILD_CERTIFICATE_BASE64`, `P12_PASSWORD`, `KEYCHAIN_PASSWORD`, `PROVISION_PROFILE_BASE64`, `PROVISIONING_PROFILE_NAME`, `TEAM_ID`); App Store Connect (`APPSTORE_API_KEY`, `APPSTORE_API_KEY_ID`, `APPSTORE_ISSUER_ID`, `APP_APPLE_ID`, `TESTFLIGHT_GROUP_NAME`).

### Pending release gates (reliability work, #832; not yet released as of v1.0.2)
`release-all` does not enforce these. Complete them, then delete this subsection, before dispatching the next release:
1. Deploy the portal first: the additive migration `20260920120000_sync_reliability_contract.sql`, both mobile sync Edge Functions and their portal callers, released together. Then verify the deployed contract with controlled accounts: component-safe writes, permanent workout deletion, exact acknowledgements, ownership transfer and event replay, and cycle rejection/nullable-field behaviour.
2. On macOS, build the release iOS framework and app and run the native startup/database tests with the production driver. Confirm foreign keys are enforced after migration and reopening.
3. On a V-Form and a Trainer+, verify manual and autoplay transitions, edited rack loads and counterweights, same-exercise and next-exercise transitions, idle/armed disconnect and recovery, and repeated timed warmups with the next exercise initially stationary.
4. During rollout, watch pending ownership/deletion operation counts, age and failures. Missing acknowledgements must stay pending: never treat them as success or rebind them to another account.

## Sync Architecture

### Key Sync Files
- `data/sync/SyncManager.kt` — Orchestrates push/pull operations with batching and partial success handling
- `data/sync/SyncTriggerManager.kt` — Debounces sync triggers, exponential backoff for transient errors
- `data/sync/PortalApiClient.kt` — HTTP client with error classification and token refresh
- `data/repository/SqlDelightSyncRepository.kt` — Merge strategies per entity type (INSERT OR IGNORE, upsert, LWW). Several "upserts" are `INSERT OR REPLACE` queries in `PhoenixDatabase.sq`; see the FK rule in Working Rules
- `data/sync/PortalSyncDtos.kt` — Wire-format DTOs (camelCase JSON, matches the Edge Functions), e.g. `PortalWorkoutSessionDto`, `PortalExerciseDto`
- `data/sync/PortalSyncAdapter.kt` / `PortalPullAdapter.kt` — Map local rows to push DTOs and pulled DTOs back to local rows
- `data/sync/ProfilePreferenceSyncCodec.kt`, `ProfilePreferenceSyncPlanner.kt`, `ProfilePreferenceSyncRepository.kt` — Per-profile preference sections sync
- `data/sync/SyncModels.kt` — Internal (non-wire) sync models: repository/merge entity DTOs (`WorkoutSessionSyncDto`, `PersonalRecordSyncDto`, `RoutineSyncDto`, … used by `mergeSessions` etc.), profile-preference internals, `IdMappings`, auth DTOs
- `data/sync/PortalTokenStorage.kt` — Auth token persistence

### Sync Trigger Patterns
- **`onWorkoutCompleted()`**: Always syncs immediately (bypasses throttle) since workout data is critical
- **`onAppForeground()`**: Respects throttle/backoff to avoid excessive sync attempts
- **`onConnectivityRestored()`**: Triggers immediate retry if sync was waiting for network

### Error Classification
```kotlin
enum class SyncErrorCategory {
    TRANSIENT,  // 5xx, timeout, 429 - retry with backoff
    PERMANENT,  // 400, 402, 403, 404, 413 - don't retry
    AUTH,       // 401 - trigger re-login
    NETWORK,    // UnknownHost, connection errors - wait for connectivity
}
```

### Batch Handling
- **SYNC_BATCH_SIZE = 50**: Sessions per batch to stay under Edge Function body limit (~1MB)
- **MAX_FULL_BATCH_RETRIES = 3**: Consecutive failures before requiring manual intervention
- Non-session data (routines, cycles, RPG, badges) included only in final batch

### Token Storage
- **iOS**: multiplatform-settings with Keychain backend (`KeychainSettings(service = "com.devil.phoenixproject.auth")`, `PlatformModule.ios.kt`) for access token, refresh token and expiry. Migrates from legacy NSUserDefaults on first access. No keychain-access-groups entitlement is needed (the entitlements file only has HealthKit keys).
- **Android**: `EncryptedSharedPreferences` backed by the Android Keystore (`PlatformModule.android.kt`).

### 1RM Estimate Parity (PARITY-CRITICAL)
- Two estimators, plus a third column — never relabel one as another:
  1. Hybrid rep-based 1RM — Brzycki `w*36/(37-reps)` for reps <= 10, Epley `w*(1+reps/30)` for reps > 10. Continuous at reps == 10. Single implementation: `OneRepMaxCalculator.estimate()` (`util/Constants.kt`). All hybrid estimates (UI display, PR storage, cycle reporting, portal DTO) route through it — never reimplement the formula.
  2. Velocity OLS (VBT) — `VelocityOneRepMaxEstimator` from BLE mean concentric velocity. Shipped separately as `PortalExerciseDto.velocityEstimatedOneRepMaxKg`. Must never overwrite the hybrid field.
  3. Max-weight PRs (`personal_records`) are a SEPARATE metric from either estimated 1RM.
- Portal sync (mobile contract): compute the hybrid estimate per exercise-session from **workingReps** (fallback `totalReps` if working is 0), per-cable kg, and still ship it as `PortalExerciseDto.estimatedOneRepMaxKg`. Portal store-verbatim of that field is **unverified** — do not assert the portal writes it unchanged or skips recompute. Client remains drop-resistant.
- The nullable, per-profile `ProfileExerciseBaseline` (manual 5/3/1 input, VBT assessment, or explicitly assigned legacy value) is the fallback scaling baseline for `% of PR` routines when no matching PersonalRecord exists (`ResolveRoutineWeightsUseCase`). `Exercise.one_rep_max_kg` is legacy migration input only.
- Saving a PersonalRecord never creates or updates a `ProfileExerciseBaseline`; PRs and user/assessment training baselines have separate ownership and lifecycles.
