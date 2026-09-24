package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.data.local.ExerciseImporter
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.AsymmetryResult
import com.devil.phoenixproject.domain.model.BiomechanicsRepResult
import com.devil.phoenixproject.domain.model.BiomechanicsVelocityZone
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.ForceCurveResult
import com.devil.phoenixproject.domain.model.RepMetricData
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.StrengthProfile
import com.devil.phoenixproject.domain.model.VelocityResult
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.testutil.FakePreferencesManager
import com.devil.phoenixproject.testutil.createTestSchema
import com.devil.phoenixproject.testutil.seedExercise
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * F-012: a completed set is committed in ONE transaction.
 *
 * The interesting case is the one the step-by-step save could not survive — a
 * failure part-way through. Before, the session and the CompletedSet were
 * already durable by the time the rep metrics were written, so a failure there
 * left a session whose set data never arrived (and which a concurrent push
 * would stamp as synced). Now it leaves nothing.
 */
class CommitCompletedSetTest {

    private lateinit var realDriver: JdbcSqliteDriver
    private lateinit var database: PhoenixDatabase
    private lateinit var repository: SqlDelightWorkoutRepository

    /** Fails every statement whose SQL contains [failOnSqlFragment]; null disables injection. */
    private var failOnSqlFragment: String? = null

