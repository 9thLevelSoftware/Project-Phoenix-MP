package com.devil.phoenixproject.domain.model

import com.devil.phoenixproject.adapter.vitruvian.VitruvianWorkoutIntentMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkoutIntentTest {

    @Test
    fun validatorRejectsUnsupportedTempo() {
        val capability = ProtocolCapabilityDescriptor(
            supportedStrengthProfiles = setOf(StrengthProfile.STANDARD),
            supportedTempoProfiles = setOf(TempoProfile.NORMAL),
            supportsAssistedMode = false,
            supportsEccentricBias = false,
            supportsRepTarget = true,
            supportsTimeTarget = false,
            supportsRomConstraints = false
        )

        val errors = WorkoutIntentValidator.validate(
            WorkoutIntent(tempo = TempoProfile.EXPLOSIVE),
            capability
        )

        assertTrue(errors.any { it.contains("tempo", ignoreCase = true) })
    }

    @Test
    fun mapperMapsPumpToProgramMode() {
        val intent = WorkoutIntent(
            strengthProfile = StrengthProfile.PUMP,
            target = WorkoutTarget.Repetitions(12)
        )

        val payload = VitruvianWorkoutIntentMapper.map(intent)

        assertEquals(ProgramMode.Pump, payload.programMode)
        assertEquals(12, payload.targetReps)
    }
}
