package com.devil.phoenixproject.presentation.manager

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.data.repository.SqlDelightCompletedSetRepository
import com.devil.phoenixproject.data.repository.SqlDelightWorkoutRepository
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.usecase.EchoAchievedLoadResolver
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Issue #1182 durability/ownership acceptance (merge-gate R5a).
 *
 * The earlier persistence matrix built fresh repositories around the SAME in-memory database
 * object and called that "reopen", and its ownership check compared a profileId to its own
 * re-read value. Neither proves anything: no driver was ever closed, no file was ever re-read,
 * and no second profile was present to be confused with the first.
 *
 * These tests close the driver and re-open the SAME database FILE with a brand new driver, then
 * assert:
 *  - the configured-versus-achieved distinction and the unmeasured sentinel survive a real
 *    reopen (durable on disk, not cached in the first connection);
 *  - two profiles keep strictly separate rows - neither profile's query may leak the other's
 *    session, and a completed set stays owned by its own session across the reopen.
 *
 * Writes go through [SqlDelightWorkoutRepository.commitCompletedSet], the same F-012 atomic
 * transaction the engine uses at set completion, so the durability claim covers the production
 * write path rather than a test-only insert.
 */
class Issue1182DatabaseDurabilityTest {

    /** A real on-disk database file, distinct per test run. */
    private fun databaseFile(name: String): File =
        File.createTempFile("issue1182-$name", ".db").also { it.deleteOnExit() }

    /**
     * Opens a file-backed driver that CAN be closed; schema-created on first use only.
     *
     * The shared [createTestSchema] helper asserts the `foreign_keys` pragma read-back, which
     * only holds for the single-connection IN_MEMORY driver - a file-backed `JdbcSqliteDriver`
     * serves `execute` and `executeQuery` from separate connections, so a per-connection pragma
     * cannot be read back that way. That assertion is unrelated to what these tests prove
     * (durability across a real close/reopen and profile ownership), so the schema is created
     * here without it.
     */
    private fun openDriver(file: File, createSchema: Boolean): JdbcSqliteDriver =
        JdbcSqliteDriver("jdbc:sqlite:${file.path}").also { driver ->
            if (createSchema) {
                PhoenixDatabase.Schema.create(driver)
                driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            }
        }

    private fun deleteWithSidecars(file: File) {
        file.delete()
        listOf("-wal", "-shm", "-journal").forEach { File("${file.path}$it").delete() }
    }

    // ===== fixtures =====

    /** The reporter's row shape: configured 5 kg seed, measured peak 80 kg/cable. */
    private fun achievedEchoSession(id: String, profileId: String) = WorkoutSession(
        id = id,
        timestamp = 1_790_000_000_000L,
        mode = "Echo",
        reps = 0,
        weightPerCableKg = 5f,
        duration = 94_000L,
        totalReps = 9,
        workingReps = 9,
        exerciseName = "Barbell Squat",
        heaviestLiftKg = 80f,
        workingAvgWeightKg = 80f,
        peakForceConcentricA = 640f,
        peakForceEccentricA = 660f,
        profileId = profileId,
    )

    /** An Echo set that captured no accepted working telemetry: the non-null 0 sentinel. */
    private fun unmeasuredEchoSession(id: String, profileId: String) = WorkoutSession(
        id = id,
        timestamp = 1_790_000_100_000L,
        mode = "Echo",
        reps = 0,
        weightPerCableKg = 5f,
        duration = 30_000L,
        totalReps = 3,
        workingReps = 3,
        exerciseName = "Barbell Squat",
        heaviestLiftKg = 0f,
        profileId = profileId,
    )

    /** Echo set row carrying the achieved peak (80) rather than the configured seed (5). */
    private fun achievedSet(sessionId: String, setId: String) = CompletedSet.create(
        id = setId,
        sessionId = sessionId,
        setNumber = 1,
        actualReps = 9,
        actualWeightKg = 80f,
        setEndReason = SetEndReason.CABLE_RELEASED,
    )

    // ===== R5a: real close / reopen =====