    private inner class FaultInjectingDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            failOnSqlFragment?.let { fragment ->
                if (sql.replace('\n', ' ').contains(fragment)) {
                    throw IllegalStateException("injected write failure for $fragment")
                }
            }
            return delegate.execute(identifier, sql, parameters, binders)
        }
    }

    @Before
    fun setup() {
        realDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createTestSchema(realDriver)
        database = PhoenixDatabase(FaultInjectingDriver(realDriver))
        database.seedExercise(EXERCISE_ID)
        val exerciseRepository = SqlDelightExerciseRepository(
            database,
            ExerciseImporter(database),
            FakePreferencesManager(),
        )
        repository = SqlDelightWorkoutRepository(database, exerciseRepository)
    }

    @After
    fun tearDown() {
        failOnSqlFragment = null
        realDriver.close()
    }

    @Test
    fun `a rep-metric failure rolls back the session and the set`() = runTest {
        failOnSqlFragment = "INSERT INTO RepMetric"

        assertFailsWith<IllegalStateException> {
            repository.commitCompletedSet(
                session = session(),
                metrics = listOf(metric()),
                completedSet = completedSet(),
                repMetrics = listOf(repMetric()),
                repBiomechanics = listOf(repBiomechanics()),
            )
        }

        val q = database.phoenixDatabaseQueries
        assertNull(q.selectSessionById(SESSION_ID).executeAsOneOrNull(), "The session must not survive a failed commit")
        assertNull(q.selectCompletedSetById(SET_ID).executeAsOneOrNull(), "The set must not survive a failed commit")
        assertTrue(q.selectMetricsBySession(SESSION_ID).executeAsList().isEmpty(), "No metric samples may survive")
        assertEquals(0L, q.countRepMetricsBySession(SESSION_ID).executeAsOne(), "No rep metrics may survive")
        assertTrue(
            q.selectRepBiomechanicsBySession(SESSION_ID).executeAsList().isEmpty(),
            "No rep biomechanics may survive",
        )
    }

    @Test
    fun `a successful commit writes every table once`() = runTest {
        repository.commitCompletedSet(
            session = session(),
            metrics = listOf(metric()),
            completedSet = completedSet(),
            repMetrics = listOf(repMetric()),
            repBiomechanics = listOf(repBiomechanics()),
        )

        val q = database.phoenixDatabaseQueries
        assertNotNull(q.selectSessionById(SESSION_ID).executeAsOneOrNull())
        assertNotNull(q.selectCompletedSetById(SET_ID).executeAsOneOrNull())
        assertEquals(1, q.selectMetricsBySession(SESSION_ID).executeAsList().size)
        assertEquals(1L, q.countRepMetricsBySession(SESSION_ID).executeAsOne())
        assertEquals(1, q.selectRepBiomechanicsBySession(SESSION_ID).executeAsList().size)
    }

    @Test
    fun `re-committing the same snapshot is a no-op and keeps is_pr`() = runTest {
        repository.commitCompletedSet(
            session = session(),
            metrics = listOf(metric()),
            completedSet = completedSet(),
            repMetrics = listOf(repMetric()),
            repBiomechanics = listOf(repBiomechanics()),
        )
        val q = database.phoenixDatabaseQueries
        // A retry happens after PR evaluation already marked the set.
        q.markCompletedSetAsPr(id = SET_ID)

        repository.commitCompletedSet(
            session = session(rpe = 9),
            metrics = listOf(metric()),
            completedSet = completedSet(),
            repMetrics = listOf(repMetric()),
            repBiomechanics = listOf(repBiomechanics()),
        )

        assertEquals(1, q.selectCompletedSetsBySession(SESSION_ID).executeAsList().size)
        assertEquals(1L, q.selectCompletedSetById(SET_ID).executeAsOne().is_pr, "A retry must not clear is_pr")
        assertEquals(1, q.selectMetricsBySession(SESSION_ID).executeAsList().size, "Samples are replaced, not appended")
        assertEquals(1L, q.countRepMetricsBySession(SESSION_ID).executeAsOne(), "Rep metrics are replaced, not appended")
        assertNull(
            q.selectSessionById(SESSION_ID).executeAsOne().rpe,
            "The first committed session row wins; a retry must not rewrite it",
        )
    }

    private fun session(rpe: Int? = null) = WorkoutSession(
        id = SESSION_ID,
        timestamp = 1_700_000_000_000L,
        mode = "Old School",
        reps = 5,
        weightPerCableKg = 40f,
        duration = 60_000L,
        totalReps = 5,
        warmupReps = 0,
        workingReps = 5,
        exerciseId = EXERCISE_ID,
        exerciseName = EXERCISE_ID,
        rpe = rpe,
        profileId = "default",
    )

    private fun completedSet() = CompletedSet(
        id = SET_ID,
        sessionId = SESSION_ID,
        plannedSetId = null,
        setNumber = 0,
        setType = SetType.STANDARD,
        actualReps = 5,
        actualWeightKg = 40f,
        loggedRpe = null,
        isPr = false,
        completedAt = 1_700_000_060_000L,
    )

    private fun metric() = WorkoutMetric(
        timestamp = 1_700_000_010_000L,
        loadA = 40f,
        loadB = 40f,
        positionA = 100f,
        positionB = 100f,
        velocityA = 0.5,
        velocityB = 0.5,
    )

    private fun repMetric() = RepMetricData(
        repNumber = 1,
        isWarmup = false,
        startTimestamp = 1_700_000_010_000L,
        endTimestamp = 1_700_000_012_000L,
        durationMs = 2_000L,
        concentricDurationMs = 1_000L,
        concentricPositions = floatArrayOf(0f, 50f),
        concentricLoadsA = floatArrayOf(40f, 40f),
        concentricLoadsB = floatArrayOf(40f, 40f),
        concentricVelocities = floatArrayOf(0.4f, 0.5f),
        concentricTimestamps = longArrayOf(1_700_000_010_000L, 1_700_000_011_000L),
        eccentricDurationMs = 1_000L,
        eccentricPositions = floatArrayOf(50f, 0f),
        eccentricLoadsA = floatArrayOf(40f, 40f),
        eccentricLoadsB = floatArrayOf(40f, 40f),
        eccentricVelocities = floatArrayOf(-0.4f, -0.5f),
        eccentricTimestamps = longArrayOf(1_700_000_011_000L, 1_700_000_012_000L),
        peakForceA = 42f,
        peakForceB = 41f,
        avgForceConcentricA = 40f,
        avgForceConcentricB = 40f,
        avgForceEccentricA = 38f,
        avgForceEccentricB = 38f,
        peakVelocity = 0.5f,
        avgVelocityConcentric = 0.45f,
        avgVelocityEccentric = -0.45f,
        rangeOfMotionMm = 500f,
        peakPowerWatts = 120f,
        avgPowerWatts = 100f,
    )

    private fun repBiomechanics() = BiomechanicsRepResult(
        velocity = VelocityResult(
            meanConcentricVelocityMmS = 450f,
            peakVelocityMmS = 500f,
            zone = BiomechanicsVelocityZone.MODERATE,
            velocityLossPercent = null,
            estimatedRepsRemaining = null,
            shouldStopSet = false,
            repNumber = 1,
        ),
        forceCurve = ForceCurveResult(
            normalizedForceN = floatArrayOf(1f, 1f),
            normalizedPositionPct = floatArrayOf(0f, 100f),
            stickingPointPct = null,
            strengthProfile = StrengthProfile.FLAT,
            repNumber = 1,
        ),
        asymmetry = AsymmetryResult(
            asymmetryPercent = 1f,
            dominantSide = "A",
            avgLoadA = 40f,
            avgLoadB = 39f,
            repNumber = 1,
        ),
        repNumber = 1,
        timestamp = 1_700_000_012_000L,
    )

    private companion object {
        const val SESSION_ID = "commit-session"
        const val SET_ID = "commit-set"
        const val EXERCISE_ID = "bench-press"
    }
}
