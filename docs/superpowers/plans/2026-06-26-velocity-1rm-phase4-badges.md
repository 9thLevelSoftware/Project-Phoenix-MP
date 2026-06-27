# Velocity-1RM Phase 4 — Distinct Velocity-1RM Badges — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Award distinct, tiered badges as a user's velocity-estimated 1RM improves over time — separate from the existing PR badges.

**Architecture:** A "velocity-1RM improvement" = a new passing estimate that beats that exercise's prior best passing estimate by ≥ 2.5%. Improvements are counted statelessly from the persisted `VelocityOneRepMaxEstimate` history (no new schema). Three tiered `STRENGTH` badges (1 / 5 / 15 cumulative improvements across all exercises) fire through the existing `GamificationManager` badge-emit flow, checked from the post-save hook after each new estimate is computed.

**Tech Stack:** Kotlin Multiplatform, SQLDelight, Koin, kotlin.test.

Builds on the merged foundation + Phase 3 (this branch). Reuses `VelocityOneRepMaxRepository`, `VelocityOneRepMaxEntity`, the `GamificationManager` badge pattern (`processSetQualityEvent` is the template), and `BadgeDefinitions`.

## Global Constraints

- Improvement threshold: **`IMPROVEMENT_FACTOR = 1.025f`** (new passing estimate ≥ prior best × 1.025 for that exercise).
- Improvements are **cumulative across all exercises** for a profile, counted from passing estimates only.
- Tiers: **1 / 5 / 15** improvements → BRONZE / SILVER / GOLD, `BadgeCategory.STRENGTH`.
- Badges are **distinct** from PR badges — new `BadgeRequirement.VelocityOneRepMaxImprovements(count)` subtype.
- The `BadgeRequirement` sealed class drives an **exhaustive** `when` in `Badge.getProgressDescription` — adding a subtype REQUIRES adding a branch there (compiler-enforced).
- Per-cable weights; estimates already per-cable. No schema migration in this phase (query-only repo addition).
- Module test task: `:shared:testAndroidHostTest`. Branch: `fix/issue-517-review-followups` (PR #598). Only `git add` files you change.

---

## File Structure

**Modify:**
- `domain/model/Gamification.kt` — new `BadgeRequirement.VelocityOneRepMaxImprovements` + `getProgressDescription` branch.
- `data/local/BadgeDefinitions.kt` — 3 tiered badge definitions.
- `sqldelight/.../VitruvianDatabase.sq` — `selectAllPassingVelocityOneRepMaxByProfile` query.
- `data/repository/VelocityOneRepMaxRepository.kt` — `getAllPassing(profileId)`.
- `presentation/manager/GamificationManager.kt` — `checkVelocityOneRepMaxBadges(...)`.
- `presentation/viewmodel/MainViewModel.kt` — invoke the count + badge check from `onPostSaveComputed`.
- `di/DomainModule.kt` — register the count use case.

**Create:**
- `domain/usecase/CountVelocityOneRepMaxImprovementsUseCase.kt`

---

### Task 1: `VelocityOneRepMaxImprovements` requirement + tiered badge definitions

**Files:**
- Modify: `domain/model/Gamification.kt`, `data/local/BadgeDefinitions.kt`
- Test: `shared/src/commonTest/.../data/local/VelocityOneRepMaxBadgeDefinitionsTest.kt`

**Interfaces:**
- Produces: `BadgeRequirement.VelocityOneRepMaxImprovements(val count: Int)`; 3 badges with ids `velocity_1rm_1`, `velocity_1rm_5`, `velocity_1rm_15`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.devil.phoenixproject.data.local

import com.devil.phoenixproject.domain.model.BadgeCategory
import com.devil.phoenixproject.domain.model.BadgeRequirement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VelocityOneRepMaxBadgeDefinitionsTest {
    @Test fun `three tiered velocity 1RM badges exist with STRENGTH category`() {
        val ids = listOf("velocity_1rm_1", "velocity_1rm_5", "velocity_1rm_15")
        val badges = ids.map { id -> assertNotNull(BadgeDefinitions.getBadgeById(id), "missing $id") }
        badges.forEach { assertEquals(BadgeCategory.STRENGTH, it.category) }
        assertEquals(
            listOf(1, 5, 15),
            badges.map { (it.requirement as BadgeRequirement.VelocityOneRepMaxImprovements).count },
        )
    }

    @Test fun `velocity 1RM badges are distinct from PR badges`() {
        val velocity = BadgeDefinitions.allBadges.filter { it.requirement is BadgeRequirement.VelocityOneRepMaxImprovements }
        val pr = BadgeDefinitions.allBadges.filter { it.requirement is BadgeRequirement.PRsAchieved }
        assertTrue(velocity.isNotEmpty() && pr.isNotEmpty())
        assertTrue(velocity.none { v -> pr.any { it.id == v.id } })
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :shared:testAndroidHostTest --tests "*VelocityOneRepMaxBadgeDefinitionsTest*"`
Expected: FAIL — requirement/badges absent.

- [ ] **Step 3: Implement**

In `Gamification.kt`, add to the `BadgeRequirement` sealed class (with the others):
```kotlin
/** Cumulative velocity-estimated 1RM improvements (>=2.5% over prior best) across all exercises. */
data class VelocityOneRepMaxImprovements(val count: Int) : BadgeRequirement()
```
Add the exhaustive-`when` branch in `Badge.getProgressDescription`:
```kotlin
is BadgeRequirement.VelocityOneRepMaxImprovements -> "$currentValue/${req.count} velocity 1RMs"
```

In `BadgeDefinitions.kt`, add to `allBadges` (use the existing `Badge(...)` field shape; pick an `iconResource` consistent with other STRENGTH badges — reuse an existing icon string from a nearby STRENGTH badge):
```kotlin
Badge(
    id = "velocity_1rm_1",
    name = "Velocity Breakthrough",
    description = "Improved your velocity-estimated 1RM on any lift",
    category = BadgeCategory.STRENGTH,
    iconResource = "trophy", // match an existing STRENGTH badge's iconResource
    tier = BadgeTier.BRONZE,
    requirement = BadgeRequirement.VelocityOneRepMaxImprovements(1),
),
Badge(
    id = "velocity_1rm_5",
    name = "Velocity Climber",
    description = "5 velocity-estimated 1RM improvements",
    category = BadgeCategory.STRENGTH,
    iconResource = "trophy",
    tier = BadgeTier.SILVER,
    requirement = BadgeRequirement.VelocityOneRepMaxImprovements(5),
),
Badge(
    id = "velocity_1rm_15",
    name = "Velocity Master",
    description = "15 velocity-estimated 1RM improvements",
    category = BadgeCategory.STRENGTH,
    iconResource = "trophy",
    tier = BadgeTier.GOLD,
    requirement = BadgeRequirement.VelocityOneRepMaxImprovements(15),
),
```
Read an existing STRENGTH `Badge(...)` entry first and match its exact field set/`iconResource` convention.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :shared:testAndroidHostTest --tests "*VelocityOneRepMaxBadgeDefinitionsTest*"`
Expected: PASS. (Also confirms the exhaustive `when` compiles.)

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Gamification.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/BadgeDefinitions.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/data/local/VelocityOneRepMaxBadgeDefinitionsTest.kt
git commit -m "feat(1rm): add tiered velocity-1RM improvement badges (#517)"
```

---

### Task 2: `getAllPassing(profileId)` repository query

**Files:**
- Modify: `sqldelight/.../VitruvianDatabase.sq`, `data/repository/VelocityOneRepMaxRepository.kt`
- Test: `shared/src/androidHostTest/.../repository/SqlDelightVelocityOneRepMaxRepositoryTest.kt` (extend)

**Interfaces:**
- Produces: `suspend fun VelocityOneRepMaxRepository.getAllPassing(profileId: String): List<VelocityOneRepMaxEntity>` ordered by `exerciseId, computedAt ASC`.

- [ ] **Step 1: Add the query** to `VitruvianDatabase.sq` (near the other VelocityOneRepMax queries):

```sql
selectAllPassingVelocityOneRepMaxByProfile:
SELECT * FROM VelocityOneRepMaxEstimate
WHERE profile_id = :profileId AND passedQualityGate = 1 AND deletedAt IS NULL
ORDER BY exerciseId ASC, computedAt ASC;
```

- [ ] **Step 2: Write the failing test** (extend the existing repo test)

```kotlin
@Test fun `getAllPassing returns only passing rows for the profile ordered by exercise then time`() = runTest {
    val db = createInMemoryTestDatabase()
    seedExercise(db, id = "exA"); seedExercise(db, id = "exB")
    val repo = SqlDelightVelocityOneRepMaxRepository(db)
    repo.insert(result(100f, passed = true), "exA", computedAt = 1L, profileId = "default")
    repo.insert(result(110f, passed = true), "exA", computedAt = 2L, profileId = "default")
    repo.insert(result(90f, passed = false), "exA", computedAt = 3L, profileId = "default") // excluded
    repo.insert(result(80f, passed = true), "exB", computedAt = 1L, profileId = "default")
    repo.insert(result(200f, passed = true), "exA", computedAt = 1L, profileId = "other") // other profile

    val all = repo.getAllPassing("default")
    assertEquals(3, all.size)
    assertEquals(listOf("exA", "exA", "exB"), all.map { it.exerciseId })
    assertEquals(listOf(100f, 110f, 80f), all.map { it.estimatedPerCableKg })
}
```
(`result(...)`/`seedExercise`/`createInMemoryTestDatabase` already exist in this test file from the foundation.)

- [ ] **Step 3: Run to verify failure.** `./gradlew :shared:testAndroidHostTest --tests "*SqlDelightVelocityOneRepMaxRepositoryTest*"` — FAIL (method missing).

- [ ] **Step 4: Implement** in `VelocityOneRepMaxRepository.kt`:
- Interface: `suspend fun getAllPassing(profileId: String): List<VelocityOneRepMaxEntity>`
- Impl:
```kotlin
override suspend fun getAllPassing(profileId: String): List<VelocityOneRepMaxEntity> =
    withContext(Dispatchers.IO) {
        queries.selectAllPassingVelocityOneRepMaxByProfile(profileId, ::map).executeAsList()
    }
```
(Use the same `::map` mapper already defined in this class.)

- [ ] **Step 5: Run to verify pass.** Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/sqldelight shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/VelocityOneRepMaxRepository.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightVelocityOneRepMaxRepositoryTest.kt
git commit -m "feat(1rm): add getAllPassing velocity-1RM query (#517)"
```

---

### Task 3: `CountVelocityOneRepMaxImprovementsUseCase` (pure)

**Files:**
- Create: `domain/usecase/CountVelocityOneRepMaxImprovementsUseCase.kt`
- Test: `shared/src/commonTest/.../usecase/CountVelocityOneRepMaxImprovementsUseCaseTest.kt`

**Interfaces:**
- Produces: `class CountVelocityOneRepMaxImprovementsUseCase { operator fun invoke(passingEstimates: List<VelocityOneRepMaxEntity>): Int }`. Groups by `exerciseId`, orders each by `computedAt` asc, walks maintaining a running best, counts each estimate that is ≥ runningBest × 1.025 (and updates runningBest), sums across exercises. The first estimate per exercise is the baseline (not an improvement).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.VelocityOneRepMaxEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class CountVelocityOneRepMaxImprovementsUseCaseTest {
    private val useCase = CountVelocityOneRepMaxImprovementsUseCase()
    private fun e(ex: String, kg: Float, t: Long) =
        VelocityOneRepMaxEntity(0, ex, kg, 0.3f, 0.95f, 3, true, t, "default")

    @Test fun `counts improvements above 2_5 percent per exercise`() {
        // exA: 100 (base) -> 103 (+3% improvement) -> 104 (<2.5% over 103, no) -> 110 (improvement over 104)
        val points = listOf(
            e("exA", 100f, 1), e("exA", 103f, 2), e("exA", 104f, 3), e("exA", 110f, 4),
            e("exB", 50f, 1), e("exB", 60f, 2), // exB: base -> +20% improvement
        )
        assertEquals(3, useCase(points)) // exA:2 + exB:1
    }

    @Test fun `single estimate per exercise is not an improvement`() {
        assertEquals(0, useCase(listOf(e("exA", 100f, 1), e("exB", 80f, 1))))
    }

    @Test fun `empty input is zero`() = assertEquals(0, useCase(emptyList()))
}
```

- [ ] **Step 2: Run to verify failure.** FAIL — use case unresolved.

- [ ] **Step 3: Implement**

```kotlin
package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.VelocityOneRepMaxEntity

/** Counts cumulative velocity-1RM improvements (>= prior best x 1.025) across all exercises. */
class CountVelocityOneRepMaxImprovementsUseCase {
    operator fun invoke(passingEstimates: List<VelocityOneRepMaxEntity>): Int {
        var improvements = 0
        passingEstimates.groupBy { it.exerciseId }.forEach { (_, rows) ->
            var best: Float? = null
            rows.sortedBy { it.computedAt }.forEach { row ->
                val prior = best
                if (prior != null && row.estimatedPerCableKg >= prior * IMPROVEMENT_FACTOR) {
                    improvements++
                    best = row.estimatedPerCableKg
                } else if (prior == null || row.estimatedPerCableKg > prior) {
                    best = row.estimatedPerCableKg
                }
            }
        }
        return improvements
    }

    companion object {
        const val IMPROVEMENT_FACTOR = 1.025f
    }
}
```

- [ ] **Step 4: Run to verify pass.** Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/CountVelocityOneRepMaxImprovementsUseCase.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/usecase/CountVelocityOneRepMaxImprovementsUseCaseTest.kt
git commit -m "feat(1rm): count velocity-1RM improvements (#517)"
```

---

### Task 4: Award the badges — `GamificationManager` + post-save wiring

**Files:**
- Modify: `presentation/manager/GamificationManager.kt`, `presentation/viewmodel/MainViewModel.kt`, `di/DomainModule.kt`
- Test: `shared/src/androidHostTest/.../manager/GamificationManagerTest.kt` (extend)

**Interfaces:**
- Produces: `suspend fun GamificationManager.checkVelocityOneRepMaxBadges(improvementCount: Int, profileId: String): List<Badge>` — awards unearned `VelocityOneRepMaxImprovements` badges whose `count <= improvementCount`, emits `HapticEvent.BADGE_EARNED` + `_badgeEarnedEvents`, returns newly earned. No new constructor params (uses the existing `gamificationRepository` + emit flows).

- [ ] **Step 1: Write the failing test** (extend `GamificationManagerTest`, mirroring the `processSetQualityEvent` tests)

```kotlin
@Test fun `checkVelocityOneRepMaxBadges awards bronze at one improvement`() = runTest {
    // construct GamificationManager via the existing test harness in this file
    val earned = manager.checkVelocityOneRepMaxBadges(improvementCount = 1, profileId = "default")
    assertTrue(earned.any { it.id == "velocity_1rm_1" })
    assertFalse(earned.any { it.id == "velocity_1rm_5" })
}

@Test fun `checkVelocityOneRepMaxBadges does not re-award already earned`() = runTest {
    manager.checkVelocityOneRepMaxBadges(1, "default")
    val again = manager.checkVelocityOneRepMaxBadges(1, "default")
    assertTrue(again.isEmpty())
}
```
Use the same `GamificationManager(...)` construction the existing tests use (the gamification repo there must support `awardBadge`/`isBadgeEarned`).

- [ ] **Step 2: Run to verify failure.** FAIL — method missing.

- [ ] **Step 3: Implement** `checkVelocityOneRepMaxBadges` in `GamificationManager` (mirror `processSetQualityEvent`, lines ~292–316):

```kotlin
suspend fun checkVelocityOneRepMaxBadges(improvementCount: Int, profileId: String = "default"): List<Badge> {
    if (!gamificationEnabled.value) return emptyList()
    val candidates = BadgeDefinitions.allBadges.filter {
        it.requirement is BadgeRequirement.VelocityOneRepMaxImprovements
    }
    val newlyEarned = mutableListOf<Badge>()
    for (badge in candidates) {
        val req = badge.requirement as BadgeRequirement.VelocityOneRepMaxImprovements
        if (improvementCount >= req.count && !gamificationRepository.isBadgeEarned(badge.id, profileId)) {
            if (gamificationRepository.awardBadge(badge.id, profileId)) newlyEarned.add(badge)
        }
    }
    if (newlyEarned.isNotEmpty()) {
        hapticEvents.emit(HapticEvent.BADGE_EARNED)
        _badgeEarnedEvents.emit(newlyEarned)
    }
    return newlyEarned
}
```

- [ ] **Step 4: Wire into the post-save hook**

In `MainViewModel`, register the count use case (constructor param + DI) and extend the `onPostSaveComputed` lambda so AFTER `computeVelocityOneRepMaxUseCase(...)` completes (the new estimate is now persisted), it counts improvements and checks badges:
```kotlin
computeVelocityOneRepMaxUseCase(exId, profile, currentTimeMillis())
val improvements = countVelocityOneRepMaxImprovementsUseCase(
    velocityOneRepMaxRepository.getAllPassing(profile),
)
gamificationManager.checkVelocityOneRepMaxBadges(improvements, profile)
```
`DomainModule.kt`: `single { CountVelocityOneRepMaxImprovementsUseCase() }`; add it to `MainViewModel`'s constructor + every MainViewModel registration site (PlatformModule.android/ios, MainViewModelTest, WorkoutFlowE2ETest — grep `MainViewModel(`). (MainViewModel already holds `velocityOneRepMaxRepository` and `gamificationManager`.)

- [ ] **Step 5: Run tests + build**

Run: `./gradlew :shared:testAndroidHostTest` (full) and `./gradlew :androidApp:assembleDebug`.
Expected: GREEN / BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/GamificationManager.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DomainModule.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/manager/GamificationManagerTest.kt
# + any MainViewModel registration sites touched
git commit -m "feat(1rm): award velocity-1RM badges from post-save hook (#517)"
```

---

## Self-Review

**Spec coverage (design §5):** distinct badge(s) — Task 1 (new `BadgeRequirement` subtype, separate from `PRsAchieved`) ✓; fired when a new passing estimate beats prior best by ≥2.5% — Task 3 (`IMPROVEMENT_FACTOR = 1.025`) ✓; via existing gamification flow — Task 4 (mirrors `processSetQualityEvent`) ✓; tiered improvement count (user decision) — Tasks 1+3 ✓.

**Placeholder scan:** Task 1 says "match an existing STRENGTH badge's `iconResource`" rather than guessing an icon string — read-then-match, not a placeholder. No "TBD".

**Type consistency:** `VelocityOneRepMaxImprovements(count)`, `getAllPassing(profileId)`, `CountVelocityOneRepMaxImprovementsUseCase` (takes `List<VelocityOneRepMaxEntity>` → Int), `checkVelocityOneRepMaxBadges(improvementCount, profileId)`, `IMPROVEMENT_FACTOR = 1.025f` consistent across tasks.

**No schema migration** in this phase (query-only). Badges surface automatically via the existing Badges screen (driven by `BadgeDefinitions.allBadges`) — no UI task needed.

**Deferred:** backfill (Phase 5), portal parity (Phase 6, separate repo).
