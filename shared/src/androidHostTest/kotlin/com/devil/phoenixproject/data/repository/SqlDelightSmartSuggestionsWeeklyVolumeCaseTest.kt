package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.MuscleGroupVolume
import com.devil.phoenixproject.domain.premium.SmartSuggestionsEngine
import com.devil.phoenixproject.testutil.createTestDatabase
import com.devil.phoenixproject.testutil.seedExercise
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Issue #1016 regression: Smart Insights "This Week's Volume" must aggregate muscle-group
 * tags case-insensitively through the REAL production read path:
 *
 *   Exercise rows (raw mixed-case muscleGroup strings)
 *     -> selectSessionSummariesSince (PhoenixDatabase.sq)
 *     -> SqlDelightSmartSuggestionsRepository.getSessionSummariesSince
 *     -> SmartSuggestionsEngine.computeWeeklyVolume
 *     -> rows rendered verbatim by SmartInsightsTab.WeeklyVolumeCard
 *
 * The first test seeds the reporter's screenshot data exactly (same tags, same case
 * variants, same per-set reps/load) and asserts the five-row canonical aggregate.
 */
class SqlDelightSmartSuggestionsWeeklyVolumeCaseTest {

    private lateinit var database: PhoenixDatabase
    private lateinit var repository: SqlDelightSmartSuggestionsRepository

    private val nowMs = 1_800_000_000_000L
    private val dayMs = 24L * 60 * 60 * 1000

    @Before
    fun setup() {
        database = createTestDatabase()
        repository = SqlDelightSmartSuggestionsRepository(database)
    }

    /**
     * One screenshot row: (tag as stored, per-set (working reps, weight kg)), plus the
     * expected per-tag aggregate (sets, reps, total kg) taken from the reporter's table.
     */
    private val screenshotRows: List<Pair<String, List<Pair<Int, Double>>>> =
        listOf(
            "Legs" to listOf(10 to 10.0, 10 to 10.0, 10 to 10.0, 10 to 10.0, 4 to 10.0, 3 to 10.0, 3 to 10.0),
            "LEGS" to listOf(4 to 10.0, 3 to 10.0),
            "SHOULDERS" to listOf(11 to 10.0, 11 to 10.0),
            "Shoulders" to listOf(7 to 10.0, 7 to 10.0),
            "BACK" to listOf(11 to 10.0, 11 to 10.0),
            "Back" to listOf(5 to 7.5, 5 to 7.5, 5 to 7.5, 4 to 5.625),
            "Chest" to listOf(10 to 8.0, 10 to 8.0, 10 to 8.0, 4 to 6.25, 4 to 6.875, 4 to 6.875),
            "CHEST" to listOf(11 to 10.0, 11 to 10.0),
            "Arms" to listOf(11 to 10.0, 11 to 10.0, 11 to 10.0, 11 to 10.0),
        )

    @Test
    fun `weekly volume aggregates the reporter's mixed-case tags into five canonical rows`() = runTest {
        seedScreenshotSessions(profileId = "default")

        val report = weeklyVolumeFor("default")

        // Case variants collapse to one row per muscle group, with a canonical label.
        assertEquals(
            listOf("Arms", "Back", "Chest", "Legs", "Shoulders"),
            report.volumes.map { it.muscleGroup }.sorted(),
        )

        val byLabel = report.volumes.associateBy { it.muscleGroup }
        assertRow(byLabel, "Legs", sets = 9, reps = 57, totalKg = 570f)
        assertRow(byLabel, "Shoulders", sets = 4, reps = 36, totalKg = 360f)
        assertRow(byLabel, "Back", sets = 6, reps = 41, totalKg = 355f)
        assertRow(byLabel, "Chest", sets = 8, reps = 64, totalKg = 540f)
        assertRow(byLabel, "Arms", sets = 4, reps = 44, totalKg = 440f)

        // Aggregation conserves every metric across the collapsed rows.
        assertEquals(31, report.volumes.sumOf { it.sets })
        assertEquals(242, report.volumes.sumOf { it.reps })
        assertTrue(abs(report.volumes.sumOf { it.totalKg.toDouble() } - 2265.0) < 0.01)
    }

    @Test
    fun `blank and whitespace-only stored tags aggregate into one Unknown row`() = runTest {
        database.seedExercise(id = "ex-blank", name = "Blank Tag", muscleGroup = "")
        database.seedExercise(id = "ex-space", name = "Space Tag", muscleGroup = "   ")
        database.seedExercise(id = "ex-tab", name = "Tab Tag", muscleGroup = "\t")
        insertSession(id = "s1", exerciseId = "ex-blank", exerciseName = "Blank Tag", timestamp = nowMs - dayMs, workingReps = 5L)
        insertSession(id = "s2", exerciseId = "ex-space", exerciseName = "Space Tag", timestamp = nowMs - dayMs * 2, workingReps = 5L)
        insertSession(id = "s3", exerciseId = "ex-tab", exerciseName = "Tab Tag", timestamp = nowMs - dayMs * 3, workingReps = 5L)

        val report = weeklyVolumeFor("default")

        assertEquals(listOf("Unknown"), report.volumes.map { it.muscleGroup })
        assertRow(report.volumes.associateBy { it.muscleGroup }, "Unknown", sets = 3, reps = 15, totalKg = 75f)
    }

    @Test
    fun `distinct muscle groups stay separate and are not alias-merged`() = runTest {
        database.seedExercise(id = "ex-back", name = "Row", muscleGroup = "BACK")
        database.seedExercise(id = "ex-lower-back", name = "Hyperextension", muscleGroup = "Lower Back")
        insertSession(id = "s1", exerciseId = "ex-back", exerciseName = "Row", timestamp = nowMs - dayMs, workingReps = 5L)
        insertSession(id = "s2", exerciseId = "ex-lower-back", exerciseName = "Hyperextension", timestamp = nowMs - dayMs * 2, workingReps = 5L)

        val report = weeklyVolumeFor("default")

        assertEquals(listOf("Back", "Lower Back"), report.volumes.map { it.muscleGroup }.sorted())
    }

