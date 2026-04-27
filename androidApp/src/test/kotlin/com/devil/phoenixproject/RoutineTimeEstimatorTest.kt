package com.devil.phoenixproject

import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.Superset
import com.devil.phoenixproject.domain.model.WarmupSet
import com.devil.phoenixproject.domain.usecase.RoutineTimeEstimator
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for RoutineTimeEstimator.
 * Issue #225: Historical time estimate enhancement.
 */
class RoutineTimeEstimatorTest {

    private lateinit var workoutRepository: WorkoutRepository
    private lateinit var estimator: RoutineTimeEstimator
    private val profileId = "test-profile"

    @Before
    fun setup() {
        workoutRepository = mockk(relaxed = true)
        estimator = RoutineTimeEstimator(workoutRepository)

        // Default: no historical data (under threshold)
        coEvery { workoutRepository.getSessionCountForExercise(any(), any()) } returns 0
        coEvery { workoutRepository.getAverageSetDurationMs(any(), any()) } returns null
    }

    // === Helper builders ===

    private fun cableExercise(
        id: String = "ex-1",
        name: String = "Bench Press",
    ) = Exercise(
        name = name,
        muscleGroup = "Chest",
        equipment = "BAR",
        id = id,
    )

    private fun bodyweightExercise(
        id: String = "bw-1",
        name: String = "Push Ups",
    ) = Exercise(
        name = name,
        muscleGroup = "Chest",
        equipment = "BENCH",
        id = id,
    )

    private fun routineExercise(
        exercise: Exercise = cableExercise(),
        setReps: List<Int?> = listOf(10, 10, 10),
        restSeconds: List<Int> = listOf(60, 60),
        warmupSets: List<WarmupSet> = emptyList(),
        orderIndex: Int = 0,
        supersetId: String? = null,
        orderInSuperset: Int = 0,
    ) = RoutineExercise(
        id = "re-${exercise.id}-$orderIndex",
        exercise = exercise,
        orderIndex = orderIndex,
        setReps = setReps,
        weightPerCableKg = 50f,
        setRestSeconds = restSeconds,
        warmupSets = warmupSets,
        supersetId = supersetId,
        orderInSuperset = orderInSuperset,
    )

    private fun routine(
        exercises: List<RoutineExercise>,
        supersets: List<Superset> = emptyList(),
    ) = Routine(
        id = "routine-1",
        name = "Test Routine",
        exercises = exercises,
        supersets = supersets,
        profileId = profileId,
    )

    // ==================== BASIC TESTS ====================

    @Test
    fun `empty routine returns zero estimate`() = runTest {
        val result = estimator.estimateRoutineDuration(routine(emptyList()), profileId)

        assertEquals(0, result.totalSeconds)
        assertEquals(0, result.lowerBoundSeconds)
        assertEquals(0, result.upperBoundSeconds)
        assertFalse(result.isHistoryBased)
        assertEquals(0, result.totalExerciseCount)
    }

    @Test
    fun `single cable exercise uses fallback when no history`() = runTest {
        val exercises = listOf(
            routineExercise(
                setReps = listOf(10, 10, 10),
                restSeconds = listOf(60, 60),
            ),
        )
        val result = estimator.estimateRoutineDuration(routine(exercises), profileId)

        // 3 sets * 45s fallback = 135s work + 2 * 60s rest = 255s
        // No transition time (single exercise)
        assertEquals(255, result.totalSeconds)
        assertFalse(result.isHistoryBased)
        assertTrue(result.isEntirelyFallback)
        assertFalse(result.hasRange)
    }

    @Test
    fun `bodyweight exercise uses 30s fallback`() = runTest {
        val exercises = listOf(
            routineExercise(
                exercise = bodyweightExercise(),
                setReps = listOf(10, 10),
                restSeconds = listOf(60),
            ),
        )
        val result = estimator.estimateRoutineDuration(routine(exercises), profileId)

        // 2 sets * 30s = 60s work + 1 * 60s rest = 120s
        assertEquals(120, result.totalSeconds)
    }

    // ==================== HISTORICAL DATA ====================

