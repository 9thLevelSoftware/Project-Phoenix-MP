package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Round-trip tests for enum parity between mobile and portal wire format.
 *
 *  - WorkoutPhase (COMBINED, CONCENTRIC, ECCENTRIC) must survive push → pull as strings
 *    equal to their enum name (no remapping needed per audit 02).
 *  - ProgramMode.toSyncString() ↔ ProgramMode.fromSyncString() must be total on every
 *    case of the sealed class.
 *  - PortalPullAdapter.toPersonalRecordSyncDto must preserve the workoutPhase passed in.
 */
class WorkoutPhaseRoundTripTest {

    // ==================== WorkoutPhase Enum ====================

    @Test
    fun workoutPhaseEnumHasExactlyThreeCases() {
        assertEquals(
            listOf("COMBINED", "CONCENTRIC", "ECCENTRIC"),
            WorkoutPhase.values().map { it.name },
            "WorkoutPhase must contain exactly COMBINED, CONCENTRIC, ECCENTRIC (per audit 02)",
        )
    }

    @Test
    fun workoutPhaseNameRoundTripsThroughValueOf() {
        for (phase in WorkoutPhase.values()) {
            val wire = phase.name
            val parsed = WorkoutPhase.valueOf(wire)
            assertEquals(phase, parsed, "Round trip ${phase.name} ↔ $wire must be lossless")
        }
    }

    // ==================== ProgramMode Round Trip ====================

    @Test
    fun allProgramModesRoundTripThroughSyncString() {
        val all = listOf(
            ProgramMode.OldSchool,
            ProgramMode.Pump,
            ProgramMode.TUT,
            ProgramMode.TUTBeast,
            ProgramMode.EccentricOnly,
            ProgramMode.Echo,
        )
        for (mode in all) {
            val wire = mode.toSyncString()
            val parsed = ProgramMode.fromSyncString(wire)
            assertNotNull(parsed, "fromSyncString should parse ${mode::class.simpleName}'s wire format '$wire'")
            assertEquals(mode, parsed, "Round trip via wire '$wire' must return the same ProgramMode")
        }
    }

    @Test
    fun toSyncStringProducesDocumentedStrings() {
        // Assert the exact wire strings that both portal and mobile agree on.
        assertEquals("OLD_SCHOOL", ProgramMode.OldSchool.toSyncString())
        assertEquals("PUMP", ProgramMode.Pump.toSyncString())
        assertEquals("TUT", ProgramMode.TUT.toSyncString())
        assertEquals("TUT_BEAST", ProgramMode.TUTBeast.toSyncString())
        assertEquals("ECCENTRIC_ONLY", ProgramMode.EccentricOnly.toSyncString())
        assertEquals("ECHO", ProgramMode.Echo.toSyncString())
    }

    @Test
    fun fromSyncStringAcceptsLegacyClassicAlias() {
        // Portal has legacy data using CLASSIC; mobile must still parse it.
        assertEquals(ProgramMode.OldSchool, ProgramMode.fromSyncString("CLASSIC"))
    }

    @Test
    fun fromSyncStringRejectsUnknown() {
        assertEquals(null, ProgramMode.fromSyncString("NEW_FANCY_MODE"))
    }

    // ==================== PR DTO prPhase Round Trip ====================

    @Test
    fun pullPersonalRecordConcentricPhaseIsPreserved() {
        val pulled = PullPersonalRecordDto(
            id = "pr-1",
            userId = "user-x",
            exerciseName = "Squat",
            muscleGroup = "Legs",
            recordType = "MAX_WEIGHT",
            value = 120.0,
            weightKg = 120.0,
            reps = 1,
            workoutPhase = "CONCENTRIC",
            sessionId = "sess-1",
            achievedAt = "2026-01-01T12:00:00Z",
            updatedAt = "2026-01-01T12:00:00Z",
        )

        val merged = PortalPullAdapter.toPersonalRecordSyncDto(pulled)

        assertEquals("CONCENTRIC", merged.phase, "CONCENTRIC phase must round-trip through merge DTO")
    }

    @Test
    fun pullPersonalRecordEccentricPhaseIsPreserved() {
        val pulled = PullPersonalRecordDto(
            id = "pr-2",
            userId = "user-x",
            exerciseName = "Squat",
            muscleGroup = "Legs",
            recordType = "MAX_WEIGHT",
            value = 120.0,
            weightKg = 120.0,
            reps = 1,
            workoutPhase = "ECCENTRIC",
            sessionId = "sess-2",
            achievedAt = "2026-01-01T12:00:00Z",
            updatedAt = "2026-01-01T12:00:00Z",
        )

        val merged = PortalPullAdapter.toPersonalRecordSyncDto(pulled)

        assertEquals("ECCENTRIC", merged.phase)
    }

    @Test
    fun pullPersonalRecordCombinedPhaseIsPreserved() {
        val pulled = PullPersonalRecordDto(
            id = "pr-3",
            userId = "user-x",
            exerciseName = "Squat",
            muscleGroup = "Legs",
            recordType = "MAX_WEIGHT",
            value = 120.0,
            weightKg = 120.0,
            reps = 1,
            workoutPhase = "COMBINED",
            sessionId = "sess-3",
            achievedAt = "2026-01-01T12:00:00Z",
            updatedAt = "2026-01-01T12:00:00Z",
        )

        val merged = PortalPullAdapter.toPersonalRecordSyncDto(pulled)

        assertEquals("COMBINED", merged.phase)
    }

    @Test
    fun pullPersonalRecordNullPhaseDefaultsToCombined() {
        val pulled = PullPersonalRecordDto(
            id = "pr-4",
            userId = "user-x",
            exerciseName = "Squat",
            muscleGroup = "Legs",
            recordType = "MAX_WEIGHT",
            value = 120.0,
            weightKg = 120.0,
            reps = 1,
            workoutPhase = null,
            sessionId = "sess-4",
            achievedAt = "2026-01-01T12:00:00Z",
            updatedAt = "2026-01-01T12:00:00Z",
        )

        val merged = PortalPullAdapter.toPersonalRecordSyncDto(pulled)

        assertEquals(
            "COMBINED",
            merged.phase,
            "Null/missing phase on the wire must default to COMBINED (backward compat)",
        )
    }

    @Test
    fun pushSideWorkoutPhaseWireStringsMatchEnumNames() {
        // The push adapter writes pr?.phase?.name directly to PortalSetDto.prPhase
        // (PortalSyncAdapter.kt line 254). Verify enum names are what the audit documents.
        assertEquals("COMBINED", WorkoutPhase.COMBINED.name)
        assertEquals("CONCENTRIC", WorkoutPhase.CONCENTRIC.name)
        assertEquals("ECCENTRIC", WorkoutPhase.ECCENTRIC.name)
    }

    @Test
    fun portalSetDtoAcceptsAllThreeWorkoutPhaseStrings() {
        // Smoke-test that the DTO can hold every valid wire string without custom validation.
        val combined = PortalSetDto(
            id = "s1",
            exerciseId = "e1",
            setNumber = 1,
            prPhase = "COMBINED",
            weightKg = 50f,
        )
        val concentric = combined.copy(prPhase = "CONCENTRIC")
        val eccentric = combined.copy(prPhase = "ECCENTRIC")

        assertEquals("COMBINED", combined.prPhase)
        assertEquals("CONCENTRIC", concentric.prPhase)
        assertEquals("ECCENTRIC", eccentric.prPhase)
    }
}
