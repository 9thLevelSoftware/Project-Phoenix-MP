package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.PhoenixModel

/**
 * Trainer hardware detection from the advertised BLE name.
 *
 * This prefix map **is** the chassis-limit signal used by [ChassisLimits]:
 * - `Vee_` → V-Form (100 kg/cable)
 * - `VIT` → Trainer+ (110 kg/cable)
 * - anything else, including empty/disconnected → [PhoenixModel.Unknown] fail-closed 100 kg/cable
 *
 * Firmware VERSION is still unused. Do not "fix" unknown names to Trainer+ 110.
 */
object HardwareDetection {

    /**
     * Detect the model based on the advertised device name.
     * - "Vee_" prefix -> V-Form Trainer
     * - "VIT" prefix -> Trainer+
     */
    fun detectModel(deviceName: String): PhoenixModel = when {
        deviceName.startsWith("Vee_", ignoreCase = true) -> PhoenixModel.VFormTrainer
        deviceName.startsWith("VIT", ignoreCase = true) -> PhoenixModel.TrainerPlus
        else -> PhoenixModel.Unknown
    }

    /**
     * Get device display info without making extra capability assumptions
     */
    fun getDeviceDisplayInfo(deviceName: String): String = "Trainer ($deviceName)"

    /**
     * Per-cable chassis capabilities derived from [detectModel].
     * [HardwareCapabilities.maxResistanceKg] is kg **per cable**, not total.
     */
    fun getCapabilities(deviceName: String): HardwareCapabilities {
        val model = detectModel(deviceName)
        return HardwareCapabilities(
            supportsEccentricMode = true,
            supportsEchoMode = true,
            maxResistanceKg = ChassisLimits.maxKgPerCable(model),
        )
    }
}

/**
 * Hardware capabilities for supported trainers.
 *
 * [maxResistanceKg] is the per-cable chassis ceiling from [ChassisLimits].
 */
data class HardwareCapabilities(val supportsEccentricMode: Boolean, val supportsEchoMode: Boolean, val maxResistanceKg: Float) {
    companion object {
        val DEFAULT = HardwareCapabilities(
            supportsEccentricMode = true,
            supportsEchoMode = true,
            maxResistanceKg = ChassisLimits.UNKNOWN_FAIL_CLOSED_KG_PER_CABLE,
        )
    }
}
