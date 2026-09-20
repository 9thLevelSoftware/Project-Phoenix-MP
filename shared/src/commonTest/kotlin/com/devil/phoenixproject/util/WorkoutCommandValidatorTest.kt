package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.PhoenixModel
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutParameters
import kotlin.test.Test
import kotlin.test.assertTrue

class WorkoutCommandValidatorTest {

    /**
     * The validator takes the CONNECTED model's per-cable ceiling (KD-9). Cases that are
     * not about the ceiling use the absolute hardware maximum, which is what a builder
     * that cannot know the model passes.
     */
    private fun validateProgram(
        params: WorkoutParameters,
        maxWeightPerCableKg: Float = Constants.MAX_WEIGHT_PER_CABLE_KG,
    ) = WorkoutCommandValidator.validateProgramParams(params, maxWeightPerCableKg)

    @Test
    fun `program params accept normal finite bounded command`() {
        val result = validateProgram(
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
            validateProgram(
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
            ).isSuccess,
        )
    }

    @Test
    fun `program params reject non-finite and out-of-range weights`() {
        assertFailureContains(
            validateProgram(
                WorkoutParameters(ProgramMode.OldSchool, reps = 8, weightPerCableKg = Float.NaN),
            ),
            "finite",
        )
        assertFailureContains(
            validateProgram(
                WorkoutParameters(ProgramMode.OldSchool, reps = 8, weightPerCableKg = 111f),
            ),
            "weightPerCableKg",
        )
        assertFailureContains(
            validateProgram(
                WorkoutParameters(ProgramMode.OldSchool, reps = 8, weightPerCableKg = 0f),
            ),
            "greater than",
        )
    }

    @Test
    fun `just lift requires minimum nonzero weight`() {
        assertFailureContains(
            validateProgram(
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
        val result = validateProgram(
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
            validateProgram(
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
            validateProgram(
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
            validateProgram(
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

    // ===== KD-9 backstop: per-model ceiling and per-rep progression =====

    @Test
    fun `program params reject a weight above the connected model ceiling`() {
        // F-009: the same 105kg/cable command is legal on a Trainer+ and illegal on a V-Form.
        val params = WorkoutParameters(ProgramMode.OldSchool, reps = 8, weightPerCableKg = 105f)
        assertFailureContains(
            validateProgram(params, CommandLimits.V_FORM_MAX_WEIGHT_PER_CABLE_KG),
            "weightPerCableKg",
        )
        assertTrue(validateProgram(params, CommandLimits.TRAINER_PLUS_MAX_WEIGHT_PER_CABLE_KG).isSuccess)

        // Unknown fails closed to the lowest known ceiling.
        assertFailureContains(
            validateProgram(params, CommandLimits.maxWeightPerCableKg(PhoenixModel.Unknown)),
            "weightPerCableKg",
        )
    }

    @Test
    fun `program params tolerate float and lb rounding at the ceiling but not real overshoot`() {
        fun atWeight(kg: Float) = WorkoutParameters(ProgramMode.OldSchool, reps = 8, weightPerCableKg = kg)

        // Within the 0.05kg tolerance (220.5 lb is 100.017 kg on a 100kg cable).
        assertTrue(validateProgram(atWeight(100.04f), CommandLimits.V_FORM_MAX_WEIGHT_PER_CABLE_KG).isSuccess)
        assertFailureContains(
            validateProgram(atWeight(100.1f), CommandLimits.V_FORM_MAX_WEIGHT_PER_CABLE_KG),
            "weightPerCableKg",
        )
        assertTrue(validateProgram(atWeight(110.04f), CommandLimits.TRAINER_PLUS_MAX_WEIGHT_PER_CABLE_KG).isSuccess)
        assertFailureContains(
            validateProgram(atWeight(110.1f), CommandLimits.TRAINER_PLUS_MAX_WEIGHT_PER_CABLE_KG),
            "weightPerCableKg",
        )
    }

    @Test
    fun `program params bound per-rep progression`() {
        // F-020/F-044: previously only checked for finiteness, so a crafted backup or a
        // CSV import could command +50kg per rep.
        fun atProgression(kg: Float) = WorkoutParameters(
            ProgramMode.OldSchool,
            reps = 8,
            weightPerCableKg = 40f,
            progressionRegressionKg = kg,
        )

        for (bounded in listOf(0f, 3f, -3f, 1.5f)) {
            assertTrue(validateProgram(atProgression(bounded)).isSuccess, "progression $bounded")
        }
        for (rejected in listOf(3.1f, -3.1f, 50f, -50f)) {
            assertFailureContains(validateProgram(atProgression(rejected)), "progressionRegressionKg")
        }
        assertFailureContains(validateProgram(atProgression(Float.NaN)), "finite")
        assertFailureContains(validateProgram(atProgression(Float.POSITIVE_INFINITY)), "finite")
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
