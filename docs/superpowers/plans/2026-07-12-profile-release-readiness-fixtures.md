# Profile Release-Readiness Fixtures Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a reviewed schema-42 upgrade fixture and a deterministic, local-only debug Profile dataset so every remaining Profile release-readiness check can be repeated without remote services.

**Architecture:** A one-line fixture branch materializes the missing SQLDelight schema-42 APK from the pre-profile commit. The current branch gains an explicit debug-only broadcast seeder that writes through the real repositories, while a debug `PortalApiClient` subclass blocks profile fixture data from all portal push/pull traffic. APKs and AVD snapshots remain untracked; checksummed recipes, fixture input, automated release-boundary tests, and the final evidence report are tracked.

**Tech Stack:** Kotlin/JVM and Android, Kotlin Multiplatform repositories, SQLDelight 2, Koin, coroutines, JUnit4, Android debug source sets, PowerShell, adb, Android Emulator API 36.

## Global Constraints

- No Supabase CLI, MCP, Dashboard, portal authentication, remote SQL, or remote network mutation is permitted.
- Disable emulator networking before injecting legacy values, seeding QA profiles, or launching the upgraded app.
- The schema-42 fixture branch starts at `ac84d9bb8e156002833ad526bf324a8f12710da0` and changes only SQLDelight's version/comment from 41 to 42; it must not contain `42.sqm`.
- Both fixture and current APKs use `com.devil.phoenixproject.debug`, version code 5, and the same local debug keystore.
- QA fixture behavior exists only under `androidApp/src/debug`; release artifacts contain no receiver, application override, gate, seeder, action, or QA profile literal.
- Setting the QA fixture flag must happen synchronously before any seed write. While set, `pushPortalPayload` and `pullPortalPayload` return a local failure before opening a network request.
- The QA fixture is explicit, never automatic at startup, idempotent, and uses exact profile names `[QA] Profile A` and `[QA] Profile B`.
- Seed writes use repository APIs except for fixture-owned cleanup where no delete API exists. Never reset through `UserProfileRepository.deleteProfile`, because deletion merges data into Default.
- Seed exactly five completed sessions per QA profile for one deterministic catalog exercise, two stored PR types that render all three insight highlights, one assessment, one passing velocity 1RM, and per-rep VBT metrics.
- Profile A and B have visibly distinct complete core, rack, workout, LED, VBT, and local-safety preferences. Profile B has VBT disabled while historical assessment and velocity data remain present.
- APKs, emulator images, AVD directories, snapshots, databases, logs, and hashes containing absolute machine paths are stored outside Git under `.phoenix-review/profile-readiness/`.
- Any new production-visible Kotlin behavior follows strict red-green-refactor TDD. Documentation/configuration artifacts have contract tests before their final contents are added.
- Task 11 remains verification-only. All fixture and harness work is completed and reviewed before rerunning it.

---

### Task 1: Track and Materialize the Genuine Schema-42 Fixture

**Files:**
- Create: `androidApp/src/test/kotlin/com/devil/phoenixproject/qa/Schema42FixtureContractTest.kt`
- Create: `docs/qa/fixtures/profile-schema42/vitruvian_preferences.xml`
- Create: `docs/qa/profile-schema42-fixture.md`
- Modify only in fixture worktree: `shared/build.gradle.kts`

**Interfaces:**
- Consumes: pre-profile commit `ac84d9bb8e156002833ad526bf324a8f12710da0`, migrations through `41.sqm`, Android debug signing configuration.
- Produces: branch `codex/schema-42-fixture`, a schema-42 debug APK, a named AVD snapshot, fixture input checksum, APK checksum/signer evidence, and a reproducible upgrade recipe.

- [ ] **Step 1: Write the failing fixture contract test**

Create a JUnit test that resolves the repository root and asserts the tracked XML contains exact sentinel entries for `weight_unit=KG`, `body_weight_kg=82.5`, `weight_increment=2.5`, `color_scheme=3`, `summary_countdown_seconds=15`, `autostart_countdown_seconds=7`, `velocity_loss_threshold_percent=35`, and `default_scaling_basis=ESTIMATED_1RM`. It must assert `profile_preferences_legacy_migration_complete_v1` is absent, the rack JSON contains `fixture-vest`, and the fixture guide pins the full `ac84d9bb...` SHA plus the exact schema-version diff.

