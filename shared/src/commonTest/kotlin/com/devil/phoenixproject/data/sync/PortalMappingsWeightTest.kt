package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Weight-convention parity tests per audit 02:
 *
 *   "All weight values in the database are per-cable (single cable, 0-220kg range).
 *    Portal multiplies by 2 for display (WEIGHT_MULTIPLIER = 2 in src/schemas/transforms.ts).
 *    Mobile stores and sends per-cable values in sync DTOs."
 *
 * Push path (PortalSyncAdapter.buildPortalExerciseWithTelemetry):
 *   PortalSetDto.weightKg = session.weightPerCableKg    (NO ×2, already per-cable)
 *
 * Push totalVolume aggregation (PortalSyncAdapter.buildPortalSession):
 *   - If session.totalVolumeKg != null (measured): divide by cableCount → per-cable.
 *   - Else fallback: weightPerCableKg × totalReps (already per-cable).
 *
 * Pull path (PortalPullAdapter.toWorkoutSessions):
 *   WorkoutSession.weightPerCableKg = max(PullSetDto.weightKg)    (NO ÷2, already per-cable)
 *
 * These assertions keep the mobile side honest so the portal's ×2 display multiplier
 * produces correct "total weight lifted" numbers.
 */
class PortalMappingsWeightTest {

    private fun sessionWithReps(
        weightPerCableKg: Float = 0f,
        totalReps: Int = 0,
        totalVolumeKg: Float? = null,
        cableCount: Int? = null,
    ): PortalSyncAdapter.SessionWithReps {
        val ws = WorkoutSession(
            id = "sess-1",
            timestamp = 1_000L,
            mode = "OldSchool",
            reps = 10,
            weightPerCableKg = weightPerCableKg,
            totalReps = totalReps,
            exerciseId = "ex-1",
            exerciseName = "Squat",
            totalVolumeKg = totalVolumeKg,
            cableCount = cableCount,
            profileId = "default",
        )
        return PortalSyncAdapter.SessionWithReps(session = ws, muscleGroup = "Legs")
    }

    // ==================== Push Side: weightKg ====================

    @Test
    fun pushSetWeightKgIsPerCableUnchanged() {
        // Mobile session says 50 kg/cable → wire must carry 50 kg/cable.
        val swr = sessionWithReps(weightPerCableKg = 50f, totalReps = 10)
        val sessions = PortalSyncAdapter.toPortalWorkoutSessions(listOf(swr), "user-1")

        assertEquals(1, sessions.size)
        val weight = sessions[0].exercises[0].sets[0].weightKg
        assertEquals(
            50f,
            weight,
            "PortalSetDto.weightKg must equal session.weightPerCableKg (NO ×2 mobile-side)",
        )
    }

    @Test
    fun pushSetWeightKgAtMaxRangeBoundaryIsPerCable() {
        // 110 kg/cable = 220 kg total for a dual-cable Vitruvian Trainer+ max.
        val swr = sessionWithReps(weightPerCableKg = 110f, totalReps = 5)
        val sessions = PortalSyncAdapter.toPortalWorkoutSessions(listOf(swr), "user-1")
        assertEquals(110f, sessions[0].exercises[0].sets[0].weightKg)
    }

    // ==================== Push Side: totalVolume with cableCount ====================

    @Test
    fun pushDividesMeasuredTotalVolumeByCableCount() {
        // Measured total volume = 100 kg (across both cables). cableCount = 2 → per-cable = 50.
        val swr = sessionWithReps(
            weightPerCableKg = 25f,
            totalReps = 10,
            totalVolumeKg = 100f,
            cableCount = 2,
        )
        val sessions = PortalSyncAdapter.toPortalWorkoutSessions(listOf(swr), "user-1")
        assertEquals(
            50f,
            sessions[0].totalVolume,
            "Measured totalVolumeKg is TOTAL across cables → divide by cableCount=2 → per-cable",
        )
    }