    @Test
    fun `uses historical data when session count meets threshold`() = runTest {
        val exerciseId = "ex-1"
        coEvery { workoutRepository.getSessionCountForExercise(exerciseId, profileId) } returns 5
        coEvery { workoutRepository.getAverageSetDurationMs(exerciseId, profileId) } returns 60_000L // 60s avg

        val exercises = listOf(
            routineExercise(
                exercise = cableExercise(id = exerciseId),
                setReps = listOf(10, 10, 10),
                restSeconds = listOf(90, 90),
            ),
        )
        val result = estimator.estimateRoutineDuration(routine(exercises), profileId)

        // 3 sets * 60s historical = 180s work + 2 * 90s rest = 360s
        assertEquals(360, result.totalSeconds)
        assertTrue(result.isHistoryBased)
        assertEquals(1, result.historicalExerciseCount)
    }

    @Test
    fun `falls back when session count below threshold`() = runTest {
        val exerciseId = "ex-1"
        coEvery { workoutRepository.getSessionCountForExercise(exerciseId, profileId) } returns 2 // Below 3
        coEvery { workoutRepository.getAverageSetDurationMs(exerciseId, profileId) } returns 60_000L

        val exercises = listOf(
            routineExercise(
                exercise = cableExercise(id = exerciseId),
                setReps = listOf(10, 10),
                restSeconds = listOf(60),
            ),
        )
        val result = estimator.estimateRoutineDuration(routine(exercises), profileId)

        // Should use 45s fallback, not 60s historical
        // 2 sets * 45s = 90s + 60s rest = 150s
        assertEquals(150, result.totalSeconds)
        assertFalse(result.isHistoryBased)
    }

    // ==================== AMRAP HANDLING ====================

    @Test
    fun `AMRAP set uses 90s fallback without history`() = runTest {
        val exercises = listOf(
            routineExercise(
                setReps = listOf(10, 10, null), // Last set is AMRAP (null reps)
                restSeconds = listOf(60, 60),
            ),
        )
        val result = estimator.estimateRoutineDuration(routine(exercises), profileId)

        // 2 fixed sets * 45s = 90s
        // 1 AMRAP set * 90s midpoint = 90s
        // 2 * 60s rest = 120s
        // Total midpoint = 300s
        assertTrue(result.hasRange)
        assertEquals(300, result.totalSeconds)
        assertTrue(result.lowerBoundSeconds < result.totalSeconds)
        assertTrue(result.upperBoundSeconds > result.totalSeconds)
    }

    @Test
    fun `AMRAP set with history uses multiplier range`() = runTest {
        val exerciseId = "ex-1"
        coEvery { workoutRepository.getSessionCountForExercise(exerciseId, profileId) } returns 5
        coEvery { workoutRepository.getAverageSetDurationMs(exerciseId, profileId) } returns 40_000L // 40s avg

        val exercises = listOf(
            routineExercise(
                exercise = cableExercise(id = exerciseId),
                setReps = listOf(null), // Single AMRAP set
                restSeconds = emptyList(),
            ),
        )
        val result = estimator.estimateRoutineDuration(routine(exercises), profileId)

        // AMRAP with 40s historical: lower=40s, mid=60s (1.5x), upper=80s (2x)
        assertTrue(result.hasRange)
        assertEquals(60, result.totalSeconds) // 1.5x * 40s = 60s
        assertEquals(40, result.lowerBoundSeconds) // 1.0x * 40s = 40s
        assertEquals(80, result.upperBoundSeconds) // 2.0x * 40s = 80s
    }

    // ==================== WARMUP SETS ====================

    @Test
    fun `warmup sets counted at 0_7x duration`() = runTest {
        val exercises = listOf(
            routineExercise(
                setReps = listOf(10),
                restSeconds = emptyList(),
                warmupSets = listOf(
                    WarmupSet(reps = 10, percentOfWorking = 50),
                    WarmupSet(reps = 8, percentOfWorking = 70),
                ),
            ),
        )
        val result = estimator.estimateRoutineDuration(routine(exercises), profileId)

        // Working: 1 set * 45s = 45s
        // Warmup: 2 sets * (45s * 0.7) = 63s
        // No rest between warmup sets
        // Total = 45 + 63 = 108s
        // Note: integer truncation from ms conversion
        val expectedWarmupMs = (2 * 45_000L * 0.7).toLong()
        val expectedTotalMs = 45_000L + expectedWarmupMs
        assertEquals((expectedTotalMs / 1000).toInt(), result.totalSeconds)
    }

