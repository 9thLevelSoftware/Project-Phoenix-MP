package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.data.integration.intervals.AdmissionResult
import com.devil.phoenixproject.data.integration.intervals.IntervalsChildKind
import com.devil.phoenixproject.data.integration.intervals.IntervalsChildRef
import com.devil.phoenixproject.data.integration.intervals.IntervalsCompletionCapture
import com.devil.phoenixproject.data.integration.intervals.IntervalsCompletionKey
import com.devil.phoenixproject.data.integration.intervals.IntervalsFinishReason
import com.devil.phoenixproject.data.integration.intervals.IntervalsJobState
import com.devil.phoenixproject.data.integration.intervals.IntervalsLocalKind
import com.devil.phoenixproject.data.integration.intervals.WorkoutCompletionFinalizer
import com.devil.phoenixproject.data.local.ExerciseImporter
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.AsymmetryResult
import com.devil.phoenixproject.domain.model.BiomechanicsRepResult
import com.devil.phoenixproject.domain.model.BiomechanicsVelocityZone
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.ForceCurveResult
import com.devil.phoenixproject.domain.model.RepMetricData
import com.devil.phoenixproject.domain.model.SampleStatus
import com.devil.phoenixproject.domain.model.SetEndReason
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
    fun `commitCompletedSet round trips selected cable samples exactly`() = runTest {
        val samples = (0..16).map { index ->
            val positionA = when (index) {
                in 0..7 -> 100f
                8 -> 101.5f
                9 -> 103f
                10 -> 105.5f
                11 -> 108f
                else -> 111f
            }
            WorkoutMetric(
                timestamp = index * 250L,
                loadA = 20f + index,
                loadB = 18f + index * 0.5f,
                positionA = positionA,
                positionB = 200f,
                velocityA = 0.25 + index * 0.01,
                velocityB = -0.5 + index * 0.02,
                status = when (index) {
                    8 -> SampleStatus.ROM_OUTSIDE_HIGH
                    9 -> SampleStatus.ROM_OUTSIDE_LOW
                    10 -> SampleStatus.SPOTTER_ACTIVE
                    11 -> SampleStatus.DELOAD_WARN
                    12 -> SampleStatus.DELOAD_OCCURRED
                    else -> 0
                },
                heartRateBpm = 100 + index,
                rrIntervalMs = 800f - index,
                torsoPitchDeg = 1f + index,
                torsoRollDeg = -2f - index,
                dynamicAccMg = 10 + index,
                chestAccXMg = -20 + index,
                chestAccYMg = 30 + index,
                chestAccZMg = -40 + index,
            )
        }
        val retained = samples.drop(4)

        repository.commitCompletedSet(
            session = session(),
            metrics = retained,
            completedSet = completedSet(),
            repMetrics = emptyList(),
            repBiomechanics = emptyList(),
        )

        val persisted = database.phoenixDatabaseQueries.selectMetricsBySession(SESSION_ID).executeAsList()
        assertEquals(retained.size, persisted.size, "persist exactly the selected samples")
        assertEquals(retained.map { it.timestamp }, persisted.map { it.timestamp }, "preserve timestamp order")
        assertEquals(retained.map { it.positionA.toDouble() }, persisted.map { it.position })
        assertEquals(retained.map { it.positionB.toDouble() }, persisted.map { it.positionB })
        assertEquals(retained.map { it.velocityA }, persisted.map { it.velocity })
        assertEquals(retained.map { it.velocityB }, persisted.map { it.velocityB })
        assertEquals(retained.map { it.loadA.toDouble() }, persisted.map { it.load })
        assertEquals(retained.map { it.loadB.toDouble() }, persisted.map { it.loadB })
        assertEquals(
            retained.map { ((it.loadA + it.loadB) * it.velocityA.toFloat()).toDouble() },
            persisted.map { it.power },
        )
        assertEquals(retained.map { it.status.toLong() }, persisted.map { it.status })
        assertEquals(retained.map { it.heartRateBpm?.toLong() }, persisted.map { it.heartRateBpm })
        assertEquals(retained.map { it.rrIntervalMs?.toDouble() }, persisted.map { it.rrIntervalMs })
        assertEquals(retained.map { it.torsoPitchDeg?.toDouble() }, persisted.map { it.torsoPitchDeg })
        assertEquals(retained.map { it.torsoRollDeg?.toDouble() }, persisted.map { it.torsoRollDeg })
        assertEquals(retained.map { it.dynamicAccMg?.toLong() }, persisted.map { it.dynamicAccMg })
        assertEquals(retained.map { it.chestAccXMg?.toLong() }, persisted.map { it.chestAccXMg })
        assertEquals(retained.map { it.chestAccYMg?.toLong() }, persisted.map { it.chestAccYMg })
        assertEquals(retained.map { it.chestAccZMg?.toLong() }, persisted.map { it.chestAccZMg })
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

    // ==================== Issue #1384: completion-aware commit ====================

    private fun newFinalizer() = WorkoutCompletionFinalizer(
        db = database,
        workoutRepository = repository,
        now = { 1_700_000_060_000L },
    )

    private fun routineMembership() = WorkoutCompletionMembership(
        profileId = "default",
        localKind = IntervalsLocalKind.ROUTINE,
        completionId = "routine-1384",
        sessionId = SESSION_ID,
        childKey = SET_ID,
        childKind = IntervalsChildKind.COMPLETED_SET,
    )

    @Test
    fun `a completion-aware commit marks the admitted child committed in the same transaction`() = runTest {
        val finalizer = newFinalizer()
        val write = com.devil.phoenixproject.data.integration.intervals.PendingChildWrite(
            session = session(),
            metrics = listOf(metric()),
            completedSet = completedSet(),
            repMetrics = listOf(repMetric()),
            repBiomechanics = listOf(repBiomechanics()),
        )
        assertEquals(
            AdmissionResult.Accepted,
            finalizer.requestFinalize(
                capture(),
                listOf(
                    IntervalsChildRef(SET_ID, IntervalsChildKind.COMPLETED_SET, SESSION_ID, pendingAtAdmission = true),
                ),
                listOf(
                    com.devil.phoenixproject.data.integration.intervals.PendingChildReplay(SET_ID, write),
                ),
            ),
        )

        repository.commitCompletedSetWithCompletion(
            session = session(),
            metrics = listOf(metric()),
            completedSet = completedSet(),
            repMetrics = listOf(repMetric()),
            repBiomechanics = listOf(repBiomechanics()),
            completion = routineMembership(),
        )

        val child = database.phoenixDatabaseQueries
            .selectCompletionJournalChildren("default", "ROUTINE", "routine-1384")
            .executeAsList()
            .single()
        assertEquals(1L, child.committed, "the child is committed in the SAME transaction as its set write")
        assertNull(child.replay_payload, "the replay payload is cleared only by that same transaction")
    }

    @Test
    fun `a failed completion-aware commit rolls the child back to uncommitted with its payload`() = runTest {
        val finalizer = newFinalizer()
        val write = com.devil.phoenixproject.data.integration.intervals.PendingChildWrite(
            session = session(),
            metrics = listOf(metric()),
            completedSet = completedSet(),
            repMetrics = listOf(repMetric()),
            repBiomechanics = listOf(repBiomechanics()),
        )
        assertEquals(
            AdmissionResult.Accepted,
            finalizer.requestFinalize(
                capture(),
                listOf(
                    IntervalsChildRef(SET_ID, IntervalsChildKind.COMPLETED_SET, SESSION_ID, pendingAtAdmission = true),
                ),
                listOf(
                    com.devil.phoenixproject.data.integration.intervals.PendingChildReplay(SET_ID, write),
                ),
            ),
        )

        failOnSqlFragment = "INSERT INTO RepMetric"
        assertFailsWith<IllegalStateException> {
            repository.commitCompletedSetWithCompletion(
                session = session(),
                metrics = listOf(metric()),
                completedSet = completedSet(),
                repMetrics = listOf(repMetric()),
                repBiomechanics = listOf(repBiomechanics()),
                completion = routineMembership(),
            )
        }

        val child = database.phoenixDatabaseQueries
            .selectCompletionJournalChildren("default", "ROUTINE", "routine-1384")
            .executeAsList()
            .single()
        assertEquals(0L, child.committed, "a failed set write must not acknowledge the child")
        assertNotNull(child.replay_payload, "the only recoverable copy must survive the failed write")
    }

    @Test
    fun `a standalone commit creates its FINALIZED receipt and export intent atomically`() = runTest {
        val capture = capture(
            localKind = IntervalsLocalKind.STANDALONE,
            completionId = SESSION_ID,
            finishReason = IntervalsFinishReason.STANDALONE_COMMIT,
        )
        repository.commitCompletedSetWithCompletion(
            session = session(),
            metrics = listOf(metric()),
            completedSet = completedSet(),
            repMetrics = listOf(repMetric()),
            repBiomechanics = listOf(repBiomechanics()),
            completion = WorkoutCompletionMembership(
                profileId = "default",
                localKind = IntervalsLocalKind.STANDALONE,
                completionId = SESSION_ID,
                sessionId = SESSION_ID,
                childKey = SET_ID,
                childKind = IntervalsChildKind.COMPLETED_SET,
                standaloneCapture = capture,
            ),
        )

        val q = database.phoenixDatabaseQueries
        assertNotNull(q.selectSessionById(SESSION_ID).executeAsOneOrNull())
        val receipt = q.selectCompletionReceipt("default", "STANDALONE", SESSION_ID).executeAsOne()
        assertEquals(1L, receipt.eligible)
        assertEquals(SESSION_ID, receipt.external_id)
        assertNotNull(receipt.export_payload)
        val job = q.selectIntervalsJob("default", "athlete-1", SESSION_ID).executeAsOne()
        assertEquals(IntervalsJobState.PENDING.name, job.state)
    }

    @Test
    fun `a standalone receipt failure never rolls back or fails the user's set write`() = runTest {
        val capture = capture(
            localKind = IntervalsLocalKind.STANDALONE,
            completionId = SESSION_ID,
            finishReason = IntervalsFinishReason.STANDALONE_COMMIT,
        )
        failOnSqlFragment = "INSERT INTO WorkoutCompletionReceipt"
        repository.commitCompletedSetWithCompletion(
            session = session(),
            metrics = listOf(metric()),
            completedSet = completedSet(),
            repMetrics = emptyList(),
            repBiomechanics = emptyList(),
            completion = WorkoutCompletionMembership(
                profileId = "default",
                localKind = IntervalsLocalKind.STANDALONE,
                completionId = SESSION_ID,
                sessionId = SESSION_ID,
                childKey = SET_ID,
                childKind = IntervalsChildKind.COMPLETED_SET,
                standaloneCapture = capture,
            ),
        )

        // The split guarded transaction boundary: an optional, opt-in export must never
        // cost workout data OR surface an exception to ActiveSessionEngine's set-save
        // pipeline. The user's set/metric writes are durable and the commit returns
        // normally even when the export-side receipt write fails.
        val q = database.phoenixDatabaseQueries
        assertNotNull(q.selectSessionById(SESSION_ID).executeAsOneOrNull(), "workout data must survive an export failure")
        assertNotNull(q.selectCompletedSetById(SET_ID).executeAsOneOrNull(), "completed set must survive an export failure")
        assertEquals(1, q.selectMetricsBySession(SESSION_ID).executeAsList().size, "metrics must survive an export failure")
        assertNull(q.selectCompletionReceipt("default", "STANDALONE", SESSION_ID).executeAsOneOrNull())
    }

    @Test
    fun `a routine journal child side-effect failure never fails the user's set write`() = runTest {
        val finalizer = newFinalizer()
        val write = com.devil.phoenixproject.data.integration.intervals.PendingChildWrite(
            session = session(),
            metrics = listOf(metric()),
            completedSet = completedSet(),
            repMetrics = listOf(repMetric()),
            repBiomechanics = listOf(repBiomechanics()),
        )
        assertEquals(
            AdmissionResult.Accepted,
            finalizer.requestFinalize(
                capture(),
                listOf(
                    IntervalsChildRef(SET_ID, IntervalsChildKind.COMPLETED_SET, SESSION_ID, pendingAtAdmission = true),
                ),
                listOf(
                    com.devil.phoenixproject.data.integration.intervals.PendingChildReplay(SET_ID, write),
                ),
            ),
        )

        failOnSqlFragment = "UPDATE WorkoutCompletionJournalChild"
        repository.commitCompletedSetWithCompletion(
            session = session(),
            metrics = listOf(metric()),
            completedSet = completedSet(),
            repMetrics = listOf(repMetric()),
            repBiomechanics = listOf(repBiomechanics()),
            completion = routineMembership(),
        )

        val q = database.phoenixDatabaseQueries
        assertNotNull(q.selectSessionById(SESSION_ID).executeAsOneOrNull(), "session must survive journal side-effect failure")
        assertNotNull(q.selectCompletedSetById(SET_ID).executeAsOneOrNull(), "set must survive journal side-effect failure")
        val child = q.selectCompletionJournalChildren("default", "ROUTINE", "routine-1384").executeAsList().single()
        assertEquals(0L, child.committed, "failed side-effect transaction leaves replay payload intact for recovery")
        assertNotNull(child.replay_payload)
    }

    @Test
    fun `a standalone eligible commit fires onStandaloneExportQueued once`() = runTest {
        var signals = 0
        val exerciseRepository = SqlDelightExerciseRepository(
            database,
            ExerciseImporter(database),
            FakePreferencesManager(),
        )
        val signalingRepository = SqlDelightWorkoutRepository(
            db = database,
            exerciseRepository = exerciseRepository,
            onStandaloneExportQueued = { signals++ },
        )
        val capture = capture(
            localKind = IntervalsLocalKind.STANDALONE,
            completionId = SESSION_ID,
            finishReason = IntervalsFinishReason.STANDALONE_COMMIT,
        )
        signalingRepository.commitCompletedSetWithCompletion(
            session = session(),
            metrics = listOf(metric()),
            completedSet = completedSet(),
            repMetrics = emptyList(),
            repBiomechanics = emptyList(),
            completion = WorkoutCompletionMembership(
                profileId = "default",
                localKind = IntervalsLocalKind.STANDALONE,
                completionId = SESSION_ID,
                sessionId = SESSION_ID,
                childKey = SET_ID,
                childKind = IntervalsChildKind.COMPLETED_SET,
                standaloneCapture = capture,
            ),
        )
        assertEquals(1, signals, "eligible standalone commit must signal the export drain")
    }

    @Test
    fun `zero-rep standalone work produces no receipt`() = runTest {
        val capture = capture(
            localKind = IntervalsLocalKind.STANDALONE,
            completionId = SESSION_ID,
            finishReason = IntervalsFinishReason.STANDALONE_COMMIT,
        )
        repository.commitCompletedSetWithCompletion(
            session = session().copy(totalReps = 0, workingReps = 0),
            metrics = emptyList(),
            completedSet = completedSet().copy(actualReps = 0, setEndReason = SetEndReason.STALL_FAILURE),
            repMetrics = emptyList(),
            repBiomechanics = emptyList(),
            completion = WorkoutCompletionMembership(
                profileId = "default",
                localKind = IntervalsLocalKind.STANDALONE,
                completionId = SESSION_ID,
                sessionId = SESSION_ID,
                childKey = SET_ID,
                childKind = IntervalsChildKind.COMPLETED_SET,
                standaloneCapture = capture,
            ),
        )

        val q = database.phoenixDatabaseQueries
        assertNotNull(q.selectSessionById(SESSION_ID).executeAsOneOrNull(), "the workout itself is still saved")
        assertNull(q.selectCompletionReceipt("default", "STANDALONE", SESSION_ID).executeAsOneOrNull())
    }

    @Test
    fun `saveSession never manufactures completion eligibility`() = runTest {
        repository.saveSession(session())
        val q = database.phoenixDatabaseQueries
        assertNull(q.selectCompletionReceipt("default", "STANDALONE", SESSION_ID).executeAsOneOrNull())
        assertNull(q.selectCompletionReceipt("default", "ROUTINE", SESSION_ID).executeAsOneOrNull())
        assertTrue(q.selectRequestedCompletions().executeAsList().isEmpty())
    }

    private fun capture(
        localKind: IntervalsLocalKind = IntervalsLocalKind.ROUTINE,
        completionId: String = "routine-1384",
        finishReason: IntervalsFinishReason = IntervalsFinishReason.NORMAL_FINISH,
    ) = IntervalsCompletionCapture(
        key = IntervalsCompletionKey("default", localKind, completionId),
        installationId = "install-1",
        finishReason = finishReason,
        routineTitle = "Push Day",
        externalId = completionId,
        startEpochMs = 1_700_000_000_000L,
        startLocalIso = "2023-11-14T17:13:20",
        timeZoneId = "America/New_York",
        utcOffsetMinutes = -300,
        completedEpochMs = 1_700_000_060_000L,
        eligible = true,
        resolvedAthleteId = "athlete-1",
        connectionGeneration = 7L,
    )

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
