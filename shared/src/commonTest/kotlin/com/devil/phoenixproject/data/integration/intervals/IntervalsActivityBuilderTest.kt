package com.devil.phoenixproject.data.integration.intervals

import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * Golden tests for the canonical Intervals activity builder (issue #1384, integration-model
 * §5): literal IDs, receipt membership, no CompletedSet/session double counting, attempt
 * ordering, warmup/working separation, Echo seed-vs-actual, OldSchool per-cable labelling,
 * kg peaks, volume-not-work, omitted null/nonfinite values, HR duration weighting + coverage
 * label, sub-second/int32 elapsed rejection, and description length failure.
 */
class IntervalsActivityBuilderTest {

    // ==================== identity ====================

    @Test
    fun `external id is the literal completion id with no namespacing or rewriting`() {
        val oddId = "phoenix:routine:MiXeD Case 123"
        val result = build(capture(externalId = oddId))
        val activity = assertBuilt(result)
        assertEquals(oddId, activity.externalId)
        assertEquals("WeightTraining", activity.type)
    }

    @Test
    fun `empty external id is rejected`() {
        assertTrue(build(capture(externalId = "")) is IntervalsActivityResult.Invalid)
    }

    @Test
    fun `empty receipt membership is rejected`() {
        assertTrue(build(capture(), rows = emptyList()) is IntervalsActivityResult.Invalid)
    }

    // ==================== canonical membership ====================

    @Test
    fun `persisted completed sets take precedence over the session-derived fallback set`() {
        val session = session(id = "s1")
        val set = completedSet(id = "c1", sessionId = "s1", setNumber = 1, actualReps = 5)
        val activity = assertBuilt(build(rows = listOf(IntervalsActivitySessionRow(session, listOf(set)))))

        // Exactly one set line for the session — never the fallback copy as well.
        assertEquals(1, activity.description.lines().count { it.trimStart().startsWith("Set ") })
        assertTrue(activity.description.contains("5 working reps"), activity.description)
    }

    @Test
    fun `a session without persisted sets emits one fallback set with separately labelled warmup`() {
        val session = session(id = "s1", workingReps = 5, warmupReps = 2)
        val activity = assertBuilt(build(rows = listOf(IntervalsActivitySessionRow(session, emptyList()))))

        assertEquals(1, activity.description.lines().count { it.trimStart().startsWith("Set ") })
        assertTrue(activity.description.contains("5 working reps"), activity.description)
        assertTrue(activity.description.contains("2 warmup reps"), activity.description)
    }

    @Test
    fun `distinct attempts are never collapsed and sets sort by setNumber completedAt id`() {
        val session = session(id = "s1")
        val laterRetry = completedSet(id = "b", sessionId = "s1", setNumber = 1, actualReps = 3, completedAt = 200, attemptNumber = 2)
        val firstAttempt = completedSet(id = "a", sessionId = "s1", setNumber = 1, actualReps = 4, completedAt = 100, attemptNumber = 1)
        val secondSet = completedSet(id = "c", sessionId = "s1", setNumber = 0, actualReps = 6, completedAt = 50, attemptNumber = 1)
        val activity = assertBuilt(
            build(rows = listOf(IntervalsActivitySessionRow(session, listOf(laterRetry, firstAttempt, secondSet)))),
        )

        val setLines = activity.description.lines().filter { it.trimStart().startsWith("Set ") }
        assertEquals(3, setLines.size)
        assertEquals("  Set 0 (attempt 1, working): 6 working reps; 40 configured kg per cable", setLines[0])
        assertEquals("  Set 1 (attempt 1, working): 4 working reps; 40 configured kg per cable", setLines[1])
        assertEquals("  Set 1 (attempt 2, working): 3 working reps; 40 configured kg per cable", setLines[2])
    }

    @Test
    fun `sessions enumerate once each and sort by timestamp then id`() {
        val first = IntervalsActivitySessionRow(
            session(id = "s-b", timestamp = 100, exerciseName = "Bench"),
            listOf(completedSet(id = "c1", sessionId = "s-b", setNumber = 1, actualReps = 5)),
        )
        val sameTimeEarlierId = IntervalsActivitySessionRow(
            session(id = "s-a", timestamp = 100, exerciseName = "Row"),
            listOf(completedSet(id = "c2", sessionId = "s-a", setNumber = 1, actualReps = 8)),
        )
        val earlier = IntervalsActivitySessionRow(
            session(id = "s-c", timestamp = 50, exerciseName = "Squat"),
            listOf(completedSet(id = "c3", sessionId = "s-c", setNumber = 1, actualReps = 7)),
        )
        val activity = assertBuilt(build(rows = listOf(first, sameTimeEarlierId, earlier)))

        val sessionLines = activity.description.lines().filter { it.startsWith("Session ") }
        assertEquals(3, sessionLines.size)
        assertTrue(sessionLines[0].contains("Squat"), sessionLines.toString())
        assertTrue(sessionLines[1].contains("Row"), sessionLines.toString())
        assertTrue(sessionLines[2].contains("Bench"), sessionLines.toString())
        assertEquals(3, activity.description.lines().count { it.trimStart().startsWith("Set ") })
    }

