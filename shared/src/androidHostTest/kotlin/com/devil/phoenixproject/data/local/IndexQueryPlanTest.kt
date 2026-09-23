package com.devil.phoenixproject.data.local

import app.cash.sqldelight.db.QueryResult
import com.devil.phoenixproject.testutil.createTestDriver
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * F-068: the history list, the legacy catalogue remap and the routine-group lookups must be
 * index searches, not full WorkoutSession / RoutineExercise scans. Each statement below is the
 * production query from PhoenixDatabase.sq with literals in place of its parameters.
 */
class IndexQueryPlanTest {

    private val driver = createTestDriver()

    @Test
    fun `history list uses the profile and timestamp index without a sort`() {
        // selectAllSessions
        val plan = queryPlan(
            """
            SELECT * FROM WorkoutSession
            WHERE profile_id = 'p1'
            AND deletedAt IS NULL
            ORDER BY timestamp DESC
            """,
        )
        assertTrue(plan.any { "idx_session_profile_ts" in it }, "plan: $plan")
        assertFalse(plan.any { "TEMP B-TREE FOR ORDER BY" in it }, "plan: $plan")
    }

    @Test
    fun `legacy remap rewrites session exercise ids through an index`() {
        // reassignWorkoutSessionExerciseId
        val plan = queryPlan(
            """
            UPDATE WorkoutSession
            SET exerciseId = 'new-id',
                local_sync_generation = local_sync_generation + 1
            WHERE exerciseId = 'old-id'
            """,
        )
        assertTrue(plan.any { "idx_session_exercise" in it }, "plan: $plan")
    }

    @Test
    fun `legacy remap rewrites routine exercise ids through an index`() {
        // reassignRoutineExerciseId
        val plan = queryPlan("UPDATE RoutineExercise SET exerciseId = 'new-id' WHERE exerciseId = 'old-id'")
        assertTrue(plan.any { "idx_routine_exercise_exercise" in it }, "plan: $plan")
    }

    @Test
    fun `routine group lookups use the routine session index`() {
        // softDeleteSessionsByRoutineSessionId
        val softDelete = queryPlan(
            """
            UPDATE WorkoutSession
            SET deletedAt = 1, updatedAt = 1
            WHERE routineSessionId = 'rs1'
              AND deletedAt IS NULL
            """,
        )
        assertTrue(softDelete.any { "idx_session_routine_session" in it }, "plan: $softDelete")

        // selectNextCompletedSetAttemptNumber (runs on every completed routine set)
        val attempt = queryPlan(
            """
            SELECT COALESCE(MAX(CASE WHEN cs.attempt_number < 1 THEN 1 ELSE cs.attempt_number END), 0) + 1
            FROM CompletedSet cs
            INNER JOIN WorkoutSession ws ON cs.session_id = ws.id
            WHERE ws.routineSessionId = 'rs1'
              AND ws.deletedAt IS NULL
              AND cs.routine_exercise_id = 're1'
              AND cs.set_number = 1
              AND cs.set_type = 'STANDARD'
            """,
        )
        assertTrue(attempt.any { "idx_session_routine_session" in it }, "plan: $attempt")
    }

    @Test
    fun `last weight lookup and recent history are index searches`() {
        // selectLastWeightForExercise
        val lastWeight = queryPlan(
            """
            SELECT weightPerCableKg FROM WorkoutSession
            WHERE profile_id = 'p1'
            AND exerciseId = 'bench'
            AND deletedAt IS NULL
            ORDER BY timestamp DESC
            LIMIT 1
            """,
        )
        assertTrue(lastWeight.any { "idx_session_exercise" in it || "idx_session_profile_ts" in it }, "plan: $lastWeight")
        assertFalse(lastWeight.any { it.startsWith("SCAN WorkoutSession") }, "plan: $lastWeight")

        // selectRecentVisibleSessions
        val recent = queryPlan(
            """
            SELECT * FROM WorkoutSession
            WHERE profile_id = 'p1'
            AND deletedAt IS NULL
            ORDER BY timestamp DESC
            LIMIT 20
            """,
        )
        assertTrue(recent.any { "idx_session_profile_ts" in it }, "plan: $recent")
        assertFalse(recent.any { "TEMP B-TREE FOR ORDER BY" in it }, "plan: $recent")
    }

    private fun queryPlan(sql: String): List<String> {
        val details = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = "EXPLAIN QUERY PLAN ${sql.trimIndent()}",
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
}
