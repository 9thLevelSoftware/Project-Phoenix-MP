package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.VitruvianModel

/**
 * Utility for detecting Vitruvian hardware model from device name.
 */
object HardwareDetection {

    /**
     * Detect the model based on the advertised device name.
     * - "Vee_" prefix -> V-Form Trainer
     * - "VIT" prefix -> Trainer+
     */
    fun detectModel(deviceName: String): VitruvianModel {
        return when {
            deviceName.startsWith("Vee_", ignoreCase = true) -> VitruvianModel.VFormTrainer
            deviceName.startsWith("VIT", ignoreCase = true) -> VitruvianModel.TrainerPlus
            else -> VitruvianModel.Unknown
        }
    }
}
