package com.devil.phoenixproject.data.integration

import android.annotation.SuppressLint
import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.domain.model.WorkoutSession
import java.time.Instant
import java.time.ZoneId

private val log = Logger.withTag("HealthIntegration.Android")

private val VITRUVIAN_DEVICE = Device(
    manufacturer = "Vitruvian",
    model = "Trainer",
    type = Device.TYPE_UNKNOWN,
)

internal val requiredHealthPermissions = setOf(
    HealthPermission.getWritePermission(ExerciseSessionRecord::class),
)

internal val optionalHealthPermissions = setOf(
    HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
)

internal val requestedHealthPermissions = requiredHealthPermissions + optionalHealthPermissions

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
}