    @Test
    fun `achieved and configured distinction survives an actual driver close and reopen`() {
        val dbFile = databaseFile("durability")
        try {
            val sessionId = "sess-achieved-80"
            // ---- first connection: write through the production F-012 transaction ----
            openDriver(dbFile, createSchema = true).use { driver ->
                val repository = SqlDelightWorkoutRepository(PhoenixDatabase(driver), FakeExerciseRepository())
                runBlocking {
                    repository.commitCompletedSet(
                        session = achievedEchoSession(sessionId, profileId = "alpha"),
                        metrics = emptyList<WorkoutMetric>(),
                        completedSet = null,
                        repMetrics = emptyList(),
                        repBiomechanics = emptyList(),
                    )
                }
            } // <-- the driver is genuinely CLOSED here (JdbcSqliteDriver.close())

            // ---- second connection: a brand new driver over the same FILE ----
            openDriver(dbFile, createSchema = false).use { driver ->
                val repository = SqlDelightWorkoutRepository(PhoenixDatabase(driver), FakeExerciseRepository())
                val reopened = assertNotNull(
                    runBlocking { repository.getSession(sessionId) },
                    "the session row must survive an actual close/reopen of the database file",
                )

                // Durable, not cached: configured metadata and measured peak stay distinct.
                assertEquals(5f, reopened.weightPerCableKg, "configured/command metadata is durable")
                assertEquals(80f, reopened.heaviestLiftKg, "the measured peak is durable")
                assertEquals(80f, EchoAchievedLoadResolver.fromSession(reopened), "achieved load resolves after reopen")
                assertEquals(80f, EchoAchievedLoadResolver.primaryLoadKg(reopened))
            }
        } finally {
            deleteWithSidecars(dbFile)
        }
    }

    @Test
    fun `unmeasured sentinel survives reopen and never resolves to the configured seed`() {
        val dbFile = databaseFile("sentinel")
        try {
            val sessionId = "sess-unmeasured"
            openDriver(dbFile, createSchema = true).use { driver ->
                val repository = SqlDelightWorkoutRepository(PhoenixDatabase(driver), FakeExerciseRepository())
                runBlocking {
                    repository.commitCompletedSet(
                        session = unmeasuredEchoSession(sessionId, profileId = "alpha"),
                        metrics = emptyList<WorkoutMetric>(),
                        completedSet = null,
                        repMetrics = emptyList(),
                        repBiomechanics = emptyList(),
                    )
                }
            }

            openDriver(dbFile, createSchema = false).use { driver ->
                val repository = SqlDelightWorkoutRepository(PhoenixDatabase(driver), FakeExerciseRepository())
                val reopened = assertNotNull(runBlocking { repository.getSession(sessionId) })

                assertEquals(0f, reopened.heaviestLiftKg, "the non-null 0 sentinel is durable")
                assertEquals(5f, reopened.weightPerCableKg, "the configured seed is NOT rewritten into the measured column")
                assertNull(
                    EchoAchievedLoadResolver.fromSession(reopened),
                    "no accepted telemetry -> Load unavailable after reopen, never the configured 11.02 lb",
                )
                assertNull(EchoAchievedLoadResolver.primaryLoadKg(reopened))
            }
        } finally {
            deleteWithSidecars(dbFile)
        }
    }

    @Test
    fun `a completed set stays owned by its own session across a real reopen`() {
        val dbFile = databaseFile("ownership")
        try {
            val sessionId = "sess-owner"
            val setId = "set-owner-1"
            openDriver(dbFile, createSchema = true).use { driver ->
                val repository = SqlDelightWorkoutRepository(PhoenixDatabase(driver), FakeExerciseRepository())
                runBlocking {
                    repository.commitCompletedSet(
                        session = achievedEchoSession(sessionId, profileId = "alpha"),
                        metrics = emptyList<WorkoutMetric>(),
                        completedSet = achievedSet(sessionId, setId),
                        repMetrics = emptyList(),
                        repBiomechanics = emptyList(),
                    )
                }
            }

            openDriver(dbFile, createSchema = false).use { driver ->
                val database = PhoenixDatabase(driver)
                val workoutRepository = SqlDelightWorkoutRepository(database, FakeExerciseRepository())
                val completedSetRepository = SqlDelightCompletedSetRepository(database)

                val reopenedSession = assertNotNull(runBlocking { workoutRepository.getSession(sessionId) })
                val sets = runBlocking { completedSetRepository.getCompletedSets(sessionId) }
                assertEquals(1, sets.size, "exactly one set row for this session")
                val set = sets.single()
                assertEquals(sessionId, set.sessionId, "the set row stays owned by its session")
                assertEquals(setId, set.id, "the set id is stable across reopen")
                assertEquals(80f, set.actualWeightKg, "the achieved peak is durable on the set row")
                assertEquals(
                    80f,
                    EchoAchievedLoadResolver.completedSetLoadKg(set, reopenedSession),
                    "the set row resolves to the achieved load after reopen",
                )
            }
        } finally {
            deleteWithSidecars(dbFile)
        }
    }

