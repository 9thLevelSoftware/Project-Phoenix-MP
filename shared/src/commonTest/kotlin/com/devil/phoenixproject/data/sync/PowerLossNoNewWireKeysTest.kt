package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.RepMetricData
import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden no-new-wire-keys guard for the power-loss autostop enhancement (#1392).
 *
 * The power-loss v1 contract is local-only (SQLDelight companions + on-device
 * power-history export); the mobile-sync-push payload must stay key-identical to
 * the pre-feature wire contract. On the PR branch the entire wire chain —
 * `PortalSyncDtos.kt`, `PortalSyncAdapter.kt`, `PortalWireJson.kt`, `Routine.kt` —
 * is byte-identical to `main@4d4de558` (empty `git diff main..HEAD` for
 * `data/sync/` and the routine/domain models the push reads), so the golden path
 * set pinned below IS the pre-feature contract: it was captured on this branch,
 * whose serialization code is unchanged since before the feature. Any power-loss
 * field leaking onto the wire — now or later — fails this test loudly.
 */
class PowerLossNoNewWireKeysTest {

    @Test
    fun `session and telemetry push wire paths equal the pinned pre-feature golden`() {
        val paths = collectAllWirePaths()

        assertEquals(goldenWirePaths, paths, "mobile-sync-push wire key surface changed")
    }

    @Test
    fun `no power-loss identifiers appear anywhere in the push payloads`() {
        for (path in collectAllWirePaths()) {
            val lower = path.lowercase()
            for (token in FORBIDDEN_POWER_LOSS_TOKENS) {
                assertTrue(
                    !lower.contains(token),
                    "power-loss identifier '$token' leaked onto the wire at '$path'",
                )
            }
        }
    }

    @Test
    fun `legacy powerWatts on the wire still equals avgPowerWatts`() {
        val result = buildResult()
        val payload = PortalWireJson.encodeToString(
            ListSerializer(PortalWorkoutSessionDto.serializer()),
            result.sessions,
        )
        val objects = collectObjectsWithKey(PortalWireJson.parseToJsonElement(payload), "powerWatts")
        assertTrue(objects.isNotEmpty(), "expected at least one rep summary with powerWatts")
        for (obj in objects) {
            val watts = (obj.getValue("powerWatts") as JsonPrimitive).content
            assertEquals(
                "200.0",
                watts,
                "legacy powerWatts must stay the avgPowerWatts mapping (200f), got $watts",
            )
        }
    }

    /** Both push payloads (sessions and rep telemetry) as one path set (arrays collapsed to []). */
    private fun collectAllWirePaths(): Set<String> {
        val result = buildResult()
        val sessionsJson = PortalWireJson.parseToJsonElement(
            PortalWireJson.encodeToString(
                ListSerializer(PortalWorkoutSessionDto.serializer()),
                result.sessions,
            ),
        ).jsonArray
        val telemetryJson = PortalWireJson.parseToJsonElement(
            PortalWireJson.encodeToString(
                ListSerializer(PortalRepTelemetryDto.serializer()),
                result.telemetry,
            ),
        ).jsonArray
        val paths = linkedSetOf<String>()
        collectPaths(JsonArray(sessionsJson + telemetryJson), "", paths)
        return paths
    }

    private fun buildResult(): PortalSyncAdapter.PortalSessionBuildResult =
        PortalSyncAdapter.toPortalWorkoutSessionsWithTelemetry(listOf(fullFixtureSession()), "user-1")

    private fun fullFixtureSession(): PortalSyncAdapter.SessionWithReps {
        val session = WorkoutSession(
            id = "golden-session",
            timestamp = 1700000000000L,
            mode = "Old School",
            reps = 10,
            weightPerCableKg = 20f,
            duration = 60000L,
            totalReps = 10,
            warmupReps = 1,
            workingReps = 9,
            exerciseName = "Bench Press",
            exerciseId = "ex-catalog-1",
            routineSessionId = null,
            routineName = "Push Day",
            totalVolumeKg = 1800f,
            rpe = 8,
            updatedAt = 1700000060000L,
        )
        return PortalSyncAdapter.SessionWithReps(
            session = session,
            repMetrics = listOf(fullFixtureRep()),
            repBiomechanics = listOf(
                PortalSyncAdapter.RepBiomechanicsData(
                    repNumber = 1,
                    mcvMmS = 500f,
                    peakVelocityMmS = 800f,
                    velocityZone = "MODERATE",
                    asymmetryPercent = 5f,
                    dominantSide = "LEFT",
                    avgLoadA = 10f,
                    avgLoadB = 10f,
                ),
            ),
            muscleGroup = "Chest",
            isPr = false,
        )
    }

    private fun fullFixtureRep(): RepMetricData = RepMetricData(
        repNumber = 1,
        isWarmup = false,
        startTimestamp = 1700000000000L,
        endTimestamp = 1700000002500L,
        durationMs = 2500L,
        concentricDurationMs = 1000L,
        concentricPositions = floatArrayOf(0f, 100f, 200f),
        concentricLoadsA = floatArrayOf(10f, 12f, 11f),
        concentricLoadsB = floatArrayOf(10f, 12f, 11f),
        concentricVelocities = floatArrayOf(400f, 500f, 600f),
        concentricTimestamps = longArrayOf(0L, 500L, 1000L),
        eccentricDurationMs = 1500L,
        eccentricPositions = floatArrayOf(200f, 100f, 0f),
        eccentricLoadsA = floatArrayOf(11f, 10f, 9f),
        eccentricLoadsB = floatArrayOf(11f, 10f, 9f),
        eccentricVelocities = floatArrayOf(300f, 400f, 350f),
        eccentricTimestamps = longArrayOf(0L, 500L, 1000L),
        peakForceA = 15f,
        peakForceB = 15f,
        avgForceConcentricA = 10f,
        avgForceConcentricB = 10f,
        avgForceEccentricA = 8f,
        avgForceEccentricB = 8f,
        peakVelocity = 800f,
        avgVelocityConcentric = 500f,
        avgVelocityEccentric = 350f,
        rangeOfMotionMm = 300f,
        peakPowerWatts = 300f,
        avgPowerWatts = 200f,
    )

    private fun collectPaths(el: JsonElement, prefix: String, out: MutableSet<String>) {
        when (el) {
            is JsonObject -> {
                for (key in el.keys.sorted()) {
                    val path = if (prefix.isEmpty()) key else "$prefix.$key"
                    out.add(path)
                    collectPaths(el.getValue(key), path, out)
                }
            }
            is JsonArray -> for (item in el) collectPaths(item, "$prefix[]", out)
            else -> Unit
        }
    }

    private fun collectObjectsWithKey(el: JsonElement, key: String): List<JsonObject> {
        val found = mutableListOf<JsonObject>()
        when (el) {
            is JsonObject -> {
                if (el.containsKey(key)) found.add(el)
                for (v in el.values) found += collectObjectsWithKey(v, key)
            }
            is JsonArray -> for (item in el) found += collectObjectsWithKey(item, key)
            else -> Unit
        }
        return found
    }

    companion object {
        private const val FORBIDDEN_TOKEN_CSV =
            "powerloss,mcp,powermetric,losspercent,referenceworking,shortrom," +
                "triggerordinal,workingordinal,maxworkingreps,requiretwoconsecutive," +
                "targetmode,eccentricpower,concentricpower"

        private val FORBIDDEN_POWER_LOSS_TOKENS = FORBIDDEN_TOKEN_CSV.split(",")

        // Pinned pre-feature wire contract (arrays collapsed to []). Captured from the
        // fully-populated fixture on the PR branch, whose wire-relevant sources are
        // byte-identical to main@4d4de558. DO NOT extend this set for power-loss
        // fields — they must never reach mobile-sync-push.
        private val goldenWirePaths: Set<String> = setOf(
            "[].cable",
            "[].durationSeconds",
            "[].eccentricLoad",
            "[].echoLevel",
            "[].exerciseCount",
            "[].exercises",
            "[].exercises[].estimatedOneRepMaxKg",
            "[].exercises[].exerciseId",
            "[].exercises[].id",
            "[].exercises[].muscleGroup",
            "[].exercises[].name",
            "[].exercises[].orderIndex",
            "[].exercises[].sessionId",
            "[].exercises[].sets",
            "[].exercises[].sets[].actualReps",
            "[].exercises[].sets[].exerciseId",
            "[].exercises[].sets[].id",
            "[].exercises[].sets[].isPr",
            "[].exercises[].sets[].repSummaries",
            "[].exercises[].sets[].repSummaries[].asymmetryPct",
            "[].exercises[].sets[].repSummaries[].id",
            "[].exercises[].sets[].repSummaries[].leftForceAvg",
            "[].exercises[].sets[].repSummaries[].meanForceN",
            "[].exercises[].sets[].repSummaries[].meanVelocityMps",
            "[].exercises[].sets[].repSummaries[].peakForceN",
            "[].exercises[].sets[].repSummaries[].peakVelocityMps",
            "[].exercises[].sets[].repSummaries[].powerWatts",
            "[].exercises[].sets[].repSummaries[].repNumber",
            "[].exercises[].sets[].repSummaries[].rightForceAvg",
            "[].exercises[].sets[].repSummaries[].romMm",
            "[].exercises[].sets[].repSummaries[].setId",
            "[].exercises[].sets[].repSummaries[].tutMs",
            "[].exercises[].sets[].repSummaries[].vbtZone",
            "[].exercises[].sets[].rpe",
            "[].exercises[].sets[].setNumber",
            "[].exercises[].sets[].targetReps",
            "[].exercises[].sets[].weightKg",
            "[].exercises[].sets[].workoutMode",
            "[].forceN",
            "[].id",
            "[].name",
            "[].positionMm",
            "[].prCount",
            "[].routineName",
            "[].setCount",
            "[].setId",
            "[].startedAt",
            "[].timestampMs",
            "[].totalVolume",
            "[].updatedAt",
            "[].userId",
            "[].velocityMps",
            "[].warmupReps",
            "[].workingReps",
            "[].workoutMode",
        )
    }
}
