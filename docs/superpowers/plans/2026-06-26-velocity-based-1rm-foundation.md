# Velocity-Based 1RM — Foundation (Phases 1–2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automatically estimate a per-exercise velocity-based 1RM from completed workout sets, persist it as a time-series, and display it alongside the existing PR/assessment 1RM on the exercise detail screen.

**Architecture:** Reuse the existing `AssessmentEngine` OLS estimator. A pure-domain `VelocityOneRepMaxEstimator` builds `(load, MCV)` points from a rolling window of completed `WorkoutSession`s (which already carry `avgMcvMmS` and `workingAvgWeightKg`), resolves a Minimum Velocity Threshold (MVT) via a movement-pattern model, and fits the line. A use case orchestrates DB I/O and persists each estimate to a new `VelocityOneRepMaxEstimate` table, triggered from the existing post-save hook. Phase 2 surfaces the latest passing estimate on `ExerciseDetailScreen`.

**Tech Stack:** Kotlin Multiplatform (commonMain), SQLDelight 2.2.1, Koin 4.1.1, Coroutines/Flow, kotlin.test.

Source spec: `docs/superpowers/specs/2026-06-26-velocity-based-1rm-design.md` (issue #517).

## Global Constraints

- All weight values are **per-cable** (0–220 kg). Estimates are stored and computed in per-cable space.
- MCV is stored in the DB in **mm/s** (`WorkoutSession.avgMcvMmS`); convert to m/s by dividing by `1000f` before fitting.
- Quality gate: an estimate **passes** iff `distinctLoads >= 2` AND `r2 >= 0.8f` AND the regression slope is negative (the latter is already enforced by `AssessmentEngine`).
- MVT pattern defaults (m/s): horizontal press `0.15`, vertical press `0.20`, squat `0.30`, hinge/deadlift `0.15`, global fallback `OTHER = 0.20`.
- Personalized MVT overrides the pattern default only after **≥ 3** captured samples.
- `Exercise.oneRepMaxKg` remains the authoritative "true/assessment" 1RM and is NOT overwritten by this pipeline.
- Reuse `AssessmentEngine.estimateOneRepMax()` unchanged — do not reimplement OLS.
- **No phoenix-portal / sync changes in this plan** (that is Phase 6, a separate plan). New table may include `updatedAt`/`serverId`/`deletedAt` columns for future parity but no sync wiring.
- Adding a table requires updating **four** places: the `.sq` schema + queries, a new numbered `.sqm` migration, `MigrationStatements.getMigrationStatements()`, and `SchemaManifest` (`SchemaTableOperation`). The `SchemaParityTest`/`SchemaManifestTest` enforce this.
- Rolling window: **last 28 days** of sessions for the exercise+profile; dedup points by load (most-recent wins); require ≥2 distinct loads.

---

## File Structure

**Create:**
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/onerepmax/MovementPattern.kt` — pattern enum + classifier (pure).
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/onerepmax/MvtProvider.kt` — MVT resolution (pure).
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/onerepmax/VelocityOneRepMaxEstimator.kt` — point construction + fit + quality gate (pure).
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/ComputeVelocityOneRepMaxUseCase.kt` — orchestration.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/RecordPersonalMvtSampleUseCase.kt` — personalized MVT capture.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/VelocityOneRepMaxRepository.kt` — interface + entity + impl.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/PersonalMvtRepository.kt` — interface + entity + impl.
- `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/36.sqm`
- `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/37.sqm`
- Test files mirroring each (`commonTest` for pure domain, `androidHostTest` for DB repos).

**Modify:**
- `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq` — two CREATE TABLEs, `Exercise.mvtOverrideMs` column, queries.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/MigrationStatements.kt` — `36 ->`, `37 ->` branches.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/SchemaManifest.kt` — two `SchemaTableOperation`s + `Exercise.mvtOverrideMs` heal op.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepository.kt` + `WorkoutRepository.kt` — velocity-points query method.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Exercise.kt` — `mvtOverrideMs` field.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DomainModule.kt` + `DataModule.kt` — register new components.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/GamificationManager.kt` — invoke compute use case in `processPostSaveEvents`.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseDetailScreen.kt` — display latest passing estimate.

---

## Phase 1 — Estimation engine, MVT model, persistence

### Task 1: MovementPattern enum + classifier

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/onerepmax/MovementPattern.kt`
- Test: `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/onerepmax/MovementPatternTest.kt`

**Interfaces:**
- Produces: `enum class MovementPattern(val defaultMvtMs: Float)` with values `HORIZONTAL_PRESS(0.15f)`, `VERTICAL_PRESS(0.20f)`, `SQUAT(0.30f)`, `HINGE(0.15f)`, `OTHER(0.20f)`; `fun classifyMovementPattern(name: String, muscleGroups: String): MovementPattern`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.devil.phoenixproject.domain.onerepmax

import kotlin.test.Test
import kotlin.test.assertEquals

class MovementPatternTest {
    @Test fun `bench press classifies as horizontal press`() =
        assertEquals(MovementPattern.HORIZONTAL_PRESS, classifyMovementPattern("Barbell Bench Press", "Chest"))

    @Test fun `overhead press classifies as vertical press`() =
        assertEquals(MovementPattern.VERTICAL_PRESS, classifyMovementPattern("Overhead Press", "Shoulders"))

    @Test fun `back squat classifies as squat`() =
        assertEquals(MovementPattern.SQUAT, classifyMovementPattern("Back Squat", "Legs"))

    @Test fun `deadlift classifies as hinge`() =
        assertEquals(MovementPattern.HINGE, classifyMovementPattern("Romanian Deadlift", "Hamstrings"))

    @Test fun `bicep curl falls back to other`() =
        assertEquals(MovementPattern.OTHER, classifyMovementPattern("Bicep Curl", "Biceps"))

    @Test fun `default mvt values match spec`() {
        assertEquals(0.15f, MovementPattern.HORIZONTAL_PRESS.defaultMvtMs)
        assertEquals(0.20f, MovementPattern.VERTICAL_PRESS.defaultMvtMs)
        assertEquals(0.30f, MovementPattern.SQUAT.defaultMvtMs)
        assertEquals(0.15f, MovementPattern.HINGE.defaultMvtMs)
        assertEquals(0.20f, MovementPattern.OTHER.defaultMvtMs)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:testDebugUnitTest --tests "*MovementPatternTest*"`
Expected: FAIL — `classifyMovementPattern` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.devil.phoenixproject.domain.onerepmax

/** Movement patterns with their default Minimum Velocity Threshold (m/s) for 1RM extrapolation. */
enum class MovementPattern(val defaultMvtMs: Float) {
    HORIZONTAL_PRESS(0.15f),
    VERTICAL_PRESS(0.20f),
    SQUAT(0.30f),
    HINGE(0.15f),
    OTHER(0.20f),
}

/**
 * Best-effort classification of an exercise into a movement pattern from its name and
 * muscle groups. Keyword order matters: more specific patterns are checked first.
 */
fun classifyMovementPattern(name: String, muscleGroups: String): MovementPattern {
    val haystack = "$name $muscleGroups".lowercase()
    return when {
        listOf("deadlift", "rdl", "hip thrust", "hinge", "good morning", "swing").any { it in haystack } -> MovementPattern.HINGE
        listOf("squat", "leg press", "lunge", "split squat").any { it in haystack } -> MovementPattern.SQUAT
        listOf("overhead", "ohp", "shoulder press", "military", "push press").any { it in haystack } -> MovementPattern.VERTICAL_PRESS
        listOf("bench", "chest press", "horizontal press", "floor press").any { it in haystack } -> MovementPattern.HORIZONTAL_PRESS
        else -> MovementPattern.OTHER
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:testDebugUnitTest --tests "*MovementPatternTest*"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/onerepmax/MovementPattern.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/onerepmax/MovementPatternTest.kt
git commit -m "feat(1rm): add movement-pattern classifier with MVT defaults (#517)"
```

---

### Task 2: MvtProvider

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/onerepmax/MvtProvider.kt`
- Test: `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/onerepmax/MvtProviderTest.kt`

**Interfaces:**
- Consumes: `MovementPattern`, `classifyMovementPattern` (Task 1).
- Produces: `class MvtProvider { fun resolve(exerciseName: String, muscleGroups: String, userOverrideMs: Float?, personalMvtMs: Float?, personalSampleCount: Int): Float }`. Resolution priority: `userOverrideMs` (if > 0) → `personalMvtMs` (if `personalSampleCount >= MIN_PERSONAL_MVT_SAMPLES`) → pattern default. Constant `MvtProvider.MIN_PERSONAL_MVT_SAMPLES = 3`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.devil.phoenixproject.domain.onerepmax

import kotlin.test.Test
import kotlin.test.assertEquals

class MvtProviderTest {
    private val provider = MvtProvider()

    @Test fun `falls back to pattern default when no personal or override`() =
        assertEquals(0.30f, provider.resolve("Back Squat", "Legs", null, null, 0))

    @Test fun `personal mvt overrides pattern once threshold met`() =
        assertEquals(0.22f, provider.resolve("Back Squat", "Legs", null, 0.22f, 3))

    @Test fun `personal mvt ignored below sample threshold`() =
        assertEquals(0.30f, provider.resolve("Back Squat", "Legs", null, 0.22f, 2))

    @Test fun `user override beats personal and pattern`() =
        assertEquals(0.18f, provider.resolve("Back Squat", "Legs", 0.18f, 0.22f, 5))

    @Test fun `non-positive override is ignored`() =
        assertEquals(0.20f, provider.resolve("Cable Fly", "Chest", 0f, null, 0))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:testDebugUnitTest --tests "*MvtProviderTest*"`
Expected: FAIL — `MvtProvider` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.devil.phoenixproject.domain.onerepmax

/** Resolves the Minimum Velocity Threshold (m/s) for an exercise. */
class MvtProvider {
    fun resolve(
        exerciseName: String,
        muscleGroups: String,
        userOverrideMs: Float?,
        personalMvtMs: Float?,
        personalSampleCount: Int,
    ): Float {
        if (userOverrideMs != null && userOverrideMs > 0f) return userOverrideMs
        if (personalMvtMs != null && personalMvtMs > 0f && personalSampleCount >= MIN_PERSONAL_MVT_SAMPLES) {
            return personalMvtMs
        }
        return classifyMovementPattern(exerciseName, muscleGroups).defaultMvtMs
    }

    companion object {
        const val MIN_PERSONAL_MVT_SAMPLES = 3
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:testDebugUnitTest --tests "*MvtProviderTest*"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/onerepmax/MvtProvider.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/onerepmax/MvtProviderTest.kt
git commit -m "feat(1rm): add MvtProvider with personal-override threshold (#517)"
```

---

### Task 3: VelocityOneRepMaxEstimator

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/onerepmax/VelocityOneRepMaxEstimator.kt`
- Test: `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/onerepmax/VelocityOneRepMaxEstimatorTest.kt`

**Interfaces:**
- Consumes: `AssessmentEngine.estimateOneRepMax(points: List<LoadVelocityPoint>, config: AssessmentConfig)` and `LoadVelocityPoint(loadKg, meanVelocityMs)` from `com.devil.phoenixproject.domain.assessment`.
- Produces:
  - `data class WorkoutVelocityPoint(val loadPerCableKg: Float, val mcvMmS: Float, val timestampMs: Long, val workingReps: Int)`
  - `data class VelocityOneRepMaxResult(val estimatedPerCableKg: Float, val mvtUsedMs: Float, val r2: Float, val distinctLoads: Int, val passedQualityGate: Boolean)`
  - `class VelocityOneRepMaxEstimator(private val assessmentEngine: AssessmentEngine) { fun estimate(points: List<WorkoutVelocityPoint>, mvtMs: Float): VelocityOneRepMaxResult? }`
  - Returns `null` when no estimate is computable (fewer than 2 distinct loads after dedup, or the regression is degenerate/positive-slope). When non-null, `estimatedPerCableKg` is per-cable.
  - Constants on companion: `R2_PASS_THRESHOLD = 0.8f`, `MIN_DISTINCT_LOADS = 2`, `LOAD_BUCKET_KG = 0.5f`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.devil.phoenixproject.domain.onerepmax

import com.devil.phoenixproject.domain.assessment.AssessmentEngine
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VelocityOneRepMaxEstimatorTest {
    private val estimator = VelocityOneRepMaxEstimator(AssessmentEngine())

    private fun point(load: Float, mcvMmS: Float, ts: Long = 0L) =
        WorkoutVelocityPoint(loadPerCableKg = load, mcvMmS = mcvMmS, timestampMs = ts, workingReps = 5)

    @Test fun `two distinct loads produce a passing estimate`() {
        // load 40 @ 1.2 m/s (1200 mm/s), load 80 @ 0.6 m/s -> 1RM at 0.30 m/s
        val result = estimator.estimate(
            points = listOf(point(40f, 1200f), point(80f, 600f)),
            mvtMs = 0.30f,
        )
        assertNotNull(result)
        assertTrue(result.passedQualityGate, "2 clean points on a line should pass")
        assertTrue(result.estimatedPerCableKg in 95f..105f, "expected ~100kg, got ${result.estimatedPerCableKg}")
    }

    @Test fun `single distinct load returns null`() {
        assertNull(estimator.estimate(listOf(point(60f, 800f), point(60f, 790f)), mvtMs = 0.30f))
    }

    @Test fun `positive slope returns null`() {
        // velocity increasing with load is non-physiological -> AssessmentEngine rejects
        assertNull(estimator.estimate(listOf(point(40f, 600f), point(80f, 900f)), mvtMs = 0.30f))
    }

    @Test fun `duplicate loads are deduped keeping most recent`() {
        // newest 60kg point (ts=100) should win over older (ts=1)
        val result = estimator.estimate(
            points = listOf(point(40f, 1200f, ts = 50), point(60f, 900f, ts = 100), point(60f, 100f, ts = 1)),
            mvtMs = 0.30f,
        )
        assertNotNull(result)
        // distinctLoads counts unique buckets, not raw points
        assertTrue(result.distinctLoads == 2)
    }

    @Test fun `warmup-only or zero-rep points excluded by caller contract are not required here`() {
        // estimator trusts caller to pass working points; verify it still needs 2 loads
        assertNull(estimator.estimate(listOf(point(50f, 700f)), mvtMs = 0.20f))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:testDebugUnitTest --tests "*VelocityOneRepMaxEstimatorTest*"`
Expected: FAIL — estimator unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.devil.phoenixproject.domain.onerepmax

import com.devil.phoenixproject.domain.assessment.AssessmentConfig
import com.devil.phoenixproject.domain.assessment.AssessmentEngine
import com.devil.phoenixproject.domain.assessment.LoadVelocityPoint
import kotlin.math.roundToInt

/** One completed-set data point fed into velocity-1RM estimation. Load is per-cable; MCV in mm/s. */
data class WorkoutVelocityPoint(
    val loadPerCableKg: Float,
    val mcvMmS: Float,
    val timestampMs: Long,
    val workingReps: Int,
)

/** Result of a velocity-based 1RM estimate. Estimate is per-cable kg. */
data class VelocityOneRepMaxResult(
    val estimatedPerCableKg: Float,
    val mvtUsedMs: Float,
    val r2: Float,
    val distinctLoads: Int,
    val passedQualityGate: Boolean,
)

/**
 * Builds load-velocity points from completed sets and fits a 1RM via the existing
 * [AssessmentEngine]. Pure: no I/O. The caller supplies an already-windowed list of
 * working-set points and the resolved MVT.
 */
class VelocityOneRepMaxEstimator(private val assessmentEngine: AssessmentEngine) {

    fun estimate(points: List<WorkoutVelocityPoint>, mvtMs: Float): VelocityOneRepMaxResult? {
        // Keep only usable points, then dedup by load bucket keeping the most recent.
        val deduped = points
            .filter { it.loadPerCableKg > 0f && it.mcvMmS > 0f && it.workingReps > 0 }
            .groupBy { bucketOf(it.loadPerCableKg) }
            .map { (_, group) -> group.maxByOrNull { it.timestampMs }!! }

        val distinctLoads = deduped.size
        if (distinctLoads < MIN_DISTINCT_LOADS) return null

        val lvPoints = deduped.map {
            LoadVelocityPoint(loadKg = it.loadPerCableKg, meanVelocityMs = it.mcvMmS / 1000f)
        }

        // minSets=2 matches our gate; oneRmVelocityMs is the resolved MVT.
        val config = AssessmentConfig(minSets = MIN_DISTINCT_LOADS, oneRmVelocityMs = mvtMs)
        val assessment = assessmentEngine.estimateOneRepMax(lvPoints, config) ?: return null

        val passed = distinctLoads >= MIN_DISTINCT_LOADS && assessment.r2 >= R2_PASS_THRESHOLD
        return VelocityOneRepMaxResult(
            estimatedPerCableKg = assessment.estimatedOneRepMaxKg,
            mvtUsedMs = mvtMs,
            r2 = assessment.r2,
            distinctLoads = distinctLoads,
            passedQualityGate = passed,
        )
    }

    private fun bucketOf(loadKg: Float): Int = (loadKg / LOAD_BUCKET_KG).roundToInt()

    companion object {
        const val R2_PASS_THRESHOLD = 0.8f
        const val MIN_DISTINCT_LOADS = 2
        const val LOAD_BUCKET_KG = 0.5f
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:testDebugUnitTest --tests "*VelocityOneRepMaxEstimatorTest*"`
Expected: PASS (5 tests). If the `~100kg` assertion is off, recheck the mm/s→m/s conversion (`/1000f`).

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/onerepmax/VelocityOneRepMaxEstimator.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/onerepmax/VelocityOneRepMaxEstimatorTest.kt
git commit -m "feat(1rm): add VelocityOneRepMaxEstimator reusing AssessmentEngine (#517)"
```

---

### Task 4: `VelocityOneRepMaxEstimate` table (schema + migration + manifest)

**Files:**
- Modify: `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq`
- Create: `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/36.sqm`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/MigrationStatements.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/SchemaManifest.kt`
- Test: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/local/SchemaManifestTest.kt` (existing parity test — must still pass).

**Interfaces:**
- Produces SQLDelight queries: `insertVelocityOneRepMax`, `selectVelocityOneRepMaxByExercise`, `selectLatestPassingVelocityOneRepMax`.

- [ ] **Step 1: Add the table + queries to `VitruvianDatabase.sq`**

Insert after the `AssessmentResult` block (around line 434):

```sql
-- ==================== VELOCITY-BASED 1RM ESTIMATES ====================
-- Auto-computed velocity 1RM time-series (issue #517). Separate from
-- AssessmentResult (wizard) and from Exercise.oneRepMaxKg (authoritative true 1RM).

CREATE TABLE VelocityOneRepMaxEstimate (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    exerciseId TEXT NOT NULL,
    estimatedPerCableKg REAL NOT NULL,
    mvtUsedMs REAL NOT NULL,
    r2 REAL NOT NULL,
    distinctLoads INTEGER NOT NULL,
    passedQualityGate INTEGER NOT NULL DEFAULT 0,
    computedAt INTEGER NOT NULL,
    profile_id TEXT NOT NULL DEFAULT 'default',
    -- Sync columns (future parity; unused in this plan)
    updatedAt INTEGER,
    serverId TEXT,
    deletedAt INTEGER,
    FOREIGN KEY (exerciseId) REFERENCES Exercise(id) ON DELETE CASCADE
);

CREATE INDEX idx_velocity_1rm_exercise ON VelocityOneRepMaxEstimate(exerciseId, profile_id);

insertVelocityOneRepMax:
INSERT INTO VelocityOneRepMaxEstimate (exerciseId, estimatedPerCableKg, mvtUsedMs, r2, distinctLoads, passedQualityGate, computedAt, profile_id)
VALUES (?, ?, ?, ?, ?, ?, ?, ?);

selectVelocityOneRepMaxByExercise:
SELECT * FROM VelocityOneRepMaxEstimate
WHERE exerciseId = :exerciseId AND profile_id = :profileId AND deletedAt IS NULL
ORDER BY computedAt DESC;

selectLatestPassingVelocityOneRepMax:
SELECT * FROM VelocityOneRepMaxEstimate
WHERE exerciseId = :exerciseId AND profile_id = :profileId
AND passedQualityGate = 1 AND deletedAt IS NULL
ORDER BY computedAt DESC
LIMIT 1;
```

- [ ] **Step 2: Create migration `36.sqm`**

```sql
-- Migration 36: Velocity-based 1RM estimate time-series (issue #517).
CREATE TABLE IF NOT EXISTS VelocityOneRepMaxEstimate (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    exerciseId TEXT NOT NULL,
    estimatedPerCableKg REAL NOT NULL,
    mvtUsedMs REAL NOT NULL,
    r2 REAL NOT NULL,
    distinctLoads INTEGER NOT NULL,
    passedQualityGate INTEGER NOT NULL DEFAULT 0,
    computedAt INTEGER NOT NULL,
    profile_id TEXT NOT NULL DEFAULT 'default',
    updatedAt INTEGER,
    serverId TEXT,
    deletedAt INTEGER,
    FOREIGN KEY (exerciseId) REFERENCES Exercise(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_velocity_1rm_exercise ON VelocityOneRepMaxEstimate(exerciseId, profile_id);
```

- [ ] **Step 3: Add the `36 ->` branch to `MigrationStatements.getMigrationStatements()`**

Add a new `when` branch (match the existing `35 ->` style — `CREATE TABLE IF NOT EXISTS` + index as separate list entries):

```kotlin
36 -> listOf(
    """CREATE TABLE IF NOT EXISTS VelocityOneRepMaxEstimate (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        exerciseId TEXT NOT NULL,
        estimatedPerCableKg REAL NOT NULL,
        mvtUsedMs REAL NOT NULL,
        r2 REAL NOT NULL,
        distinctLoads INTEGER NOT NULL,
        passedQualityGate INTEGER NOT NULL DEFAULT 0,
        computedAt INTEGER NOT NULL,
        profile_id TEXT NOT NULL DEFAULT 'default',
        updatedAt INTEGER,
        serverId TEXT,
        deletedAt INTEGER,
        FOREIGN KEY (exerciseId) REFERENCES Exercise(id) ON DELETE CASCADE
    )""",
    """CREATE INDEX IF NOT EXISTS idx_velocity_1rm_exercise ON VelocityOneRepMaxEstimate(exerciseId, profile_id)""",
)
```

- [ ] **Step 4: Add a `SchemaTableOperation` to `SchemaManifest.kt`**

Append to the table-operations list (same shape as the existing `SchemaTableOperation(...)` entries near line 1029+), using `CREATE TABLE IF NOT EXISTS` with the identical column list from Step 2.

```kotlin
SchemaTableOperation(
    table = "VelocityOneRepMaxEstimate",
    createSql = """
        CREATE TABLE IF NOT EXISTS VelocityOneRepMaxEstimate (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            exerciseId TEXT NOT NULL,
            estimatedPerCableKg REAL NOT NULL,
            mvtUsedMs REAL NOT NULL,
            r2 REAL NOT NULL,
            distinctLoads INTEGER NOT NULL,
            passedQualityGate INTEGER NOT NULL DEFAULT 0,
            computedAt INTEGER NOT NULL,
            profile_id TEXT NOT NULL DEFAULT 'default',
            updatedAt INTEGER,
            serverId TEXT,
            deletedAt INTEGER,
            FOREIGN KEY (exerciseId) REFERENCES Exercise(id) ON DELETE CASCADE
        )
    """.trimIndent(),
),
```

- [ ] **Step 5: Run the schema build + parity tests**

Run: `./gradlew :shared:generateCommonMainVitruvianDatabaseInterface :shared:testDebugUnitTest --tests "*SchemaManifestTest*" --tests "*SchemaParityTest*"`
Expected: SQLDelight generates `insertVelocityOneRepMax` etc.; parity tests PASS. If parity fails, the `.sq` CREATE and the `SchemaManifest` createSql differ — reconcile them character-for-character (ignoring whitespace).

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/sqldelight shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/MigrationStatements.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/SchemaManifest.kt
git commit -m "feat(1rm): add VelocityOneRepMaxEstimate table + migration 36 (#517)"
```

---

### Task 5: VelocityOneRepMaxRepository

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/VelocityOneRepMaxRepository.kt`
- Test: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightVelocityOneRepMaxRepositoryTest.kt`

**Interfaces:**
- Consumes: generated queries from Task 4; `VitruvianDatabase`.
- Produces:
  - `data class VelocityOneRepMaxEntity(val id: Long, val exerciseId: String, val estimatedPerCableKg: Float, val mvtUsedMs: Float, val r2: Float, val distinctLoads: Int, val passedQualityGate: Boolean, val computedAt: Long, val profileId: String)`
  - `interface VelocityOneRepMaxRepository { suspend fun insert(result: VelocityOneRepMaxResult, exerciseId: String, computedAt: Long, profileId: String); suspend fun getLatestPassing(exerciseId: String, profileId: String): VelocityOneRepMaxEntity?; fun getHistory(exerciseId: String, profileId: String): Flow<List<VelocityOneRepMaxEntity>> }`
  - `class SqlDelightVelocityOneRepMaxRepository(private val db: VitruvianDatabase) : VelocityOneRepMaxRepository`

- [ ] **Step 1: Write the failing test**

Copy the in-memory DB harness from the existing `SqlDelightAssessmentRepositoryTest.kt` (same package/source set) for driver + schema creation, then:

```kotlin
package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.onerepmax.VelocityOneRepMaxResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlDelightVelocityOneRepMaxRepositoryTest {
    // val db = createInMemoryTestDatabase()  // harness copied from SqlDelightAssessmentRepositoryTest

    private fun result(estimate: Float, passed: Boolean) =
        VelocityOneRepMaxResult(estimatedPerCableKg = estimate, mvtUsedMs = 0.3f, r2 = if (passed) 0.95f else 0.4f, distinctLoads = 3, passedQualityGate = passed)

    @Test fun `insert then latest passing returns most recent passing row`() = runTest {
        val db = createInMemoryTestDatabase()
        // Seed an Exercise row 'ex1' first (FK). Reuse the exercise-insert helper from the harness.
        seedExercise(db, id = "ex1")
        val repo = SqlDelightVelocityOneRepMaxRepository(db)

        repo.insert(result(100f, passed = true), exerciseId = "ex1", computedAt = 1_000L, profileId = "default")
        repo.insert(result(120f, passed = false), exerciseId = "ex1", computedAt = 2_000L, profileId = "default")
        repo.insert(result(110f, passed = true), exerciseId = "ex1", computedAt = 3_000L, profileId = "default")

        val latest = repo.getLatestPassing("ex1", "default")
        assertEquals(110f, latest?.estimatedPerCableKg)
        assertTrue(latest!!.passedQualityGate)
    }

    @Test fun `latest passing is null when only failing rows exist`() = runTest {
        val db = createInMemoryTestDatabase()
        seedExercise(db, id = "ex2")
        val repo = SqlDelightVelocityOneRepMaxRepository(db)
        repo.insert(result(90f, passed = false), exerciseId = "ex2", computedAt = 1_000L, profileId = "default")
        assertNull(repo.getLatestPassing("ex2", "default"))
    }
}
```

> Note: `createInMemoryTestDatabase()` and `seedExercise(...)` are the harness helpers — copy them verbatim from `SqlDelightAssessmentRepositoryTest.kt`, which already constructs a `VitruvianDatabase` over an in-memory `JdbcSqliteDriver` and inserts Exercise rows for FK satisfaction.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:testDebugUnitTest --tests "*SqlDelightVelocityOneRepMaxRepositoryTest*"`
Expected: FAIL — repository unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.onerepmax.VelocityOneRepMaxResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class VelocityOneRepMaxEntity(
    val id: Long,
    val exerciseId: String,
    val estimatedPerCableKg: Float,
    val mvtUsedMs: Float,
    val r2: Float,
    val distinctLoads: Int,
    val passedQualityGate: Boolean,
    val computedAt: Long,
    val profileId: String,
)

interface VelocityOneRepMaxRepository {
    suspend fun insert(result: VelocityOneRepMaxResult, exerciseId: String, computedAt: Long, profileId: String)
    suspend fun getLatestPassing(exerciseId: String, profileId: String): VelocityOneRepMaxEntity?
    fun getHistory(exerciseId: String, profileId: String): Flow<List<VelocityOneRepMaxEntity>>
}

class SqlDelightVelocityOneRepMaxRepository(private val db: VitruvianDatabase) : VelocityOneRepMaxRepository {
    private val queries = db.vitruvianDatabaseQueries

    private fun map(
        id: Long, exerciseId: String, estimatedPerCableKg: Double, mvtUsedMs: Double, r2: Double,
        distinctLoads: Long, passedQualityGate: Long, computedAt: Long, profile_id: String,
        updatedAt: Long?, serverId: String?, deletedAt: Long?,
    ) = VelocityOneRepMaxEntity(
        id = id, exerciseId = exerciseId, estimatedPerCableKg = estimatedPerCableKg.toFloat(),
        mvtUsedMs = mvtUsedMs.toFloat(), r2 = r2.toFloat(), distinctLoads = distinctLoads.toInt(),
        passedQualityGate = passedQualityGate != 0L, computedAt = computedAt, profileId = profile_id,
    )

    override suspend fun insert(result: VelocityOneRepMaxResult, exerciseId: String, computedAt: Long, profileId: String) =
        withContext(Dispatchers.IO) {
            queries.insertVelocityOneRepMax(
                exerciseId = exerciseId,
                estimatedPerCableKg = result.estimatedPerCableKg.toDouble(),
                mvtUsedMs = result.mvtUsedMs.toDouble(),
                r2 = result.r2.toDouble(),
                distinctLoads = result.distinctLoads.toLong(),
                passedQualityGate = if (result.passedQualityGate) 1L else 0L,
                computedAt = computedAt,
                profile_id = profileId,
            )
        }

    override suspend fun getLatestPassing(exerciseId: String, profileId: String): VelocityOneRepMaxEntity? =
        withContext(Dispatchers.IO) {
            queries.selectLatestPassingVelocityOneRepMax(exerciseId, profileId, ::map).executeAsOneOrNull()
        }

    override fun getHistory(exerciseId: String, profileId: String): Flow<List<VelocityOneRepMaxEntity>> =
        queries.selectVelocityOneRepMaxByExercise(exerciseId, profileId, ::map).asFlow().mapToList(Dispatchers.IO)
}
```

> If the generated `map` parameter order/types differ (SQLDelight orders by column declaration), align the `::map` signature to the generated query's mapper exactly — the compiler error will name the expected lambda type.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:testDebugUnitTest --tests "*SqlDelightVelocityOneRepMaxRepositoryTest*"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/VelocityOneRepMaxRepository.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightVelocityOneRepMaxRepositoryTest.kt
git commit -m "feat(1rm): add VelocityOneRepMaxRepository (#517)"
```

---

### Task 6: Per-exercise velocity-points query on WorkoutRepository

**Files:**
- Modify: `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq` (add query)
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/WorkoutRepository.kt` (interface method)
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepository.kt` (impl)
- Test: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepositoryVelocityPointsTest.kt`

**Interfaces:**
- Produces: `suspend fun WorkoutRepository.getVelocityPointsForExercise(exerciseId: String, profileId: String, sinceTimestampMs: Long): List<WorkoutVelocityPoint>` (returns the domain type from Task 3). Load uses `workingAvgWeightKg` when non-null, else `weightPerCableKg`.

- [ ] **Step 1: Add the query to `VitruvianDatabase.sq`**

Place near the other `WorkoutSession` SELECTs (after `selectSessionsByExerciseSince` region ~line 595):

```sql
selectVelocityPointsByExercise:
SELECT workingAvgWeightKg, weightPerCableKg, avgMcvMmS, timestamp, workingReps
FROM WorkoutSession
WHERE exerciseId = :exerciseId
  AND profile_id = :profileId
  AND timestamp >= :sinceTimestamp
  AND deletedAt IS NULL
  AND avgMcvMmS IS NOT NULL
  AND workingReps > 0
ORDER BY timestamp DESC;
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.onerepmax.WorkoutVelocityPoint
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlDelightWorkoutRepositoryVelocityPointsTest {
    @Test fun `returns one point per qualifying session using working avg weight`() = runTest {
        val db = createInMemoryTestDatabase()
        seedExercise(db, id = "ex1")
        val repo = SqlDelightWorkoutRepository(db /* + same deps as production ctor */)

        // session A: workingAvgWeightKg=40, mcv=1200, ts=2000, workingReps=5
        seedSession(db, exerciseId = "ex1", weightPerCableKg = 42f, workingAvgWeightKg = 40f, avgMcvMmS = 1200f, timestamp = 2000L, workingReps = 5, profileId = "default")
        // session B (older, before window): excluded by sinceTimestamp
        seedSession(db, exerciseId = "ex1", weightPerCableKg = 80f, workingAvgWeightKg = 80f, avgMcvMmS = 600f, timestamp = 100L, workingReps = 5, profileId = "default")

        val points = repo.getVelocityPointsForExercise("ex1", "default", sinceTimestampMs = 1000L)
        assertEquals(1, points.size)
        assertEquals(40f, points.first().loadPerCableKg)
        assertEquals(1200f, points.first().mcvMmS)
    }
}
```

> `seedSession(...)` is a harness helper inserting a `WorkoutSession` with the given columns — add it next to `seedExercise` in the shared test harness (it wraps `queries.insertSession(...)` with defaults for unrelated columns).

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :shared:testDebugUnitTest --tests "*WorkoutRepositoryVelocityPointsTest*"`
Expected: FAIL — `getVelocityPointsForExercise` unresolved.

- [ ] **Step 4: Implement the interface method + impl**

In `WorkoutRepository.kt`:

```kotlin
suspend fun getVelocityPointsForExercise(
    exerciseId: String,
    profileId: String,
    sinceTimestampMs: Long,
): List<com.devil.phoenixproject.domain.onerepmax.WorkoutVelocityPoint>
```

In `SqlDelightWorkoutRepository.kt`:

```kotlin
override suspend fun getVelocityPointsForExercise(
    exerciseId: String,
    profileId: String,
    sinceTimestampMs: Long,
): List<WorkoutVelocityPoint> = withContext(Dispatchers.IO) {
    queries.selectVelocityPointsByExercise(exerciseId, profileId, sinceTimestampMs).executeAsList().map { row ->
        WorkoutVelocityPoint(
            loadPerCableKg = (row.workingAvgWeightKg ?: row.weightPerCableKg).toFloat(),
            mcvMmS = (row.avgMcvMmS ?: 0.0).toFloat(),
            timestampMs = row.timestamp,
            workingReps = row.workingReps.toInt(),
        )
    }
}
```

Add imports for `WorkoutVelocityPoint`, `Dispatchers`, `IO`, `withContext` if absent.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :shared:testDebugUnitTest --tests "*WorkoutRepositoryVelocityPointsTest*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/sqldelight shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/WorkoutRepository.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepository.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepositoryVelocityPointsTest.kt
git commit -m "feat(1rm): add per-exercise velocity-points query (#517)"
```

---

### Task 7: Personal MVT table + Exercise override column + repository

**Files:**
- Modify: `VitruvianDatabase.sq` (ExerciseMvt table + `Exercise.mvtOverrideMs` column + queries), `MigrationStatements.kt` (`37 ->`), `SchemaManifest.kt` (table op + Exercise heal op), `Exercise.kt` (field), the Exercise SQL mapper wherever `selectExerciseById`/`insertExercise` are mapped.
- Create: `shared/src/commonMain/sqldelight/.../migrations/37.sqm`, `data/repository/PersonalMvtRepository.kt`
- Test: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightPersonalMvtRepositoryTest.kt`

**Interfaces:**
- Produces:
  - `data class PersonalMvtEntity(val exerciseId: String, val profileId: String, val personalMvtMs: Float, val sampleCount: Int)`
  - `interface PersonalMvtRepository { suspend fun get(exerciseId: String, profileId: String): PersonalMvtEntity?; suspend fun upsert(exerciseId: String, profileId: String, personalMvtMs: Float, sampleCount: Int) }`
  - `Exercise.mvtOverrideMs: Float?` (nullable, default null).

- [ ] **Step 1: Schema additions in `VitruvianDatabase.sq`**

```sql
ALTER TABLE Exercise ADD COLUMN mvtOverrideMs REAL;  -- handled via migration; see note

CREATE TABLE ExerciseMvt (
    exerciseId TEXT NOT NULL,
    profile_id TEXT NOT NULL DEFAULT 'default',
    personalMvtMs REAL NOT NULL,
    sampleCount INTEGER NOT NULL DEFAULT 0,
    updatedAt INTEGER NOT NULL,
    PRIMARY KEY (exerciseId, profile_id),
    FOREIGN KEY (exerciseId) REFERENCES Exercise(id) ON DELETE CASCADE
);

selectExerciseMvt:
SELECT * FROM ExerciseMvt WHERE exerciseId = :exerciseId AND profile_id = :profileId;

upsertExerciseMvt:
INSERT INTO ExerciseMvt (exerciseId, profile_id, personalMvtMs, sampleCount, updatedAt)
VALUES (:exerciseId, :profileId, :personalMvtMs, :sampleCount, :updatedAt)
ON CONFLICT(exerciseId, profile_id) DO UPDATE SET
    personalMvtMs = excluded.personalMvtMs,
    sampleCount = excluded.sampleCount,
    updatedAt = excluded.updatedAt;
```

> The `mvtOverrideMs` column must be added to the actual `CREATE TABLE Exercise` definition in the `.sq` (so generated code/mapper includes it), AND added via migration 37 for existing DBs. Update the Exercise SQL mapper (`mapToExercise`/`insertExercise` call sites) to read/write the new nullable column (`mvtOverrideMs = null` on insert unless set).

- [ ] **Step 2: Migration `37.sqm`**

```sql
-- Migration 37: personalized MVT storage + per-exercise MVT override (issue #517).
ALTER TABLE Exercise ADD COLUMN mvtOverrideMs REAL;
CREATE TABLE IF NOT EXISTS ExerciseMvt (
    exerciseId TEXT NOT NULL,
    profile_id TEXT NOT NULL DEFAULT 'default',
    personalMvtMs REAL NOT NULL,
    sampleCount INTEGER NOT NULL DEFAULT 0,
    updatedAt INTEGER NOT NULL,
    PRIMARY KEY (exerciseId, profile_id),
    FOREIGN KEY (exerciseId) REFERENCES Exercise(id) ON DELETE CASCADE
);
```

- [ ] **Step 3: `MigrationStatements.kt` `37 ->` branch** — mirror Step 2 as two list entries (`ALTER TABLE ... ADD COLUMN mvtOverrideMs REAL` is recoverable on "duplicate column"; the reconciler already treats duplicate-column as recoverable). **`SchemaManifest.kt`**: add a `SchemaTableOperation` for `ExerciseMvt` and a `SchemaHealOperation(table = "Exercise", column = "mvtOverrideMs", sql = "ALTER TABLE Exercise ADD COLUMN mvtOverrideMs REAL")`.

- [ ] **Step 4: Add `mvtOverrideMs` to `Exercise.kt`**

```kotlin
val mvtOverrideMs: Float? = null, // User-set Minimum Velocity Threshold override (m/s) for velocity-1RM
```

Add to the `data class Exercise(...)` parameter list (after `displayName`). Update the Exercise repository mapper to populate it from the row and persist it on insert/update.

- [ ] **Step 5: Write the failing repository test**

```kotlin
package com.devil.phoenixproject.data.repository

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SqlDelightPersonalMvtRepositoryTest {
    @Test fun `upsert then get round-trips and updates`() = runTest {
        val db = createInMemoryTestDatabase()
        seedExercise(db, id = "ex1")
        val repo = SqlDelightPersonalMvtRepository(db)
        assertNull(repo.get("ex1", "default"))

        repo.upsert("ex1", "default", personalMvtMs = 0.18f, sampleCount = 1)
        assertEquals(1, repo.get("ex1", "default")?.sampleCount)

        repo.upsert("ex1", "default", personalMvtMs = 0.19f, sampleCount = 2)
        val updated = repo.get("ex1", "default")
        assertEquals(2, updated?.sampleCount)
        assertEquals(0.19f, updated?.personalMvtMs)
    }
}
```

- [ ] **Step 6: Implement `PersonalMvtRepository.kt`**

```kotlin
package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.VitruvianDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

data class PersonalMvtEntity(val exerciseId: String, val profileId: String, val personalMvtMs: Float, val sampleCount: Int)

interface PersonalMvtRepository {
    suspend fun get(exerciseId: String, profileId: String): PersonalMvtEntity?
    suspend fun upsert(exerciseId: String, profileId: String, personalMvtMs: Float, sampleCount: Int)
}

class SqlDelightPersonalMvtRepository(private val db: VitruvianDatabase) : PersonalMvtRepository {
    private val queries = db.vitruvianDatabaseQueries

    override suspend fun get(exerciseId: String, profileId: String): PersonalMvtEntity? = withContext(Dispatchers.IO) {
        queries.selectExerciseMvt(exerciseId, profileId).executeAsOneOrNull()?.let {
            PersonalMvtEntity(it.exerciseId, it.profile_id, it.personalMvtMs.toFloat(), it.sampleCount.toInt())
        }
    }

    override suspend fun upsert(exerciseId: String, profileId: String, personalMvtMs: Float, sampleCount: Int) =
        withContext(Dispatchers.IO) {
            queries.upsertExerciseMvt(
                exerciseId = exerciseId,
                profileId = profileId,
                personalMvtMs = personalMvtMs.toDouble(),
                sampleCount = sampleCount.toLong(),
                updatedAt = com.devil.phoenixproject.util.currentTimeMillis(),
            )
        }
}
```

- [ ] **Step 7: Run schema + repo tests**

Run: `./gradlew :shared:generateCommonMainVitruvianDatabaseInterface :shared:testDebugUnitTest --tests "*SchemaManifestTest*" --tests "*SchemaParityTest*" --tests "*PersonalMvtRepositoryTest*"`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add shared/src/commonMain/sqldelight shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Exercise.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/PersonalMvtRepository.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightExerciseRepository.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightPersonalMvtRepositoryTest.kt
git commit -m "feat(1rm): add personal MVT storage + exercise override column, migration 37 (#517)"
```

---

### Task 8: ComputeVelocityOneRepMaxUseCase

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/ComputeVelocityOneRepMaxUseCase.kt`
- Test: `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/usecase/ComputeVelocityOneRepMaxUseCaseTest.kt`

**Interfaces:**
- Consumes: `WorkoutRepository.getVelocityPointsForExercise` (Task 6), `ExerciseRepository.getExerciseById`, `PersonalMvtRepository.get` (Task 7), `MvtProvider` (Task 2), `VelocityOneRepMaxEstimator` (Task 3), `VelocityOneRepMaxRepository.insert` (Task 5).
- Produces: `class ComputeVelocityOneRepMaxUseCase(...) { suspend operator fun invoke(exerciseId: String, profileId: String, nowMs: Long): VelocityOneRepMaxResult? }`. Returns the result (or null) AND persists it when non-null. Window constant `WINDOW_DAYS = 28`.

- [ ] **Step 1: Write the failing test (with fakes)**

```kotlin
package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.assessment.AssessmentEngine
import com.devil.phoenixproject.domain.onerepmax.MvtProvider
import com.devil.phoenixproject.domain.onerepmax.VelocityOneRepMaxEstimator
import com.devil.phoenixproject.domain.onerepmax.VelocityOneRepMaxResult
import com.devil.phoenixproject.domain.onerepmax.WorkoutVelocityPoint
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ComputeVelocityOneRepMaxUseCaseTest {
    @Test fun `computes and persists an estimate from windowed points`() = runTest {
        val points = listOf(
            WorkoutVelocityPoint(40f, 1200f, timestampMs = 5L, workingReps = 5),
            WorkoutVelocityPoint(80f, 600f, timestampMs = 6L, workingReps = 5),
        )
        val inserted = mutableListOf<VelocityOneRepMaxResult>()
        val useCase = ComputeVelocityOneRepMaxUseCase(
            workoutPoints = { _, _, _ -> points },
            exerciseLookup = { _ -> FakeExercise(name = "Back Squat", muscleGroups = "Legs", mvtOverrideMs = null) },
            personalMvtLookup = { _, _ -> null },
            mvtProvider = MvtProvider(),
            estimator = VelocityOneRepMaxEstimator(AssessmentEngine()),
            persist = { result, _, _, _ -> inserted += result },
        )

        val result = useCase("ex1", "default", nowMs = 1_000_000L)
        assertNotNull(result)
        assertTrue(result.passedQualityGate)
        assertEquals(1, inserted.size)
        assertEquals(result.estimatedPerCableKg, inserted.first().estimatedPerCableKg)
    }

    @Test fun `returns null and persists nothing when fewer than two loads`() = runTest {
        val inserted = mutableListOf<VelocityOneRepMaxResult>()
        val useCase = ComputeVelocityOneRepMaxUseCase(
            workoutPoints = { _, _, _ -> listOf(WorkoutVelocityPoint(50f, 700f, 1L, 5)) },
            exerciseLookup = { _ -> FakeExercise("Curl", "Biceps", null) },
            personalMvtLookup = { _, _ -> null },
            mvtProvider = MvtProvider(),
            estimator = VelocityOneRepMaxEstimator(AssessmentEngine()),
            persist = { r, _, _, _ -> inserted += r },
        )
        assertEquals(null, useCase("ex1", "default", nowMs = 1L))
        assertEquals(0, inserted.size)
    }
}
```

> To keep this test pure, the use case takes function-typed collaborators (shown above). `FakeExercise` is a tiny local stub exposing `name`, `muscleGroups`, `mvtOverrideMs`. In production wiring (Task 10) these lambdas delegate to the real repositories.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:testDebugUnitTest --tests "*ComputeVelocityOneRepMaxUseCaseTest*"`
Expected: FAIL — use case unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.onerepmax.MvtProvider
import com.devil.phoenixproject.domain.onerepmax.VelocityOneRepMaxEstimator
import com.devil.phoenixproject.domain.onerepmax.VelocityOneRepMaxResult
import com.devil.phoenixproject.domain.onerepmax.WorkoutVelocityPoint

/** Minimal view of an exercise needed for MVT resolution. */
interface MvtExerciseView { val name: String; val muscleGroups: String; val mvtOverrideMs: Float? }

class ComputeVelocityOneRepMaxUseCase(
    private val workoutPoints: suspend (exerciseId: String, profileId: String, sinceMs: Long) -> List<WorkoutVelocityPoint>,
    private val exerciseLookup: suspend (exerciseId: String) -> MvtExerciseView?,
    private val personalMvtLookup: suspend (exerciseId: String, profileId: String) -> Pair<Float, Int>?, // (mvt, sampleCount)
    private val mvtProvider: MvtProvider,
    private val estimator: VelocityOneRepMaxEstimator,
    private val persist: suspend (result: VelocityOneRepMaxResult, exerciseId: String, computedAt: Long, profileId: String) -> Unit,
) {
    suspend operator fun invoke(exerciseId: String, profileId: String, nowMs: Long): VelocityOneRepMaxResult? {
        val exercise = exerciseLookup(exerciseId) ?: return null
        val sinceMs = nowMs - WINDOW_DAYS * DAY_MS
        val points = workoutPoints(exerciseId, profileId, sinceMs)
        val personal = personalMvtLookup(exerciseId, profileId)
        val mvt = mvtProvider.resolve(
            exerciseName = exercise.name,
            muscleGroups = exercise.muscleGroups,
            userOverrideMs = exercise.mvtOverrideMs,
            personalMvtMs = personal?.first,
            personalSampleCount = personal?.second ?: 0,
        )
        val result = estimator.estimate(points, mvt) ?: return null
        persist(result, exerciseId, nowMs, profileId)
        return result
    }

    companion object {
        const val WINDOW_DAYS = 28
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
```

> Note: the `FakeExercise` in the test must implement `MvtExerciseView`. In Task 10 wiring, `exerciseLookup` maps the real `Exercise` to an inline `MvtExerciseView`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:testDebugUnitTest --tests "*ComputeVelocityOneRepMaxUseCaseTest*"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/ComputeVelocityOneRepMaxUseCase.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/usecase/ComputeVelocityOneRepMaxUseCaseTest.kt
git commit -m "feat(1rm): add ComputeVelocityOneRepMaxUseCase (#517)"
```

---

### Task 9: RecordPersonalMvtSampleUseCase

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/RecordPersonalMvtSampleUseCase.kt`
- Test: `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/usecase/RecordPersonalMvtSampleUseCaseTest.kt`

**Heuristic (documented, tunable):** a completed session is an RIR≈0 proxy when its mean concentric velocity has collapsed to at or below `1.1 ×` the pattern default MVT for that exercise. When so, capture the session's `avgMcvMmS` (in m/s) as a sample; store the rolling mean of the last up-to-5 samples and increment `sampleCount`. This is a deliberately conservative proxy; refining failure detection (true last-rep RIR=0 from rep metrics) is a follow-on.

**Interfaces:**
- Consumes: `PersonalMvtRepository` (Task 7), `classifyMovementPattern`/`MovementPattern` (Task 1).
- Produces: `class RecordPersonalMvtSampleUseCase(private val personalMvtRepo: PersonalMvtRepository) { suspend operator fun invoke(exerciseId: String, profileId: String, exerciseName: String, muscleGroups: String, sessionMcvMmS: Float): Boolean }` — returns true when a sample was captured.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.PersonalMvtEntity
import com.devil.phoenixproject.data.repository.PersonalMvtRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakePersonalMvtRepo : PersonalMvtRepository {
    val store = mutableMapOf<String, PersonalMvtEntity>()
    override suspend fun get(exerciseId: String, profileId: String) = store["$exerciseId/$profileId"]
    override suspend fun upsert(exerciseId: String, profileId: String, personalMvtMs: Float, sampleCount: Int) {
        store["$exerciseId/$profileId"] = PersonalMvtEntity(exerciseId, profileId, personalMvtMs, sampleCount)
    }
}

class RecordPersonalMvtSampleUseCaseTest {
    @Test fun `captures sample when velocity collapsed near squat threshold`() = runTest {
        val repo = FakePersonalMvtRepo()
        val useCase = RecordPersonalMvtSampleUseCase(repo)
        // squat default 0.30 m/s; 0.31 m/s (310 mm/s) <= 1.1*0.30 -> capture
        val captured = useCase("ex1", "default", "Back Squat", "Legs", sessionMcvMmS = 310f)
        assertTrue(captured)
        assertEquals(1, repo.get("ex1", "default")?.sampleCount)
        assertEquals(0.31f, repo.get("ex1", "default")?.personalMvtMs!!, absoluteTolerance = 0.001f)
    }

    @Test fun `ignores fast non-failure set`() = runTest {
        val repo = FakePersonalMvtRepo()
        val useCase = RecordPersonalMvtSampleUseCase(repo)
        // 0.60 m/s is well above threshold -> not a failure proxy
        assertFalse(useCase("ex1", "default", "Back Squat", "Legs", sessionMcvMmS = 600f))
        assertEquals(null, repo.get("ex1", "default"))
    }

    @Test fun `rolling mean across samples`() = runTest {
        val repo = FakePersonalMvtRepo()
        val useCase = RecordPersonalMvtSampleUseCase(repo)
        useCase("ex1", "default", "Back Squat", "Legs", 300f) // 0.30
        useCase("ex1", "default", "Back Squat", "Legs", 320f) // 0.32
        val e = repo.get("ex1", "default")!!
        assertEquals(2, e.sampleCount)
        assertEquals(0.31f, e.personalMvtMs, absoluteTolerance = 0.001f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:testDebugUnitTest --tests "*RecordPersonalMvtSampleUseCaseTest*"`
Expected: FAIL — use case unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.PersonalMvtRepository
import com.devil.phoenixproject.domain.onerepmax.classifyMovementPattern

/**
 * Captures a personalized MVT sample from a completed set whose velocity has collapsed to
 * an RIR≈0 proxy (<= 1.1 × the pattern default). Maintains a rolling mean of recent samples.
 */
class RecordPersonalMvtSampleUseCase(private val personalMvtRepo: PersonalMvtRepository) {
    suspend operator fun invoke(
        exerciseId: String,
        profileId: String,
        exerciseName: String,
        muscleGroups: String,
        sessionMcvMmS: Float,
    ): Boolean {
        if (sessionMcvMmS <= 0f) return false
        val sampleMs = sessionMcvMmS / 1000f
        val patternDefault = classifyMovementPattern(exerciseName, muscleGroups).defaultMvtMs
        if (sampleMs > patternDefault * FAILURE_PROXY_FACTOR) return false

        val existing = personalMvtRepo.get(exerciseId, profileId)
        val prevCount = existing?.sampleCount ?: 0
        val prevMean = existing?.personalMvtMs ?: 0f
        // Incremental mean capped at MAX_SAMPLES weighting.
        val effectiveCount = minOf(prevCount, MAX_SAMPLES - 1)
        val newCount = prevCount + 1
        val newMean = (prevMean * effectiveCount + sampleMs) / (effectiveCount + 1)
        personalMvtRepo.upsert(exerciseId, profileId, newMean, newCount)
        return true
    }

    companion object {
        const val FAILURE_PROXY_FACTOR = 1.1f
        const val MAX_SAMPLES = 5
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:testDebugUnitTest --tests "*RecordPersonalMvtSampleUseCaseTest*"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/RecordPersonalMvtSampleUseCase.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/usecase/RecordPersonalMvtSampleUseCaseTest.kt
git commit -m "feat(1rm): add personalized MVT capture use case (#517)"
```

---

### Task 10: DI wiring + post-save hook

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DataModule.kt` (repos), `DomainModule.kt` (providers + use cases).
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/GamificationManager.kt` (constructor dep + call in `processPostSaveEvents`).
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/PresentationModule.kt` (GamificationManager construction — add new arg).
- Test: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/local/KoinGraphSmokeTest.kt` if one exists, else a focused construction test.

**Interfaces:**
- Consumes everything from Tasks 1–9.
- Produces: GamificationManager invokes `computeVelocityOneRepMaxUseCase(exerciseId, profileId, currentTimeMillis())` and `recordPersonalMvtSampleUseCase(...)` after PR processing.

- [ ] **Step 1: Register repositories in `DataModule.kt`**

```kotlin
single<VelocityOneRepMaxRepository> { SqlDelightVelocityOneRepMaxRepository(get()) }
single<PersonalMvtRepository> { SqlDelightPersonalMvtRepository(get()) }
```

- [ ] **Step 2: Register providers + use cases in `DomainModule.kt`**

```kotlin
// Velocity-based 1RM (issue #517)
single { MvtProvider() }
single { VelocityOneRepMaxEstimator(get()) } // get() = AssessmentEngine

single {
    val workoutRepo = get<com.devil.phoenixproject.data.repository.WorkoutRepository>()
    val exerciseRepo = get<com.devil.phoenixproject.data.repository.ExerciseRepository>()
    val personalRepo = get<com.devil.phoenixproject.data.repository.PersonalMvtRepository>()
    val velRepo = get<com.devil.phoenixproject.data.repository.VelocityOneRepMaxRepository>()
    com.devil.phoenixproject.domain.usecase.ComputeVelocityOneRepMaxUseCase(
        workoutPoints = { id, profile, since -> workoutRepo.getVelocityPointsForExercise(id, profile, since) },
        exerciseLookup = { id ->
            exerciseRepo.getExerciseById(id)?.let { ex ->
                object : com.devil.phoenixproject.domain.usecase.MvtExerciseView {
                    override val name = ex.name
                    override val muscleGroups = ex.muscleGroups
                    override val mvtOverrideMs = ex.mvtOverrideMs
                }
            }
        },
        personalMvtLookup = { id, profile -> personalRepo.get(id, profile)?.let { it.personalMvtMs to it.sampleCount } },
        mvtProvider = get(),
        estimator = get(),
        persist = { result, id, computedAt, profile -> velRepo.insert(result, id, computedAt, profile) },
    )
}

single { com.devil.phoenixproject.domain.usecase.RecordPersonalMvtSampleUseCase(get()) }
```

- [ ] **Step 3: Add deps to `GamificationManager` and call them**

In the constructor add:
```kotlin
private val computeVelocityOneRepMax: com.devil.phoenixproject.domain.usecase.ComputeVelocityOneRepMaxUseCase,
private val recordPersonalMvtSample: com.devil.phoenixproject.domain.usecase.RecordPersonalMvtSampleUseCase,
```

Add a parameter to `processPostSaveEvents` for the session MCV so personal-MVT capture has it:
```kotlin
sessionMcvMmS: Float? = null,
```

After PR processing completes (before `return`), when `exerciseId` is non-null:
```kotlin
val exId = exerciseId
if (exId != null) {
    try {
        sessionMcvMmS?.let { mcv ->
            val ex = exerciseRepository.getExerciseById(exId)
            if (ex != null) {
                recordPersonalMvtSample(exId, effectiveProfileId, ex.name, ex.muscleGroups, mcv)
            }
        }
        computeVelocityOneRepMax(exId, effectiveProfileId, com.devil.phoenixproject.util.currentTimeMillis())
    } catch (e: Exception) {
        Logger.w(e) { "VELOCITY_1RM: estimate computation failed for $exId" }
    }
}
```

Update the call site in `ActiveSessionEngine.kt` (both `processPostSaveEvents(...)` invocations — manual-stop ~line 3024 and the other save path) to pass `sessionMcvMmS = summary.avgMcvMmS` (or the session's `avgMcvMmS`; use the value persisted on the session). Update `PresentationModule.kt` where `GamificationManager(...)` is constructed to supply the two new `get()` args.

- [ ] **Step 4: Build the shared module**

Run: `./gradlew :shared:assembleDebug`
Expected: BUILD SUCCESSFUL (Koin graph compiles; constructor args satisfied).

- [ ] **Step 5: Run the full shared test suite**

Run: `./gradlew :shared:testDebugUnitTest`
Expected: PASS (all prior tasks' tests plus existing suite green).

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/di shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/GamificationManager.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt
git commit -m "feat(1rm): wire velocity-1RM computation into post-save hook (#517)"
```

---

## Phase 2 — Display alongside PR

### Task 11: Surface the latest passing estimate on ExerciseDetailScreen

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseDetailScreen.kt`
- Modify: the screen's data source (the composable currently loads PR/1RM data near line 77 "Calculate 1RM progression" and renders the "1RM Hero Card" ~line 125 and "ESTIMATED 1RM" ~line 275). Inject `VelocityOneRepMaxRepository` through the same path the screen already uses for repositories (follow how `PersonalRecordRepository`/assessment data reach this screen — likely a ViewModel or Koin `get()`).

**Interfaces:**
- Consumes: `VelocityOneRepMaxRepository.getLatestPassing(exerciseId, profileId)` (Task 5).
- Produces: a visible "VELOCITY 1RM" value (×2 for display via the existing display-multiplier convention used by the hero card) next to the existing assessment/PR 1RM, with the MVT used and timestamp, shown only when a passing estimate exists.

- [ ] **Step 1: Load the estimate**

Following the screen's existing data-loading pattern, fetch the latest passing estimate for the active exercise + profile and hold it in screen state:

```kotlin
// Pseudocode at the screen's data-load site (mirror the existing PR/assessment load):
val velocity1Rm by produceState<VelocityOneRepMaxEntity?>(initialValue = null, exerciseId, profileId) {
    value = velocityOneRepMaxRepository.getLatestPassing(exerciseId, profileId)
}
```

- [ ] **Step 2: Render it in the hero card**

Add a labeled value beside the existing "ESTIMATED 1RM" block. Convert per-cable → display using the same multiplier the card already applies to the assessment 1RM (do not hard-code ×2; reuse the card's existing display-weight helper). Show MVT used and the computed date. Render nothing when `velocity1Rm == null`.

```kotlin
velocity1Rm?.let { v ->
    StatColumn(
        label = "VELOCITY 1RM",
        value = formatDisplayWeight(v.estimatedPerCableKg), // same helper the assessment 1RM uses
        subtitle = "MVT ${v.mvtUsedMs} m/s",
    )
}
```

- [ ] **Step 3: Verify in the running app (manual — no Compose UI test harness in this module)**

Run: `./gradlew :androidApp:installDebug`
Then, on a device/emulator with at least two completed sets of one exercise at **different** weights (so a passing estimate exists), open that exercise's detail screen.
Expected: a "VELOCITY 1RM" value appears next to the existing 1RM, with the MVT and date. For an exercise with only single-weight sets, the value is absent (no passing estimate).

If no real data exists, seed via the existing assessment flow or log a temporary `Logger.d` of `getLatestPassing(...)` to confirm wiring, then remove it.

- [ ] **Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseDetailScreen.kt
git commit -m "feat(1rm): display velocity-estimated 1RM on exercise detail (#517)"
```

---

## Self-Review

**Spec coverage (Phases 1–2):**
- Estimate from (load, MCV) via line-of-best-fit mapped to MVT → Tasks 1–3, 8. ✓
- Rolling-window source (≥2 distinct loads, 28 days) → Tasks 6, 8. ✓
- Per-movement-pattern MVT defaults + global fallback → Task 1. ✓
- Personalized MVT (≥3 samples) + user override → Tasks 2, 7, 9. ✓
- Tiered quality gate (store always; gate display/badges on R²≥0.8 + ≥2 loads) → Task 3 (`passedQualityGate`), Task 5 (`getLatestPassing` filters), Task 11 (display gated). ✓
- Time-series persistence, `Exercise.oneRepMaxKg` untouched → Tasks 4, 5. ✓
- Display alongside PR → Task 11. ✓
- Out of scope here (correctly deferred): badges (Phase 4), `% of 1RM` scaling (Phase 3), backfill (Phase 5), portal parity (Phase 6). The post-save hook (Task 10) is the seam where Phase 4 badges will attach.

**Placeholder scan:** No "TBD"/"handle edge cases"/"similar to Task N". Test code is concrete. Two intentional pointers to existing harness helpers (`createInMemoryTestDatabase`, `seedExercise`/`seedSession`) name the exact source file to copy from — acceptable, not hand-waving.

**Type consistency:** `VelocityOneRepMaxResult`, `WorkoutVelocityPoint`, `MvtExerciseView`, `VelocityOneRepMaxEntity`, `PersonalMvtEntity`, and method names (`getVelocityPointsForExercise`, `getLatestPassing`, `insert`, `upsert`, `resolve`, `estimate`) are used identically across producing and consuming tasks. Quality-gate constants live once on `VelocityOneRepMaxEstimator`. ✓

**Known risks to flag at execution:**
- SQLDelight generated mapper parameter order may differ from the hand-written `::map`; align to the compiler error (noted in Task 5).
- `processPostSaveEvents` adds a parameter with a default; both `ActiveSessionEngine` call sites must pass `sessionMcvMmS` (noted in Task 10).
- The personal-MVT capture heuristic (Task 9) is a deliberate proxy; refining true RIR=0 detection from rep metrics is a follow-on.