    @Test
    fun pushSingleCableSessionLeavesTotalVolumeUnchanged() {
        // cableCount=1 → no division; totalVolumeKg passes through as-is.
        val swr = sessionWithReps(
            weightPerCableKg = 25f,
            totalReps = 10,
            totalVolumeKg = 100f,
            cableCount = 1,
        )
        val sessions = PortalSyncAdapter.toPortalWorkoutSessions(listOf(swr), "user-1")
        assertEquals(
            100f,
            sessions[0].totalVolume,
            "cableCount=1 means totalVolumeKg already equals per-cable volume (no division)",
        )
    }

    @Test
    fun pushNullCableCountDefaultsToSingleCable() {
        // cableCount=null → coerceAtLeast(1) = 1 cable → no division.
        val swr = sessionWithReps(
            weightPerCableKg = 25f,
            totalReps = 10,
            totalVolumeKg = 100f,
            cableCount = null,
        )
        val sessions = PortalSyncAdapter.toPortalWorkoutSessions(listOf(swr), "user-1")
        assertEquals(
            100f,
            sessions[0].totalVolume,
            "Null cableCount is treated as 1 cable (legacy defensive default)",
        )
    }

    @Test
    fun pushZeroCableCountIsCoercedToOneAndDoesNotCrash() {
        // Defensive: cableCount=0 must not cause a /0 crash — production code uses coerceAtLeast(1).
        val swr = sessionWithReps(
            weightPerCableKg = 25f,
            totalReps = 10,
            totalVolumeKg = 100f,
            cableCount = 0,
        )
        val sessions = PortalSyncAdapter.toPortalWorkoutSessions(listOf(swr), "user-1")
        assertEquals(
            100f,
            sessions[0].totalVolume,
            "cableCount=0 must NOT divide by zero; coerceAtLeast(1) treats it as single cable",
        )
    }

    // ==================== Push Side: totalVolume fallback (null totalVolumeKg) ====================

    @Test
    fun pushFallsBackToWeightTimesRepsWhenTotalVolumeIsNull() {
        // No measured volume: fallback = weightPerCableKg × totalReps (already per-cable).
        val swr = sessionWithReps(
            weightPerCableKg = 20f,
            totalReps = 8,
            totalVolumeKg = null,
            cableCount = 2,
        )
        val sessions = PortalSyncAdapter.toPortalWorkoutSessions(listOf(swr), "user-1")
        assertEquals(
            160f,
            sessions[0].totalVolume,
            "Fallback: weightPerCableKg (20) × totalReps (8) = 160 per-cable kg",
        )
    }

    @Test
    fun pushFallbackIgnoresCableCount() {
        // The fallback is already per-cable; we must NOT double-count by multiplying again.
        val withCable2 = sessionWithReps(
            weightPerCableKg = 20f,
            totalReps = 8,
            totalVolumeKg = null,
            cableCount = 2,
        )
        val withCable1 = sessionWithReps(
            weightPerCableKg = 20f,
            totalReps = 8,
            totalVolumeKg = null,
            cableCount = 1,
        )
        val a = PortalSyncAdapter.toPortalWorkoutSessions(listOf(withCable2), "u")
        val b = PortalSyncAdapter.toPortalWorkoutSessions(listOf(withCable1), "u")
        assertEquals(
            a[0].totalVolume,
            b[0].totalVolume,
            "Fallback branch uses weightPerCableKg × totalReps — cableCount must NOT multiply",
        )
    }

    // ==================== Pull Side: weightKg reading ====================

