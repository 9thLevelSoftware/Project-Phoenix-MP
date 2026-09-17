package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.ConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Adapter used only by the explicit safety recovery path; it never selects an arbitrary trainer. */
class BleRepositoryMachineSafetyTransport(
    private val bleRepository: BleRepository,
    private val scanTimeoutMs: Long = 30_000L,
) : com.devil.phoenixproject.presentation.manager.MachineSafetyTransport {
    override val connectedTrainerAddress: String?
        get() = (bleRepository.connectionState.value as? ConnectionState.Connected)?.deviceAddress

    override suspend fun connectMatchingTrainer(trainerAddress: String): Result<Unit> {
        if (trainerAddress.isBlank()) return Result.failure(IllegalArgumentException("trainer address is blank"))
        bleRepository.startScanning().getOrElse { return Result.failure(it) }
        return try {
            val found = withTimeoutOrNull<ScannedDevice>(scanTimeoutMs) {
                while (true) {
                    val matchingDevice = bleRepository.scannedDevices.value.firstOrNull { it.address == trainerAddress }
                    if (matchingDevice != null) return@withTimeoutOrNull matchingDevice
                    delay(50L)
                }
            }
            found?.let { bleRepository.connect(it) }
                ?: Result.failure(IllegalStateException("matching trainer not found"))
        } finally {
            bleRepository.stopScanning()
        }
    }

    override suspend fun stopWorkout(): Result<Unit> = bleRepository.stopWorkout()
}
