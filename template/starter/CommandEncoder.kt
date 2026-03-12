package com.phoenix.vendor.template

/**
 * Converts app-level intent into BLE or transport payloads.
 */
interface CommandEncoder {
    fun startSession(profile: MachineProfile): ByteArray
    fun setResistance(level: Int): ByteArray
    fun stopSession(): ByteArray
}
