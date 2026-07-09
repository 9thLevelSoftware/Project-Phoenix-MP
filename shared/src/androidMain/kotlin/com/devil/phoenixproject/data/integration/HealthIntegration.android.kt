package com.devil.phoenixproject.data.integration

import android.annotation.SuppressLint
import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.domain.model.WorkoutSession
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private val log = Logger.withTag("HealthIntegration.Android")

private val VITRUVIAN_DEVICE = Device(
    manufacturer = "Vitruvian",
    model = "Trainer",
    type = Device.TYPE_UNKNOWN,
)

// Issue #531: Only the workout-export write permission is required for the
// Health Connect integration to function. Bundling the body-weight read into
// `requiredHealthPermissions` (PR #515) silently broke workout export for any
// user who had Health Connect enabled before the body-weight feature shipped:
// `hasPermissions()` started returning false because the user had never been
// asked for `WeightRecord` read, so `writeHealthWorkout()` rejected every
// post-workout push with a `SecurityException` and the failure was logged at
// `Logger.w` only. The body-weight read is now treated as an *additive*
// capability, mirroring the iOS side (where `hasPermissions()` only inspects
// `requiredWriteTypes`). The launcher still requests it via
// `requestedHealthPermissions`, and the body-weight sync path continues to
// gate on `hasBodyWeightReadPermission()` so users who decline the read
// prompt keep their workout sync and simply skip the body-weight import.
internal val requiredHealthPermissions = setOf(
    HealthPermission.getWritePermission(ExerciseSessionRecord::class),
)

internal val optionalHealthPermissions = setOf(
    HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
)

internal val bodyWeightReadHealthPermissions = setOf(
    HealthPermission.getReadPermission(WeightRecord::class),
)

internal val workoutWriteHealthPermissions = requiredHealthPermissions

internal val workoutExportRequestedHealthPermissions = workoutWriteHealthPermissions + optionalHealthPermissions

// The full prompt surface still asks for body-weight read alongside workout
// write so users can opt into the body-weight import in a single flow.
internal val requestedHealthPermissions = requiredHealthPermissions +
    optionalHealthPermissions +
    bodyWeightReadHealthPermissions

private const val BODY_WEIGHT_LOOKBACK_DAYS = 3650L
private const val BODY_WEIGHT_READ_PAGE_SIZE = 100
private const val BODY_WEIGHT_READ_MAX_PAGES = 10

/**
 * Android implementation of HealthIntegration using Google Health Connect.
 *
 * Permission launching (requestPermissions) is intentionally delegated to
 * [hasPermissions] — the actual permission grant UI must be launched from
 * the Compose/Activity layer via [HealthConnectClient.getOrCreate] and the
 * Health Connect permission contract.
 */
