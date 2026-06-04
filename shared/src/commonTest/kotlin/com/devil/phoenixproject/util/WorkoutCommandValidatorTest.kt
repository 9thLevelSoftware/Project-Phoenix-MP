package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutParameters
import kotlin.test.Test
import kotlin.test.assertTrue

class WorkoutCommandValidatorTest {

    @Test
    fun `program params accept normal finite bounded command`() {
        val result = WorkoutCommandValidator.validateProgramParams(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                weightPerCableKg = 12.5f,
                warmupReps = 3,
            ),
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `program params reject non-finite and out-of-range weights`() {
        assertFailureContains(
            WorkoutCommandValidator.validateProgramParams(
                WorkoutParameters(ProgramMode.OldSchool, reps = 8, weightPerCableKg = Float.NaN),
            ),
            "finite",
        )
        assertFailureContains(
            WorkoutCommandValidator.validateProgramParams(
                WorkoutParameters(ProgramMode.OldSchool, reps = 8, weightPerCableKg = 111f),
            ),
            "weightPerCableKg",
        )
    }

    @Test
    fun `just lift requires minimum nonzero weight`() {
        assertFailureContains(
            WorkoutCommandValidator.validateProgramParams(
                WorkoutParameters(
                    programMode = ProgramMode.OldSchool,
                    reps = 1,
                    weightPerCableKg = 0.5f,
                    isJustLift = true,
                ),
            ),
            "Just Lift",
        )
    }

    @Test
    fun `amrap allows zero target reps but finite bounded weight still applies`() {
        val result = WorkoutCommandValidator.validateProgramParams(
            WorkoutParameters(
                programMode = ProgramMode.Pump,
                reps = 0,
                weightPerCableKg = 0f,
                isAMRAP = true,
            ),
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `rep and warmup bytes must fit one byte`() {
        assertFailureContains(
            WorkoutCommandValidator.validateProgramParams(
                WorkoutParameters(
                    programMode = ProgramMode.Pump,
                    reps = 253,
                    warmupReps = 3,
                    weightPerCableKg = 10f,
                ),
            ),
            "fit in one byte",
        )
        assertFailureContains(
            WorkoutCommandValidator.validateEchoControl(
                level = EchoLevel.HARD,
                warmupReps = 256,
                targetReps = 8,
                isJustLift = false,
                isAMRAP = false,
                eccentricPct = 100,
            ),
            "warmupReps",
        )
    }

    @Test
    fun `echo eccentric percent must stay within machine range`() {
        assertFailureContains(
            WorkoutCommandValidator.validateEchoControl(
                level = EchoLevel.EPIC,
                warmupReps = 3,
                targetReps = 8,
                isJustLift = false,
                isAMRAP = false,
                eccentricPct = 151,
            ),
            "eccentricPct",
        )
    }

    @Test
    fun `color scheme brightness must be finite normalized value`() {
        assertFailureContains(
            WorkoutCommandValidator.validateColorScheme(
                brightness = Float.POSITIVE_INFINITY,
                colors = validColors(),
            ),
            "finite",
        )
        assertFailureContains(
            WorkoutCommandValidator.validateColorScheme(
                brightness = 1.01f,
                colors = validColors(),
            ),
            "0.0..1.0",
        )
    }

    private fun validColors(): List<RGBColor> = listOf(
        RGBColor(255, 0, 0),
        RGBColor(0, 255, 0),
        RGBColor(0, 0, 255),
    )

    private fun assertFailureContains(result: Result<Unit>, expectedMessage: String) {
        assertTrue(result.isFailure, "Expected validation failure")
        assertTrue(
            result.exceptionOrNull()?.message.orEmpty().contains(expectedMessage),
            "Expected failure message to contain '$expectedMessage', got '${result.exceptionOrNull()?.message}'",
        )
    }
}