    @Test
    fun pullWorkoutSessionWeightIsPerCableFromWire() {
        // Server sends per-cable 50 → local WorkoutSession.weightPerCableKg = 50.
        val serverPayload = PullWorkoutSessionDto(
            id = "sess-X",
            userId = "user-1",
            startedAt = "2026-01-01T00:00:00Z",
            durationSeconds = 60,
            exerciseCount = 1,
            exercises = listOf(
                PullExerciseDto(
                    id = "ex-X",
                    sessionId = "sess-X",
                    name = "Squat",
                    muscleGroup = "Legs",
                    sets = listOf(
                        PullSetDto(
                            id = "set-X",
                            exerciseId = "ex-X",
                            setNumber = 1,
                            actualReps = 5,
                            weightKg = 50f, // per-cable
                        ),
                    ),
                ),
            ),
        )

        val local = PortalPullAdapter.toWorkoutSessions(serverPayload, profileId = "default")

        assertEquals(1, local.size, "one exercise → one WorkoutSession row")
        assertEquals(
            50f,
            local[0].weightPerCableKg,
            "Pull: weightPerCableKg = PullSetDto.weightKg directly (NO ÷2)",
        )
    }

    @Test
    fun pullUsesMaxWeightAcrossSetsAsSessionWeight() {
        // Multiple sets with different weights → session.weightPerCableKg = max.
        val payload = PullWorkoutSessionDto(
            id = "s",
            userId = "u",
            startedAt = "2026-01-01T00:00:00Z",
            exerciseCount = 1,
            exercises = listOf(
                PullExerciseDto(
                    id = "ex",
                    sessionId = "s",
                    name = "Bench",
                    sets = listOf(
                        PullSetDto(id = "1", exerciseId = "ex", setNumber = 1, weightKg = 40f, actualReps = 5),
                        PullSetDto(id = "2", exerciseId = "ex", setNumber = 2, weightKg = 60f, actualReps = 3),
                        PullSetDto(id = "3", exerciseId = "ex", setNumber = 3, weightKg = 55f, actualReps = 4),
                    ),
                ),
            ),
        )

        val local = PortalPullAdapter.toWorkoutSessions(payload, profileId = "default")

        assertEquals(60f, local[0].weightPerCableKg, "heaviest set per-cable drives the session weight")
        assertEquals(60f, local[0].heaviestLiftKg, "heaviestLiftKg tracks the same value")
    }

    // ==================== Round-Trip Symmetry ====================

    @Test
    fun perCableWeightSurvivesPushAndPullRoundTrip() {
        // Push 50 kg/cable from mobile, simulate portal echoing the value back, expect 50.
        val pushed = PortalSyncAdapter.toPortalWorkoutSessions(
            listOf(sessionWithReps(weightPerCableKg = 50f, totalReps = 5)),
            "user-1",
        )
        val pushedWeight = pushed[0].exercises[0].sets[0].weightKg

        // Simulate portal response reflecting the same payload back through the pull DTO.
        val pullPayload = PullWorkoutSessionDto(
            id = "roundtrip",
            userId = "user-1",
            startedAt = "2026-01-01T00:00:00Z",
            exerciseCount = 1,
            exercises = listOf(
                PullExerciseDto(
                    id = "ex",
                    sessionId = "roundtrip",
                    name = "Squat",
                    sets = listOf(
                        PullSetDto(id = "s", exerciseId = "ex", setNumber = 1, weightKg = pushedWeight, actualReps = 5),
                    ),
                ),
            ),
        )
        val pulled = PortalPullAdapter.toWorkoutSessions(pullPayload, profileId = "default")

        assertEquals(
            50f,
            pulled[0].weightPerCableKg,
            "End-to-end round trip (push → portal store → pull) must preserve the per-cable value exactly",
        )
    }

    @Test
    fun portalUiWouldDoubleThisForDisplay() {
        // Sanity guard: document that the portal's ×2 multiplier (audit 02) applies on TOP
        // of what we send. If we accidentally ×2 on mobile, the portal would show ×4.
        // This test is a tripwire assertion that the mobile-sent value is ≤ 110 kg for the
        // max-per-cable case, i.e., not already multiplied.
        val swr = sessionWithReps(weightPerCableKg = 110f, totalReps = 1)
        val sent = PortalSyncAdapter.toPortalWorkoutSessions(listOf(swr), "u")[0]
            .exercises[0].sets[0].weightKg
        assertTrue(
            sent <= 110f,
            "Mobile must send per-cable kg; portal's ×2 would turn $sent into ${sent * 2} for display",
        )
    }
}
