package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.WorkoutMetric
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for BLE device scanning and discovery.
 * Platform-specific implementations will handle actual BLE scanning.
 */
interface BleScanner {
    /**
     * Flow of discovered Vitruvian devices.
     */
    val discoveredDevices: Flow<VitruvianDevice>
    
    /**
     * Whether scanning is currently active.
     */
    val isScanning: StateFlow<Boolean>
    
    /**
     * Start scanning for Vitruvian devices.
     */
    suspend fun startScan()
    
    /**
     * Stop scanning for devices.
     */
    suspend fun stopScan()
}

/**
 * Interface for managing BLE connection to a Vitruvian device.
 * Platform-specific implementations will handle actual BLE connections.
 */
interface BleConnection {
    /**
     * Current connection state.
     */
    val connectionState: StateFlow<ConnectionState>
    
    /**
     * Flow of real-time workout metrics from the device.
     */
    val metrics: Flow<WorkoutMetric>
    
    /**
     * Connect to a Vitruvian device.
     * @param device The device to connect to.
     */
    suspend fun connect(device: VitruvianDevice)
    
    /**
     * Disconnect from the current device.
     */
    suspend fun disconnect()
    
    /**
     * Send a command to set the target weight.
     * @param weightKg Weight in kilograms.
     */
    suspend fun setWeight(weightKg: Float)
    
    /**
     * Send a command to start a workout.
     */
    suspend fun startWorkout()
    
    /**
     * Send a command to stop the current workout.
     */
    suspend fun stopWorkout()
}

/**
 * Represents a discovered Vitruvian device.
 */
data class VitruvianDevice(
    val address: String,
    val name: String,
    val rssi: Int = 0,
    val isConnected: Boolean = false
) {
    /**
     * Check if this is a valid Vitruvian device based on naming convention.
     */
    val isVitruvian: Boolean
        get() = name.startsWith("Vee_") || name.startsWith("VIT")
}

/**
 * Vitruvian BLE service and characteristic UUIDs.
 */
object VitruvianBleUuids {
    const val SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    const val TX_CHARACTERISTIC = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    const val RX_CHARACTERISTIC = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
}