    // ==================== TRANSITION TIME ====================

    @Test
    fun `30s transition between exercises`() = runTest {
        val exercises = listOf(
            routineExercise(
                exercise = cableExercise(id = "ex-1"),
                setReps = listOf(10),
                restSeconds = emptyList(),
                orderIndex = 0,
            ),
            routineExercise(
                exercise = cableExercise(id = "ex-2", name = "Squat"),
                setReps = listOf(10),
                restSeconds = emptyList(),
                orderIndex = 1,
            ),
        )
        val result = estimator.estimateRoutineDuration(routine(exercises), profileId)

        // 2 exercises * 45s = 90s work + 30s transition = 120s
        assertEquals(120, result.totalSeconds)
    }

    @Test
    fun `no transition after last exercise`() = runTest {
        val exercises = listOf(
            routineExercise(
                setReps = listOf(10),
                restSeconds = emptyList(),
            ),
        )
        val result = estimator.estimateRoutineDuration(routine(exercises), profileId)

        // 1 exercise * 45s = 45s, no transition time
        assertEquals(45, result.totalSeconds)
    }

    // ==================== SUPERSET HANDLING ====================

    @Test
    fun `superset exercises share rest between rounds`() = runTest {
        val supersetId = "ss-1"
        val exercises = listOf(
            routineExercise(
                exercise = cableExercise(id = "ex-1", name = "Bench Press"),
                setReps = listOf(10, 10),
                restSeconds = listOf(60),
                orderIndex = 0,
                supersetId = supersetId,
                orderInSuperset = 0,
            ),
            routineExercise(
                exercise = cableExercise(id = "ex-2", name = "Row"),
                setReps = listOf(10, 10),
                restSeconds = listOf(60),
                orderIndex = 1,
                supersetId = supersetId,
                orderInSuperset = 1,
            ),
        )
        val superset = Superset(
            id = supersetId,
            routineId = "routine-1",
            name = "Chest & Back",
            restBetweenSeconds = 10,
            orderIndex = 0,
        )
        val result = estimator.estimateRoutineDuration(
            routine(exercises, supersets = listOf(superset)),
            profileId,
        )

        // Work: 2 exercises * 2 sets * 45s = 180s
        // Intra-superset rest: 10s between exercises per round * 1 transition * 2 rounds = 20s
        // Between-round rest: 1 round boundary * 60s (primary exercise rest) = 60s
        // No transitions (single top-level item)
        // Total = 180 + 20 + 60 = 260s
        assertEquals(260, result.totalSeconds)
        assertEquals(2, result.totalExerciseCount)
    }

    // ==================== DATA CLASS TESTS ====================

    @Test
    fun `formattedDuration formats minutes correctly`() {
        val estimate = com.devil.phoenixproject.domain.usecase.RoutineTimeEstimate(
            totalSeconds = 42 * 60,
            lowerBoundSeconds = 42 * 60,
            upperBoundSeconds = 42 * 60,
            isHistoryBased = true,
            historicalExerciseCount = 3,
            totalExerciseCount = 5,
        )
        assertEquals("42m", estimate.formattedDuration)
    }

    @Test
    fun `formattedDuration formats hours correctly`() {
        val estimate = com.devil.phoenixproject.domain.usecase.RoutineTimeEstimate(
            totalSeconds = 90 * 60,
            lowerBoundSeconds = 90 * 60,
            upperBoundSeconds = 90 * 60,
            isHistoryBased = true,
            historicalExerciseCount = 3,
            totalExerciseCount = 5,
        )
        assertEquals("1h 30m", estimate.formattedDuration)
    }

    @Test
    fun `formattedRange shows range for AMRAP`() {
        val estimate = com.devil.phoenixproject.domain.usecase.RoutineTimeEstimate(
            totalSeconds = 42 * 60,
            lowerBoundSeconds = 35 * 60,
            upperBoundSeconds = 50 * 60,
            isHistoryBased = true,
            historicalExerciseCount = 3,
            totalExerciseCount = 5,
        )
        assertTrue(estimate.hasRange)
        assertEquals("35-50m", estimate.formattedRange)
    }

