package com.devil.phoenixproject.data.integration

import co.touchlab.kermit.Logger
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSSortDescriptor
import platform.HealthKit.HKAuthorizationStatusSharingAuthorized
import platform.HealthKit.HKDevice
import platform.HealthKit.HKHealthStore
import platform.HealthKit.HKMetadataKeyWasUserEntered
import platform.HealthKit.HKObjectType
import platform.HealthKit.HKQuantity
import platform.HealthKit.HKQuantitySample
import platform.HealthKit.HKQuantityType
import platform.HealthKit.HKQuantityTypeIdentifierActiveEnergyBurned
import platform.HealthKit.HKQuantityTypeIdentifierBodyMass
import platform.HealthKit.HKSampleQuery
import platform.HealthKit.HKSampleSortIdentifierEndDate
import platform.HealthKit.HKUnit
import platform.HealthKit.HKWorkout
import platform.HealthKit.HKWorkoutActivityTypeTraditionalStrengthTraining

private val log = Logger.withTag("HealthIntegration.iOS")

private const val BODY_MASS_QUERY_LIMIT = 50UL

/**
 * iOS implementation of HealthIntegration using Apple HealthKit.
 *
 * Writes a completed Phoenix workout as one aggregate traditional
 * strength-training workout, including optional active energy (calories)
 * when that write is authorized. [HealthWorkoutData.segments] are not
 * persisted. Also reads body mass one way into Phoenix: the newest eligible
 * scale sample from a bounded HealthKit query. Manual entries and sources
 * that do not look like a scale are skipped.
 *
 * HealthKit authorization uses system dialogs managed by the OS.
 * Unlike Android Health Connect, no Activity Result contract is needed --
 * requestAuthorization can be called from any context.
 */
