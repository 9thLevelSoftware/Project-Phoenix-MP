# Velocity-1RM Phase 5 — Historical Backfill — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** On a one-time, idempotent, off-startup pass, compute a current velocity-1RM estimate for every exercise the user has already trained (using all available MCV history), so existing users immediately see estimates without waiting for new workouts.

**Architecture:** A `BackfillVelocityOneRepMaxUseCase` enumerates exercises that have MCV-bearing sessions, and for each eligible one (≥2 distinct loads across **all** history) computes a single current estimate via the existing estimator + MVT model and persists it — skipping exercises that already have an estimate. The whole job runs once, gated by a `velocityOneRepMaxBackfillDone` preference, launched from `MainViewModel` init on a background coroutine. No schema migration.

**Tech Stack:** Kotlin Multiplatform, SQLDelight, Koin, kotlin.test.

Builds on the foundation + Phases 3–4 (this branch). Reuses `ComputeVelocityOneRepMaxUseCase`, `VelocityOneRepMaxRepository`, the preferences pattern.

## Global Constraints

- Backfill computes **one current estimate per exercise** from **all-time** MCV history (not the 28-day live window). Decision: current-estimate-only (no trend reconstruction).
- Eligibility is the estimator's own gate (≥2 distinct loads; quality flag stored as usual). Per-cable weights.
- **Idempotent:** gated by a run-once `velocityOneRepMaxBackfillDone` boolean preference AND per-exercise skip when an estimate already exists (handles partial runs / flag resets).
- Runs **off the startup critical path** (background coroutine), must not block UI or crash the app on failure (wrap in try/catch).
- No schema migration (query-only additions). Module test task: `:shared:testAndroidHostTest`. Branch: `fix/issue-517-review-followups` (PR #598). Only `git add` files you change.

---

## File Structure

**Modify:**
- `sqldelight/.../VitruvianDatabase.sq` — `selectExerciseIdsWithVelocityData` + `countVelocityOneRepMaxByExercise` queries.
- `data/repository/VelocityOneRepMaxRepository.kt` — `hasEstimates(exerciseId, profileId)`.
- `data/repository/WorkoutRepository.kt` + `SqlDelightWorkoutRepository.kt` — `getExerciseIdsWithVelocityData(profileId)`.
- `domain/usecase/ComputeVelocityOneRepMaxUseCase.kt` — optional `windowDays` param (default unchanged).
- `domain/model/UserPreferences.kt`, `data/preferences/PreferencesManager.kt`, `presentation/manager/SettingsManager.kt` — `velocityOneRepMaxBackfillDone` flag.
- `presentation/viewmodel/MainViewModel.kt` — one-time backfill trigger; `di/DomainModule.kt` — register the backfill use case + MainViewModel param.

**Create:**
- `domain/usecase/BackfillVelocityOneRepMaxUseCase.kt`

---

### Task 1: Enumerate exercises with MCV data + `hasEstimates`

**Files:**
- Modify: `VitruvianDatabase.sq`, `WorkoutRepository.kt` + `SqlDelightWorkoutRepository.kt`, `VelocityOneRepMaxRepository.kt`
- Test: extend `SqlDelightVelocityOneRepMaxRepositoryTest.kt` + a workout-repo test.

**Interfaces:**
- `suspend fun WorkoutRepository.getExerciseIdsWithVelocityData(profileId: String): List<String>`
- `suspend fun VelocityOneRepMaxRepository.hasEstimates(exerciseId: String, profileId: String): Boolean`

- [ ] **Step 1: Add queries to `VitruvianDatabase.sq`**

```sql
selectExerciseIdsWithVelocityData:
SELECT DISTINCT exerciseId FROM WorkoutSession
WHERE profile_id = :profileId AND exerciseId IS NOT NULL
  AND deletedAt IS NULL AND avgMcvMmS IS NOT NULL AND workingReps > 0;

countVelocityOneRepMaxByExercise:
SELECT COUNT(*) FROM VelocityOneRepMaxEstimate
WHERE exerciseId = :exerciseId AND profile_id = :profileId AND deletedAt IS NULL;
```

- [ ] **Step 2: Write failing tests**

In the velocity repo test:
```kotlin
@Test fun `hasEstimates reflects presence of rows`() = runTest {
    val db = createInMemoryTestDatabase(); seedExercise(db, id = "ex1")
    val repo = SqlDelightVelocityOneRepMaxRepository(db)
    assertFalse(repo.hasEstimates("ex1", "default"))
    repo.insert(result(100f, passed = true), "ex1", computedAt = 1L, profileId = "default")
    assertTrue(repo.hasEstimates("ex1", "default"))
}
```
In a workout-repo test (mirror the existing velocity-points test harness with `seedSession`):
```kotlin
@Test fun `getExerciseIdsWithVelocityData returns distinct mcv-bearing exercises`() = runTest {
    val db = createInMemoryTestDatabase(); seedExercise(db, id = "exA"); seedExercise(db, id = "exB")
    val repo = SqlDelightWorkoutRepository(db /* + deps */)
    seedSession(db, exerciseId = "exA", weightPerCableKg = 40f, workingAvgWeightKg = 40f, avgMcvMmS = 800f, timestamp = 1L, workingReps = 5, profileId = "default")
    seedSession(db, exerciseId = "exA", weightPerCableKg = 60f, workingAvgWeightKg = 60f, avgMcvMmS = 500f, timestamp = 2L, workingReps = 5, profileId = "default")
    seedSession(db, exerciseId = "exB", weightPerCableKg = 50f, workingAvgWeightKg = 50f, avgMcvMmS = null, timestamp = 1L, workingReps = 5, profileId = "default") // no MCV -> excluded
    val ids = repo.getExerciseIdsWithVelocityData("default")
    assertEquals(listOf("exA"), ids)
}
```

- [ ] **Step 3: Run to verify failure.** `./gradlew :shared:testAndroidHostTest --tests "*VelocityOneRepMaxRepositoryTest*" --tests "*WorkoutRepositoryVelocityPointsTest*"` — FAIL.

- [ ] **Step 4: Implement**

`VelocityOneRepMaxRepository.kt`:
```kotlin
// interface
suspend fun hasEstimates(exerciseId: String, profileId: String): Boolean
// impl
override suspend fun hasEstimates(exerciseId: String, profileId: String): Boolean =
    withContext(Dispatchers.IO) {
        queries.countVelocityOneRepMaxByExercise(exerciseId, profileId).executeAsOne() > 0L
    }
```
`WorkoutRepository.kt` + impl:
```kotlin
suspend fun getExerciseIdsWithVelocityData(profileId: String): List<String>
// impl
override suspend fun getExerciseIdsWithVelocityData(profileId: String): List<String> =
    withContext(Dispatchers.IO) {
        queries.selectExerciseIdsWithVelocityData(profileId).executeAsList()
    }
```
Add the new methods to any `FakeWorkoutRepository`/`FakeVelocityOneRepMaxRepository` (commonTest/testutil) + inline test stubs the compiler flags.

- [ ] **Step 5: Run to verify pass; Step 6: Commit**

```bash
git commit -m "feat(1rm): enumerate MCV-bearing exercises + hasEstimates (#517)"
```

---

### Task 2: `windowDays` param on `ComputeVelocityOneRepMaxUseCase`

**Files:**
- Modify: `domain/usecase/ComputeVelocityOneRepMaxUseCase.kt`
- Test: `ComputeVelocityOneRepMaxUseCaseTest.kt`

**Interfaces:**
- `suspend operator fun invoke(exerciseId, profileId, nowMs, windowDays: Int = WINDOW_DAYS): VelocityOneRepMaxResult?` — `sinceMs = nowMs - windowDays * DAY_MS`. Existing callers unaffected (default).

- [ ] **Step 1: Failing test** — add a case asserting a large `windowDays` includes an old point that the default 28-day window excludes (capture `sinceMs` via the `workoutPoints` lambda and assert it equals `nowMs - windowDays*DAY_MS`).

- [ ] **Step 2: Run → FAIL** (signature/behavior).

- [ ] **Step 3: Implement** — add the `windowDays: Int = WINDOW_DAYS` param; compute `sinceMs = nowMs - windowDays.toLong() * DAY_MS`. Keep `WINDOW_DAYS = 28`.

- [ ] **Step 4: Run → PASS** (existing tests still green since default is unchanged).

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(1rm): parameterize compute window for backfill (#517)"
```

---

### Task 3: `BackfillVelocityOneRepMaxUseCase` + run-once preference

**Files:**
- Create: `domain/usecase/BackfillVelocityOneRepMaxUseCase.kt`
- Modify: `domain/model/UserPreferences.kt`, `data/preferences/PreferencesManager.kt`, `presentation/manager/SettingsManager.kt`
- Test: `shared/src/commonTest/.../usecase/BackfillVelocityOneRepMaxUseCaseTest.kt`

**Interfaces:**
- `UserPreferences.velocityOneRepMaxBackfillDone: Boolean = false`; `PreferencesManager.setVelocityOneRepMaxBackfillDone(done: Boolean)` (mirror the existing `gamificationEnabled` boolean-pref pattern: `KEY_VELOCITY_1RM_BACKFILL_DONE`, `settings.getBoolean(KEY, false)` on load, setter persists + `updateAndEmit`). `SettingsManager.velocityOneRepMaxBackfillDone: StateFlow<Boolean>`.
- `class BackfillVelocityOneRepMaxUseCase(private val exerciseIds: suspend (profileId: String) -> List<String>, private val hasEstimates: suspend (exerciseId: String, profileId: String) -> Boolean, private val computeAllTime: suspend (exerciseId: String, profileId: String, nowMs: Long) -> VelocityOneRepMaxResult?) { suspend operator fun invoke(profileId: String, nowMs: Long): Int }` — returns the number of estimates created. For each exercise id: skip if `hasEstimates`; else call `computeAllTime` (which persists). Returns count of non-null results.

- [ ] **Step 1: Failing test (fakes)**

```kotlin
@Test fun `backfills only exercises without an existing estimate`() = runTest {
    val computed = mutableListOf<String>()
    val useCase = BackfillVelocityOneRepMaxUseCase(
        exerciseIds = { _ -> listOf("exA", "exB", "exC") },
        hasEstimates = { id, _ -> id == "exB" }, // exB already has one
        computeAllTime = { id, _, _ -> computed += id; if (id == "exC") null else dummyResult() }, // exC ineligible
    )
    val created = useCase("default", nowMs = 1_000L)
    assertEquals(listOf("exA", "exC"), computed) // exB skipped
    assertEquals(1, created)                      // only exA produced an estimate
}
```
`dummyResult()` builds a `VelocityOneRepMaxResult(...)`.

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement** the use case (loop, skip-if-hasEstimates, count non-null `computeAllTime`), and the boolean preference across UserPreferences/PreferencesManager/SettingsManager (mirror `gamificationEnabled`).

- [ ] **Step 4: Run → PASS.**

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(1rm): add BackfillVelocityOneRepMaxUseCase + run-once flag (#517)"
```

---

### Task 4: One-time startup trigger (idempotent) + DI

**Files:**
- Modify: `presentation/viewmodel/MainViewModel.kt`, `di/DomainModule.kt`
- Test: extend an appropriate integration/`MainViewModel` test if one covers init; otherwise verify by build + the use-case test.

**Interfaces:**
- `DomainModule`: register `BackfillVelocityOneRepMaxUseCase` wiring `exerciseIds → WorkoutRepository.getExerciseIdsWithVelocityData`, `hasEstimates → VelocityOneRepMaxRepository.hasEstimates`, `computeAllTime → { id, profile, now -> computeVelocityOneRepMaxUseCase(id, profile, now, windowDays = BACKFILL_WINDOW_DAYS) }` where `BACKFILL_WINDOW_DAYS = 3650` (10 years ≈ all history).
- `MainViewModel` gains the backfill use case + reads/sets the `velocityOneRepMaxBackfillDone` flag.

- [ ] **Step 1: Register DI + MainViewModel param**

`DomainModule.kt`:
```kotlin
single {
    val workoutRepo = get<WorkoutRepository>()
    val velRepo = get<VelocityOneRepMaxRepository>()
    val compute = get<ComputeVelocityOneRepMaxUseCase>()
    BackfillVelocityOneRepMaxUseCase(
        exerciseIds = { profile -> workoutRepo.getExerciseIdsWithVelocityData(profile) },
        hasEstimates = { id, profile -> velRepo.hasEstimates(id, profile) },
        computeAllTime = { id, profile, now -> compute(id, profile, now, windowDays = 3650) },
    )
}
```
Add `backfillVelocityOneRepMaxUseCase` to `MainViewModel`'s constructor; update all MainViewModel construction sites (grep `MainViewModel(` across source sets — production passes `get()`, tests pass a `BackfillVelocityOneRepMaxUseCase(...)` with trivial lambdas or a fake).

- [ ] **Step 2: One-time trigger in `MainViewModel` init**

Add a background launch (mirror the existing `viewModelScope.launch(NonCancellable)` pattern at ~line 663), gated by the run-once flag and wrapped in try/catch:
```kotlin
viewModelScope.launch(kotlinx.coroutines.NonCancellable) {
    try {
        if (!settingsManager.velocityOneRepMaxBackfillDone.value) {
            val profile = activeProfileId.value
            backfillVelocityOneRepMaxUseCase(profile, com.devil.phoenixproject.domain.model.currentTimeMillis())
            preferencesManager.setVelocityOneRepMaxBackfillDone(true)
        }
    } catch (e: Exception) {
        Logger.w(e) { "VELOCITY_1RM: backfill failed" }
    }
}
```
(Use the real `preferencesManager`/`settingsManager` references already on MainViewModel; match the profile-id accessor used elsewhere — `activeProfileId`.)

- [ ] **Step 3: Build + full suite**

Run: `./gradlew :androidApp:assembleDebug` and `./gradlew :shared:testAndroidHostTest`.
Expected: BUILD SUCCESSFUL / GREEN.

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(1rm): run velocity-1RM backfill once on startup (#517)"
```

---

## Self-Review

**Spec coverage (design §6):** replays historical sets with MCV — Task 1 (enumerate) + Task 3 (compute all-time) ✓; per-exercise windowed estimate — Task 2 (windowDays) + Task 3 ✓; populates the time-series with quality flags — reuses the foundation's persist (Task 3 via computeAllTime) ✓; lazily off startup — Task 4 (background launch) ✓; idempotent — run-once flag (Task 3/4) + per-exercise `hasEstimates` skip (Task 1/3) ✓.

**Placeholder scan:** Tasks 3–4 reference the `gamificationEnabled` boolean-pref pattern and the `NonCancellable` init pattern by exact location to mirror — read-then-match, not placeholders. `dummyResult()`/`BACKFILL_WINDOW_DAYS = 3650` are concrete.

**Type consistency:** `getExerciseIdsWithVelocityData`, `hasEstimates`, `windowDays`, `velocityOneRepMaxBackfillDone`, `BackfillVelocityOneRepMaxUseCase(exerciseIds, hasEstimates, computeAllTime)` consistent across tasks.

**No schema migration** (query-only). **Deferred:** portal parity (Phase 6, separate phoenix-portal repo) — the only remaining piece after this.
