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

internal val requiredHealthPermissions = setOf(
    HealthPermission.getWritePermission(ExerciseSessionRecord::class),
    HealthPermission.getReadPermission(WeightRecord::class),
)

internal val optionalHealthPermissions = setOf(
    HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
)

internal val requestedHealthPermissions = requiredHealthPermissions + optionalHealthPermissions

internal val workoutWriteHealthPermissions = setOf(
    HealthPermission.getWritePermission(ExerciseSessionRecord::class),
)

internal val bodyWeightReadHealthPermissions = setOf(
    HealthPermission.getReadPermission(WeightRecord::class),
)

internal val workoutExportRequestedHealthPermissions = workoutWriteHealthPermissions + optionalHealthPermissions

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
            setIndex = setIndex.coerceAtLeast(0),
            rateOfPerceivedExertion = rpe?.toFloat(),
        )
    }

    private fun segmentTypeForExercise(name: String): Int {
        val normalized = name.lowercase()
        return when {
            "bench" in normalized && "press" in normalized -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS
            "deadlift" in normalized -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_DEADLIFT
            "squat" in normalized -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT
            "curl" in normalized -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL
            "row" in normalized -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_WEIGHTLIFTING
            "pulldown" in normalized || "pull down" in normalized || "lat pull" in normalized -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_WEIGHTLIFTING
            "pull-up" in normalized || "pull up" in normalized -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_PULL_UP
            "lunge" in normalized -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_LUNGE
            "shoulder press" in normalized || "overhead press" in normalized -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_SHOULDER_PRESS
            "tricep" in normalized || "triceps" in normalized -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_DOUBLE_ARM_TRICEPS_EXTENSION
            else -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_WEIGHTLIFTING
        }
    }

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
