package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.VitruvianModel

/**
 * Vitruvian Hardware Detection
 *
 * Previously attempted to identify hardware models (Euclid, Trainer+) from device name prefixes,
 * but this approach was flawed - device name patterns don't reliably indicate hardware capabilities.
 *
 * Current approach: Report only what we can actually detect (device name) and avoid making
 * assumptions about capabilities. True capability detection would require reading firmware
 * version from the device, which is not currently implemented.
 *
 * The VERSION BLE characteristic (UUID: 74e994ac-0e80-4c02-9cd0-76cb31d3959b) contains
 * hardware/firmware info but the parsing format is undocumented.
 */
object HardwareDetection {

    /**
     * Detect the model based on the advertised device name.
     * - "Vee_" prefix -> V-Form Trainer
     * - "VIT" prefix -> Trainer+
     */
    fun detectModel(deviceName: String): VitruvianModel = when {
        deviceName.startsWith("Vee_", ignoreCase = true) -> VitruvianModel.VFormTrainer
        deviceName.startsWith("VIT", ignoreCase = true) -> VitruvianModel.TrainerPlus
        else -> VitruvianModel.Unknown
    }

    /**
     * Get device display info without making capability assumptions
     */
    fun getDeviceDisplayInfo(deviceName: String): String = "Vitruvian Trainer ($deviceName)"

    /**
     * Get hardware capabilities - currently returns defaults since we can't
     * reliably detect hardware model from device name alone.
     *
     * All capabilities are assumed to be available until we can implement
     * proper firmware version detection.
     */
    fun getCapabilities(deviceName: String): HardwareCapabilities = HardwareCapabilities.DEFAULT
}

/**
 * Hardware capabilities for Vitruvian trainers
 *
 * Note: Without firmware version detection, we assume all features are available.
 * This is safer than incorrectly disabling features based on flawed model detection.
 */
data class HardwareCapabilities(val supportsEccentricMode: Boolean, val supportsEchoMode: Boolean, val maxResistanceKg: Float) {
    companion object {
        /**
         * Default capabilities - assume all features available
         * Conservative max resistance of 200kg (lowest known model)
         */
        val DEFAULT = HardwareCapabilities(
            supportsEccentricMode = true,
            supportsEchoMode = true,
            maxResistanceKg = 200f,
        )
    }
}