    @Test
    fun `formattedRange equals formattedDuration for fixed-rep`() {
        val estimate = com.devil.phoenixproject.domain.usecase.RoutineTimeEstimate(
            totalSeconds = 42 * 60,
            lowerBoundSeconds = 42 * 60,
            upperBoundSeconds = 42 * 60,
            isHistoryBased = true,
            historicalExerciseCount = 3,
            totalExerciseCount = 5,
        )
        assertFalse(estimate.hasRange)
        assertEquals(estimate.formattedDuration, estimate.formattedRange)
    }

    // ==================== PROFILEID BUG FIX ====================

    @Test
    fun `profileId is passed through to repository - not hardcoded`() = runTest {
        val customProfileId = "custom-profile-42"
        val exerciseId = "ex-1"

        coEvery { workoutRepository.getSessionCountForExercise(exerciseId, customProfileId) } returns 5
        coEvery { workoutRepository.getAverageSetDurationMs(exerciseId, customProfileId) } returns 50_000L

        val exercises = listOf(
            routineExercise(
                exercise = cableExercise(id = exerciseId),
                setReps = listOf(10),
                restSeconds = emptyList(),
            ),
        )
        val result = estimator.estimateRoutineDuration(routine(exercises), customProfileId)

        // Should use historical 50s, not fallback 45s
        assertEquals(50, result.totalSeconds)
        assertTrue(result.isHistoryBased)
    }

    // ==================== MIN_HISTORICAL_SESSIONS CONSTANT ====================

    @Test
    fun `MIN_HISTORICAL_SESSIONS is 3`() {
        assertEquals(3, RoutineTimeEstimator.MIN_HISTORICAL_SESSIONS)
    }

    // ==================== PHASE 40 INTEGRATION TESTS ====================

    @Test
    fun `3 exercises x 3 sets all historical produces consistent estimate`() = runTest {
        // Set up historical data for 3 exercises
        for (id in listOf("ex-1", "ex-2", "ex-3")) {
            coEvery { workoutRepository.getSessionCountForExercise(id, profileId) } returns 5
            coEvery { workoutRepository.getAverageSetDurationMs(id, profileId) } returns 40_000L // 40s avg
        }

        val exercises = listOf(
            routineExercise(
                exercise = cableExercise(id = "ex-1", name = "Bench Press"),
                setReps = listOf(10, 10, 10),
                restSeconds = listOf(90, 90),
                orderIndex = 0,
            ),
            routineExercise(
                exercise = cableExercise(id = "ex-2", name = "Row"),
                setReps = listOf(10, 10, 10),
                restSeconds = listOf(90, 90),
                orderIndex = 1,
            ),
            routineExercise(
                exercise = cableExercise(id = "ex-3", name = "Shoulder Press"),
                setReps = listOf(10, 10, 10),
                restSeconds = listOf(90, 90),
                orderIndex = 2,
            ),
        )
        val result = estimator.estimateRoutineDuration(routine(exercises), profileId)

        // Each exercise: 3 sets * 40s = 120s work + 2 * 90s rest = 300s
        // 3 exercises = 900s work/rest
        // 2 transitions * 30s = 60s
        // Total = 960s
        assertEquals(960, result.totalSeconds)
        assertTrue(result.isHistoryBased)
        assertEquals(3, result.historicalExerciseCount)
        assertEquals(3, result.totalExerciseCount)
        assertFalse(result.hasRange) // No AMRAP
    }

    @Test
    fun `2 warmup + 3 working sets with warmup at 0_7x and no warmup rest`() = runTest {
        val exercises = listOf(
            routineExercise(
                setReps = listOf(10, 10, 10), // 3 working sets
                restSeconds = listOf(60, 60), // Rest between working sets only
                warmupSets = listOf(
                    WarmupSet(reps = 10, percentOfWorking = 50),
                    WarmupSet(reps = 8, percentOfWorking = 70),
                ),
            ),
        )
        val result = estimator.estimateRoutineDuration(routine(exercises), profileId)

        // Working: 3 sets * 45s = 135s
        // Working rest: 2 * 60s = 120s
        // Warmup: 2 sets * (45s * 0.7) = 63s (truncated from ms)
        // No rest between warmup sets
        // Total = 135 + 120 + 63 = 318s
        val expectedWarmupMs = (2 * 45_000L * 0.7).toLong()
        val expectedWorkMs = 3 * 45_000L
        val expectedRestMs = 2 * 60_000L
        val expectedTotalMs = expectedWorkMs + expectedRestMs + expectedWarmupMs
        assertEquals((expectedTotalMs / 1000).toInt(), result.totalSeconds)
    }

