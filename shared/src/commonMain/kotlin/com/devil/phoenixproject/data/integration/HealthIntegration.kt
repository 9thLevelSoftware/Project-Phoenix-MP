package com.devil.phoenixproject.data.integration

import com.devil.phoenixproject.domain.model.WorkoutSession

/**
 * Platform-specific health integration.
 * Android: Google Health Connect
 * iOS: Apple HealthKit
 *
 * Write-only: pushes Phoenix workout data to the platform health store
 * after each completed workout.
 */
expect class HealthIntegration {
    suspend fun isAvailable(): Boolean
    suspend fun requestPermissions(): Boolean
    suspend fun hasPermissions(): Boolean
    suspend fun writeWorkout(session: WorkoutSession): Result<Unit>
}
