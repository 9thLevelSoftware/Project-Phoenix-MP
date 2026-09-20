package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.PhoenixModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * KD-9: the one place that bounds a machine command. [CommandLimits.resolve] clamps at
 * command-resolution time so a stored routine, a portal pull, a backup or a CSV import
 * still starts — it just starts bounded, and the user is told.
 */
class CommandLimitsTest {

    @Test
    fun `per-cable ceiling is the connected model's and unknown fails closed`() {
        assertEquals(100f, CommandLimits.maxWeightPerCableKg(PhoenixModel.VFormTrainer))
        assertEquals(110f, CommandLimits.maxWeightPerCableKg(PhoenixModel.TrainerPlus))
        assertEquals(100f, CommandLimits.maxWeightPerCableKg(PhoenixModel.Unknown))
        assertEquals(100f, CommandLimits.maxWeightPerCableKg(null))
    }

    @Test
    fun `planning ceiling opens up when the model is unknown`() {
        // The editor is used offline: a Trainer+ owner must be able to plan 110 before
        // ever connecting. A known V-Form is still held to its own ceiling.
        assertEquals(110f, CommandLimits.planningMaxWeightPerCableKg(null))
        assertEquals(110f, CommandLimits.planningMaxWeightPerCableKg(PhoenixModel.Unknown))
        assertEquals(110f, CommandLimits.planningMaxWeightPerCableKg(PhoenixModel.TrainerPlus))
        assertEquals(100f, CommandLimits.planningMaxWeightPerCableKg(PhoenixModel.VFormTrainer))
    }

    @Test
    fun `progression is clamped to plus or minus 3 kg per rep`() {
        val capped = CommandLimits.resolve(40f, 5f, PhoenixModel.TrainerPlus)
        assertEquals(3f, capped.progressionKg)
        assertTrue(capped.progressionCapped)
        assertFalse(capped.weightCapped)

        assertEquals(-3f, CommandLimits.resolve(40f, -50f, PhoenixModel.TrainerPlus).progressionKg)

        val untouched = CommandLimits.resolve(40f, 2.5f, PhoenixModel.TrainerPlus)
        assertEquals(2.5f, untouched.progressionKg)
        assertFalse(untouched.cappedAnything)
    }

    @Test
    fun `weight is clamped to the connected model's ceiling`() {
        val vForm = CommandLimits.resolve(105f, 0f, PhoenixModel.VFormTrainer)
        assertEquals(100f, vForm.weightPerCableKg)
        assertTrue(vForm.weightCapped)

        val trainerPlus = CommandLimits.resolve(105f, 0f, PhoenixModel.TrainerPlus)
        assertEquals(105f, trainerPlus.weightPerCableKg)
        assertFalse(trainerPlus.weightCapped)

        val unknown = CommandLimits.resolve(105f, 0f, PhoenixModel.Unknown)
        assertEquals(100f, unknown.weightPerCableKg)
        assertTrue(unknown.weightCapped)
    }

    @Test
    fun `a lb weight that rounds just past the ceiling is accepted without a capped notice`() {
        // 220.5 lb per cable is 100.017 kg. Reporting that as "capped" would show the
        // notice on every set for a lb user training at the machine maximum.
        val lbAtCeiling = UnitConverter.lbToKg(220.5f)
        assertTrue(lbAtCeiling > CommandLimits.V_FORM_MAX_WEIGHT_PER_CABLE_KG)

        val resolved = CommandLimits.resolve(lbAtCeiling, 0f, PhoenixModel.VFormTrainer)
        assertFalse(resolved.weightCapped)
        assertEquals(100f, resolved.weightPerCableKg)
        assertTrue(
            WorkoutCommandValidator.validateLegacyWorkoutCommand(
                programMode = com.devil.phoenixproject.domain.model.ProgramMode.OldSchool,
                weightPerCableKg = lbAtCeiling,
                targetReps = 8,
                maxWeightPerCableKg = CommandLimits.V_FORM_MAX_WEIGHT_PER_CABLE_KG,
            ).isSuccess,
        )
    }

    @Test
    fun `non-finite values are left for the validator to reject not silently invented`() {
        val nan = CommandLimits.resolve(Float.NaN, Float.NaN, PhoenixModel.TrainerPlus)
        assertTrue(nan.weightPerCableKg.isNaN())
        assertTrue(nan.progressionKg.isNaN())
        assertFalse(nan.cappedAnything)

        val infinite = CommandLimits.resolve(
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            PhoenixModel.TrainerPlus,
        )
        assertEquals(Float.POSITIVE_INFINITY, infinite.weightPerCableKg)
        assertEquals(Float.NEGATIVE_INFINITY, infinite.progressionKg)
        assertFalse(infinite.cappedAnything)
    }
}
