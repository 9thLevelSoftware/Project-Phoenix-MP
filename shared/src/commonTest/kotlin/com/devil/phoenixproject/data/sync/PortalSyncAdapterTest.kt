package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepMetricData
import com.devil.phoenixproject.domain.model.RepCountTiming
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.Superset
import com.devil.phoenixproject.domain.model.SupersetColors
import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortalSyncAdapterTest {

    // ========== Session Grouping ==========

    @Test
    fun `standalone sessions each become separate portal workouts`() {
        val sessions = listOf(
            makeSessionWithReps(sessionId = "s1", routineSessionId = null, exerciseName = "Bench Press"),
            makeSessionWithReps(sessionId = "s2", routineSessionId = null, exerciseName = "Squat")
        )

        val result = PortalSyncAdapter.toPortalWorkoutSessions(sessions, "user-1")

        assertEquals(2, result.size)
        // Each standalone session uses its own session id as portal session id
        assertEquals("s1", result[0].id)
        assertEquals("s2", result[1].id)
    }

    @Test
    fun `sessions with same routineSessionId are grouped into one portal workout`() {
        val sessions = listOf(
            makeSessionWithReps(sessionId = "s1", routineSessionId = "routine-run-1", exerciseName = "Bench Press"),
            makeSessionWithReps(sessionId = "s2", routineSessionId = "routine-run-1", exerciseName = "Incline Press"),
            makeSessionWithReps(sessionId = "s3", routineSessionId = "routine-run-1", exerciseName = "Cable Fly")
        )

        val result = PortalSyncAdapter.toPortalWorkoutSessions(sessions, "user-1")

        assertEquals(1, result.size)
        assertEquals("routine-run-1", result[0].id)
        assertEquals("routine-run-1", result[0].routineSessionId)
        assertEquals(3, result[0].exercises.size)
    }

    @Test
    fun `mixed standalone and routine sessions are separated correctly`() {
        val sessions = listOf(
            makeSessionWithReps(sessionId = "standalone-1", routineSessionId = null),
            makeSessionWithReps(sessionId = "routine-ex-1", routineSessionId = "run-1"),
            makeSessionWithReps(sessionId = "routine-ex-2", routineSessionId = "run-1"),
            makeSessionWithReps(sessionId = "standalone-2", routineSessionId = null)
        )

        val result = PortalSyncAdapter.toPortalWorkoutSessions(sessions, "user-1")

        assertEquals(3, result.size)
        val standalones = result.filter { it.routineSessionId == null }
        val routineGroups = result.filter { it.routineSessionId != null }
        assertEquals(2, standalones.size)
        assertEquals(1, routineGroups.size)
        assertEquals(2, routineGroups[0].exercises.size)
    }

    // ========== Aggregation ==========

    @Test
    fun `portal session aggregates duration from ms to seconds`() {
        val sessions = listOf(
            makeSessionWithReps(durationMs = 60000), // 60s
            makeSessionWithReps(durationMs = 30000)  // 30s
        )
        // Put both in same routine group
        val groupedSessions = sessions.map {
            it.copy(session = it.session.copy(routineSessionId = "group-1"))
        }

        val result = PortalSyncAdapter.toPortalWorkoutSessions(groupedSessions, "user-1")

        assertEquals(1, result.size)
        assertEquals(90, result[0].durationSeconds) // 60 + 30
    }

    @Test
    fun `portal session aggregates total volume`() {
        val s1 = makeSessionWithReps(
            totalVolumeKg = 500f,
            weightPerCableKg = 25f,
            totalReps = 10
        )
        val s2 = makeSessionWithReps(
            totalVolumeKg = 300f,
            weightPerCableKg = 15f,
            totalReps = 10
        )
        val grouped = listOf(s1, s2).map {
            it.copy(session = it.session.copy(routineSessionId = "group-1"))
        }

        val result = PortalSyncAdapter.toPortalWorkoutSessions(grouped, "user-1")

        assertFloatEquals(800f, result[0].totalVolume)
    }

    @Test
    fun `portal session volume falls back to weight x reps when totalVolumeKg is null`() {
        val sessions = listOf(
            makeSessionWithReps(
                totalVolumeKg = null,
                weightPerCableKg = 20f,
                totalReps = 10
            )
        )

        val result = PortalSyncAdapter.toPortalWorkoutSessions(sessions, "user-1")

        // Fallback: weightPerCableKg * totalReps = 20 * 10 = 200
        assertFloatEquals(200f, result[0].totalVolume)
    }

    @Test
    fun `portal session setCount equals number of mobile sessions in group`() {
        val grouped = listOf(
            makeSessionWithReps(routineSessionId = "g1"),
            makeSessionWithReps(routineSessionId = "g1"),
            makeSessionWithReps(routineSessionId = "g1")
        )

        val result = PortalSyncAdapter.toPortalWorkoutSessions(grouped, "user-1")

        assertEquals(3, result[0].setCount)
    }

    @Test
    fun `portal session exerciseCount equals number of exercises`() {
        val grouped = listOf(
            makeSessionWithReps(routineSessionId = "g1"),
            makeSessionWithReps(routineSessionId = "g1")
        )

        val result = PortalSyncAdapter.toPortalWorkoutSessions(grouped, "user-1")

        assertEquals(2, result[0].exerciseCount)
    }

    // ========== Exercise Mapping ==========

    @Test
    fun `exercises get sequential orderIndex starting from 0`() {
        val grouped = listOf(
            makeSessionWithReps(routineSessionId = "g1", exerciseName = "Ex A", timestamp = 1000L),
            makeSessionWithReps(routineSessionId = "g1", exerciseName = "Ex B", timestamp = 2000L),
            makeSessionWithReps(routineSessionId = "g1", exerciseName = "Ex C", timestamp = 3000L)
        )

        val result = PortalSyncAdapter.toPortalWorkoutSessions(grouped, "user-1")

        assertEquals(0, result[0].exercises[0].orderIndex)
        assertEquals(1, result[0].exercises[1].orderIndex)
        assertEquals(2, result[0].exercises[2].orderIndex)
    }

    @Test
    fun `exercise uses exerciseName from session or falls back to Unknown Exercise`() {
        val withName = makeSessionWithReps(exerciseName = "Bench Press")
        val withoutName = makeSessionWithReps(exerciseName = null)

        val resultNamed = PortalSyncAdapter.toPortalWorkoutSessions(listOf(withName), "user-1")
        val resultUnnamed = PortalSyncAdapter.toPortalWorkoutSessions(listOf(withoutName), "user-1")

        assertEquals("Bench Press", resultNamed[0].exercises[0].name)
        assertEquals("Unknown Exercise", resultUnnamed[0].exercises[0].name)
    }

    @Test
    fun `exercise maps muscleGroup from SessionWithReps`() {
        val swr = makeSessionWithReps(muscleGroup = "Chest")

        val result = PortalSyncAdapter.toPortalWorkoutSessions(listOf(swr), "user-1")

        assertEquals("Chest", result[0].exercises[0].muscleGroup)
    }

    @Test
    fun `exercise contains one set with correct weight and reps`() {
        val swr = makeSessionWithReps(
            weightPerCableKg = 30f,
            reps = 10,
            totalReps = 8
        )

        val result = PortalSyncAdapter.toPortalWorkoutSessions(listOf(swr), "user-1")

        val set = result[0].exercises[0].sets[0]
        assertEquals(1, set.setNumber)
        assertEquals(10, set.targetReps)
        assertEquals(8, set.actualReps)
        assertFloatEquals(30f, set.weightKg)
    }

    @Test
    fun `set maps workoutMode through PortalMappings`() {
        val swr = makeSessionWithReps(mode = "Old School")

        val result = PortalSyncAdapter.toPortalWorkoutSessions(listOf(swr), "user-1")

        assertEquals("OLD_SCHOOL", result[0].exercises[0].sets[0].workoutMode)
    }

    // ========== Rep Summary Mapping ==========

    @Test
    fun `rep summary converts velocity from mm-s to m-s`() {
        val repMetric = makeRepMetricData(
            avgVelocityConcentric = 500f, // 500 mm/s
            peakVelocity = 1000f // 1000 mm/s
        )
        val swr = makeSessionWithReps(repMetrics = listOf(repMetric))

        val result = PortalSyncAdapter.toPortalWorkoutSessions(listOf(swr), "user-1")

        val repSummary = result[0].exercises[0].sets[0].repSummaries[0]
        assertFloatEquals(0.5f, repSummary.meanVelocityMps!!)
        assertFloatEquals(1.0f, repSummary.peakVelocityMps!!)
    }

    @Test
    fun `rep summary converts force from kg to Newtons`() {
        val repMetric = makeRepMetricData(
            avgForceConcentricA = 10f,
            avgForceConcentricB = 10f,
            peakForceA = 15f,
            peakForceB = 15f
        )
        val swr = makeSessionWithReps(repMetrics = listOf(repMetric))

        val result = PortalSyncAdapter.toPortalWorkoutSessions(listOf(swr), "user-1")

        val repSummary = result[0].exercises[0].sets[0].repSummaries[0]
        // meanForceN = (10 + 10) * 9.80665 = 196.133
        assertFloatEquals(196.133f, repSummary.meanForceN!!)
        // peakForceN = (15 + 15) * 9.80665 = 294.1995
        assertFloatEquals(294.1995f, repSummary.peakForceN!!)
    }

    @Test
    fun `rep summary calculates TUT as concentric plus eccentric duration`() {
        val repMetric = makeRepMetricData(
            concentricDurationMs = 800L,
            eccentricDurationMs = 1200L
        )
        val swr = makeSessionWithReps(repMetrics = listOf(repMetric))

        val result = PortalSyncAdapter.toPortalWorkoutSessions(listOf(swr), "user-1")

        val repSummary = result[0].exercises[0].sets[0].repSummaries[0]
        assertEquals(2000, repSummary.tutMs) // 800 + 1200
    }

    @Test
    fun `rep summary maps cable forces as left and right in Newtons`() {
        val repMetric = makeRepMetricData(
            avgForceConcentricA = 20f, // Cable A = left
            avgForceConcentricB = 18f  // Cable B = right
        )
        val swr = makeSessionWithReps(repMetrics = listOf(repMetric))

        val result = PortalSyncAdapter.toPortalWorkoutSessions(listOf(swr), "user-1")

        val repSummary = result[0].exercises[0].sets[0].repSummaries[0]
        // left = 20 * 9.80665 = 196.133
        assertFloatEquals(196.133f, repSummary.leftForceAvg!!)
        // right = 18 * 9.80665 = 176.5197
        assertFloatEquals(176.5197f, repSummary.rightForceAvg!!)
    }

    @Test
    fun `rep summary maps power and ROM directly`() {
        val repMetric = makeRepMetricData(
            avgPowerWatts = 250f,
            rangeOfMotionMm = 400f
        )
        val swr = makeSessionWithReps(repMetrics = listOf(repMetric))

        val result = PortalSyncAdapter.toPortalWorkoutSessions(listOf(swr), "user-1")

        val repSummary = result[0].exercises[0].sets[0].repSummaries[0]
        assertFloatEquals(250f, repSummary.powerWatts!!)
        assertFloatEquals(400f, repSummary.romMm!!)
    }

    @Test
    fun `rep summary includes biomechanics data when available`() {
        val repMetric = makeRepMetricData(repNumber = 1)
        val bio = PortalSyncAdapter.RepBiomechanicsData(
            repNumber = 1,
            mcvMmS = 600f,
            peakVelocityMmS = 900f,
            velocityZone = "EXPLOSIVE",
            asymmetryPercent = 5.2f,
            dominantSide = "left",
            avgLoadA = 25f,
            avgLoadB = 23f
        )
        val swr = makeSessionWithReps(
            repMetrics = listOf(repMetric),
            repBiomechanics = listOf(bio)
        )

        val result = PortalSyncAdapter.toPortalWorkoutSessions(listOf(swr), "user-1")

        val repSummary = result[0].exercises[0].sets[0].repSummaries[0]
        // peakVelocityMps should use biomechanics peakVelocityMmS when available
        assertFloatEquals(0.9f, repSummary.peakVelocityMps!!) // 900 / 1000
        assertFloatEquals(5.2f, repSummary.asymmetryPct!!)
        assertEquals("EXPLOSIVE", repSummary.vbtZone)
    }

    // ========== Session Name and Mode ==========

    @Test
    fun `portal session name uses routineName when available`() {
        val swr = makeSessionWithReps(routineName = "Push Day", exerciseName = "Bench Press")

        val result = PortalSyncAdapter.toPortalWorkoutSessions(listOf(swr), "user-1")

        assertEquals("Push Day", result[0].name)
    }

    @Test
    fun `portal session name falls back to exerciseName when no routineName`() {
        val swr = makeSessionWithReps(routineName = null, exerciseName = "Squat")

        val result = PortalSyncAdapter.toPortalWorkoutSessions(listOf(swr), "user-1")

        assertEquals("Squat", result[0].name)
    }

    @Test
    fun `portal session workoutMode is the most common mode in group`() {
        val sessions = listOf(
            makeSessionWithReps(routineSessionId = "g1", mode = "Old School"),
            makeSessionWithReps(routineSessionId = "g1", mode = "Old School"),
            makeSessionWithReps(routineSessionId = "g1", mode = "Pump")
        )

        val result = PortalSyncAdapter.toPortalWorkoutSessions(sessions, "user-1")

        assertEquals("OLD_SCHOOL", result[0].workoutMode)
    }

    // ========== startedAt ISO 8601 ==========

    @Test
    fun `portal session startedAt is ISO 8601 format from earliest session timestamp`() {
        val sessions = listOf(
            makeSessionWithReps(routineSessionId = "g1", timestamp = 1700000000000L),
            makeSessionWithReps(routineSessionId = "g1", timestamp = 1700000060000L)
        )

        val result = PortalSyncAdapter.toPortalWorkoutSessions(sessions, "user-1")

        // Should be ISO 8601 format - kotlinx.datetime.Instant.toString()
        // 1700000000000 = 2023-11-14T22:13:20Z
        assertTrue(result[0].startedAt.contains("2023-11-14"), "startedAt should be ISO 8601: ${result[0].startedAt}")
        assertTrue(result[0].startedAt.contains("T"), "startedAt should contain 'T' separator")
    }

    // ========== Routine Mapping ==========

    @Test
    fun `toPortalRoutine maps basic fields`() {
        val routine = makeRoutine(
            id = "routine-1",
            name = "Push Day",
            description = "Chest and triceps",
            useCount = 5
        )

        val result = PortalSyncAdapter.toPortalRoutine(routine, "user-1")

        assertEquals("routine-1", result.id)
        assertEquals("user-1", result.userId)
        assertEquals("Push Day", result.name)
        assertEquals("Chest and triceps", result.description)
        assertEquals(5, result.timesCompleted)
    }

    @Test
    fun `toPortalRoutine maps exercise count`() {
        val routine = makeRoutine(exerciseCount = 3)

        val result = PortalSyncAdapter.toPortalRoutine(routine, "user-1")

        assertEquals(3, result.exerciseCount)
    }

    @Test
    fun `toPortalRoutine maps exercise basic fields`() {
        val exercises = listOf(
            makeRoutineExercise(
                id = "ex-1",
                name = "Bench Press",
                muscleGroup = "Chest",
                sets = 3,
                reps = 10,
                weightPerCableKg = 25f,
                orderIndex = 0,
                mode = ProgramMode.OldSchool
            )
        )
        val routine = makeRoutine(id = "r-1", exercises = exercises)

        val result = PortalSyncAdapter.toPortalRoutine(routine, "user-1")

        val ex = result.exercises[0]
        assertEquals("ex-1", ex.id)
        assertEquals("r-1", ex.routineId)
        assertEquals("Bench Press", ex.name)
        assertEquals("Chest", ex.muscleGroup)
        assertEquals(3, ex.sets)
        assertEquals(10, ex.reps)
        assertFloatEquals(25f, ex.weight)
        assertEquals(0, ex.orderIndex)
        assertEquals("OLD_SCHOOL", ex.mode)
    }

    @Test
    fun `toPortalRoutine maps superset colors`() {
        val superset = Superset(
            id = "ss-1",
            routineId = "r-1",
            name = "Superset 1",
            colorIndex = SupersetColors.PINK,
            orderIndex = 0
        )
        val exercises = listOf(
            makeRoutineExercise(
                supersetId = "ss-1",
                orderInSuperset = 0
            ),
            makeRoutineExercise(
                id = "ex-2",
                supersetId = "ss-1",
                orderInSuperset = 1
            )
        )
        val routine = makeRoutine(
            id = "r-1",
            exercises = exercises,
            supersets = listOf(superset)
        )

        val result = PortalSyncAdapter.toPortalRoutine(routine, "user-1")

        assertEquals("ss-1", result.exercises[0].supersetId)
        assertEquals("pink", result.exercises[0].supersetColor)
        assertEquals(0, result.exercises[0].supersetOrder)
        assertEquals(1, result.exercises[1].supersetOrder)
    }

    @Test
    fun `toPortalRoutine maps per-set weights as JSON`() {
        val exercises = listOf(
            makeRoutineExercise(
                setWeightsPerCableKg = listOf(20f, 25f, 30f)
            )
        )
        val routine = makeRoutine(exercises = exercises)

        val result = PortalSyncAdapter.toPortalRoutine(routine, "user-1")

        assertNotNull(result.exercises[0].perSetWeights)
        assertTrue(result.exercises[0].perSetWeights!!.contains("20"))
        assertTrue(result.exercises[0].perSetWeights!!.contains("25"))
        assertTrue(result.exercises[0].perSetWeights!!.contains("30"))
    }

    @Test
    fun `toPortalRoutine maps per-set rest as JSON`() {
        val exercises = listOf(
            makeRoutineExercise(
                setRestSeconds = listOf(60, 90, 120)
            )
        )
        val routine = makeRoutine(exercises = exercises)

        val result = PortalSyncAdapter.toPortalRoutine(routine, "user-1")

        assertNotNull(result.exercises[0].perSetRest)
        assertTrue(result.exercises[0].perSetRest!!.contains("60"))
        assertTrue(result.exercises[0].perSetRest!!.contains("90"))
        assertTrue(result.exercises[0].perSetRest!!.contains("120"))
    }

    @Test
    fun `toPortalRoutine perSetWeights is null when empty`() {
        val exercises = listOf(
            makeRoutineExercise(setWeightsPerCableKg = emptyList())
        )
        val routine = makeRoutine(exercises = exercises)

        val result = PortalSyncAdapter.toPortalRoutine(routine, "user-1")

        assertNull(result.exercises[0].perSetWeights)
    }

    @Test
    fun `toPortalRoutine maps Echo mode settings`() {
        val exercises = listOf(
            makeRoutineExercise(
                mode = ProgramMode.Echo,
                eccentricLoad = EccentricLoad.LOAD_130,
                echoLevel = EchoLevel.HARDEST
            )
        )
        val routine = makeRoutine(exercises = exercises)

        val result = PortalSyncAdapter.toPortalRoutine(routine, "user-1")

        val ex = result.exercises[0]
        assertEquals("ECHO", ex.mode)
        assertEquals("LOAD_130", ex.eccentricLoad)
        assertEquals("HARDEST", ex.echoLevel)
    }

    @Test
    fun `toPortalRoutine omits Echo settings for non-Echo modes`() {
        val exercises = listOf(
            makeRoutineExercise(mode = ProgramMode.OldSchool)
        )
        val routine = makeRoutine(exercises = exercises)

        val result = PortalSyncAdapter.toPortalRoutine(routine, "user-1")

        val ex = result.exercises[0]
        assertNull(ex.eccentricLoad)
        assertNull(ex.echoLevel)
    }

    @Test
    fun `toPortalRoutine maps rest seconds from first element of setRestSeconds`() {
        val exercises = listOf(
            makeRoutineExercise(setRestSeconds = listOf(45, 60, 90))
        )
        val routine = makeRoutine(exercises = exercises)

        val result = PortalSyncAdapter.toPortalRoutine(routine, "user-1")

        assertEquals(45, result.exercises[0].restSeconds)
    }

    @Test
    fun `toPortalRoutine defaults rest to 60 when setRestSeconds is empty`() {
        val exercises = listOf(
            makeRoutineExercise(setRestSeconds = emptyList())
        )
        val routine = makeRoutine(exercises = exercises)

        val result = PortalSyncAdapter.toPortalRoutine(routine, "user-1")

        assertEquals(60, result.exercises[0].restSeconds)
    }

    @Test
    fun `toPortalRoutine maps AMRAP flag`() {
        val exercises = listOf(
            makeRoutineExercise(isAMRAP = true)
        )
        val routine = makeRoutine(exercises = exercises)

        val result = PortalSyncAdapter.toPortalRoutine(routine, "user-1")

        assertTrue(result.exercises[0].isAmrap)
    }

    @Test
    fun `toPortalRoutine maps stopAtTop as position string`() {
        val exercisesTop = listOf(makeRoutineExercise(stopAtTop = true))
        val exercisesBottom = listOf(makeRoutineExercise(stopAtTop = false))
        val routineTop = makeRoutine(exercises = exercisesTop)
        val routineBottom = makeRoutine(exercises = exercisesBottom)

        val resultTop = PortalSyncAdapter.toPortalRoutine(routineTop, "user-1")
        val resultBottom = PortalSyncAdapter.toPortalRoutine(routineBottom, "user-1")

        assertEquals("TOP", resultTop.exercises[0].stopAtPosition)
        assertNull(resultBottom.exercises[0].stopAtPosition)
    }

    @Test
    fun `toPortalRoutine maps stall detection flag`() {
        val exercises = listOf(
            makeRoutineExercise(stallDetectionEnabled = false)
        )
        val routine = makeRoutine(exercises = exercises)

        val result = PortalSyncAdapter.toPortalRoutine(routine, "user-1")

        assertEquals(false, result.exercises[0].stallDetection)
    }

    @Test
    fun `toPortalRoutine maps PR percentage when enabled`() {
        val exercises = listOf(
            makeRoutineExercise(
                usePercentOfPR = true,
                weightPercentOfPR = 85
            )
        )
        val routine = makeRoutine(exercises = exercises)

        val result = PortalSyncAdapter.toPortalRoutine(routine, "user-1")

        assertFloatEquals(85f, result.exercises[0].prPercentage!!)
    }

    @Test
    fun `toPortalRoutine prPercentage is null when usePercentOfPR is false`() {
        val exercises = listOf(
            makeRoutineExercise(usePercentOfPR = false)
        )
        val routine = makeRoutine(exercises = exercises)

        val result = PortalSyncAdapter.toPortalRoutine(routine, "user-1")

        assertNull(result.exercises[0].prPercentage)
    }

    @Test
    fun `toPortalRoutine maps repCountTiming`() {
        val exercises = listOf(
            makeRoutineExercise(repCountTiming = RepCountTiming.BOTTOM)
        )
        val routine = makeRoutine(exercises = exercises)

        val result = PortalSyncAdapter.toPortalRoutine(routine, "user-1")

        assertEquals("BOTTOM", result.exercises[0].repCountTiming)
    }

    @Test
    fun `toPortalRoutine estimates duration based on sets and rest`() {
        val exercises = listOf(
            makeRoutineExercise(
                sets = 3,
                setRestSeconds = listOf(60, 60, 60) // 3 sets * 120s + 180s rest = 540s
            )
        )
        val routine = makeRoutine(exercises = exercises)

        val result = PortalSyncAdapter.toPortalRoutine(routine, "user-1")

        // estimateRoutineDuration: sets(3) * 120 + sum(60+60+60)=180 = 540
        assertEquals(540, result.estimatedDuration)
    }

    // ========== userId passthrough ==========

    @Test
    fun `portal session userId is set from parameter`() {
        val swr = makeSessionWithReps()

        val result = PortalSyncAdapter.toPortalWorkoutSessions(listOf(swr), "uid-abc-123")

        assertEquals("uid-abc-123", result[0].userId)
    }

    // ========== Telemetry setId consistency (Task 1.2) ==========

    @Test
    fun `telemetry setIds match generated exercise set IDs`() {
        val repMetric = makeRepMetricData(repNumber = 1)
        val swr = makeSessionWithReps(repMetrics = listOf(repMetric))

        val buildResult = PortalSyncAdapter.toPortalWorkoutSessionsWithTelemetry(listOf(swr), "user-1")

        // The exercise's set ID
        val setId = buildResult.sessions[0].exercises[0].sets[0].id
        // All telemetry points should reference this same setId
        assertTrue(buildResult.telemetry.isNotEmpty(), "Should have telemetry points")
        for (point in buildResult.telemetry) {
            assertEquals(setId, point.setId, "Telemetry setId should match exercise set ID")
        }
    }

    @Test
    fun `toPortalWorkoutSessionsWithTelemetry produces both sessions and telemetry`() {
        val repMetric = makeRepMetricData(repNumber = 1)
        val swr = makeSessionWithReps(repMetrics = listOf(repMetric))

        val buildResult = PortalSyncAdapter.toPortalWorkoutSessionsWithTelemetry(listOf(swr), "user-1")

        assertEquals(1, buildResult.sessions.size)
        assertTrue(buildResult.telemetry.isNotEmpty())
    }

    @Test
    fun `toPortalWorkoutSessionsWithTelemetry returns empty telemetry when no force curves`() {
        // Rep metric with empty concentric/eccentric timestamps
        val swr = makeSessionWithReps(repMetrics = emptyList())

        val buildResult = PortalSyncAdapter.toPortalWorkoutSessionsWithTelemetry(listOf(swr), "user-1")

        assertEquals(1, buildResult.sessions.size)
        assertTrue(buildResult.telemetry.isEmpty())
    }

    // ========== PR count computation (Task 1.4) ==========

    @Test
    fun `portal session prCount counts sessions with isPr true`() {
        val sessions = listOf(
            makeSessionWithReps(routineSessionId = "g1", isPr = true),
            makeSessionWithReps(routineSessionId = "g1", isPr = false),
            makeSessionWithReps(routineSessionId = "g1", isPr = true)
        )

        val result = PortalSyncAdapter.toPortalWorkoutSessions(sessions, "user-1")

        assertEquals(2, result[0].prCount)
    }

    @Test
    fun `portal session prCount is zero when no PRs`() {
        val sessions = listOf(
            makeSessionWithReps(routineSessionId = "g1", isPr = false),
            makeSessionWithReps(routineSessionId = "g1", isPr = false)
        )

        val result = PortalSyncAdapter.toPortalWorkoutSessions(sessions, "user-1")

        assertEquals(0, result[0].prCount)
    }

    // ========== Assessment stable ID (Task 1.3) ==========

    @Test
    fun `toPortalAssessmentResult uses stable result id`() {
        val result1 = com.devil.phoenixproject.database.AssessmentResult(
            id = 42,
            exerciseId = "ex-1",
            estimatedOneRepMaxKg = 100.0,
            loadVelocityData = "[]",
            assessmentSessionId = "sess-1",
            userOverrideKg = null,
            createdAt = 1700000000000L,
            profile_id = "default"
        )

        val dto1 = PortalSyncAdapter.toPortalAssessmentResult(result1)
        val dto2 = PortalSyncAdapter.toPortalAssessmentResult(result1)

        assertEquals("42", dto1.id, "Should use result.id as string")
        assertEquals(dto1.id, dto2.id, "Same result should produce same portal ID")
    }

    // ========== DB-stored mode names in session (Task 1.1 integration) ==========

    @Test
    fun `set workoutMode handles DB-stored OldSchool class name`() {
        val swr = makeSessionWithReps(mode = "OldSchool")

        val result = PortalSyncAdapter.toPortalWorkoutSessions(listOf(swr), "user-1")

        assertEquals("OLD_SCHOOL", result[0].exercises[0].sets[0].workoutMode)
    }

    @Test
    fun `set workoutMode handles DB-stored TUTBeast class name`() {
        val swr = makeSessionWithReps(mode = "TUTBeast")

        val result = PortalSyncAdapter.toPortalWorkoutSessions(listOf(swr), "user-1")

        assertEquals("TUT_BEAST", result[0].exercises[0].sets[0].workoutMode)
    }

    @Test
    fun `set workoutMode handles DB-stored EccentricOnly class name`() {
        val swr = makeSessionWithReps(mode = "EccentricOnly")

        val result = PortalSyncAdapter.toPortalWorkoutSessions(listOf(swr), "user-1")

        assertEquals("ECCENTRIC_ONLY", result[0].exercises[0].sets[0].workoutMode)
    }

    // ========== Factory Helpers ==========

    private fun makeSessionWithReps(
        sessionId: String = "session-${idCounter++}",
        routineSessionId: String? = null,
        exerciseName: String? = "Test Exercise",
        routineName: String? = null,
        mode: String = "Old School",
        timestamp: Long = 1700000000000L + (idCounter * 1000),
        durationMs: Long = 60000,
        weightPerCableKg: Float = 20f,
        reps: Int = 10,
        totalReps: Int = 10,
        totalVolumeKg: Float? = null,
        rpe: Int? = null,
        muscleGroup: String = "General",
        isPr: Boolean = false,
        repMetrics: List<RepMetricData> = emptyList(),
        repBiomechanics: List<PortalSyncAdapter.RepBiomechanicsData> = emptyList()
    ): PortalSyncAdapter.SessionWithReps {
        val session = WorkoutSession(
            id = sessionId,
            timestamp = timestamp,
            mode = mode,
            reps = reps,
            weightPerCableKg = weightPerCableKg,
            duration = durationMs,
            totalReps = totalReps,
            exerciseName = exerciseName,
            routineSessionId = routineSessionId,
            routineName = routineName,
            totalVolumeKg = totalVolumeKg,
            rpe = rpe
        )
        return PortalSyncAdapter.SessionWithReps(
            session = session,
            repMetrics = repMetrics,
            repBiomechanics = repBiomechanics,
            muscleGroup = muscleGroup,
            isPr = isPr
        )
    }

    private fun makeRepMetricData(
        repNumber: Int = 1,
        avgVelocityConcentric: Float = 500f,
        peakVelocity: Float = 800f,
        avgForceConcentricA: Float = 10f,
        avgForceConcentricB: Float = 10f,
        peakForceA: Float = 15f,
        peakForceB: Float = 15f,
        avgPowerWatts: Float = 200f,
        rangeOfMotionMm: Float = 300f,
        concentricDurationMs: Long = 1000L,
        eccentricDurationMs: Long = 1500L
    ): RepMetricData = RepMetricData(
        repNumber = repNumber,
        isWarmup = false,
        startTimestamp = 1700000000000L,
        endTimestamp = 1700000002500L,
        durationMs = concentricDurationMs + eccentricDurationMs,
        concentricDurationMs = concentricDurationMs,
        concentricPositions = floatArrayOf(0f, 100f, 200f),
        concentricLoadsA = floatArrayOf(10f, 12f, 11f),
        concentricLoadsB = floatArrayOf(10f, 12f, 11f),
        concentricVelocities = floatArrayOf(400f, 500f, 600f),
        concentricTimestamps = longArrayOf(0L, 500L, 1000L),
        eccentricDurationMs = eccentricDurationMs,
        eccentricPositions = floatArrayOf(200f, 100f, 0f),
        eccentricLoadsA = floatArrayOf(11f, 10f, 9f),
        eccentricLoadsB = floatArrayOf(11f, 10f, 9f),
        eccentricVelocities = floatArrayOf(300f, 400f, 350f),
        eccentricTimestamps = longArrayOf(0L, 500L, 1000L),
        peakForceA = peakForceA,
        peakForceB = peakForceB,
        avgForceConcentricA = avgForceConcentricA,
        avgForceConcentricB = avgForceConcentricB,
        avgForceEccentricA = 8f,
        avgForceEccentricB = 8f,
        peakVelocity = peakVelocity,
        avgVelocityConcentric = avgVelocityConcentric,
        avgVelocityEccentric = 350f,
        rangeOfMotionMm = rangeOfMotionMm,
        peakPowerWatts = avgPowerWatts * 1.5f,
        avgPowerWatts = avgPowerWatts
    )

    private fun makeRoutineExercise(
        id: String = "ex-${idCounter++}",
        name: String = "Bench Press",
        muscleGroup: String = "Chest",
        sets: Int = 3,
        reps: Int = 10,
        weightPerCableKg: Float = 25f,
        orderIndex: Int = 0,
        mode: ProgramMode = ProgramMode.OldSchool,
        setRestSeconds: List<Int> = listOf(60, 60, 60),
        setWeightsPerCableKg: List<Float> = emptyList(),
        isAMRAP: Boolean = false,
        stopAtTop: Boolean = false,
        stallDetectionEnabled: Boolean = true,
        repCountTiming: RepCountTiming = RepCountTiming.TOP,
        usePercentOfPR: Boolean = false,
        weightPercentOfPR: Int = 80,
        supersetId: String? = null,
        orderInSuperset: Int = 0,
        eccentricLoad: EccentricLoad = EccentricLoad.LOAD_100,
        echoLevel: EchoLevel = EchoLevel.HARDER
    ): RoutineExercise = RoutineExercise(
        id = id,
        exercise = Exercise(name = name, muscleGroup = muscleGroup),
        orderIndex = orderIndex,
        setReps = List(sets) { reps },
        weightPerCableKg = weightPerCableKg,
        setWeightsPerCableKg = setWeightsPerCableKg,
        programMode = mode,
        eccentricLoad = eccentricLoad,
        echoLevel = echoLevel,
        setRestSeconds = setRestSeconds,
        isAMRAP = isAMRAP,
        stopAtTop = stopAtTop,
        stallDetectionEnabled = stallDetectionEnabled,
        repCountTiming = repCountTiming,
        usePercentOfPR = usePercentOfPR,
        weightPercentOfPR = weightPercentOfPR,
        supersetId = supersetId,
        orderInSuperset = orderInSuperset
    )

    private fun makeRoutine(
        id: String = "routine-${idCounter++}",
        name: String = "Test Routine",
        description: String = "",
        useCount: Int = 0,
        exerciseCount: Int = -1, // -1 means auto-detect from exercises list
        exercises: List<RoutineExercise> = if (exerciseCount > 0) {
            (0 until exerciseCount).map { makeRoutineExercise(orderIndex = it) }
        } else {
            listOf(makeRoutineExercise())
        },
        supersets: List<Superset> = emptyList()
    ): Routine = Routine(
        id = id,
        name = name,
        description = description,
        exercises = exercises,
        supersets = supersets,
        useCount = useCount
    )

    private fun assertFloatEquals(expected: Float, actual: Float, tolerance: Float = 0.01f) {
        assertTrue(
            abs(expected - actual) < tolerance,
            "Expected $expected but got $actual (tolerance $tolerance)"
        )
    }

    companion object {
        private var idCounter = 1
    }
}
