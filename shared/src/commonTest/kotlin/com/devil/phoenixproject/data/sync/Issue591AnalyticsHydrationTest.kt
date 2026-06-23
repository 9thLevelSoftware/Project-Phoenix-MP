package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.data.sync.PortalPullAdapter
import com.devil.phoenixproject.data.sync.PullExerciseDto
import com.devil.phoenixproject.data.sync.PullRepSummaryDto
import com.devil.phoenixproject.data.sync.PullSetDto
import com.devil.phoenixproject.data.sync.PullWorkoutSessionDto
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #591: Analytics only records first set of first exercise in
 * multi-exercise routine; per-set drill-down shows 'after v0.2.1'
 * placeholder on v0.9.2.
 *
 * Regression coverage for the two-part fix:
 *   1. PortalPullAdapter.toWorkoutSessionsWithLookup must hydrate
 *      detailed metric columns (peakForceConcentricA/B, etc.) from the
 *      per-rep telemetry carried in PullSetDto.repSummaries so a freshly
 *      recorded local row is not overwritten by a null-metric pull row.
 *   2. When the pull DTO carries no rep summaries at all, the
 *      hydration helper returns null so the LWW merge preserves the
 *      locally captured value (covered separately by the sync repo
 *      test).
 *
 * Unit conventions: portal rep-summaries carry force in Newtons.
 * Mobile WorkoutSession metric columns store kg-load (Newtons / 9.80665).
 * The hydration helper must convert.
 */
class Issue591AnalyticsHydrationTest {