    @Test
    fun `warmup sets are labelled warmup and are not extra copies`() {
        val session = session(id = "s1", workingReps = 5, warmupReps = 2)
        val warmup = completedSet(id = "c1", sessionId = "s1", setNumber = 1, actualReps = 2, completedAt = 100, setType = SetType.WARMUP)
        val working = completedSet(id = "c2", sessionId = "s1", setNumber = 2, actualReps = 5, completedAt = 200)
        val activity = assertBuilt(build(rows = listOf(IntervalsActivitySessionRow(session, listOf(warmup, working)))))

        assertEquals(2, activity.description.lines().count { it.trimStart().startsWith("Set ") })
        assertTrue(activity.description.contains("2 warmup reps"), activity.description)
        assertTrue(activity.description.contains("5 working reps"), activity.description)
    }

    // ==================== loads ====================

    @Test
    fun `configured load is labelled configured kg per cable and never doubled`() {
        val session = session(id = "s1", weightPerCableKg = 40f, cableCount = 2)
        val set = completedSet(id = "c1", sessionId = "s1", setNumber = 1, actualReps = 5, actualWeightKg = 40f)
        val activity = assertBuilt(build(rows = listOf(IntervalsActivitySessionRow(session, listOf(set)))))

        assertTrue(activity.description.contains("40 configured kg per cable"), activity.description)
        assertFalse(activity.description.contains("80"), "no universal x2 rule: " + activity.description)
    }

    @Test
    fun `echo achieved load comes from the measured peak and never from the configured seed`() {
        // Measured peak distinct from the configured seed -> a real measurement.
        val measuredSession = session(id = "s1", mode = "Echo", weightPerCableKg = 10f, heaviestLiftKg = 42f)
        val measuredSet = completedSet(id = "c1", sessionId = "s1", setNumber = 1, actualReps = 5, actualWeightKg = 42f)
        val measured = assertBuilt(build(rows = listOf(IntervalsActivitySessionRow(measuredSession, listOf(measuredSet)))))
        assertTrue(measured.description.contains("measured peak 42 kg per cable"), measured.description)
        assertFalse(measured.description.contains("10 configured kg per cable"), measured.description)

        // No accepted working telemetry -> load unavailable, never the configured seed or 0 kg sentinels (#1182).
        val emptySession = session(
            id = "s2",
            mode = "Echo",
            weightPerCableKg = 10f,
            heaviestLiftKg = 0f,
            workingAvgWeightKg = 0f,
            totalVolumeKg = 0f,
        )
        val emptySet = completedSet(id = "c2", sessionId = "s2", setNumber = 1, actualReps = 5, actualWeightKg = 0f)
        val unavailable = assertBuilt(build(rows = listOf(IntervalsActivitySessionRow(emptySession, listOf(emptySet)))))
        assertTrue(unavailable.description.contains("load unavailable (no measured load)"), unavailable.description)
        assertFalse(unavailable.description.contains("10 configured kg per cable"), unavailable.description)
        assertFalse(unavailable.description.contains("Mean working load"), unavailable.description)
        assertFalse(unavailable.description.contains("Total volume"), unavailable.description)
    }

    @Test
    fun `echo mean working load is included only when measured and never fabricated`() {
        val withMean = session(id = "s1", mode = "Echo", heaviestLiftKg = 42f, workingAvgWeightKg = 39.5f)
        val activity = assertBuilt(build(rows = listOf(IntervalsActivitySessionRow(withMean, emptyList()))))
        assertTrue(activity.description.contains("Mean working load: 39.5 kg per cable (Echo measured)"), activity.description)

        val withoutMean = session(id = "s2", workingAvgWeightKg = null)
        val nullMean = assertBuilt(build(rows = listOf(IntervalsActivitySessionRow(withoutMean, emptyList()))))
        assertFalse(nullMean.description.contains("Mean working load"), nullMean.description)
    }

