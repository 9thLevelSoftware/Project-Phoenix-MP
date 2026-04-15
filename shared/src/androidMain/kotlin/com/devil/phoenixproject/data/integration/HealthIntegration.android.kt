package com.devil.phoenixproject.data.integration

import android.annotation.SuppressLint
import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.units.Energy
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.domain.model.WorkoutSession
import java.time.Instant
import java.time.ZoneId

private val log = Logger.withTag("HealthIntegration.Android")

internal val requiredHealthPermissions = setOf(
    HealthPermission.getWritePermission(ExerciseSessionRecord::class),
    HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
)

/**
 * Android implementation of HealthIntegration using Google Health Connect.
 *
 * Permission launching (requestPermissions) is intentionally delegated to
 * [hasPermissions] — the actual permission grant UI must be launched from
 * the Compose/Activity layer via [HealthConnectClient.getOrCreate] and the
 * Health Connect permission contract.
 */
actual class HealthIntegration(private val context: Context) {

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

    actual suspend fun isAvailable(): Boolean = try {
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

    actual suspend fun hasPermissions(): Boolean {
        val c = client ?: return false
        return try {
            val granted = c.permissionController.getGrantedPermissions()
            granted.containsAll(requiredHealthPermissions)
        } catch (e: Exception) {
            log.w(e) { "Error checking Health Connect permissions" }
            false
        }
    }

    @SuppressLint("RestrictedApi") // ExerciseSessionRecord constructor restricted in alpha; fixed in stable 1.1.0
    actual suspend fun writeWorkout(session: WorkoutSession): Result<Unit> {
        val c = client ?: return Result.failure(
            IllegalStateException("Health Connect is not available on this device"),
        )

        if (!hasPermissions()) {
            return Result.failure(
                SecurityException("Health Connect write permissions not granted"),
            )
        }

        return try {
            val startInstant = Instant.ofEpochMilli(session.timestamp)
            // session.duration is stored in MILLISECONDS (from currentTimeMillis() - startTime)
            // Issue #362: Convert to seconds for Health Connect; minimum 1 second
            val durationMs = session.duration.coerceAtLeast(1000L)
            val durationSeconds = durationMs / 1000L
            val endInstant = startInstant.plusSeconds(durationSeconds)
            val zoneOffset = ZoneId.systemDefault().rules.getOffset(startInstant)

            val records = buildList {
                add(
                    ExerciseSessionRecord(
                        startTime = startInstant,
                        startZoneOffset = zoneOffset,
                        endTime = endInstant,
                        endZoneOffset = zoneOffset,
                        exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
                        title = buildExerciseTitle(session),
                    ),
                )

                val calories = session.estimatedCalories
                if (calories != null && calories > 0f) {
                    add(
                        TotalCaloriesBurnedRecord(
                            startTime = startInstant,
                            startZoneOffset = zoneOffset,
                            endTime = endInstant,
                            endZoneOffset = zoneOffset,
                            energy = Energy.kilocalories(calories.toDouble()),
                        ),
                    )
                }
            }

            c.insertRecords(records)
            log.d { "Wrote ${records.size} Health Connect record(s) for session ${session.id}" }
            Result.success(Unit)
        } catch (e: Exception) {
            log.e(e) { "Failed to write workout to Health Connect for session ${session.id}" }
            Result.failure(e)
        }
    }

    /**
     * Builds a human-readable title for the exercise session.
     *
     * Weight display logic:
     * - If cableCount is explicitly set (1 or 2), use it
     * - If null (legacy data), default to 1 (per-cable weight shown as-is)
     *
     * This matches the rest of the codebase (effectiveTotalVolumeKg, InsightCards, etc.)
     * and the official Vitruvian app which displays weight per-cable without doubling.
     */
    private fun buildExerciseTitle(session: WorkoutSession): String {
        val exerciseName = session.exerciseName?.takeIf { it.isNotBlank() } ?: "Phoenix Workout"
        // Default to 1 cable for legacy sessions without cableCount metadata
        // This prevents incorrect weight inflation (Issue #358: 80kg showing as 160kg)
        val totalWeightKg = session.weightPerCableKg * (session.cableCount ?: 1).toFloat()
        return if (totalWeightKg > 0f) {
            "$exerciseName — ${totalWeightKg.toInt()}kg"
        } else {
            exerciseName
        }
    }
}