- [ ] **Step 2: Run the contract and observe RED**

Run:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :androidApp:testDebugUnitTest --tests "com.devil.phoenixproject.qa.Schema42FixtureContractTest" --rerun-tasks --console=plain
```

Expected: failure because the tracked XML and guide do not exist.

- [ ] **Step 3: Add the XML and reproduction guide**

The XML is a complete Android `map` document containing valid values for every legacy profile-owned key enumerated by `ProfilePreferencesMigration`. Use the exact rack JSON:

```json
[{"id":"fixture-vest","name":"Fixture Vest","category":"WEIGHTED_VEST","weightKg":10.0,"behavior":"ADDED_RESISTANCE","enabled":true,"sortOrder":7,"createdAt":1700000000000,"updatedAt":1700000001000}]
```

The guide includes creation, offline build, install, injection, snapshot, checksum, immutable restore, `adb install -r`, and post-upgrade SQL inspection commands. It explicitly forbids uninstall/`pm clear` between fixture and upgrade.

- [ ] **Step 4: Run the contract GREEN and commit tracked fixture inputs**

Run the Step 2 command again. Expected: one clean passing suite. Commit:

```powershell
git add androidApp/src/test/kotlin/com/devil/phoenixproject/qa/Schema42FixtureContractTest.kt docs/qa/fixtures/profile-schema42/vitruvian_preferences.xml docs/qa/profile-schema42-fixture.md
git commit -m "test: define schema 42 upgrade fixture"
```

- [ ] **Step 5: Create the isolated schema-42 worktree and fixture commit**

From the repository root:

```powershell
git check-ignore -q .worktrees/profile-schema42
git worktree add .worktrees/profile-schema42 -b codex/schema-42-fixture ac84d9bb8e156002833ad526bf324a8f12710da0
```

In the fixture worktree, change only:

```kotlin
// Version 42 = initial schema (1) + 41 migrations (1.sqm through 41.sqm).
version = 42
```

Use `apply_patch`, then verify `42.sqm` is absent, run SQLDelight generation/migration checks and `:androidApp:assembleDebug` offline, and commit `test: materialize schema 42 fixture`.

- [ ] **Step 6: Create the disposable API-36 AVD fixture and record immutable evidence**

Use the installed API 36 Google Play x86_64 image. Install the schema-42 debug APK, launch once, force-stop, inject the tracked XML with `run-as`, save named snapshot `phoenix-schema42-v1`, and record fixture commit, APK SHA-256, signer digest, XML SHA-256, emulator/adb versions, AVD/API/ABI, and per-file snapshot hashes under `.phoenix-review/profile-readiness/schema42/`.

---

### Task 2: Enforce the Debug-Only and Local-Only Boundary

**Files:**
- Create: `androidApp/src/test/kotlin/com/devil/phoenixproject/qa/QaReleaseBoundaryTest.kt`
- Create: `androidApp/src/testDebug/kotlin/com/devil/phoenixproject/qa/QaBlockingPortalApiClientTest.kt`
- Create: `androidApp/src/debug/AndroidManifest.xml`
- Create: `androidApp/src/debug/kotlin/com/devil/phoenixproject/qa/ProfileQaDebugApp.kt`
- Create: `androidApp/src/debug/kotlin/com/devil/phoenixproject/qa/ProfileQaFixtureGate.kt`
- Create: `androidApp/src/debug/kotlin/com/devil/phoenixproject/qa/QaBlockingPortalApiClient.kt`
- Modify: `androidApp/src/main/kotlin/com/devil/phoenixproject/VitruvianApp.kt`

**Interfaces:**
- Consumes: `PortalApiClient`, `PortalSyncPayload`, `KnownEntityIds`, `PortalTokenStorage`, `SupabaseConfig`, Koin startup.
- Produces: `ProfileQaFixtureGate`, `QaBlockingPortalApiClient`, and a debug application override installed before any Activity can resolve `SyncManager`.

- [ ] **Step 1: Write failing boundary and blocker tests**

The release-boundary contract scans `androidApp/src/main`, `src/release`, and the merged release manifest inputs and rejects `ProfileQa`, `QA_SEED_PROFILE`, `[QA] Profile`, or `QaBlockingPortalApiClient`. It requires all QA sources beneath `src/debug` except tests/docs. The blocker test uses a Ktor mock engine and proves push and pull make zero requests when the gate is set, returning `PortalApiException("QA fixture is local-only")`.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :androidApp:testDebugUnitTest --tests "*QaReleaseBoundaryTest" --tests "*QaBlockingPortalApiClientTest" --rerun-tasks --console=plain
```