    @Test
    fun `weekly volume stays profile scoped`() = runTest {
        seedScreenshotSessions(profileId = "default")
        database.seedExercise(id = "ex-other", name = "Other Curls", muscleGroup = "biceps")
        insertSession(
            id = "s-other",
            exerciseId = "ex-other",
            exerciseName = "Other Curls",
            timestamp = nowMs - dayMs,
            workingReps = 5L,
            profileId = "profile-b",
        )

        val defaultReport = weeklyVolumeFor("default")
        val profileBReport = weeklyVolumeFor("profile-b")

        assertEquals(listOf("Arms", "Back", "Chest", "Legs", "Shoulders"), defaultReport.volumes.map { it.muscleGroup }.sorted())
        assertEquals(listOf("Biceps"), profileBReport.volumes.map { it.muscleGroup })
    }

    @Test
    fun `weekly volume keeps the inclusive seven-day window on the real read path`() = runTest {
        database.seedExercise(id = "ex-back", name = "Row", muscleGroup = "BACK")
        // Repository fetch is `timestamp > nowMs - 28d`; engine window is inclusive [nowMs - 7d, nowMs].
        insertSession(id = "s-upper", exerciseId = "ex-back", exerciseName = "Row", timestamp = nowMs, workingReps = 5L)
        insertSession(id = "s-lower", exerciseId = "ex-back", exerciseName = "Row", timestamp = nowMs - dayMs * 7, workingReps = 5L)
        insertSession(id = "s-older", exerciseId = "ex-back", exerciseName = "Row", timestamp = nowMs - dayMs * 7 - 1, workingReps = 5L)
        insertSession(id = "s-future", exerciseId = "ex-back", exerciseName = "Row", timestamp = nowMs + 1, workingReps = 5L)

        val report = weeklyVolumeFor("default")

        assertEquals(listOf("Back"), report.volumes.map { it.muscleGroup })
        assertRow(report.volumes.associateBy { it.muscleGroup }, "Back", sets = 2, reps = 10, totalKg = 50f)
    }

    // ---- helpers ----

    private suspend fun weeklyVolumeFor(profileId: String) =
        SmartSuggestionsEngine.computeWeeklyVolume(
            repository.getSessionSummariesSince(nowMs - 28 * dayMs, profileId),
            nowMs,
        )

    private fun assertRow(
        byLabel: Map<String, MuscleGroupVolume>,
        label: String,
        sets: Int,
        reps: Int,
        totalKg: Float,
    ) {
        val row = byLabel[label] ?: error("missing row for label $label; got ${byLabel.keys}")
        assertEquals(sets, row.sets, "sets for $label")
        assertEquals(reps, row.reps, "reps for $label")
        assertTrue(abs(row.totalKg - totalKg) < 0.01f, "totalKg for $label: got ${row.totalKg}, expected $totalKg")
    }

    private fun seedScreenshotSessions(profileId: String) {
        for ((tag, sets) in screenshotRows) {
            val exerciseId = "ex-$tag"
            database.seedExercise(id = exerciseId, name = "Exercise for $tag", muscleGroup = tag)
            sets.forEachIndexed { index, (reps, weight) ->
                insertSession(
                    id = "s-$tag-$index",
                    exerciseId = exerciseId,
                    exerciseName = "Exercise for $tag",
                    timestamp = nowMs - dayMs + index,
                    weightPerCableKg = weight,
                    workingReps = reps.toLong(),
                    profileId = profileId,
                )
            }
        }
    }

    private fun insertSession(
        id: String,
        exerciseId: String,
        exerciseName: String,
        timestamp: Long,
        weightPerCableKg: Double = 5.0,
        workingReps: Long = 8L,
        profileId: String = "default",
        cableCount: Long? = 1L,
    ) {
        database.phoenixDatabaseQueries.insertSession(
            id = id,
            timestamp = timestamp,
            mode = "Old School",
            targetReps = 8L,
            weightPerCableKg = weightPerCableKg,
            progressionKg = 0.0,
            duration = 0L,
            totalReps = workingReps,
            warmupReps = 0L,
            workingReps = workingReps,
            isJustLift = 0L,
            stopAtTop = 0L,
            eccentricLoad = 100L,
            echoLevel = 0L,
            exerciseId = exerciseId,
            exerciseName = exerciseName,
            routineSessionId = null,
            routineName = null,
            routineId = null,
            safetyFlags = 0L,
            deloadWarningCount = 0L,
            romViolationCount = 0L,
            spotterActivations = 0L,
            peakForceConcentricA = null,
            peakForceConcentricB = null,
            peakForceEccentricA = null,
            peakForceEccentricB = null,
            avgForceConcentricA = null,
            avgForceConcentricB = null,
            avgForceEccentricA = null,
            avgForceEccentricB = null,
            heaviestLiftKg = null,
            totalVolumeKg = null,
            cableCount = cableCount,
            estimatedCalories = null,
            warmupAvgWeightKg = null,
            workingAvgWeightKg = null,
            burnoutAvgWeightKg = null,
            peakWeightKg = null,
            rpe = null,
            avgMcvMmS = null,
            avgAsymmetryPercent = null,
            totalVelocityLossPercent = null,
            dominantSide = null,
            strengthProfile = null,
            formScore = null,
            profile_id = profileId,
            display_multiplier = null,
            externalAddedLoadKg = 0.0,
            counterweightKg = 0.0,
            rackItemsJson = "[]",
        )
    }
}
