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

    /** Required HealthKit write types. Calories are optional so disabling them does not block workout sync. */
    private val requiredWriteTypes: Set<HKObjectType> by lazy { setOf(workoutType) }

    /** Optional HealthKit write types requested for richer workout metadata. */
    private val optionalWriteTypes: Set<HKObjectType> by lazy {
        buildSet { activeEnergyType?.let { add(it) } }
    }

    /** The full set requested from HealthKit when presenting authorization UI. */
    private val writeTypes: Set<HKObjectType> by lazy { requiredWriteTypes + optionalWriteTypes }

    /**
     * Checks whether HealthKit is available on this device.
     * Returns false on iPad and devices without HealthKit support.
     */
    actual suspend fun isAvailable(): Boolean = try {
        HKHealthStore.isHealthDataAvailable()
    } catch (e: Exception) {
        log.w(e) { "Error checking HealthKit availability" }
        false
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
            hasAuthorizationForTypes(requiredWriteTypes)
        } catch (e: Exception) {
            log.w(e) { "Error checking HealthKit authorization status" }
            false
        }
    }

    private fun hasAuthorizationForTypes(types: Set<HKObjectType>): Boolean = types.all { type ->
        healthStore.authorizationStatusForType(type) == HKAuthorizationStatusSharingAuthorized
    }

    private fun canWriteActiveEnergy(): Boolean {
        val type = activeEnergyType ?: return false
        return try {
            hasAuthorizationForTypes(setOf(type))
        } catch (e: Exception) {
            log.w(e) { "Error checking HealthKit active energy authorization" }
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
                            log.e {
                                "HealthKit authorization request error: ${error.localizedDescription}"
                            }
                        }
                        continuation.resume(success)
                    },
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
     * - Duration derived from session.duration (stored in milliseconds, converted to seconds)
     * - Optional calorie data from session.estimatedCalories
     * - Metadata with external UUID (session.id) for deduplication
     *
     * Weight display follows persisted display semantics: prefer cableCount,
     * then default to 1.
     */
    actual suspend fun writeWorkout(session: WorkoutSession): Result<Unit> {
        if (!isAvailable()) {
            return Result.failure(
                IllegalStateException("HealthKit is not available on this device"),
            )
        }

        if (!hasPermissions()) {
            return Result.failure(
                IllegalStateException("HealthKit write permissions not granted"),
            )
        }

        return try {
            // Convert epoch millis to NSDate
            // NSDate uses "reference date" (2001-01-01), not Unix epoch (1970-01-01)
            // Offset: 978307200 seconds between the two reference points
            val epochSeconds = session.timestamp / 1000.0
            val startDate = NSDate(
                timeIntervalSinceReferenceDate =
                    epochSeconds - UNIX_TO_APPLE_EPOCH_OFFSET,
            )

            // session.duration is stored in MILLISECONDS (from currentTimeMillis() - startTime)
            // Issue #362: Convert to seconds for HealthKit; minimum 1 second
            val durationMs = session.duration.coerceAtLeast(1000L)
            val durationSeconds = (durationMs / 1000L).toDouble()
            val endDate = NSDate(
                timeIntervalSinceReferenceDate =
                    (epochSeconds + durationSeconds) - UNIX_TO_APPLE_EPOCH_OFFSET,
            )

            // Build optional calorie quantity. Active energy permission is optional; do not block workout sync.
            val canWriteCalories = canWriteActiveEnergy()
            val calorieQuantity: HKQuantity? = session.estimatedCalories?.let { cal ->
                if (cal > 0f && canWriteCalories) {
                    HKQuantity.quantityWithUnit(
                        unit = HKUnit.unitFromString("kcal"),
                        doubleValue = cal.toDouble(),
                    )
                } else {
                    null
                }
            }
            if ((session.estimatedCalories ?: 0f) > 0f && !canWriteCalories) {
                log.i { "Skipping HealthKit calorie value for session ${session.id}: optional active energy permission not granted" }
            }

            // Build exercise title with Android-parity persisted display semantics
            val title = buildExerciseTitle(session)

            // Build metadata for deduplication and display
            val metadata = mutableMapOf<Any?, Any?>(
                "HKExternalUUID" to session.id,
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
                metadata = metadata,
            )

            // Save to HealthKit
            suspendCancellableCoroutine { continuation ->
                healthStore.saveObject(workout) { success: Boolean, error: NSError? ->
                    if (error != null) {
                        log.e { "HealthKit save error: ${error.localizedDescription}" }
                        continuation.resume(
                            Result.failure<Unit>(
                                RuntimeException(
                                    "HealthKit save failed: ${error.localizedDescription}",
                                ),
                            ),
                        )
                    } else if (!success) {
                        log.e { "HealthKit save returned false without error" }
                        continuation.resume(
                            Result.failure<Unit>(
                                RuntimeException("HealthKit save failed without error details"),
                            ),
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
     * Issue #395: Write a single aggregate HealthKit workout for an entire routine.
     * Called once at routine completion instead of per-set.
     */
    actual suspend fun writeRoutineWorkout(data: RoutineHealthData): Result<Unit> {
        if (!isAvailable()) {
            return Result.failure(
                IllegalStateException("HealthKit is not available on this device"),
            )
        }

        if (!hasPermissions()) {
            return Result.failure(
                IllegalStateException("HealthKit write permissions not granted"),
            )
        }

        return try {
            val epochSeconds = data.startTimeMs / 1000.0
            val startDate = NSDate(
                timeIntervalSinceReferenceDate =
                    epochSeconds - UNIX_TO_APPLE_EPOCH_OFFSET,
            )

            val durationMs = data.durationMs.coerceAtLeast(1000L)
            val durationSeconds = (durationMs / 1000L).toDouble()
            val endDate = NSDate(
                timeIntervalSinceReferenceDate =
                    (epochSeconds + durationSeconds) - UNIX_TO_APPLE_EPOCH_OFFSET,
            )

            val canWriteCalories = canWriteActiveEnergy()
            val calorieQuantity: HKQuantity? = data.totalCalories?.let { cal ->
                if (cal > 0f && canWriteCalories) {
                    HKQuantity.quantityWithUnit(
                        unit = HKUnit.unitFromString("kcal"),
                        doubleValue = cal.toDouble(),
                    )
                } else {
                    null
                }
            }
            if ((data.totalCalories ?: 0f) > 0f && !canWriteCalories) {
                log.i { "Skipping HealthKit calorie value for routine ${data.externalId}: optional active energy permission not granted" }
            }

            val metadata = mutableMapOf<Any?, Any?>(
                "HKExternalUUID" to data.externalId,
            )
            metadata["title"] = data.routineName

            @Suppress("DEPRECATION")
            val workout = HKWorkout.workoutWithActivityType(
                workoutActivityType = HKWorkoutActivityTypeTraditionalStrengthTraining,
                startDate = startDate,
                endDate = endDate,
                duration = durationSeconds,
                totalEnergyBurned = calorieQuantity,
                totalDistance = null,
                metadata = metadata,
            )

            suspendCancellableCoroutine { continuation ->
                healthStore.saveObject(workout) { success: Boolean, error: NSError? ->
                    if (error != null) {
                        log.e { "HealthKit routine save error: ${error.localizedDescription}" }
                        continuation.resume(
                            Result.failure<Unit>(
                                RuntimeException(
                                    "HealthKit routine save failed: ${error.localizedDescription}",
                                ),
                            ),
                        )
                    } else if (!success) {
                        log.e { "HealthKit routine save returned false without error" }
                        continuation.resume(
                            Result.failure<Unit>(
                                RuntimeException("HealthKit routine save failed without error details"),
                            ),
                        )
                    } else {
                        log.d { "Wrote HealthKit routine workout: ${data.routineName} (${data.externalId})" }
                        continuation.resume(Result.success(Unit))
                    }
                }
            }
        } catch (e: Exception) {
            log.e(e) { "Failed to write routine workout to HealthKit: ${data.externalId}" }
            Result.failure(e)
        }
    }

    /**
     * Builds a human-readable title for the exercise session.
     * Total weight shown is per-cable x persisted cableCount when available.
     *
     * Defaults to 1 if cableCount is null. This preserves Android/iOS Health title parity (Issue #358).
     */
    private fun buildExerciseTitle(session: WorkoutSession): String {
        val exerciseName = session.exerciseName?.takeIf { it.isNotBlank() } ?: "Phoenix Workout"
        val totalWeightKg = session.weightPerCableKg *
            (session.cableCount ?: 1).toFloat()
        return if (totalWeightKg > 0f) {
            "$exerciseName — ${totalWeightKg.toInt()}kg"
        } else {
            exerciseName
        }
    }
}