    @Test
    fun `custom exercise without ID uses fallback`() = runTest {
        // Exercise with null ID — cannot look up historical data
        val customExercise = Exercise(
            name = "Custom Lift",
            muscleGroup = "General",
            equipment = "BAR",
            id = null, // No ID
        )
        val exercises = listOf(
            routineExercise(
                exercise = customExercise,
                setReps = listOf(10, 10),
                restSeconds = listOf(60),
            ),
        )
        val result = estimator.estimateRoutineDuration(routine(exercises), profileId)

        // 2 sets * 45s fallback = 90s + 60s rest = 150s
        assertEquals(150, result.totalSeconds)
        assertFalse(result.isHistoryBased)
        assertTrue(result.isEntirelyFallback)
    }

    @Test
    fun `long routine with 6 exercises has correct transition count`() = runTest {
        val exercises = (0 until 6).map { idx ->
            routineExercise(
                exercise = cableExercise(id = "ex-$idx", name = "Exercise $idx"),
                setReps = listOf(10),
                restSeconds = emptyList(),
                orderIndex = idx,
            )
        }
        val result = estimator.estimateRoutineDuration(routine(exercises), profileId)

        // 6 exercises * 45s = 270s work
        // 5 transitions * 30s = 150s
        // Total = 420s
        assertEquals(420, result.totalSeconds)
        assertEquals(6, result.totalExerciseCount)
    }

    @Test
    fun `bodyweight exercise in mixed routine uses 30s fallback`() = runTest {
        val exercises = listOf(
            routineExercise(
                exercise = cableExercise(id = "ex-1"),
                setReps = listOf(10),
                restSeconds = emptyList(),
                orderIndex = 0,
            ),
            routineExercise(
                exercise = bodyweightExercise(id = "bw-1"),
                setReps = listOf(10),
                restSeconds = emptyList(),
                orderIndex = 1,
            ),
        )
        val result = estimator.estimateRoutineDuration(routine(exercises), profileId)

        // Cable: 1 set * 45s = 45s
        // Bodyweight: 1 set * 30s = 30s
        // 1 transition = 30s
        // Total = 105s
        assertEquals(105, result.totalSeconds)
    }

    @Test
    fun `AMRAP without history provides range around 90s fallback`() = runTest {
        val exercises = listOf(
            routineExercise(
                setReps = listOf(null), // AMRAP only
                restSeconds = emptyList(),
            ),
        )
        val result = estimator.estimateRoutineDuration(routine(exercises), profileId)

        // AMRAP fallback = 90s midpoint
        assertTrue(result.hasRange)
        assertEquals(90, result.totalSeconds) // Midpoint = 90s

        // Lower = 90s / 1.5 = 60s
        // Upper = 90s * 2 / 1.5 = 120s
        assertTrue(result.lowerBoundSeconds < result.totalSeconds)
        assertTrue(result.upperBoundSeconds > result.totalSeconds)
    }

    // ==================== ORIGINAL THRESHOLD TEST ====================

    @Test
    fun `exactly 3 sessions meets threshold`() = runTest {
        val exerciseId = "ex-1"
        coEvery { workoutRepository.getSessionCountForExercise(exerciseId, profileId) } returns 3
        coEvery { workoutRepository.getAverageSetDurationMs(exerciseId, profileId) } returns 50_000L

        val exercises = listOf(
            routineExercise(
                exercise = cableExercise(id = exerciseId),
                setReps = listOf(10),
                restSeconds = emptyList(),
            ),
        )
        val result = estimator.estimateRoutineDuration(routine(exercises), profileId)

        assertTrue(result.isHistoryBased)
        assertEquals(50, result.totalSeconds)
    }
}