    @Test
    fun `A B peaks are labelled per-cable kg load equivalents and zero defaults are omitted`() {
        val session = session(
            id = "s1",
            peakForceConcentricA = 42.5f,
            peakForceConcentricB = 0f,
            peakForceEccentricA = 40f,
            peakForceEccentricB = null,
        )
        val activity = assertBuilt(build(rows = listOf(IntervalsActivitySessionRow(session, emptyList()))))
        assertTrue(activity.description.contains("A/B peaks (per-cable kg load equivalents)"), activity.description)
        assertTrue(activity.description.contains("concentric A 42.5 kg"), activity.description)
        assertTrue(activity.description.contains("eccentric A 40 kg"), activity.description)
        assertFalse(activity.description.contains("concentric B"), "zero defaults are omitted: " + activity.description)
        assertFalse(activity.description.contains("eccentric B"), "nulls are omitted: " + activity.description)

        val zeroOnly = session(id = "s2")
        val omitted = assertBuilt(build(rows = listOf(IntervalsActivitySessionRow(zeroOnly, emptyList()))))
        assertFalse(omitted.description.contains("A/B peaks"), omitted.description)
    }

    @Test
    fun `volume is labelled volume kg reps and never work or joules`() {
        val session = session(id = "s1", totalVolumeKg = 1000f)
        val activity = assertBuilt(build(rows = listOf(IntervalsActivitySessionRow(session, emptyList()))))
        assertTrue(activity.description.contains("Total volume (kg·reps): 1000"), activity.description)
        assertFalse(activity.description.contains("joule"), activity.description)
        assertFalse(activity.description.contains("mechanical work"), activity.description)
        assertFalse(activity.description.contains("work (kg"), activity.description)
    }

    @Test
    fun `null and nonfinite measurements are omitted never zero filled`() {
        val session = session(
            id = "s1",
            totalVolumeKg = Float.NaN,
            workingAvgWeightKg = Float.POSITIVE_INFINITY,
            peakForceConcentricA = Float.NaN,
        )
        val activity = assertBuilt(build(rows = listOf(IntervalsActivitySessionRow(session, emptyList()))))
        assertFalse(activity.description.contains("Total volume"), activity.description)
        assertFalse(activity.description.contains("Mean working load"), activity.description)
        assertFalse(activity.description.contains("A/B peaks"), activity.description)
    }

    // ==================== heart rate ====================

    @Test
    fun `heart rate mean is duration weighted once per session and max is independent`() {
        // mean = (100*1000 + 200*3000) / 4000 = 175
        val first = IntervalsActivitySessionRow(
            session(id = "s1", durationMs = 1_000L, avgHr = 100, maxHr = 150),
            listOf(completedSet(id = "c1", sessionId = "s1", setNumber = 1, actualReps = 5)),
        )
        val second = IntervalsActivitySessionRow(
            session(id = "s2", durationMs = 3_000L, avgHr = 200, maxHr = 158),
            listOf(completedSet(id = "c2", sessionId = "s2", setNumber = 1, actualReps = 5)),
        )
        val activity = assertBuilt(build(rows = listOf(first, second)))
        assertEquals(175, activity.averageHeartrate)
        assertEquals(158, activity.maxHeartrate)
        assertTrue(activity.description.contains("mean 175 bpm"), activity.description)
        assertTrue(activity.description.contains("estimate over sampled working periods"), activity.description)

        // Validity window 1..300; invalid and nonpositive-duration rows are excluded and
        // the two HR fields are omitted independently.
        val invalid = IntervalsActivitySessionRow(
            session(id = "s3", durationMs = 5_000L, avgHr = 0, maxHr = 301),
            listOf(completedSet(id = "c3", sessionId = "s3", setNumber = 1, actualReps = 5)),
        )
        val none = assertBuilt(build(rows = listOf(invalid)))
        assertNull(none.averageHeartrate)
        assertNull(none.maxHeartrate)
        assertFalse(none.description.contains("Heart rate"), none.description)

        val maxOnly = assertBuilt(
            build(rows = listOf(second.copy(session = second.session.copy(avgHeartRateBpm = null)))),
        )
        assertNull(maxOnly.averageHeartrate)
        assertEquals(158, maxOnly.maxHeartrate)
        assertTrue(maxOnly.description.contains("max 158 bpm"), maxOnly.description)
        assertFalse(maxOnly.description.contains("mean"), maxOnly.description)
    }

    // ==================== elapsed time ====================

    @Test
    fun `routine elapsed uses captured wall clock finish minus absolute start including rests`() {
        val activity = assertBuilt(build(capture(startEpochMs = 1_000L, completedEpochMs = 1_801_000L)))
        assertEquals(1_800, activity.elapsedTimeSeconds)
    }

