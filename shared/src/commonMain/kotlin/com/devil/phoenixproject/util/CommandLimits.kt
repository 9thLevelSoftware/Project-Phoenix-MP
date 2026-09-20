package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.PhoenixModel
import kotlin.math.abs

/**
 * The single source of truth for what may be commanded to a trainer.
 *
 * Two layers, in this order:
 *  1. [resolve] runs at command-resolution time (just before the frame is built) and
 *     **clamps**. Stored routines, portal pulls, backups and CSV imports are never
 *     rejected and never rewritten on disk — they simply start at the bounded value
 *     and the user is told what was capped.
 *  2. [WorkoutCommandValidator] rejects anything past the same bounds. It is the
 *     backstop and is only reachable by bypassing step 1.
 *
 * The firmware itself caps force and per-rep progression, so this is defense in depth.
 */
object CommandLimits {

    /** V-Form Trainer (VIT-200): 100 kg per cable, 200 kg total. */
    const val V_FORM_MAX_WEIGHT_PER_CABLE_KG = 100f

    /** Trainer+: 110 kg per cable, 220 kg total. */
    const val TRAINER_PLUS_MAX_WEIGHT_PER_CABLE_KG = 110f

    /** Absolute per-rep weight change the machine may be commanded to apply. */
    const val MAX_PROGRESSION_KG = 3.0f

    /**
     * Tolerance applied by the validator (and by the "was this capped?" flags) so a
     * weight that only exceeds the ceiling through float/lb rounding is not treated
     * as an out-of-range command. 220.5 lb is 100.017 kg on a 100 kg/cable trainer.
     */
    const val WEIGHT_TOLERANCE_KG = 0.05f

    /**
     * Per-cable ceiling for a command that is about to be sent.
     * Unknown (or not connected) fails closed to the lowest known ceiling.
     */
    fun maxWeightPerCableKg(model: PhoenixModel?): Float = when (model) {
        PhoenixModel.TrainerPlus -> TRAINER_PLUS_MAX_WEIGHT_PER_CABLE_KG
        PhoenixModel.VFormTrainer -> V_FORM_MAX_WEIGHT_PER_CABLE_KG
        PhoenixModel.Unknown, null -> V_FORM_MAX_WEIGHT_PER_CABLE_KG
    }

    /**
     * Per-cable ceiling for planning/editor UI, which is used offline.
     * Nothing is commanded from these screens, so an unknown model opens up to the
     * highest ceiling: a Trainer+ owner must be able to plan 110 kg/cable before ever
     * connecting. A V-Form owner who has connected once is held to 100.
     */
    fun planningMaxWeightPerCableKg(model: PhoenixModel?): Float = when (model) {
        PhoenixModel.VFormTrainer -> V_FORM_MAX_WEIGHT_PER_CABLE_KG
        else -> TRAINER_PLUS_MAX_WEIGHT_PER_CABLE_KG
    }

    /** The one per-rep progression bound. Replaces the former per-class copies. */
    fun clampProgressionKg(valueKg: Float): Float = valueKg.coerceIn(-MAX_PROGRESSION_KG, MAX_PROGRESSION_KG)

    /**
     * The result of bounding one command. [weightCapped]/[progressionCapped] report a
     * change the user should be told about; a sub-tolerance trim is not reported.
     */
    data class Resolution(
        val weightPerCableKg: Float,
        val progressionKg: Float,
        val maxWeightPerCableKg: Float,
        val weightCapped: Boolean,
        val progressionCapped: Boolean,
        /** False when the ceiling came from the fail-closed default, not a recognised model. */
        val modelKnown: Boolean,
    ) {
        val cappedAnything: Boolean get() = weightCapped || progressionCapped
    }

    /**
     * Bound one command's weight and per-rep progression.
     *
     * Non-finite values are left untouched: they are a bug, not a user intent, and the
     * validator rejects them rather than letting a clamp invent a plausible number.
     */
    fun resolve(weightKg: Float, progressionKg: Float, model: PhoenixModel?): Resolution {
        val ceiling = maxWeightPerCableKg(model)
        val resolvedWeight = if (weightKg.isFinite()) weightKg.coerceAtMost(ceiling) else weightKg
        val resolvedProgression = if (progressionKg.isFinite()) clampProgressionKg(progressionKg) else progressionKg
        return Resolution(
            weightPerCableKg = resolvedWeight,
            progressionKg = resolvedProgression,
            maxWeightPerCableKg = ceiling,
            weightCapped = weightKg.isFinite() && weightKg > ceiling + WEIGHT_TOLERANCE_KG,
            // Same tolerance as the weight: displayToKg divides by 2.20462f, so a legal
            // display-unit progression can land a hair past 3.0 and must not be announced.
            progressionCapped = progressionKg.isFinite() &&
                abs(progressionKg) > MAX_PROGRESSION_KG + WEIGHT_TOLERANCE_KG,
            modelKnown = model != null && model != PhoenixModel.Unknown,
        )
    }
}
