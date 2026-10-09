package com.devil.phoenixproject.data.integration

import co.touchlab.kermit.Logger
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSBundle
import platform.Foundation.NSCompoundPredicate
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSPredicate
import platform.Foundation.NSSortDescriptor
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import platform.HealthKit.HKAuthorizationStatusSharingAuthorized
import platform.HealthKit.HKDevice
import platform.HealthKit.HKHealthStore
import platform.HealthKit.HKMetadataKeyExternalUUID
import platform.HealthKit.HKMetadataKeyWasUserEntered
import platform.HealthKit.HKObjectType
import platform.HealthKit.HKQuantity
import platform.HealthKit.HKQuantitySample
import platform.HealthKit.HKQuantityType
import platform.HealthKit.HKQuantityTypeIdentifierActiveEnergyBurned
import platform.HealthKit.HKQuantityTypeIdentifierBodyMass
import platform.HealthKit.HKQuery
import platform.HealthKit.HKSampleQuery
import platform.HealthKit.predicateForObjectsFromSource
import platform.HealthKit.predicateForObjectsWithMetadataKey
import platform.HealthKit.HKSampleSortIdentifierEndDate
import platform.HealthKit.HKSource
import platform.HealthKit.HKUnit
import platform.HealthKit.HKWorkout
import platform.HealthKit.HKWorkoutActivityTypeTraditionalStrengthTraining

private val log = Logger.withTag("HealthIntegration.iOS")

private const val BODY_MASS_QUERY_LIMIT = 50UL

/** Cap when the external-UUID query cannot be restricted to this app's source. */
private const val EXTERNAL_UUID_QUERY_LIMIT_WITHOUT_SOURCE = 25UL

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
     *
     * Before saving, looks up workouts from this app whose `HKExternalUUID` metadata
     * equals [HealthWorkoutData.externalId]. A match is a successful export and is not
     * written again. A failed lookup still saves.
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
            val epochSeconds = data.startTimeMs / 1000.0
            val startDate = NSDate.dateWithTimeIntervalSince1970(epochSeconds)

            val durationMs = (data.endTimeMs - data.startTimeMs).coerceAtLeast(1000L)
            val durationSeconds = durationMs / 1000.0
            val endDate = NSDate.dateWithTimeIntervalSince1970(epochSeconds + durationSeconds)

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

            val existing = lookupExistingExternalUuidWorkout(data.externalId)
            when (
                healthKitWorkoutExportAction(
                    queryFailed = existing.queryFailed,
                    workoutsFromThisApp = existing.workoutsFromThisApp,
                )
            ) {
                HealthKitWorkoutExportAction.SKIP_AS_SUCCESS -> {
                    log.d { "HealthKit workout already exists for ${data.externalId}; skipping duplicate save" }
                    return Result.success(Unit)
                }
                HealthKitWorkoutExportAction.SAVE -> Unit
            }

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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "Failed to write workout to HealthKit for ${data.externalId}" }
            Result.failure(e)
        }
    }

    /**
     * Workouts this app already saved with [externalId] in `HKExternalUUID` metadata.
     * A query failure is reported instead of thrown so the caller can still save.
     */
    private suspend fun lookupExistingExternalUuidWorkout(externalId: String): ExternalUuidLookup {
        if (externalId.isBlank()) {
            return ExternalUuidLookup(queryFailed = false, workoutsFromThisApp = 0)
        }
        return try {
            suspendCancellableCoroutine { continuation ->
                val spec = externalUuidWorkoutPredicate(externalId)
                val query = HKSampleQuery(
                    sampleType = workoutType,
                    predicate = spec.predicate,
                    limit = if (spec.restrictsToThisApp) 1UL else EXTERNAL_UUID_QUERY_LIMIT_WITHOUT_SOURCE,
                    sortDescriptors = null,
                ) { _, samples, error ->
                    if (!continuation.isActive) return@HKSampleQuery
                    if (error != null) {
                        log.w {
                            "HealthKit external UUID lookup failed for $externalId: " +
                                "${error.localizedDescription}; saving workout"
                        }
                        continuation.resume(ExternalUuidLookup(queryFailed = true, workoutsFromThisApp = 0))
                        return@HKSampleQuery
                    }
                    continuation.resume(
                        ExternalUuidLookup(
                            queryFailed = false,
                            workoutsFromThisApp = workoutsFromThisApp(samples),
                        ),
                    )
                }
                continuation.invokeOnCancellation {
                    healthStore.stopQuery(query)
                }
                healthStore.executeQuery(query)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "HealthKit external UUID lookup failed for $externalId; saving workout" }
            ExternalUuidLookup(queryFailed = true, workoutsFromThisApp = 0)
        }
    }

    /**
     * Metadata predicate on [HKMetadataKeyExternalUUID], AND this app's source when
     * [HKSource.defaultSource] is the current app. Otherwise the metadata predicate
     * alone; [workoutsFromThisApp] still drops other apps' samples.
     */
    private fun externalUuidWorkoutPredicate(externalId: String): ExternalUuidWorkoutPredicate {
        val metadataPredicate = HKQuery.predicateForObjectsWithMetadataKey(
            HKMetadataKeyExternalUUID,
            allowedValues = listOf(externalId),
        )
        val sourcePredicate = currentAppSourcePredicateOrNull()
        if (sourcePredicate == null) {
            return ExternalUuidWorkoutPredicate(predicate = metadataPredicate, restrictsToThisApp = false)
        }
        return ExternalUuidWorkoutPredicate(
            predicate = NSCompoundPredicate.andPredicateWithSubpredicates(
                listOf(metadataPredicate, sourcePredicate),
            ),
            restrictsToThisApp = true,
        )
    }

    @Suppress("DEPRECATION")
    private fun currentAppSourcePredicateOrNull(): NSPredicate? {
        val appBundleId = NSBundle.mainBundle.bundleIdentifier?.takeIf { it.isNotBlank() } ?: return null
        val source = try {
            HKSource.defaultSource()
        } catch (e: Exception) {
            log.w(e) { "Unable to read the current HealthKit source; external UUID query will not filter by source" }
            return null
        }
        if (source.bundleIdentifier != appBundleId) {
            log.w {
                "HealthKit default source ${source.bundleIdentifier} is not this app ($appBundleId); " +
                    "external UUID query will not filter by source"
            }
            return null
        }
        return HKQuery.predicateForObjectsFromSource(source)
    }

    private fun workoutsFromThisApp(samples: List<*>?): Int {
        val appBundleId = NSBundle.mainBundle.bundleIdentifier?.takeIf { it.isNotBlank() }
        return samples.orEmpty().count { sample ->
            val workout = sample as? HKWorkout ?: return@count false
            if (appBundleId == null) return@count true
            workout.sourceRevision.source.bundleIdentifier == appBundleId
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
        (timeIntervalSince1970 * 1000.0).toLong()

    private fun Any?.asBoolean(): Boolean? = when (this) {
        is Boolean -> this
        is NSNumber -> boolValue
        else -> null
    }

    private data class ExternalUuidLookup(
        val queryFailed: Boolean,
        val workoutsFromThisApp: Int,
    )

    private data class ExternalUuidWorkoutPredicate(
        val predicate: NSPredicate,
        val restrictsToThisApp: Boolean,
    )
}
