package com.phoenix.vendor.template

/**
 * Declares machine capabilities exposed by a vendor.
 */
data class MachineProfile(
    val model: String,
    val protocolVersion: Int,
    val supportsEccentricControl: Boolean,
    val supportsLiveTelemetry: Boolean,
    val maxResistance: Int
)