actual class HealthIntegration(private val context: Context) : HealthWorkoutWriter {

    private val client: HealthConnectClient? by lazy {
        try {
            if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
                HealthConnectClient.getOrCreate(context)
            } else {
                null
            }
        } catch (e: Exception) {
            log.e(e) { "Failed to create HealthConnectClient" }
            null
        }
    }

    actual override suspend fun isAvailable(): Boolean = try {
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    } catch (e: Exception) {
        log.w(e) { "Error checking Health Connect availability" }
        false
    }

    /**
     * Delegates to [hasPermissions] — the Compose UI layer is responsible for
     * launching the Health Connect permission request contract when this returns false.
     */
    actual suspend fun requestPermissions(): Boolean = hasPermissions()

    actual override suspend fun hasPermissions(): Boolean = hasGrantedPermissions(requiredHealthPermissions)

    actual suspend fun hasBodyWeightReadPermission(): Boolean = hasGrantedPermissions(bodyWeightReadHealthPermissions)

    private suspend fun hasCalorieWritePermission(): Boolean = hasGrantedPermissions(optionalHealthPermissions)

    private suspend fun hasGrantedPermissions(permissions: Set<String>): Boolean {
        val c = client ?: return false
        return try {
            val granted = c.permissionController.getGrantedPermissions()
            granted.containsAll(permissions)
        } catch (e: Exception) {
            log.w(e) { "Error checking Health Connect permissions" }
            false
        }
    }

    actual suspend fun readLatestScaleBodyWeight(): Result<HealthBodyWeightSample?> {
        val c = client ?: return Result.failure(
            IllegalStateException("Health Connect is not available on this device"),
        )

        if (!hasBodyWeightReadPermission()) {
            return Result.failure(SecurityException("Health Connect body weight read permission not granted"))
        }

        return try {
            val end = Instant.now()
            val start = end.minus(BODY_WEIGHT_LOOKBACK_DAYS, ChronoUnit.DAYS)
            var pageToken: String? = null
            var pagesRead = 0

            do {
                val response = c.readRecords(
                    ReadRecordsRequest(
                        recordType = WeightRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start, end),
                        ascendingOrder = false,
                        pageSize = BODY_WEIGHT_READ_PAGE_SIZE,
                        pageToken = pageToken,
                    ),
                )
                response.records.firstNotNullOfOrNull { record ->
                    record.toEligibleBodyWeightSampleOrNull()
                }?.let { sample ->
                    return Result.success(sample)
                }

                pageToken = response.pageToken
                pagesRead++
            } while (pageToken != null && pagesRead < BODY_WEIGHT_READ_MAX_PAGES)

            Result.success(null)
        } catch (e: Exception) {
            log.e(e) { "Failed to read latest Health Connect scale body weight" }
            Result.failure(e)
        }
    }

    actual suspend fun writeWorkout(session: WorkoutSession): Result<Unit> {
        val data = HealthWorkoutExportBuilder.buildStandaloneWorkout(session, completedSets = emptyList())
            ?: return Result.failure(IllegalArgumentException("Workout session has no completed reps to write"))
        return writeHealthWorkout(data)
    }

    @SuppressLint("RestrictedApi")
    actual override suspend fun writeHealthWorkout(data: HealthWorkoutData): Result<Unit> {
        val c = client ?: return Result.failure(
            IllegalStateException("Health Connect is not available on this device"),
        )

        if (!hasPermissions()) {
            return Result.failure(
                SecurityException("Health Connect write permissions not granted"),
            )
        }

        return try {
            val startInstant = Instant.ofEpochMilli(data.startTimeMs)
            val endInstant = Instant.ofEpochMilli(data.endTimeMs.coerceAtLeast(data.startTimeMs + 1000L))
            val zoneOffset = ZoneId.systemDefault().rules.getOffset(startInstant)
            val canWriteCalories = hasCalorieWritePermission()
            val exerciseSegments = data.segments.map { segment -> segment.toHealthConnectSegment() }
            val sessionRpe = data.segments
                .mapNotNull { it.rpe }
                .takeIf { it.isNotEmpty() }
                ?.average()
                ?.toFloat()

            log.i {
                "HEALTH_DEBUG_ANDROID_WRITE: externalId=${data.externalId}, title=${data.title}, " +
                    "segments=${data.segments.size}, totalCalories=${data.totalCalories ?: -1f}, " +
                    "canWriteCalories=$canWriteCalories, " +
                    "durationMs=${data.endTimeMs - data.startTimeMs}"
            }

            val records = buildList {
                add(
                    ExerciseSessionRecord(
                        startTime = startInstant,
                        startZoneOffset = zoneOffset,
                        endTime = endInstant,
                        endZoneOffset = zoneOffset,
                        metadata = Metadata.activelyRecorded(VITRUVIAN_DEVICE, data.externalId, 0L),
                        exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
                        title = data.title,
                        notes = null,
                        segments = exerciseSegments,
                        laps = emptyList(),
                        exerciseRoute = null,
                        plannedExerciseSessionId = null,
                        rateOfPerceivedExertion = sessionRpe,
                    ),
                )

                val calories = data.totalCalories
                if (calories != null && calories > 0f) {
                    if (canWriteCalories) {
                        add(
                            TotalCaloriesBurnedRecord(
                                startTime = startInstant,
                                startZoneOffset = zoneOffset,
                                endTime = endInstant,
                                endZoneOffset = zoneOffset,
                                energy = Energy.kilocalories(calories.toDouble()),
                                metadata = Metadata.activelyRecorded(
                                    VITRUVIAN_DEVICE,
                                    HealthWorkoutExportBuilder.calorieClientRecordId(data.externalId),
                                    0L,
                                ),
                            ),
                        )
                    } else {
                        log.i { "Skipping Health Connect calorie record for ${data.externalId}: optional calorie write permission not granted" }
                    }
                }
            }

            c.insertRecords(records)
            log.d { "Wrote ${records.size} Health Connect record(s) for ${data.externalId}" }
            Result.success(Unit)
        } catch (e: Exception) {
            log.e(e) { "Failed to write workout to Health Connect for ${data.externalId}" }
            Result.failure(e)
        }
    }

    private fun HealthWorkoutSegment.toHealthConnectSegment(): ExerciseSegment {
        val sessionType = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
        val preferredType = segmentTypeForExercise(exerciseName)
        val fallbackType = ExerciseSegment.EXERCISE_SEGMENT_TYPE_WEIGHTLIFTING
        val segmentType = when {
            ExerciseSegment.isSegmentTypeCompatibleWithSessionType(preferredType, sessionType) -> preferredType
            ExerciseSegment.isSegmentTypeCompatibleWithSessionType(fallbackType, sessionType) -> fallbackType
            else -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_UNKNOWN
        }

        return ExerciseSegment(
            startTime = Instant.ofEpochMilli(startTimeMs),
            endTime = Instant.ofEpochMilli(endTimeMs.coerceAtLeast(startTimeMs + 1000L)),
            segmentType = segmentType,
            repetitions = reps.coerceAtLeast(0),
            weight = Mass.kilograms(weightKg.toDouble().coerceAtLeast(0.0)),
            // Issue #639: Health Connect expects the user-visible 1-based set
            // number ("Set 1, Set 2, …") while the rest of Phoenix's internal
            // pipeline (CompletedSet.setNumber, WorkoutCoordinator._currentSetIndex)
            // is 0-based. Convert at the writer boundary so the internal 0-based
            // convention is preserved everywhere else.
            setIndex = (setIndex + 1).coerceAtLeast(1),
            rateOfPerceivedExertion = rpe?.toFloat(),
        )
    }

    private fun segmentTypeForExercise(name: String): Int = segmentTypeForExerciseInternal(name)

    private fun WeightRecord.toEligibleBodyWeightSampleOrNull(): HealthBodyWeightSample? {
        val recordMetadata = this.metadata
        val device = recordMetadata.device
        val evidence = HealthBodyWeightSourceEvidence(
            platform = HealthBodyWeightSourcePlatform.ANDROID,
            wasUserEntered = recordMetadata.recordingMethod == Metadata.RECORDING_METHOD_MANUAL_ENTRY,
            deviceType = when (device?.type) {
                Device.TYPE_SCALE -> HealthBodyWeightDeviceType.SCALE
                Device.TYPE_UNKNOWN, null -> HealthBodyWeightDeviceType.UNKNOWN
                else -> HealthBodyWeightDeviceType.OTHER
            },
            sourceName = recordMetadata.dataOrigin.packageName,
            sourceBundleIdentifier = recordMetadata.dataOrigin.packageName,
            deviceManufacturer = device?.manufacturer,
            deviceModel = device?.model,
        )

        if (!HealthBodyWeightSourceClassifier.isEligibleScaleSource(evidence)) {
            return null
        }

        return HealthBodyWeightSample(
            weightKg = weight.inKilograms.toFloat(),
            measuredAtMs = time.toEpochMilli(),
            externalId = recordMetadata.clientRecordId?.takeIf { it.isNotBlank() }
                ?: recordMetadata.id.takeIf { it.isNotBlank() }
                ?: "healthconnect-weight-${time.toEpochMilli()}-${weight.inKilograms}",
            sourceName = recordMetadata.dataOrigin.packageName.takeIf { it.isNotBlank() },
            deviceMetadata = buildAndroidWeightDeviceMetadata(recordMetadata),
            rawMetadataJson = buildAndroidWeightRawMetadataJson(this),
        )
    }

    private fun buildAndroidWeightDeviceMetadata(metadata: Metadata): Map<String, String> = buildMap {
        put("recordingMethod", metadata.recordingMethod.toString())
        metadata.dataOrigin.packageName.takeIf { it.isNotBlank() }?.let { put("dataOriginPackage", it) }
        metadata.device?.let { device ->
            put("deviceType", device.type.toString())
            device.manufacturer?.takeIf { it.isNotBlank() }?.let { put("deviceManufacturer", it) }
            device.model?.takeIf { it.isNotBlank() }?.let { put("deviceModel", it) }
        }
    }

    private fun buildAndroidWeightRawMetadataJson(record: WeightRecord): String {
        val metadata = record.metadata
        val device = metadata.device
        return buildString {
            append("{")
            append("\"platform\":\"android\",")
            append("\"recordId\":\"${metadata.id.escapeJson()}\",")
            append("\"clientRecordId\":")
            append(metadata.clientRecordId?.let { "\"${it.escapeJson()}\"" } ?: "null")
            append(",\"dataOriginPackage\":\"${metadata.dataOrigin.packageName.escapeJson()}\",")
            append("\"recordingMethod\":${metadata.recordingMethod},")
            append("\"lastModifiedTimeMs\":${metadata.lastModifiedTime.toEpochMilli()},")
            append("\"device\":{")
            append("\"type\":${device?.type ?: Device.TYPE_UNKNOWN},")
            append("\"manufacturer\":")
            append(device?.manufacturer?.let { "\"${it.escapeJson()}\"" } ?: "null")
            append(",\"model\":")
            append(device?.model?.let { "\"${it.escapeJson()}\"" } ?: "null")
            append("}")
            append("}")
        }
    }

    private fun String.escapeJson(): String = buildString {
        this@escapeJson.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}