    // ===== R5a: explicit two-profile ownership =====

    @Test
    fun `two profiles keep strictly separate echo rows through a real reopen`() {
        val dbFile = databaseFile("two-profile")
        try {
            val alphaId = "sess-alpha-achieved"
            val betaId = "sess-beta-unmeasured"
            openDriver(dbFile, createSchema = true).use { driver ->
                val repository = SqlDelightWorkoutRepository(PhoenixDatabase(driver), FakeExerciseRepository())
                runBlocking {
                    repository.commitCompletedSet(
                        session = achievedEchoSession(alphaId, profileId = "alpha"),
                        metrics = emptyList<WorkoutMetric>(),
                        completedSet = null,
                        repMetrics = emptyList(),
                        repBiomechanics = emptyList(),
                    )
                    repository.commitCompletedSet(
                        session = unmeasuredEchoSession(betaId, profileId = "beta"),
                        metrics = emptyList<WorkoutMetric>(),
                        completedSet = null,
                        repMetrics = emptyList(),
                        repBiomechanics = emptyList(),
                    )
                }
            }

            openDriver(dbFile, createSchema = false).use { driver ->
                val repository = SqlDelightWorkoutRepository(PhoenixDatabase(driver), FakeExerciseRepository())

                val alphaRows = runBlocking { repository.getAllSessions("alpha").first() }
                val betaRows = runBlocking { repository.getAllSessions("beta").first() }

                // Strict isolation: no profile may see the other's session.
                assertEquals(listOf(alphaId), alphaRows.map { it.id }, "alpha sees only its own session")
                assertEquals(listOf(betaId), betaRows.map { it.id }, "beta sees only its own session")
                assertTrue(
                    alphaRows.none { it.id == betaId } && betaRows.none { it.id == alphaId },
                    "profile queries must not leak rows across profiles",
                )

                // Ownership is carried per row, not assumed from the active profile.
                val alphaSession = assertNotNull(runBlocking { repository.getSession(alphaId) })
                val betaSession = assertNotNull(runBlocking { repository.getSession(betaId) })
                assertEquals("alpha", alphaSession.profileId, "alpha row keeps its own profileId")
                assertEquals("beta", betaSession.profileId, "beta row keeps its own profileId")

                // Each profile's row keeps its own achieved/unmeasured semantics - a mix-up would
                // report the wrong load for either profile.
                assertEquals(80f, EchoAchievedLoadResolver.primaryLoadKg(alphaSession), "alpha resolves its achieved 80 kg")
                assertNull(EchoAchievedLoadResolver.primaryLoadKg(betaSession), "beta resolves unavailable, not alpha's 80")
                assertEquals(0f, betaSession.heaviestLiftKg, "beta keeps its own sentinel")
                assertEquals(80f, alphaSession.heaviestLiftKg, "alpha keeps its own measurement")
            }
        } finally {
            deleteWithSidecars(dbFile)
        }
    }

    @Test
    fun `a completed set does not surface under a different session id after reopen`() {
        val dbFile = databaseFile("set-isolation")
        try {
            val alphaId = "sess-alpha"
            val betaId = "sess-beta"
            openDriver(dbFile, createSchema = true).use { driver ->
                val repository = SqlDelightWorkoutRepository(PhoenixDatabase(driver), FakeExerciseRepository())
                runBlocking {
                    repository.commitCompletedSet(
                        session = achievedEchoSession(alphaId, profileId = "alpha"),
                        metrics = emptyList<WorkoutMetric>(),
                        completedSet = achievedSet(alphaId, "set-alpha-1"),
                        repMetrics = emptyList(),
                        repBiomechanics = emptyList(),
                    )
                    repository.commitCompletedSet(
                        session = unmeasuredEchoSession(betaId, profileId = "beta"),
                        metrics = emptyList<WorkoutMetric>(),
                        completedSet = null,
                        repMetrics = emptyList(),
                        repBiomechanics = emptyList(),
                    )
                }
            }

            openDriver(dbFile, createSchema = false).use { driver ->
                val completedSetRepository = SqlDelightCompletedSetRepository(PhoenixDatabase(driver))

                assertEquals(
                    1,
                    runBlocking { completedSetRepository.getCompletedSets(alphaId) }.size,
                    "alpha owns its set",
                )
                assertTrue(
                    runBlocking { completedSetRepository.getCompletedSets(betaId) }.isEmpty(),
                    "beta must not inherit alpha's completed set",
                )
            }
        } finally {
            deleteWithSidecars(dbFile)
        }
    }
}