    @Test
    fun `standalone elapsed floor divides persisted milliseconds`() {
        val session = session(id = "s1", durationMs = 2_999L)
        val activity = assertBuilt(
            build(
                capture(localKind = IntervalsLocalKind.STANDALONE, externalId = "s1", completionId = "s1"),
                rows = listOf(IntervalsActivitySessionRow(session, emptyList())),
            ),
        )
        assertEquals(2, activity.elapsedTimeSeconds)
    }

    @Test
    fun `sub-second and int32-overflow elapsed values are rejected not padded or wrapped`() {
        assertTrue(
            build(capture(startEpochMs = 1_000L, completedEpochMs = 1_500L)) is IntervalsActivityResult.Invalid,
        )
        assertTrue(
            build(capture(startEpochMs = 0L, completedEpochMs = 2_147_483_648_000L)) is IntervalsActivityResult.Invalid,
        )
    }

    // ==================== deterministic formatting ====================

    @Test
    fun `description is deterministic locale independent and includes every set`() {
        val session = session(id = "s1")
        val sets = listOf(
            completedSet(id = "c1", sessionId = "s1", setNumber = 1, actualReps = 5, completedAt = 100),
            completedSet(id = "c2", sessionId = "s1", setNumber = 2, actualReps = 4, completedAt = 200),
        )
        val rows = listOf(IntervalsActivitySessionRow(session, sets))
        val first = assertBuilt(build(capture(), rows = rows))
        val second = assertBuilt(build(capture(), rows = rows))
        assertEquals(first.description, second.description)
        assertEquals(
            """
            Routine: Push Day
            Session 1: bench-press (mode Old School)
              Set 1 (attempt 1, working): 5 working reps; 40 configured kg per cable
              Set 2 (attempt 1, working): 4 working reps; 40 configured kg per cable
            """.trimIndent(),
            first.description,
        )
    }

    @Test
    fun `utc formatting is zone independent`() {
        assertEquals("2023-11-14T22:13:20Z", IntervalsActivityBuilder.formatUtcIso(1_700_000_000_000L))
    }

    // ==================== local payload bounds ====================

    @Test
    fun `name and description over the local byte bounds fail visibly with no truncation`() {
        val longName = "R".repeat(300)
        assertTrue(
            build(capture(routineTitle = longName)) is IntervalsActivityResult.PayloadTooLarge,
        )

        // Every set is included, so a huge set count overflows the description bound and
        // must fail visibly instead of silently dropping sets or splitting activities.
        val session = session(id = "s1")
        val manySets = (1..2_000).map { index ->
            completedSet(id = "c$index", sessionId = "s1", setNumber = index, actualReps = 5, completedAt = index.toLong())
        }
        val result = build(rows = listOf(IntervalsActivitySessionRow(session, manySets)))
        assertTrue(result is IntervalsActivityResult.PayloadTooLarge, "got $result")
    }

    // ==================== qualification ====================

    @Test
    fun `zero-rep failures and aborted sets never qualify while timed holds qualify without invented reps`() {
        val session = session(id = "s1", workingReps = 0, durationMs = 30_000L)
        val stall = completedSet(id = "c1", sessionId = "s1", setNumber = 1, actualReps = 0, endReason = SetEndReason.STALL_FAILURE)
        assertFalse(IntervalsActivityBuilder.qualifiesAsValidWork(session, stall, timedModeEvidence = true))
        val aborted = completedSet(id = "c2", sessionId = "s1", setNumber = 1, actualReps = 0, endReason = SetEndReason.USER_STOPPED)
        assertFalse(IntervalsActivityBuilder.qualifiesAsValidWork(session, aborted, timedModeEvidence = true))

        val timedHold = completedSet(id = "c3", sessionId = "s1", setNumber = 1, actualReps = 0, endReason = SetEndReason.TIMER_EXPIRED)
        assertTrue(IntervalsActivityBuilder.qualifiesAsValidWork(session, timedHold, timedModeEvidence = true))
        assertFalse(
            IntervalsActivityBuilder.qualifiesAsValidWork(session, timedHold, timedModeEvidence = false),
            "captured timed-mode evidence is required",
        )

        // A manual early finish with persisted valid work is not automatically an abort.
        val manualFinish = completedSet(id = "c4", sessionId = "s1", setNumber = 1, actualReps = 2, endReason = SetEndReason.USER_STOPPED)
        assertTrue(IntervalsActivityBuilder.qualifiesAsValidWork(session, manualFinish, timedModeEvidence = false))

        // The exported timed hold never invents repetitions.
        val timedSession = session(id = "s2", workingReps = 0, durationMs = 30_000L)
        val activity = assertBuilt(
            build(rows = listOf(IntervalsActivitySessionRow(timedSession, listOf(timedHold.copy(sessionId = "s2"))))),
        )
        assertTrue(
            activity.description.contains("timed hold completed (TIMER_EXPIRED; no repetitions recorded)"),
            activity.description,
        )
        assertFalse(activity.description.contains("0 working reps"), activity.description)
    }

