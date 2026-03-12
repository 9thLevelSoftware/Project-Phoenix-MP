package com.phoenix.vendor.template

/**
 * Entry point for a machine vendor integration.
 */
interface VendorPlugin {
    val id: String
    val displayName: String
    val supportedProfiles: Set<MachineProfile>

    fun encoder(profile: MachineProfile): CommandEncoder
    fun decoder(profile: MachineProfile): TelemetryDecoder
}
