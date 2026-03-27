package com.devil.phoenixproject.data.integration

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.HealthKit.HKAuthorizationStatusSharingAuthorized
import platform.HealthKit.HKHealthStore
import platform.HealthKit.HKObjectType
import platform.HealthKit.HKQuantity
import platform.HealthKit.HKQuantityType
import platform.HealthKit.HKQuantityTypeIdentifierActiveEnergyBurned
import platform.HealthKit.HKUnit
import platform.HealthKit.HKWorkout
import platform.HealthKit.HKWorkoutActivityTypeTraditionalStrengthTraining
import kotlin.coroutines.resume

private val log = Logger.withTag("HealthIntegration.iOS")

/**
 * iOS implementation of HealthIntegration using Apple HealthKit.
 *
 * Write-only integration: pushes Phoenix workout sessions to HealthKit
 * as strength training workouts with optional calorie data.
 *
 * HealthKit authorization uses system dialogs managed by the OS.
 * Unlike Android Health Connect, no Activity Result contract is needed --
 * requestAuthorization can be called from any context.
 */
actual class HealthIntegration {

    companion object {
        /** Seconds between Unix epoch (1970-01-01) and Apple reference date (2001-01-01). */
        private const val UNIX_TO_APPLE_EPOCH_OFFSET = 978307200.0
    }

    private val healthStore: HKHealthStore by lazy { HKHealthStore() }

    private val workoutType by lazy { HKObjectType.workoutType() }

    private val activeEnergyType: HKQuantityType? by lazy {
        HKQuantityType.quantityTypeForIdentifier(HKQuantityTypeIdentifierActiveEnergyBurned)
    }

    /**
     * The set of HealthKit types this integration writes.
     * Used for both authorization requests and status checks.
     */
    private val writeTypes: Set<HKObjectType> by lazy {
        buildSet {
            add(workoutType)
            activeEnergyType?.let { add(it) }
        }
    }

    /**
     * Checks whether HealthKit is available on this device.
     * Returns false on iPad and devices without HealthKit support.
     */
    actual suspend fun isAvailable(): Boolean {
        return try {
            HKHealthStore.isHealthDataAvailable()
        } catch (e: Exception) {
            log.w(e) { "Error checking HealthKit availability" }
            false
        }
    }

    /**
     * Checks whether the app has been granted write authorization for all
     * required HealthKit types.
     *
     * Note: HealthKit's authorizationStatusForType only reflects *write* status.
     * A status of SharingAuthorized means the user explicitly granted write access.
     */
    actual suspend fun hasPermissions(): Boolean {
        if (!isAvailable()) return false

        return try {
            writeTypes.all { type ->
                healthStore.authorizationStatusForType(type) ==
                    HKAuthorizationStatusSharingAuthorized
            }
        } catch (e: Exception) {
            log.w(e) { "Error checking HealthKit authorization status" }
            false
        }
    }

    /**
     * Requests HealthKit write authorization for workout and active energy types.
     *
     * The completion handler's `success` boolean only indicates whether the
     * authorization dialog was presented successfully -- it does NOT indicate
     * the user's choice. We check actual authorization status after the dialog
     * completes to determine the real result.
     */
    actual suspend fun requestPermissions(): Boolean {
        if (!isAvailable()) {
            log.d { "HealthKit not available, cannot request permissions" }
            return false
        }

        return try {
            val dialogShown = suspendCancellableCoroutine { continuation ->
                healthStore.requestAuthorizationToShareTypes(
                    typesToShare = writeTypes,
                    readTypes = null,
                    completion = { success: Boolean, error: NSError? ->
                        if (error != null) {
                            log.e { "HealthKit authorization request error: ${error.localizedDescription}" }
                        }
                        continuation.resume(success)
                    }
                )
            }

            if (!dialogShown) {
                log.w { "HealthKit authorization dialog was not shown" }
                return false
            }

            // The dialog was shown; check what the user actually granted
            val granted = hasPermissions()
            log.d { "HealthKit authorization result: permissions granted = $granted" }
            granted
        } catch (e: Exception) {
            log.e(e) { "Failed to request HealthKit permissions" }
            false
        }
    }

    /**
     * Writes a completed workout session to HealthKit as a strength training workout.
     *
     * Creates an HKWorkout with:
     * - Activity type: Traditional Strength Training
     * - Duration derived from session.duration (in seconds)
     * - Optional calorie data from session.estimatedCalories
     * - Metadata with external UUID (session.id) for deduplication
     *
     * Weight display follows the dual-cable convention: weightPerCableKg * 2.
     */
    actual suspend fun writeWorkout(session: WorkoutSession): Result<Unit> {
        if (!isAvailable()) {
            return Result.failure(
                IllegalStateException("HealthKit is not available on this device")
            )
        }

        if (!hasPermissions()) {
            return Result.failure(
                IllegalStateException("HealthKit write permissions not granted")
            )
        }

        return try {
            // Convert epoch millis to NSDate
            // NSDate uses "reference date" (2001-01-01), not Unix epoch (1970-01-01)
            // Offset: 978307200 seconds between the two reference points
            val epochSeconds = session.timestamp / 1000.0
            val startDate = NSDate(timeIntervalSinceReferenceDate = epochSeconds - UNIX_TO_APPLE_EPOCH_OFFSET)

            // session.duration is in SECONDS
            val durationSeconds = session.duration.coerceAtLeast(1L).toDouble()
            val endDate = NSDate(
                timeIntervalSinceReferenceDate = (epochSeconds + durationSeconds) - UNIX_TO_APPLE_EPOCH_OFFSET
            )

            // Build optional calorie quantity
            val calorieQuantity: HKQuantity? = session.estimatedCalories?.let { cal ->
                if (cal > 0f) {
                    HKQuantity.quantityWithUnit(
                        unit = HKUnit.unitFromString("kcal"),
                        doubleValue = cal.toDouble()
                    )
                } else {
                    null
                }
            }

            // Build exercise title with dual-cable weight convention
            val title = buildExerciseTitle(session)

            // Build metadata for deduplication and display
            val metadata = mutableMapOf<Any?, Any?>(
                "HKExternalUUID" to session.id
            )
            metadata["title"] = title

            // Create the HKWorkout object
            @Suppress("DEPRECATION")
            val workout = HKWorkout.workoutWithActivityType(
                workoutActivityType = HKWorkoutActivityTypeTraditionalStrengthTraining,
                startDate = startDate,
                endDate = endDate,
                duration = durationSeconds,
                totalEnergyBurned = calorieQuantity,
                totalDistance = null,
                metadata = metadata
            )

            // Save to HealthKit
            suspendCancellableCoroutine { continuation ->
                healthStore.saveObject(workout) { success: Boolean, error: NSError? ->
                    if (error != null) {
                        log.e { "HealthKit save error: ${error.localizedDescription}" }
                        continuation.resume(
                            Result.failure<Unit>(
                                RuntimeException("HealthKit save failed: ${error.localizedDescription}")
                            )
                        )
                    } else if (!success) {
                        log.e { "HealthKit save returned false without error" }
                        continuation.resume(
                            Result.failure<Unit>(
                                RuntimeException("HealthKit save failed without error details")
                            )
                        )
                    } else {
                        log.d { "Wrote HealthKit workout for session ${session.id}" }
                        continuation.resume(Result.success(Unit))
                    }
                }
            }
        } catch (e: Exception) {
            log.e(e) { "Failed to write workout to HealthKit for session ${session.id}" }
            Result.failure(e)
        }
    }

    /**
     * Builds a human-readable title for the exercise session.
     * Total weight shown is per-cable x 2 (dual-cable machine convention).
     */
    private fun buildExerciseTitle(session: WorkoutSession): String {
        val exerciseName = session.exerciseName?.takeIf { it.isNotBlank() } ?: "Phoenix Workout"
        val totalWeightKg = session.weightPerCableKg * 2f
        return if (totalWeightKg > 0f) {
            "$exerciseName — ${totalWeightKg.toInt()}kg"
        } else {
            exerciseName
        }
    }
}
