package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.PhoenixModel

/**
 * Trainer hardware detection from the advertised device name.
 *
 * **This is safety-relevant.** Since KD-9 the detected model is the sole input to
 * [CommandLimits.maxWeightPerCableKg], which decides the per-cable ceiling every machine
 * command is bounded by. Do not remove or neutralise it without replacing the input.
 *
 * It is still a name heuristic, and the earlier note in this file was right that a name does
 * not prove a capability. What makes it acceptable as a bound:
 * - `Unknown` **fails closed** to the lowest known ceiling (100 kg/cable), so a name we do not
 *   recognise can never widen what may be commanded.
 * - The scan filter only admits `Vee_*` and `VIT*` names, so a connected device almost always
 *   resolves to one of the two models; `Unknown` is mostly the disconnected/teardown case.
 * - Firmware caps force and per-rep progression regardless (A-001/A-011); this bound is
 *   defense in depth, not the only thing standing between the user and an overload.
 *
 * Known residual risk: `VIT` maps to Trainer+ (110 kg/cable) while the V-Form's own model
 * designation is VIT-200. A V-Form that advertised a `VIT`-prefixed name would be granted the
 * wider ceiling. Observed V-Form units advertise `Vee_*`, so this is a caution rather than a
 * known defect. The opposite direction — a Trainer+ whose name matches neither prefix losing
 * 10 kg/cable of range and getting a capped notice — is the intended fail-closed behaviour.
 *
 * A firmware-version read (the VERSION characteristic,
 * `74e994ac-0e80-4c02-9cd0-76cb31d3959b`, format undocumented) or a user override would
 * replace the heuristic; neither is implemented.
 */
object HardwareDetection {

    /**
     * Detect the model from the advertised device name.
     * - `Vee_` prefix -> V-Form Trainer (100 kg/cable)
     * - `VIT` prefix  -> Trainer+ (110 kg/cable)
     * - anything else -> [PhoenixModel.Unknown], which fails closed to 100 kg/cable.
     */
    fun detectModel(deviceName: String): PhoenixModel = when {
        deviceName.startsWith("Vee_", ignoreCase = true) -> PhoenixModel.VFormTrainer
        deviceName.startsWith("VIT", ignoreCase = true) -> PhoenixModel.TrainerPlus
        else -> PhoenixModel.Unknown
    }

    /**
     * Device display info, deliberately without any capability claim.
     */
    fun getDeviceDisplayInfo(deviceName: String): String = "Trainer ($deviceName)"

    // getCapabilities()/HardwareCapabilities were deleted here: they returned a flat
    // maxResistanceKg = 200f for every device, had no callers, and became a second and
    // contradictory limit source once CommandLimits owned the per-cable ceiling.
}