Expected: missing debug gate/client/application classes.

- [ ] **Step 3: Implement the minimum debug gate and client**

`ProfileQaFixtureGate` wraps a private debug SharedPreferences file, with `isEnabled()` and synchronous `enable()`. `QaBlockingPortalApiClient` subclasses `PortalApiClient`; its push/pull overrides return the exact local failure when enabled and otherwise delegate to `super`. `ProfileQaDebugApp` subclasses an `open VitruvianApp`, calls `super.onCreate()`, then loads a Koin override for `PortalApiClient` before Activity creation. The debug manifest replaces the application name with `.qa.ProfileQaDebugApp`.

- [ ] **Step 4: Run GREEN and verify release assembly**

Run the Step 2 command plus:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :androidApp:assembleDebug :androidApp:assembleRelease '-Pversion.code=999999' --rerun-tasks --console=plain
```

Inspect the release merged manifest and release APK dex/string inventory; no QA symbols/actions may occur. Commit `test: isolate profile QA fixtures to debug`.

---

### Task 3: Seed Deterministic Profiles, Insights, Racks, and VBT History

**Files:**
- Create: `androidApp/src/testDebug/kotlin/com/devil/phoenixproject/qa/ProfileQaSeederTest.kt`
- Create: `androidApp/src/testDebug/kotlin/com/devil/phoenixproject/qa/ProfileQaSeedReceiverTest.kt`
- Create: `androidApp/src/debug/kotlin/com/devil/phoenixproject/qa/ProfileQaSeeder.kt`
- Create: `androidApp/src/debug/kotlin/com/devil/phoenixproject/qa/ProfileQaSeedReceiver.kt`
- Modify: `androidApp/src/debug/AndroidManifest.xml`

**Interfaces:**
- Consumes: `UserProfileRepository`, `ExerciseRepository`, `WorkoutRepository`, `RepMetricRepository`, `PersonalRecordRepository`, `AssessmentRepository`, `VelocityOneRepMaxRepository`, and `VitruvianDatabase` only for fixture-row cleanup unavailable through repository interfaces.
- Produces: `ProfileQaSeeder.seed(): ProfileQaSeedResult` and exported debug broadcast action `com.devil.phoenixproject.QA_SEED_PROFILE`.

- [ ] **Step 1: Write failing seeder and receiver tests**

Tests require: exact/reused A/B profile identities; complete inverse typed preferences; deterministic rack IDs; exactly five fixed sessions/profile after two seed runs; per-session rep metrics; max-weight/max-volume records yielding three visible highlight values; one assessment; a newer passing velocity result whose total 1RM is higher than assessment/session fallback; B's VBT disabled with history retained; fixture gate enabled before the first write; `goAsync()` completion on success/failure; and one stable log/result marker without preference values.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :androidApp:testDebugUnitTest --tests "*ProfileQaSeederTest" --tests "*ProfileQaSeedReceiverTest" --rerun-tasks --console=plain
```

Expected: missing seeder/receiver APIs.

- [ ] **Step 3: Implement minimal idempotent fixture data**

Use catalog exercise `Bench Press`. Profiles use exact names and deterministic visible values. Seed fixed sessions at timestamps `1700100000000` through `1700500000000`, with increasing completed loads and nonzero velocity/force/volume metrics. Save 50 kg/cable × 3 reps for max weight and 40 kg/cable × 12 reps for max volume. Save an assessment below the velocity result. Insert a passing velocity estimate of 77 kg/cable, MVT 0.30 m/s, R² 0.97, three loads, at a fixed newer marker. Remove only prior fixture-owned rows before reinsertion; never delete a QA profile through profile deletion.

The receiver enables the gate synchronously, calls `goAsync()`, launches a supervised IO coroutine, invokes `seed()`, logs only `PROFILE_QA_SEED_OK` or `PROFILE_QA_SEED_FAILED:<category>`, and always finishes the pending result.