/**
 * Issue #639: top-level (rather than class-member) helper for testing. The
 * Android-host test suite calls this directly to lock in the exercise-name to
 * Health Connect segment-type mapping without needing a `Context`. The
 * class-member `segmentTypeForExercise` simply delegates here.
 */
@androidx.annotation.VisibleForTesting
internal fun segmentTypeForExerciseInternal(name: String): Int {
    val normalized = name.lowercase()
    return when {
        "bench" in normalized && "press" in normalized -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS
        "deadlift" in normalized -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_DEADLIFT
        "squat" in normalized -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT
        // Issue #639: Specific curl/press/raise multi-word keywords MUST be
        // checked before the generic "curl"/"press" branches below, otherwise
        // the generic single-word branch wins (e.g. "leg curl" would match
        // "curl" first and resolve to ARM_CURL, hiding the actual leg exercise).
        "leg curl" in normalized || "hamstring curl" in normalized ->
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_CURL
        "leg extension" in normalized || "quad extension" in normalized ->
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_EXTENSION
        "leg press" in normalized -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_PRESS
        "leg raise" in normalized -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_RAISE
        "barbell shoulder press" in normalized -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_BARBELL_SHOULDER_PRESS
        "front raise" in normalized || "dumbbell front raise" in normalized ->
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_FRONT_RAISE
        "lateral raise" in normalized || "dumbbell lateral raise" in normalized ->
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_LATERAL_RAISE
        "hip thrust" in normalized -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_HIP_THRUST
        "back extension" in normalized -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_BACK_EXTENSION
        // Issue #639: "Neutral Wide Grip Pulldown" (and other pulldown / lat-pull
        // variants) were collapsing to the generic WEIGHTLIFTING display label.
        // Map them to the specific Health Connect segment type so the user
        // sees the real exercise name in Health Connect / Google Fit.
        "pulldown" in normalized || "pull down" in normalized || "lat pull" in normalized ->
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_LAT_PULL_DOWN
        "pull-up" in normalized || "pull up" in normalized -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_PULL_UP
        "lunge" in normalized -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_LUNGE
        "shoulder press" in normalized || "overhead press" in normalized -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_SHOULDER_PRESS
        "tricep" in normalized || "triceps" in normalized -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_DOUBLE_ARM_TRICEPS_EXTENSION
        // Generic single-word fallbacks. Placed AFTER the specific multi-word
        // matches so they only apply when no more specific branch matches.
        "curl" in normalized -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL
        "row" in normalized -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_WEIGHTLIFTING
        else -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_WEIGHTLIFTING
    }
}
