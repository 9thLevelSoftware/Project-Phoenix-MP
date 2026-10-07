package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #1182 portal push contract (merge-gate R3): the wire set weight is the ACHIEVED
 * Echo load (per-cable), never the configured seed; an unmeasured Echo set ships the
 * explicitly authorized non-null 0 sentinel (the portal's current default) and suppresses
 * the rep-based 1RM estimate entirely - never a measured-zero claim, never a seed claim.
 */
class PortalSyncAdapterEchoAchievedLoadTest {

    private fun echoSession(
        heaviest: Float?,
        configured: Float = 5f,
    ) = WorkoutSession(
        id = "echo-session",
        timestamp = 1_790_000_000_000L,
        mode = "Echo",
        reps = 0,
        weightPerCableKg = configured,
        duration = 90_000L,
        totalReps = 9,
        workingReps = 9,
        exerciseName = "Barbell Squat",
        heaviestLiftKg = heaviest,
    )

    private fun buildEcho(session: WorkoutSession): PortalSyncAdapter.PortalSessionBuildResult =
        PortalSyncAdapter.toPortalWorkoutSessionsWithTelemetry(
            sessionsWithReps = listOf(PortalSyncAdapter.SessionWithReps(session = session)),
            userId = "user-1",
            includeTelemetry = false,
        )

    @Test
    fun `unmeasured echo set ships the authorized zero sentinel and suppresses the estimate`() {
        val result = buildEcho(echoSession(heaviest = 0f))
        val exercise = result.sessions.single().exercises.single()
        val set = exercise.sets.single()

        assertEquals(0f, set.weightKg, "the non-null 0 sentinel matches the portal default - never the configured seed")
        assertNull(exercise.estimatedOneRepMaxKg, "the rep-based estimate is suppressed when no load was measured")
    }

    @Test
    fun `measured echo set ships the achieved load and derives the estimate from it`() {
        val result = buildEcho(echoSession(heaviest = 80f, configured = 5f))
        val exercise = result.sessions.single().exercises.single()
        val set = exercise.sets.single()

        assertEquals(80f, set.weightKg, "the wire weight is the achieved measured peak per cable")
        assertTrue(set.weightKg != 5f, "the configured seed never reaches the portal wire as the set weight")
        assertEquals(
            com.devil.phoenixproject.util.OneRepMaxCalculator.estimate(80f, 9),
            exercise.estimatedOneRepMaxKg,
            "the estimate derives from the ACHIEVED load",
        )
    }

    @Test
    fun `legacy placeholder echo rows never push the configured seed as the set weight`() {
        // A pre-#1182 row's measured column holds the seed with no telemetry evidence.
        val result = buildEcho(echoSession(heaviest = 5f, configured = 5f))
        val exercise = result.sessions.single().exercises.single()
        val set = exercise.sets.single()

        assertEquals(0f, set.weightKg, "an unprovenanced placeholder resolves unavailable -> 0 sentinel")
        assertNull(exercise.estimatedOneRepMaxKg, "no estimate may be derived from the placeholder")
    }
}