    // ==================== fixtures ====================

    private fun build(
        capture: IntervalsCompletionCapture = capture(),
        rows: List<IntervalsActivitySessionRow> = defaultRows(),
    ): IntervalsActivityResult = IntervalsActivityBuilder.build(capture, rows)

    private fun assertBuilt(result: IntervalsActivityResult): IntervalsManualActivity {
        assertTrue(result is IntervalsActivityResult.Built, "expected Built, got $result")
        return result.activity
    }

    private fun defaultRows(): List<IntervalsActivitySessionRow> = listOf(
        IntervalsActivitySessionRow(
            session(id = "s1"),
            listOf(completedSet(id = "c1", sessionId = "s1", setNumber = 1, actualReps = 5)),
        ),
    )

    private fun capture(
        localKind: IntervalsLocalKind = IntervalsLocalKind.ROUTINE,
        externalId: String = "routine-1384",
        completionId: String = "routine-1384",
        routineTitle: String? = "Push Day",
        startEpochMs: Long = 1_700_000_000_000L,
        completedEpochMs: Long = 1_700_001_800_000L,
    ) = IntervalsCompletionCapture(
        key = IntervalsCompletionKey("default", localKind, completionId),
        installationId = "install-1",
        finishReason = IntervalsFinishReason.NORMAL_FINISH,
        routineTitle = routineTitle,
        externalId = externalId,
        startEpochMs = startEpochMs,
        startLocalIso = "2023-11-14T17:13:20",
        timeZoneId = "America/New_York",
        utcOffsetMinutes = -300,
        completedEpochMs = completedEpochMs,
        eligible = true,
        resolvedAthleteId = "athlete-1",
        connectionGeneration = 7L,
    )

    private fun session(
        id: String,
        timestamp: Long = 1_700_000_000_000L,
        mode: String = "Old School",
        weightPerCableKg: Float = 40f,
        durationMs: Long = 60_000L,
        workingReps: Int = 5,
        warmupReps: Int = 0,
        exerciseName: String? = "bench-press",
        heaviestLiftKg: Float? = null,
        workingAvgWeightKg: Float? = null,
        totalVolumeKg: Float? = null,
        cableCount: Int? = null,
        peakForceConcentricA: Float? = null,
        peakForceConcentricB: Float? = null,
        peakForceEccentricA: Float? = null,
        peakForceEccentricB: Float? = null,
        avgHr: Int? = null,
        maxHr: Int? = null,
    ) = WorkoutSession(
        id = id,
        timestamp = timestamp,
        mode = mode,
        reps = 5,
        weightPerCableKg = weightPerCableKg,
        duration = durationMs,
        totalReps = workingReps,
        warmupReps = warmupReps,
        workingReps = workingReps,
        exerciseName = exerciseName,
        routineSessionId = "routine-1384",
        routineName = "Push Day",
        profileId = "default",
        heaviestLiftKg = heaviestLiftKg,
        workingAvgWeightKg = workingAvgWeightKg,
        totalVolumeKg = totalVolumeKg,
        cableCount = cableCount,
        peakForceConcentricA = peakForceConcentricA,
        peakForceConcentricB = peakForceConcentricB,
        peakForceEccentricA = peakForceEccentricA,
        peakForceEccentricB = peakForceEccentricB,
        avgHeartRateBpm = avgHr,
        maxHeartRateBpm = maxHr,
    )

    private fun completedSet(
        id: String,
        sessionId: String,
        setNumber: Int,
        actualReps: Int,
        completedAt: Long = 100L,
        attemptNumber: Int = 1,
        actualWeightKg: Float = 40f,
        setType: SetType = SetType.STANDARD,
        endReason: SetEndReason = SetEndReason.TARGET_REPS_REACHED,
    ) = CompletedSet(
        id = id,
        sessionId = sessionId,
        plannedSetId = null,
        setNumber = setNumber,
        setType = setType,
        actualReps = actualReps,
        actualWeightKg = actualWeightKg,
        loggedRpe = null,
        isPr = false,
        completedAt = completedAt,
        setEndReason = endReason,
        attemptNumber = attemptNumber,
    )
}
