package com.devil.phoenixproject.data.repository.simulator

/**
 * Configuration for the BLE simulator.
 *
 * This config is used by [WorkoutSimulationEngine] for physics/timing calculations
 * and [SimulatorBleRepository] for connection simulation parameters.
 *
 * @property deviceName The simulated device name shown during BLE scanning
 * @property repDurationMs Duration of a single rep cycle in milliseconds
 * @property positionRange Valid cable position range in mm (matches typical ROM)
 * @property baseLoadKg Machine base tension when no weight is set
 * @property loadVariancePercent Natural variance in load readings (simulates real hardware)
 * @property grabDelayMs Auto-grab delay for Just Lift mode simulation
 */
data class SimulatorConfig(
    val deviceName: String = "VIT_SIMULATOR",
    val repDurationMs: Long = 3000L,
    val positionRange: ClosedFloatingPointRange<Float> = 0f..800f,
    val baseLoadKg: Float = 4f,
    val loadVariancePercent: Float = 5f,
    val grabDelayMs: Long = 2000L
)
