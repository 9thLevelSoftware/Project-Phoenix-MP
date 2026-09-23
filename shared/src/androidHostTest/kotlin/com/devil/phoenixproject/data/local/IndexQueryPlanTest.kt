package com.devil.phoenixproject.data.local

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.devil.phoenixproject.testutil.createTestDriver
import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Test

/**
 * F-068: the history list, the legacy catalogue remap and the routine-group lookups must be
 * index searches, not full WorkoutSession / RoutineExercise scans.
 *
 * Each statement is read from PhoenixDatabase.sq by its label, so a change to the production
 * query is what gets planned. Every check runs twice: on a fresh install (Schema.create) and on
 * a database built the upgrade way (migrations + post-open heal), because two of the indexes
 * exist on upgraded installs only through SchemaManifest.manifestIndexes.
 */
class IndexQueryPlanTest {

    private val databases: List<Pair<String, SqlDriver>> = listOf(
        "fresh install" to createTestDriver(),
        "upgraded + healed" to SchemaParityTest.upgradedAndHealedDriver(),
    )

    @Test
    fun `history list uses the profile and timestamp index without a sort`() = forEachDatabase { db, driver ->
        val plan = queryPlan(driver, "selectAllSessions")
        assertTrue(plan.any { "idx_session_profile_ts" in it }, "$db: $plan")
        assertFalse(plan.any { "TEMP B-TREE FOR ORDER BY" in it }, "$db: $plan")
    }

    @Test
    fun `legacy remap rewrites session exercise ids through an index`() = forEachDatabase { db, driver ->
        val plan = queryPlan(driver, "reassignWorkoutSessionExerciseId")
        assertTrue(plan.any { "idx_session_exercise" in it }, "$db: $plan")
    }

    @Test
    fun `legacy remap rewrites routine exercise ids through an index`() = forEachDatabase { db, driver ->
        val plan = queryPlan(driver, "reassignRoutineExerciseId")
        assertTrue(plan.any { "idx_routine_exercise_exercise" in it }, "$db: $plan")
    }

    @Test
    fun `routine group lookups use the routine session index`() = forEachDatabase { db, driver ->
        val softDelete = queryPlan(driver, "softDeleteSessionsByRoutineSessionId")
        assertTrue(softDelete.any { "idx_session_routine_session" in it }, "$db: $softDelete")

        // Runs on every completed routine set.
        val attempt = queryPlan(driver, "selectNextCompletedSetAttemptNumber")
        assertTrue(attempt.any { "idx_session_routine_session" in it }, "$db: $attempt")
    }

    @Test
    fun `the legacy remap gate probes every table through an index`() = forEachDatabase { db, driver ->
        val plan = queryPlan(driver, "selectArchivedStockExerciseIdsNeedingRemap")
        // The outer walk over archived catalogue rows is bounded by the catalogue; every
        // per-row probe into a user-data table must be an index search, never a scan.
        val probed = listOf(
            "WorkoutSession", "RoutineExercise", "PersonalRecord", "ExerciseSignature", "AssessmentResult",
            "VelocityOneRepMaxEstimate", "ExerciseMvt", "ProgressionEvent", "ProfileExerciseBaseline",
        )
        for (table in probed) {
            assertTrue(plan.any { it.startsWith("SEARCH $table ") && "INDEX" in it }, "$db: $table not searched: $plan")
            assertFalse(plan.any { it.startsWith("SCAN $table") }, "$db: $table scanned: $plan")
        }
    }

    private fun forEachDatabase(block: (String, SqlDriver) -> Unit) {
        for ((name, driver) in databases) block(name, driver)
    }

    private fun queryPlan(driver: SqlDriver, label: String): List<String> {
        val details = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = "EXPLAIN QUERY PLAN ${productionStatement(label)}",
            mapper = { cursor ->
                while (cursor.next().value) {
                    details += cursor.getString(3).orEmpty()
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return details
    }

    private companion object {
        val sq: String by lazy {
            readProjectFile("src/commonMain/sqldelight/com/devil/phoenixproject/database/PhoenixDatabase.sq")
                ?: fail("PhoenixDatabase.sq not found")
        }

        /**
         * The labelled statement from PhoenixDatabase.sq with its parameters turned into
         * unbound `?` placeholders (`IN :ids` becomes `IN (?)`). EXPLAIN QUERY PLAN only
         * prepares the statement, so unbound parameters are fine.
         */
        fun productionStatement(label: String): String {
            val lines = sq.lines()
            val start = lines.indexOfFirst { it == "$label:" }
            if (start < 0) fail("No statement labelled $label in PhoenixDatabase.sq")
            val body = StringBuilder()
            for (line in lines.drop(start + 1)) {
                val code = line.substringBefore("--")
                body.append(code).append('\n')
                if (code.trimEnd().endsWith(";")) break
            }
            return body.toString().trim().removeSuffix(";")
                .replace(Regex("""IN\s+:\w+"""), "IN (?)")
                .replace(Regex(""":\w+"""), "?")
        }
    }
}