    @Test
    fun `pull DTO with rep summaries hydrates peakForceConcentric from max left right force per cable`() = kotlinx.coroutines.runBlocking {
        val repForceLeftN = 50f * 9.80665f   // ≈ 50 kg-load per cable
        val repForceRightN = 55f * 9.80665f  // ≈ 55 kg-load per cable
        val dto = PullWorkoutSessionDto(
            id = "portal-session-1",
            userId = "user-1",
            startedAt = "2026-06-23T06:16:00Z",
            durationSeconds = 60,
            exerciseCount = 1,
            routineName = "4.Scaffold.Pull/Press",
            exercises = listOf(
                PullExerciseDto(
                    id = "portal-ex-1",
                    sessionId = "portal-session-1",
                    name = "Incline Bench Press",
                    muscleGroup = "Chest",
                    orderIndex = 0,
                    sets = listOf(
                        PullSetDto(
                            id = "portal-set-1",
                            exerciseId = "portal-ex-1",
                            setNumber = 1,
                            targetReps = 8,
                            actualReps = 8,
                            weightKg = 30f,
                            repSummaries = listOf(
                                PullRepSummaryDto(
                                    id = "rep-1", setId = "portal-set-1", repNumber = 1,
                                    leftForceAvg = repForceLeftN,
                                    rightForceAvg = repForceRightN,
                                ),
                                PullRepSummaryDto(
                                    id = "rep-2", setId = "portal-set-1", repNumber = 2,
                                    leftForceAvg = 48f * 9.80665f,
                                    rightForceAvg = 52f * 9.80665f,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val sessions = PortalPullAdapter.toWorkoutSessionsWithLookup(
            portalSession = dto,
            profileId = "default",
        ) { _, _, _ -> "exercise-catalog-id-1" }

        assertEquals(1, sessions.size, "one portal exercise should map to one mobile session")
        val session = sessions[0]

        // peakForceConcentricA/B is the max per-rep left/right force
        // converted back from Newtons to kg-load. Allow a small
        // floating-point tolerance because we round-trip through a
        // Float multiply/divide by 9.80665.
        val expectedPeakA = 50f
        val expectedPeakB = 55f
        assertNotNull(session.peakForceConcentricA, "peakForceConcentricA must be hydrated")
        assertNotNull(session.peakForceConcentricB, "peakForceConcentricB must be hydrated")
        assertTrue(
            abs(session.peakForceConcentricA!! - expectedPeakA) < 0.01f,
            "peakForceConcentricA expected ~$expectedPeakA, got ${session.peakForceConcentricA}",
        )
        assertTrue(
            abs(session.peakForceConcentricB!! - expectedPeakB) < 0.01f,
            "peakForceConcentricB expected ~$expectedPeakB, got ${session.peakForceConcentricB}",
        )
    }

    @Test
    fun `pull DTO with rep summaries hydrates avgForceConcentric from rep-set average`() = kotlinx.coroutines.runBlocking {
        val dto = PullWorkoutSessionDto(
            id = "portal-session-2",
            userId = "user-1",
            startedAt = "2026-06-23T06:16:00Z",
            durationSeconds = 60,
            exerciseCount = 1,
            exercises = listOf(
                PullExerciseDto(
                    id = "portal-ex-2",
                    sessionId = "portal-session-2",
                    name = "Squat",
                    muscleGroup = "Legs",
                    sets = listOf(
                        PullSetDto(
                            id = "portal-set-2",
                            exerciseId = "portal-ex-2",
                            setNumber = 1,
                            actualReps = 3,
                            weightKg = 80f,
                            repSummaries = listOf(
                                PullRepSummaryDto(
                                    id = "r1", setId = "portal-set-2", repNumber = 1,
                                    leftForceAvg = 80f * 9.80665f,
                                    rightForceAvg = 80f * 9.80665f,
                                ),
                                PullRepSummaryDto(
                                    id = "r2", setId = "portal-set-2", repNumber = 2,
                                    leftForceAvg = 70f * 9.80665f,
                                    rightForceAvg = 75f * 9.80665f,
                                ),
                                PullRepSummaryDto(
                                    id = "r3", setId = "portal-set-2", repNumber = 3,
                                    leftForceAvg = 60f * 9.80665f,
                                    rightForceAvg = 65f * 9.80665f,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val sessions = PortalPullAdapter.toWorkoutSessionsWithLookup(
            portalSession = dto,
            profileId = "default",
        ) { _, _, _ -> "exercise-catalog-id-2" }

        val session = sessions.single()
        assertNotNull(session.avgForceConcentricA)
        assertNotNull(session.avgForceConcentricB)
        // Average of 80, 70, 60 = 70; average of 80, 75, 65 = 73.33
        assertTrue(
            abs(session.avgForceConcentricA!! - 70f) < 0.05f,
            "avgForceConcentricA expected ~70, got ${session.avgForceConcentricA}",
        )
        assertTrue(
            abs(session.avgForceConcentricB!! - 73.333f) < 0.05f,
            "avgForceConcentricB expected ~73.33, got ${session.avgForceConcentricB}",
        )
    }

    @Test
    fun `pull DTO with empty rep summaries keeps metric fields null so LWW preserves local values`() = kotlinx.coroutines.runBlocking {
        val dto = PullWorkoutSessionDto(
            id = "portal-session-3",
            userId = "user-1",
            startedAt = "2026-06-23T06:16:00Z",
            durationSeconds = 60,
            exerciseCount = 1,
            exercises = listOf(
                PullExerciseDto(
                    id = "portal-ex-3",
                    sessionId = "portal-session-3",
                    name = "Bench Press",
                    muscleGroup = "Chest",
                    sets = listOf(
                        PullSetDto(
                            id = "portal-set-3",
                            exerciseId = "portal-ex-3",
                            setNumber = 1,
                            actualReps = 5,
                            weightKg = 60f,
                            repSummaries = emptyList(),
                        ),
                    ),
                ),
            ),
        )

        val sessions = PortalPullAdapter.toWorkoutSessionsWithLookup(
            portalSession = dto,
            profileId = "default",
        ) { _, _, _ -> "exercise-catalog-id-3" }

        val session = sessions.single()
        // Hydration must NOT fabricate values — they stay null so the
        // SqlDelightSyncRepository.mergeSessionsLww LWW gate can fall
        // back to the locally captured row.
        assertNull(session.peakForceConcentricA)
        assertNull(session.peakForceConcentricB)
        assertNull(session.peakForceEccentricA)
        assertNull(session.peakForceEccentricB)
        assertNull(session.avgForceConcentricA)
        assertNull(session.avgForceConcentricB)
    }

    @Test
    fun `asymmetry percent aggregates across rep summaries when supplied`() = kotlinx.coroutines.runBlocking {
        val dto = PullWorkoutSessionDto(
            id = "portal-session-4",
            userId = "user-1",
            startedAt = "2026-06-23T06:16:00Z",
            durationSeconds = 60,
            exerciseCount = 1,
            exercises = listOf(
                PullExerciseDto(
                    id = "portal-ex-4",
                    sessionId = "portal-session-4",
                    name = "Curl",
                    muscleGroup = "Arms",
                    sets = listOf(
                        PullSetDto(
                            id = "portal-set-4",
                            exerciseId = "portal-ex-4",
                            setNumber = 1,
                            actualReps = 2,
                            weightKg = 20f,
                            repSummaries = listOf(
                                PullRepSummaryDto(
                                    id = "r1", setId = "portal-set-4", repNumber = 1,
                                    leftForceAvg = 10f * 9.80665f, rightForceAvg = 12f * 9.80665f,
                                    asymmetryPct = 18f,
                                ),
                                PullRepSummaryDto(
                                    id = "r2", setId = "portal-set-4", repNumber = 2,
                                    leftForceAvg = 9f * 9.80665f, rightForceAvg = 11f * 9.80665f,
                                    asymmetryPct = 22f,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val sessions = PortalPullAdapter.toWorkoutSessionsWithLookup(
            portalSession = dto,
            profileId = "default",
        ) { _, _, _ -> "exercise-catalog-id-4" }

        val session = sessions.single()
        assertNotNull(session.avgAsymmetryPercent)
        assertEquals(20f, session.avgAsymmetryPercent, 0.01f)
    }
}
