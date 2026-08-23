package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.PhoenixModel
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutParameters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkoutCommandValidatorTest {

    private fun validateParams(
        params: WorkoutParameters,
        model: PhoenixModel = PhoenixModel.Unknown,
    ) = WorkoutCommandValidator.validateProgramParams(params, model)

    @Test
    fun `program params accept normal finite bounded command`() {
        val result = validateParams(
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
    fun `normal workout commands allow fractional positive weight`() {
        assertTrue(
            validateParams(
                WorkoutParameters(
                    programMode = ProgramMode.OldSchool,
                    reps = 8,
                    weightPerCableKg = 0.5f,
                ),
            ).isSuccess,
        )
        assertTrue(
            WorkoutCommandValidator.validateLegacyWorkoutCommand(
                programMode = ProgramMode.OldSchool,
                weightPerCableKg = 0.5f,
                targetReps = 8,
                model = PhoenixModel.TrainerPlus,
            ).isSuccess,
        )
    }

    @Test
    fun `program params reject non-finite and out-of-range weights`() {
        assertFailureContains(
            validateParams(
                WorkoutParameters(ProgramMode.OldSchool, reps = 8, weightPerCableKg = Float.NaN),
            ),
            "finite",
        )
        assertFailureContains(
            validateParams(
                WorkoutParameters(ProgramMode.OldSchool, reps = 8, weightPerCableKg = 111f),
            ),
            "weightPerCableKg",
        )
        assertFailureContains(
            validateParams(
                WorkoutParameters(ProgramMode.OldSchool, reps = 8, weightPerCableKg = 0f),
            ),
            "greater than",
        )
    }

    @Test
    fun `V-Form rejects 100_5 kg and accepts 100 kg`() {
        assertTrue(
            validateParams(
                WorkoutParameters(ProgramMode.OldSchool, reps = 8, weightPerCableKg = 100f),
                model = PhoenixModel.VFormTrainer,
            ).isSuccess,
        )
        assertFailureContains(
            validateParams(
                WorkoutParameters(ProgramMode.OldSchool, reps = 8, weightPerCableKg = 100.5f),
                model = PhoenixModel.VFormTrainer,
            ),
            "100.5",
        )
        assertFailureContains(
            validateParams(
                WorkoutParameters(ProgramMode.OldSchool, reps = 8, weightPerCableKg = 110f),
                model = PhoenixModel.VFormTrainer,
            ),
            "weightPerCableKg",
        )
    }

    @Test
    fun `Trainer+ accepts 100_5 and 110 but rejects 111`() {
        assertTrue(
            validateParams(
                WorkoutParameters(ProgramMode.OldSchool, reps = 8, weightPerCableKg = 100.5f),
                model = PhoenixModel.TrainerPlus,
            ).isSuccess,
        )
        assertTrue(
            validateParams(
                WorkoutParameters(ProgramMode.OldSchool, reps = 8, weightPerCableKg = 110f),
                model = PhoenixModel.TrainerPlus,
            ).isSuccess,
        )
        assertFailureContains(
            validateParams(
                WorkoutParameters(ProgramMode.OldSchool, reps = 8, weightPerCableKg = 111f),
                model = PhoenixModel.TrainerPlus,
            ),
            "weightPerCableKg",
        )
    }

    @Test
    fun `unknown model fail-closes at 100 kg per cable`() {
        assertTrue(
            validateParams(
                WorkoutParameters(ProgramMode.OldSchool, reps = 8, weightPerCableKg = 100f),
                model = PhoenixModel.Unknown,
            ).isSuccess,
        )
        assertFailureContains(
            validateParams(
                WorkoutParameters(ProgramMode.OldSchool, reps = 8, weightPerCableKg = 100.5f),
                model = PhoenixModel.Unknown,
            ),
            "100.5",
        )
        assertEquals(
            ChassisLimits.V_FORM_KG_PER_CABLE,
            ChassisLimits.maxKgPerCable(PhoenixModel.Unknown),
        )
        assertEquals(
            ChassisLimits.V_FORM_KG_PER_CABLE,
            ChassisLimits.maxKgPerCable(PhoenixModel.VFormTrainer),
        )
        assertEquals(
            ChassisLimits.TRAINER_PLUS_KG_PER_CABLE,
            ChassisLimits.maxKgPerCable(PhoenixModel.TrainerPlus),
        )
        assertEquals(100f, HardwareDetection.getCapabilities("Phoenix").maxResistanceKg)
        assertEquals(100f, HardwareDetection.getCapabilities("Vee_Test").maxResistanceKg)
        assertEquals(110f, HardwareDetection.getCapabilities("VIT_Test").maxResistanceKg)
    }

    @Test
    fun `just lift requires minimum nonzero weight`() {
        assertFailureContains(
            validateParams(
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
        val result = validateParams(
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
            validateParams(
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
    fun `finite rep total of 254 is accepted but 255 collides with the unlimited sentinel F069 F070`() {
        // 0xFF (255) is the unlimited/Just Lift/AMRAP sentinel; a finite total of
        // 255 must be rejected so it cannot serialize to an unlimited workout.
        assertTrue(
            validateParams(
                WorkoutParameters(
                    programMode = ProgramMode.Pump,
                    reps = 251,
                    warmupReps = 3,
                    weightPerCableKg = 10f,
                ),
            ).isSuccess,
            "reps+warmup == 254 should be accepted",
        )
        assertFailureContains(
            validateParams(
                WorkoutParameters(
                    programMode = ProgramMode.Pump,
                    reps = 252,
                    warmupReps = 3,
                    weightPerCableKg = 10f,
                ),
            ),
            "fit in one byte",
        )
    }

    @Test
    fun `finite echo target of 254 is accepted but 255 is rejected F069 F070`() {
        assertTrue(
            WorkoutCommandValidator.validateEchoControl(
                level = EchoLevel.HARD,
                warmupReps = 0,
                targetReps = 254,
                isJustLift = false,
                isAMRAP = false,
                eccentricPct = 100,
            ).isSuccess,
            "targetReps == 254 should be accepted",
        )
        assertFailureContains(
            WorkoutCommandValidator.validateEchoControl(
                level = EchoLevel.HARD,
                warmupReps = 0,
                targetReps = 255,
                isJustLift = false,
                isAMRAP = false,
                eccentricPct = 100,
            ),
            "targetReps",
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