- [ ] **Step 4: Run GREEN, repository regressions, and commit**

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :androidApp:testDebugUnitTest --tests "*ProfileQa*" --rerun-tasks --console=plain
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests "*SqlDelightWorkoutRepositoryTest*" --tests "*SqlDelightAssessmentRepositoryTest*" --tests "*SqlDelightVelocityOneRepMaxRepositoryTest*" --rerun-tasks --console=plain
```

Expected: zero failures/errors/skips. Commit `test: add deterministic profile QA seed`.

---

### Task 4: Execute Offline Upgrade and Populated Profile Acceptance

**Files:**
- Create after evidence exists: `docs/qa/profile-release-readiness-2026-07-12.md`
- Modify: none elsewhere.

**Interfaces:**
- Consumes: immutable schema-42 snapshot, current debug APK, QA seed action, Task 11 manual matrix.
- Produces: checksummed local evidence and a row-by-row acceptance result without remote actions.

- [ ] **Step 1: Validate the schema-42 to 43 upgrade**

Restore `phoenix-schema42-v1` with `-force-snapshot-load -no-snapshot-save`, keep networking disabled, run `adb install -r` with the current APK, launch, await the migration gate, and pull the debuggable DB/preferences. Verify user version 43, exactly one normalized copy into every existing profile with `legacy_migration_version=1`, the completion flag, VBT forced enabled for migrated profiles, a Ready context, and unchanged normalized values after a second launch.

- [ ] **Step 2: Seed and verify the complete Profile matrix**

Install/launch the current debug APK on a fresh disposable AVD, disable networking, broadcast the seed action, and wait for `PROFILE_QA_SEED_OK`. Through UI-tree-derived coordinates verify A/B preference swaps including racks and local safety, five-session history, all three PR highlights, velocity 1RM precedence, selection isolation, B's disabled VBT with historical assessment/velocity insight still visible, rename/delete guard, global-only Settings, Profile-only Equipment Rack/Achievements, and absence of legacy selectors.

- [ ] **Step 3: Exercise accessibility and haptic evidence**

Enable installed TalkBack, focus the Profile item, capture the exposed click and long-click actions using TalkBack's action menu/accessibility-node evidence, and trigger long press. Record vibrator-service evidence for the app UID. If a physical Android device is connected, additionally confirm audible labels and tactile feedback there; do not substitute a claim of physical sensation when no device exists.

- [ ] **Step 4: Exercise live VBT evidence**

If a compatible trainer is connected, perform identical telemetry with VBT on/off and confirm feedback appears only when enabled while history remains. Without trainer hardware, run the reviewed runtime simulation suites and record hardware validation as unavailable rather than fabricating it.

- [ ] **Step 5: Write and test the evidence report**

The report records branch/HEADs, fixture hashes, emulator/API/density/width, commands, exact test counts, every manual row, unavailable external hardware evidence, Spotless's actual result, worktree cleanliness, and the exact sentence: `No Supabase CLI/MCP/Dashboard/remote action was performed.` Add a contract test if any required evidence field is machine-checkable, then commit `docs: record profile readiness fixture evidence`.

---

### Task 5: Final Independent Review and Verification

**Files:**
- Verify only: every Task 1-4 file and generated artifact boundary.
- Modify: none unless a reviewer reports a Critical or Important defect.

**Interfaces:**
- Consumes: all reviewed task commits and evidence.
- Produces: final branch verdict and a reusable schema/history fixture handoff.

- [ ] **Step 1: Run focused and full gates**

Run all new QA contracts, the exact existing 32/109/158/34 Profile suites, schema/migration, sync/privacy, repository-race, Koin, full Android-host suite, Android/iOS compilation, Android unit/lint/debug/release assembly, and `git diff --check`. Record exact XML totals.

- [ ] **Step 2: Reproduce Spotless without mutation**

Run `spotlessCheck` only, record the exact known-baseline count or new green result, and verify HEAD/status are unchanged. Never run `spotlessApply` in this task.

- [ ] **Step 3: Perform independent whole-branch review**

Review for debug/release isolation, remote fail-closed behavior, fixture idempotence, repository invariants, sensitive-data logging, schema authenticity, and evidence accuracy. Fix Critical/Important findings with covering tests and repeat review.

- [ ] **Step 4: Finish the branch**

Use `superpowers:finishing-a-development-branch`, present the verified integration options, and retain external fixture artifacts until the user chooses cleanup.
