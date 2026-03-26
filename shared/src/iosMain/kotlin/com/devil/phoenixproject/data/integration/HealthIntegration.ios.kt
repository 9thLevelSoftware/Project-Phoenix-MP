package com.devil.phoenixproject.data.integration

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.domain.model.WorkoutSession

private val log = Logger.withTag("HealthIntegration.iOS")

/**
 * iOS stub implementation of HealthIntegration.
 *
 * Full HealthKit interop requires Kotlin/Native ↔ Swift bridging that is not yet
 * wired up. Until then all methods return false / Result.failure so callers can
 * gracefully hide the Health Connect UI on iOS.
 */
actual class HealthIntegration {

    actual suspend fun isAvailable(): Boolean {
        log.d { "HealthKit stub: isAvailable() → false (not yet implemented)" }
        return false
    }

    actual suspend fun requestPermissions(): Boolean {
        log.d { "HealthKit stub: requestPermissions() → false (not yet implemented)" }
        return false
    }

    actual suspend fun hasPermissions(): Boolean {
        log.d { "HealthKit stub: hasPermissions() → false (not yet implemented)" }
        return false
    }

    actual suspend fun writeWorkout(session: WorkoutSession): Result<Unit> {
        log.d { "HealthKit stub: writeWorkout() → failure (not yet implemented)" }
        return Result.failure(
            UnsupportedOperationException("HealthKit integration is not yet implemented on iOS")
        )
    }
}