actual class HealthIntegration : HealthWorkoutWriter {

    companion object {
        /** Seconds between Unix epoch (1970-01-01) and Apple reference date (2001-01-01). */
        private const val UNIX_TO_APPLE_EPOCH_OFFSET = 978307200.0
    }

    private val healthStore: HKHealthStore by lazy { HKHealthStore() }

    private val workoutType by lazy { HKObjectType.workoutType() }

    private val activeEnergyType: HKQuantityType? by lazy {
        HKQuantityType.quantityTypeForIdentifier(HKQuantityTypeIdentifierActiveEnergyBurned)
    }

    private val bodyMassType: HKQuantityType? by lazy {
        HKQuantityTypeIdentifierBodyMass?.let { HKQuantityType.quantityTypeForIdentifier(it) }
    }

    /** Required HealthKit write types. Calories are optional so disabling them does not block workout sync. */
    private val requiredWriteTypes: Set<HKObjectType> by lazy { setOf(workoutType) }

    /** Optional HealthKit write types requested for richer workout metadata. */
    private val optionalWriteTypes: Set<HKObjectType> by lazy {
        buildSet { activeEnergyType?.let { add(it) } }
    }

    /** The full set requested from HealthKit when presenting authorization UI. */
    private val writeTypes: Set<HKObjectType> by lazy { requiredWriteTypes + optionalWriteTypes }

    /** Read types requested for one-way body-weight import. */
    private val readTypes: Set<HKObjectType> by lazy {
        buildSet { bodyMassType?.let { add(it) } }
    }

    /**
     * Returns whether HealthKit data is available on this device.
     *
     * Delegates to [HKHealthStore.isHealthDataAvailable] and returns false if
     * that check throws. HealthKit reports data as available on iPhone and on
     * iPad running iPadOS 17 or later, and unavailable on earlier iPadOS.
     * A restricted or otherwise unsupported device also returns false.
     */
    actual override suspend fun isAvailable(): Boolean = try {
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
    actual override suspend fun hasPermissions(): Boolean {
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
     * HealthKit does not expose per-type read authorization status. If HealthKit and the body mass
     * type are available, the query path is the only reliable read-permission check.
     */
    actual suspend fun hasBodyWeightReadPermission(): Boolean = isAvailable() && bodyMassType != null

    /**
     * Reads the newest eligible scale body-mass sample from HealthKit.
     *
     * Queries body mass newest-end-date first, limited to [BODY_MASS_QUERY_LIMIT]
     * samples, and returns the first sample [HealthBodyWeightSourceClassifier]
     * accepts as a scale source. Weight is converted to kilograms. An eligible
     * reading beyond those newest samples is not returned.
     *
     * Returns [Result.success] with null when the body-mass type is missing or
     * no returned sample is an eligible scale reading. Returns
     * [Result.failure] when HealthKit is unavailable or the query fails.
     * HealthKit does not report read authorization: a denied read returns no
     * other apps' samples (only ones this app saved), so it yields
     * [Result.success] with null while [hasBodyWeightReadPermission] still
     * returns true.
     */
    actual suspend fun readLatestScaleBodyWeight(): Result<HealthBodyWeightSample?> {
        if (!isAvailable()) {
            return Result.failure(IllegalStateException("HealthKit is not available on this device"))
        }

        val sampleType = bodyMassType ?: return Result.success(null)

        return try {
            suspendCancellableCoroutine { continuation ->
                val sortDescriptors = listOf(
                    NSSortDescriptor.sortDescriptorWithKey(
                        key = HKSampleSortIdentifierEndDate,
                        ascending = false,
                    ),
                )
                val query = HKSampleQuery(
                    sampleType = sampleType,
                    predicate = null,
                    limit = BODY_MASS_QUERY_LIMIT,
                    sortDescriptors = sortDescriptors,
                ) { _, samples, error ->
                    if (error != null) {
                        log.e { "HealthKit body mass query error: ${error.localizedDescription}" }
                        continuation.resume(
                            Result.failure(
                                RuntimeException("HealthKit body mass query failed: ${error.localizedDescription}"),
                            ),
                        )
                        return@HKSampleQuery
                    }

                    val latest = samples.orEmpty()
                        .filterIsInstance<HKQuantitySample>()
                        .firstNotNullOfOrNull { sample ->
                            sample.toEligibleBodyWeightSampleOrNull()
                        }
                    continuation.resume(Result.success(latest))
                }

                continuation.invokeOnCancellation {
                    healthStore.stopQuery(query)
                }
                healthStore.executeQuery(query)
            }
        } catch (e: Exception) {
            log.e(e) { "Failed to read latest HealthKit scale body weight" }
            Result.failure(e)
        }
    }

    /**
     * Requests HealthKit authorization to share workouts and optional active
     * energy, and to read body mass for one-way scale body-weight import.
     *
     * Share types are the workout type plus active energy burned when that
     * quantity type exists. The read type is body mass when that quantity type
     * exists.
     *
     * The completion handler's `success` flag means the authorization request
     * was processed. It does not mean a dialog was shown, and it does not
     * reflect the user's choice. When the request is processed, the returned
     * value is [hasPermissions]: write authorization for the required workout
     * type only. Active-energy write and body-mass read are omitted from that
     * result. HealthKit does not expose read authorization status; see
     * [hasBodyWeightReadPermission].
     *
     * Returns false when HealthKit is unavailable, the request is not
     * processed, or required workout write access is not authorized.
     */
    actual suspend fun requestPermissions(): Boolean {
        if (!isAvailable()) {
            log.d { "HealthKit not available, cannot request permissions" }
            return false
        }

        return try {
            val requestProcessed = suspendCancellableCoroutine { continuation ->
                healthStore.requestAuthorizationToShareTypes(
                    typesToShare = writeTypes,
                    readTypes = readTypes,
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

            if (!requestProcessed) {
                log.w { "HealthKit authorization request was not processed" }
                return false
            }

            // Request processed; the result is required workout-write authorization.
            val granted = hasPermissions()
            log.d { "HealthKit authorization result: permissions granted = $granted" }
            granted
        } catch (e: Exception) {
            log.e(e) { "Failed to request HealthKit permissions" }
            false
        }
    }

    /**
     * Writes a completed Phoenix workout to HealthKit as one aggregate strength workout.
     *
     * HealthKit does not expose a public per-set strength segment model comparable to
     * Android Health Connect ExerciseSegment, so [HealthWorkoutData.segments] are not persisted on iOS.
     * Positive [HealthWorkoutData.totalCalories] are stored as active energy only when
     * that optional write is authorized; a missing calorie permission does not fail the workout write.
     */
    actual override suspend fun writeHealthWorkout(data: HealthWorkoutData): Result<Unit> {
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
            val epochSeconds = data.startTimeMs / 1000.0
            val startDate = NSDate(
                timeIntervalSinceReferenceDate =
                    epochSeconds - UNIX_TO_APPLE_EPOCH_OFFSET,
            )

            val durationMs = (data.endTimeMs - data.startTimeMs).coerceAtLeast(1000L)
            val durationSeconds = (durationMs / 1000L).toDouble()
            val endDate = NSDate(
                timeIntervalSinceReferenceDate =
                    (epochSeconds + durationSeconds) - UNIX_TO_APPLE_EPOCH_OFFSET,
            )

            // Build optional calorie quantity. Active energy permission is optional; do not block workout sync.
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
                log.i { "Skipping HealthKit calorie value for ${data.externalId}: optional active energy permission not granted" }
            }

            // Build metadata for deduplication and display
            val metadata = mutableMapOf<Any?, Any?>(
                "HKExternalUUID" to data.externalId,
            )
            metadata["title"] = data.title

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
                        log.d { "Wrote HealthKit workout for ${data.externalId}" }
                        continuation.resume(Result.success(Unit))
                    }
                }
            }
        } catch (e: Exception) {
            log.e(e) { "Failed to write workout to HealthKit for ${data.externalId}" }
            Result.failure(e)
        }
    }

    private fun HKQuantitySample.toEligibleBodyWeightSampleOrNull(): HealthBodyWeightSample? {
        val metadata = metadata
        val source = sourceRevision.source
        val wasUserEntered = metadata?.get(HKMetadataKeyWasUserEntered).asBoolean()
        val device = device
        val evidence = HealthBodyWeightSourceEvidence(
            platform = HealthBodyWeightSourcePlatform.IOS,
            wasUserEntered = wasUserEntered,
            sourceName = source.name,
            sourceBundleIdentifier = source.bundleIdentifier,
            deviceManufacturer = device?.manufacturer,
            deviceModel = device?.model,
            deviceName = device?.name,
        )

        if (!HealthBodyWeightSourceClassifier.isEligibleScaleSource(evidence)) {
            return null
        }

        val weightKg = quantity.doubleValueForUnit(HKUnit.unitFromString("kg")).toFloat()
        return HealthBodyWeightSample(
            weightKg = weightKg,
            measuredAtMs = endDate.toEpochMillis(),
            externalId = UUID.UUIDString,
            sourceName = source.name,
            deviceMetadata = buildIosBodyWeightDeviceMetadata(source.name, source.bundleIdentifier, device),
            rawMetadataJson = buildIosBodyWeightRawMetadataJson(this),
        )
    }

    private fun buildIosBodyWeightDeviceMetadata(
        sourceName: String,
        sourceBundleIdentifier: String,
        device: HKDevice?,
    ): Map<String, String> = buildMap {
        put("sourceName", sourceName)
        put("sourceBundleIdentifier", sourceBundleIdentifier)
        device?.name?.takeIf { it.isNotBlank() }?.let { put("deviceName", it) }
        device?.manufacturer?.takeIf { it.isNotBlank() }?.let { put("deviceManufacturer", it) }
        device?.model?.takeIf { it.isNotBlank() }?.let { put("deviceModel", it) }
        device?.hardwareVersion?.takeIf { it.isNotBlank() }?.let { put("deviceHardwareVersion", it) }
        device?.softwareVersion?.takeIf { it.isNotBlank() }?.let { put("deviceSoftwareVersion", it) }
        device?.localIdentifier?.takeIf { it.isNotBlank() }?.let { put("deviceLocalIdentifier", it) }
    }

    private fun buildIosBodyWeightRawMetadataJson(sample: HKQuantitySample): String {
        val source = sample.sourceRevision.source
        val device = sample.device
        val wasUserEntered = sample.metadata?.get(HKMetadataKeyWasUserEntered).asBoolean()
        return buildString {
            append("{")
            append("\"platform\":\"ios\",")
            append("\"uuid\":\"${sample.UUID.UUIDString.escapeJson()}\",")
            append("\"sourceName\":\"${source.name.escapeJson()}\",")
            append("\"sourceBundleIdentifier\":\"${source.bundleIdentifier.escapeJson()}\",")
            append("\"wasUserEntered\":${wasUserEntered ?: false},")
            append("\"device\":{")
            append("\"name\":")
            append(device?.name?.let { "\"${it.escapeJson()}\"" } ?: "null")
            append(",\"manufacturer\":")
            append(device?.manufacturer?.let { "\"${it.escapeJson()}\"" } ?: "null")
            append(",\"model\":")
            append(device?.model?.let { "\"${it.escapeJson()}\"" } ?: "null")
            append(",\"hardwareVersion\":")
            append(device?.hardwareVersion?.let { "\"${it.escapeJson()}\"" } ?: "null")
            append(",\"softwareVersion\":")
            append(device?.softwareVersion?.let { "\"${it.escapeJson()}\"" } ?: "null")
            append("}")
            append("}")
        }
    }

    private fun NSDate.toEpochMillis(): Long =
        ((timeIntervalSinceReferenceDate + UNIX_TO_APPLE_EPOCH_OFFSET) * 1000.0).toLong()

    private fun Any?.asBoolean(): Boolean? = when (this) {
        is Boolean -> this
        is NSNumber -> boolValue
        else -> null
    }
}
