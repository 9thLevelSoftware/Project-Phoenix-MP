package com.devil.phoenixproject.data.integration

/**
 * tvOS has no HealthKit capability. Keep the shared health integration contract
 * fail-closed so common sync code can report the capability as unavailable.
 */
actual class HealthIntegration : HealthWorkoutWriter {
    actual override suspend fun isAvailable(): Boolean = false

    actual suspend fun requestPermissions(): Boolean = false

    actual override suspend fun hasPermissions(): Boolean = false

    actual suspend fun hasBodyWeightReadPermission(): Boolean = false

    actual suspend fun readLatestScaleBodyWeight(): Result<HealthBodyWeightSample?> =
        Result.failure(UnsupportedOperationException("Health data is unavailable on tvOS."))

    actual override suspend fun writeHealthWorkout(data: HealthWorkoutData): Result<Unit> =
        Result.failure(UnsupportedOperationException("Health workout export is unavailable on tvOS."))
}
