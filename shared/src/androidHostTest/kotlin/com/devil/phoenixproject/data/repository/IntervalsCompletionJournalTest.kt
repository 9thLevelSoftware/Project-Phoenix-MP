package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.data.integration.intervals.AdmissionResult
import com.devil.phoenixproject.data.integration.intervals.CompletionEligibility
import com.devil.phoenixproject.data.integration.intervals.CompletionJournalCodec
import com.devil.phoenixproject.data.integration.intervals.FinalizeOutcome
import com.devil.phoenixproject.data.integration.intervals.IntervalsChildKind
import com.devil.phoenixproject.data.integration.intervals.IntervalsChildRef
import com.devil.phoenixproject.data.integration.intervals.IntervalsCompletionCapture
import com.devil.phoenixproject.data.integration.intervals.IntervalsCompletionKey
import com.devil.phoenixproject.data.integration.intervals.IntervalsFinishReason
import com.devil.phoenixproject.data.integration.intervals.IntervalsJobState
import com.devil.phoenixproject.data.integration.intervals.IntervalsLocalKind
import com.devil.phoenixproject.data.integration.intervals.PendingChildReplay
import com.devil.phoenixproject.data.integration.intervals.PendingChildWrite
import com.devil.phoenixproject.data.integration.intervals.WorkoutCompletionFinalizer
import com.devil.phoenixproject.data.local.ExerciseImporter
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.testutil.FakePreferencesManager
import com.devil.phoenixproject.testutil.createTestSchema
import com.devil.phoenixproject.testutil.seedExercise
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Issue #1384 (packet P2): real-SQLite coverage of the replayable completion finalization
 * journal — admission idempotency/conflicts, staged pending-child replay at every §3.3
 * crash window, exact membership enforcement, negative eligibility, receipt/outbox
 * atomicity, external-id collision rejection and zero-rep/abandoned work.
 */
class IntervalsCompletionJournalTest {

    private lateinit var realDriver: JdbcSqliteDriver
    private lateinit var database: PhoenixDatabase
    private lateinit var repository: SqlDelightWorkoutRepository
    private lateinit var finalizer: WorkoutCompletionFinalizer

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
        openDatabase()
    }

    @After
    fun tearDown() {
        failOnSqlFragment = null
        realDriver.close()
    }

    /** Simulates a process restart: fresh database/repositories over the same live SQLite. */
    private fun openDatabase() {
        database = PhoenixDatabase(FaultInjectingDriver(realDriver))
        database.seedExercise(EXERCISE_ID)
        repository = SqlDelightWorkoutRepository(
            database,
            SqlDelightExerciseRepository(database, ExerciseImporter(database), FakePreferencesManager()),
        )
        finalizer = WorkoutCompletionFinalizer(
            db = database,
            workoutRepository = repository,
            eligibilityResolver = { _, _ -> eligibility },
            now = { NOW },
        )
    }

    private var eligibility = CompletionEligibility(
        installationId = "install-1",
        eligible = true,
        resolvedAthleteId = "athlete-1",
        connectionGeneration = 7L,
    )

    // ==================== admission ====================

    @Test
    fun `admission is idempotent and records exact expected membership`() = runTest {
        val capture = capture()
        val children = listOf(
            childRef("s-1", IntervalsChildKind.COMPLETED_SET, "session-1", pending = false),
            childRef("s-2", IntervalsChildKind.COMPLETED_SET, "session-2", pending = true),
        )
        val replay = listOf(
            PendingChildReplay("s-2", PendingChildWrite(session("session-2"), listOf(metric()), set("s-2", "session-2"), emptyList(), emptyList())),
        )

        assertEquals(AdmissionResult.Accepted, finalizer.requestFinalize(capture, children, replay))
        assertEquals(AdmissionResult.AlreadyAdmitted, finalizer.requestFinalize(capture, children, replay))

        val q = database.phoenixDatabaseQueries
        val journal = q.selectCompletionJournal(PROFILE, KIND, COMPLETION_ID).executeAsOne()
        assertEquals("REQUESTED", journal.state)
        assertEquals(2L, journal.expected_session_count)
        assertEquals(2L, journal.expected_set_count)
        val childRows = q.selectCompletionJournalChildren(PROFILE, KIND, COMPLETION_ID).executeAsList()
        assertEquals(2, childRows.size)
        // Already durable at admission: committed without a staged payload.
        val first = childRows.first { it.child_key == "s-1" }
        assertEquals(1L, first.committed)
        assertNull(first.replay_payload)
        // Still pending at admission: staged with a replayable payload.
        val second = childRows.first { it.child_key == "s-2" }
        assertEquals(0L, second.committed)
        assertNotNull(second.replay_payload)
        assertNotNull(CompletionJournalCodec.decode(second.replay_payload!!))
    }

    @Test
    fun `conflicting membership on readback is rejected and never replaces the admission`() = runTest {
        val capture = capture()
        val original = listOf(
            childRef("s-1", IntervalsChildKind.COMPLETED_SET, "session-1", pending = false),
            childRef("s-2", IntervalsChildKind.COMPLETED_SET, "session-2", pending = false),
        )
        assertEquals(AdmissionResult.Accepted, finalizer.requestFinalize(capture, original, emptyList()))

        val conflicting = listOf(
            childRef("s-1", IntervalsChildKind.COMPLETED_SET, "session-1", pending = false),
            childRef("s-3", IntervalsChildKind.COMPLETED_SET, "session-3", pending = false),
        )
        val result = finalizer.requestFinalize(capture, conflicting, emptyList())
        assertTrue(result is AdmissionResult.Conflicting, "expected Conflicting, got $result")

        val childRows = database.phoenixDatabaseQueries
            .selectCompletionJournalChildren(PROFILE, KIND, COMPLETION_ID)
            .executeAsList()
        assertEquals(listOf("s-1", "s-2"), childRows.map { it.child_key }.sorted())
    }

    @Test
    fun `conflicting destination or time digests on readback are rejected`() = runTest {
        val children = listOf(childRef("s-1", IntervalsChildKind.COMPLETED_SET, "session-1", pending = false))
        assertEquals(AdmissionResult.Accepted, finalizer.requestFinalize(capture(), children, emptyList()))

        val laterFinish = finalizer.requestFinalize(
            capture(completedEpochMs = COMPLETED_MS + 60_000L),
            children,
            emptyList(),
        )
        assertTrue(laterFinish is AdmissionResult.Conflicting, "expected Conflicting, got $laterFinish")

        eligibility = eligibility.copy(resolvedAthleteId = "athlete-OTHER")
        val otherDestination = finalizer.requestFinalize(
            capture(athleteId = "athlete-OTHER"),
            children,
            emptyList(),
        )
        assertTrue(otherDestination is AdmissionResult.Conflicting, "expected Conflicting, got $otherDestination")
    }

    @Test
    fun `persistence failure surfaces as PersistenceFailed and keeps the journal recoverable`() = runTest {
        failOnSqlFragment = "INSERT INTO WorkoutCompletionJournal"
        val result = finalizer.requestFinalize(
            capture(),
            listOf(childRef("s-1", IntervalsChildKind.COMPLETED_SET, "session-1", pending = true)),
            listOf(PendingChildReplay("s-1", PendingChildWrite(session("session-1"), emptyList(), set("s-1", "session-1"), emptyList(), emptyList()))),
        )
        assertTrue(result is AdmissionResult.PersistenceFailed, "expected PersistenceFailed, got $result")
        failOnSqlFragment = null
        assertNull(
            database.phoenixDatabaseQueries
                .selectCompletionJournal(PROFILE, KIND, COMPLETION_ID)
                .executeAsOneOrNull(),
            "A failed admission must not leave a journal row",
        )
    }

    // ==================== §3.3 crash windows ====================

    @Test
    fun `crash after staged child before set commit replays the exact persisted input`() = runTest {
        val write = PendingChildWrite(session("session-1"), listOf(metric()), set("s-1", "session-1"), emptyList(), emptyList())
        assertEquals(
            AdmissionResult.Accepted,
            finalizer.requestFinalize(
                capture(),
                listOf(childRef("s-1", IntervalsChildKind.COMPLETED_SET, "session-1", pending = true)),
                listOf(PendingChildReplay("s-1", write)),
            ),
        )
        // Crash BEFORE the set commit: only the staged journal exists.
        assertNull(database.phoenixDatabaseQueries.selectSessionById("session-1").executeAsOneOrNull())
        assertNull(database.phoenixDatabaseQueries.selectCompletedSetById("s-1").executeAsOneOrNull())

        openDatabase() // process restart on the same SQLite

        val outcomes = finalizer.finalizePending()
        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() is FinalizeOutcome.Finalized, "got ${outcomes.single()}")
        // Same ids replayed exactly — no duplicate session/set.
        assertNotNull(database.phoenixDatabaseQueries.selectSessionById("session-1").executeAsOneOrNull())
        val sets = database.phoenixDatabaseQueries.selectCompletedSetsBySession("session-1").executeAsList()
        assertEquals(1, sets.size)
        assertEquals("s-1", sets.single().id)
        // Receipt + one dispatchable job + journal FINALIZED.
        val receipt = database.phoenixDatabaseQueries
            .selectCompletionReceipt(PROFILE, KIND, COMPLETION_ID)
            .executeAsOne()
        assertEquals(1L, receipt.eligible)
        assertNotNull(receipt.export_payload)
        val job = database.phoenixDatabaseQueries
            .selectIntervalsJob(PROFILE, "athlete-1", COMPLETION_ID)
            .executeAsOne()
        assertEquals(IntervalsJobState.PENDING.name, job.state)
        assertEquals("FINALIZED", database.phoenixDatabaseQueries
            .selectCompletionJournal(PROFILE, KIND, COMPLETION_ID).executeAsOne().state)
    }

    @Test
    fun `crash between per-set commits leaves REQUESTED with unfinished persistence and recovers to one job`() = runTest {
        // Set 1 already durable before admission; set 2 still pending.
        repository.commitCompletedSetWithCompletion(
            session = session("session-1"),
            metrics = emptyList(),
            completedSet = set("s-1", "session-1"),
            repMetrics = emptyList(),
            repBiomechanics = emptyList(),
            completion = membership("session-1", "s-1"),
        )
        val write2 = PendingChildWrite(session("session-2"), emptyList(), set("s-2", "session-2"), emptyList(), emptyList())
        assertEquals(
            AdmissionResult.Accepted,
            finalizer.requestFinalize(
                capture(),
                listOf(
                    childRef("s-1", IntervalsChildKind.COMPLETED_SET, "session-1", pending = false),
                    childRef("s-2", IntervalsChildKind.COMPLETED_SET, "session-2", pending = true),
                ),
                listOf(PendingChildReplay("s-2", write2)),
            ),
        )
        // Between per-set commits: no receipt, no routine job — the REQUESTED journal plus
        // its exact expected membership identifies the unfinished persistence.
        assertNull(
            database.phoenixDatabaseQueries
                .selectCompletionReceipt(PROFILE, KIND, COMPLETION_ID)
                .executeAsOneOrNull(),
        )
        assertTrue(
            database.phoenixDatabaseQueries
                .selectIntervalsJobCounts(PROFILE)
                .executeAsList()
                .isEmpty(),
        )

        openDatabase()
        val outcomes = finalizer.finalizePending()
        assertTrue(outcomes.single() is FinalizeOutcome.Finalized, "got ${outcomes.single()}")
        assertNotNull(database.phoenixDatabaseQueries.selectSessionById("session-2").executeAsOneOrNull())
        val jobs = database.phoenixDatabaseQueries.selectIntervalsJobCounts(PROFILE).executeAsList()
        assertEquals(1L, jobs.sumOf { it.job_count })
    }

    @Test
    fun `crash inside the receipt outbox transaction rolls back everything and stays recoverable`() = runTest {
        repository.commitCompletedSetWithCompletion(
            session = session("session-1"),
            metrics = emptyList(),
            completedSet = set("s-1", "session-1"),
            repMetrics = emptyList(),
            repBiomechanics = emptyList(),
            completion = membership("session-1", "s-1"),
        )
        assertEquals(
            AdmissionResult.Accepted,
            finalizer.requestFinalize(
                capture(),
                listOf(childRef("s-1", IntervalsChildKind.COMPLETED_SET, "session-1", pending = false)),
                emptyList(),
            ),
        )

        failOnSqlFragment = "INSERT INTO IntervalsOutbox"
        val failed = finalizer.finalizePending()
        assertTrue(failed.single() is FinalizeOutcome.Failed, "got ${failed.single()}")
        assertNull(
            database.phoenixDatabaseQueries
                .selectCompletionReceipt(PROFILE, KIND, COMPLETION_ID)
                .executeAsOneOrNull(),
            "the receipt must roll back with the outbox write",
        )
        assertEquals(
            "REQUESTED",
            database.phoenixDatabaseQueries
                .selectCompletionJournal(PROFILE, KIND, COMPLETION_ID)
                .executeAsOne().state,
        )

        failOnSqlFragment = null
        openDatabase()
        val recovered = finalizer.finalizePending()
        assertTrue(recovered.single() is FinalizeOutcome.Finalized, "got ${recovered.single()}")
        assertNotNull(
            database.phoenixDatabaseQueries
                .selectCompletionReceipt(PROFILE, KIND, COMPLETION_ID)
                .executeAsOneOrNull(),
        )
    }

    // ==================== exact membership ====================

    @Test
    fun `missing or malformed children block finalization and never publish the subset`() = runTest {
        // Malformed staged payload: replay is impossible, so the completion stays REQUESTED.
        val q = database.phoenixDatabaseQueries
        finalizer.requestFinalize(
            capture(),
            listOf(childRef("s-1", IntervalsChildKind.COMPLETED_SET, "session-1", pending = true)),
            listOf(PendingChildReplay("s-1", PendingChildWrite(session("session-1"), emptyList(), set("s-1", "session-1"), emptyList(), emptyList()))),
        )
        // Simulate a corrupt staged payload directly in the journal table.
        realDriver.execute(
            null,
            "UPDATE WorkoutCompletionJournalChild SET replay_payload = '{corrupt' " +
                "WHERE child_key = 's-1'",
            0,
        )

        val outcomes = finalizer.finalizePending()
        assertTrue(outcomes.single() is FinalizeOutcome.Blocked, "got ${outcomes.single()}")
        assertNull(
            q.selectCompletionReceipt(PROFILE, KIND, COMPLETION_ID).executeAsOneOrNull(),
            "a blocked completion must not publish the surviving subset",
        )
        assertEquals(
            "REQUESTED",
            q.selectCompletionJournal(PROFILE, KIND, COMPLETION_ID).executeAsOne().state,
        )
    }

    @Test
    fun `a missing admitted child blocks finalization`() = runTest {
        repository.commitCompletedSetWithCompletion(
            session = session("session-1"),
            metrics = emptyList(),
            completedSet = set("s-1", "session-1"),
            repMetrics = emptyList(),
            repBiomechanics = emptyList(),
            completion = membership("session-1", "s-1"),
        )
        repository.commitCompletedSetWithCompletion(
            session = session("session-2"),
            metrics = emptyList(),
            completedSet = set("s-2", "session-2"),
            repMetrics = emptyList(),
            repBiomechanics = emptyList(),
            completion = membership("session-2", "s-2"),
        )
        finalizer.requestFinalize(
            capture(),
            listOf(
                childRef("s-1", IntervalsChildKind.COMPLETED_SET, "session-1", pending = false),
                childRef("s-2", IntervalsChildKind.COMPLETED_SET, "session-2", pending = false),
            ),
            emptyList(),
        )
        realDriver.execute(
            null,
            "UPDATE WorkoutCompletionJournalChild SET committed = 0, replay_payload = NULL " +
                "WHERE child_key = 's-2'",
            0,
        )

        val outcomes = finalizer.finalizePending()
        assertTrue(outcomes.single() is FinalizeOutcome.Blocked, "got ${outcomes.single()}")
        assertNull(
            database.phoenixDatabaseQueries
                .selectCompletionReceipt(PROFILE, KIND, COMPLETION_ID)
                .executeAsOneOrNull(),
        )
    }

    // ==================== eligibility, collisions, invalid payloads ====================

    @Test
    fun `negative eligibility records a receipt and creates no job`() = runTest {
        eligibility = CompletionEligibility.Ineligible
        repository.commitCompletedSetWithCompletion(
            session = session("session-1"),
            metrics = emptyList(),
            completedSet = set("s-1", "session-1"),
            repMetrics = emptyList(),
            repBiomechanics = emptyList(),
            completion = membership("session-1", "s-1"),
        )
        assertEquals(
            AdmissionResult.Accepted,
            finalizer.requestFinalize(
                capture(eligible = false, athleteId = null),
                listOf(childRef("s-1", IntervalsChildKind.COMPLETED_SET, "session-1", pending = false)),
                emptyList(),
            ),
        )
        val outcomes = finalizer.finalizePending()
        val outcome = outcomes.single() as FinalizeOutcome.Finalized
        assertFalse(outcome.eligible)
        assertFalse(outcome.jobCreated)
        val receipt = database.phoenixDatabaseQueries
            .selectCompletionReceipt(PROFILE, KIND, COMPLETION_ID)
            .executeAsOne()
        assertEquals(0L, receipt.eligible)
        assertNull(receipt.export_payload, "negative eligibility needs no replay payload")
        assertTrue(
            database.phoenixDatabaseQueries.selectIntervalsJobCounts(PROFILE).executeAsList().isEmpty(),
            "disabled/paused receipts create NO job",
        )
    }

    @Test
    fun `external id collision with a different owner fails visibly`() = runTest {
        val q = database.phoenixDatabaseQueries
        // Another profile/kind already owns the literal wire identity.
        q.reserveIntervalsExternalId(
            resolved_athlete_id = "athlete-1",
            external_id = COMPLETION_ID,
            owner_profile_id = "other-profile",
            owner_local_kind = "STANDALONE",
            owner_completion_id = "other-completion",
            created_at = NOW,
        )

        repository.commitCompletedSetWithCompletion(
            session = session("session-1"),
            metrics = emptyList(),
            completedSet = set("s-1", "session-1"),
            repMetrics = emptyList(),
            repBiomechanics = emptyList(),
            completion = membership("session-1", "s-1"),
        )
        assertEquals(
            AdmissionResult.Accepted,
            finalizer.requestFinalize(
                capture(),
                listOf(childRef("s-1", IntervalsChildKind.COMPLETED_SET, "session-1", pending = false)),
                emptyList(),
            ),
        )
        val outcomes = finalizer.finalizePending()
        val outcome = outcomes.single() as FinalizeOutcome.Finalized
        assertTrue(outcome.externalIdCollision, "the collision must be visible")
        // The local completion is retained, but no dispatchable job exists for it.
        val job = q.selectIntervalsJob(PROFILE, "athlete-1", COMPLETION_ID).executeAsOne()
        assertEquals(IntervalsJobState.PERMANENT_FAILURE.name, job.state)
        assertEquals("VALIDATION_REJECTED", job.reason_code)
        // The foreign reservation is never reassigned.
        val reservation = q.selectIntervalsExternalIdReservation("athlete-1", COMPLETION_ID).executeAsOne()
        assertEquals("other-profile", reservation.owner_profile_id)
    }

    @Test
    fun `invalid payloads keep a final receipt plus a safe per-job failure`() = runTest {
        // Sub-second elapsed time is rejected by the builder (never padded).
        val badCapture = capture(startEpochMs = START_MS, completedEpochMs = START_MS + 500L)
        repository.commitCompletedSetWithCompletion(
            session = session("session-1"),
            metrics = emptyList(),
            completedSet = set("s-1", "session-1"),
            repMetrics = emptyList(),
            repBiomechanics = emptyList(),
            completion = membership("session-1", "s-1"),
        )
        assertEquals(
            AdmissionResult.Accepted,
            finalizer.requestFinalize(
                badCapture,
                listOf(childRef("s-1", IntervalsChildKind.COMPLETED_SET, "session-1", pending = false)),
                emptyList(),
            ),
        )
        val outcomes = finalizer.finalizePending()
        val outcome = outcomes.single() as FinalizeOutcome.Finalized
        assertNotNull(outcome.builderFailure)
        assertFalse(outcome.exportPayloadStored)
        val receipt = database.phoenixDatabaseQueries
            .selectCompletionReceipt(PROFILE, KIND, COMPLETION_ID)
            .executeAsOne()
        assertNull(receipt.export_payload, "invalid payloads retain the receipt without a wire payload")
        val job = database.phoenixDatabaseQueries
            .selectIntervalsJob(PROFILE, "athlete-1", COMPLETION_ID)
            .executeAsOne()
        assertEquals(IntervalsJobState.PERMANENT_FAILURE.name, job.state)
    }

    // ==================== zero-rep / abandoned work ====================

    @Test
    fun `zero-rep or abandoned work is never admitted and produces no receipt`() = runTest {
        // No qualifying children: nothing is admitted, nothing is ever finalized.
        assertEquals(AdmissionResult.NoQualifyingWork, finalizer.requestFinalize(capture(), emptyList(), emptyList()))
        assertTrue(finalizer.finalizePending().isEmpty())
        assertNull(
            database.phoenixDatabaseQueries
                .selectCompletionReceipt(PROFILE, KIND, COMPLETION_ID)
                .executeAsOneOrNull(),
        )
        // A zero-rep STALL_FAILURE set does not qualify; a timed hold does.
        val stallSession = session("session-9", workingReps = 0, durationMs = 30_000L)
        val stallSet = set("s-9", "session-9", actualReps = 0, endReason = SetEndReason.STALL_FAILURE)
        assertFalse(
            com.devil.phoenixproject.data.integration.intervals.IntervalsActivityBuilder
                .qualifiesAsValidWork(stallSession, stallSet, timedModeEvidence = true),
        )
    }

    // ==================== fixtures ====================

    private fun capture(
        startEpochMs: Long = START_MS,
        completedEpochMs: Long = COMPLETED_MS,
        eligible: Boolean = true,
        athleteId: String? = "athlete-1",
    ) = IntervalsCompletionCapture(
        key = IntervalsCompletionKey(PROFILE, IntervalsLocalKind.ROUTINE, COMPLETION_ID),
        installationId = "install-1",
        finishReason = IntervalsFinishReason.NORMAL_FINISH,
        routineTitle = "Push Day",
        externalId = COMPLETION_ID,
        startEpochMs = startEpochMs,
        startLocalIso = "2023-11-14T17:13:20",
        timeZoneId = "America/New_York",
        utcOffsetMinutes = -300,
        completedEpochMs = completedEpochMs,
        eligible = eligible,
        resolvedAthleteId = athleteId,
        connectionGeneration = 7L,
    )

    private fun childRef(
        childKey: String,
        kind: IntervalsChildKind,
        sessionId: String,
        pending: Boolean,
    ) = IntervalsChildRef(childKey = childKey, childKind = kind, sessionId = sessionId, pendingAtAdmission = pending)

    private fun membership(sessionId: String, childKey: String) = WorkoutCompletionMembership(
        profileId = PROFILE,
        localKind = IntervalsLocalKind.ROUTINE,
        completionId = COMPLETION_ID,
        sessionId = sessionId,
        childKey = childKey,
        childKind = IntervalsChildKind.COMPLETED_SET,
        timedModeEvidence = false,
    )

    private fun session(
        id: String,
        workingReps: Int = 5,
        durationMs: Long = 60_000L,
    ) = WorkoutSession(
        id = id,
        timestamp = 1_700_000_000_000L,
        mode = "Old School",
        reps = 5,
        weightPerCableKg = 40f,
        duration = durationMs,
        totalReps = workingReps,
        warmupReps = 0,
        workingReps = workingReps,
        exerciseId = EXERCISE_ID,
        exerciseName = EXERCISE_ID,
        routineSessionId = COMPLETION_ID,
        routineName = "Push Day",
        profileId = PROFILE,
    )

    private fun set(
        id: String,
        sessionId: String,
        actualReps: Int = 5,
        endReason: SetEndReason = SetEndReason.TARGET_REPS_REACHED,
    ) = CompletedSet(
        id = id,
        sessionId = sessionId,
        plannedSetId = null,
        setNumber = 1,
        setType = SetType.STANDARD,
        actualReps = actualReps,
        actualWeightKg = 40f,
        loggedRpe = null,
        isPr = false,
        completedAt = 1_700_000_060_000L,
        setEndReason = endReason,
    )

    private fun metric() = WorkoutMetric(
        timestamp = 1_700_000_010_000L,
        loadA = 40f,
        loadB = 40f,
        positionA = 100f,
        positionB = 100f,
    )

    @Test
    fun `crash replay preserves routineModifierApplied false on replayed session`() = runTest {
        val unmodifiedSession = session("s-unmodified").copy(routineModifierApplied = false)
        val write = PendingChildWrite(
            session = unmodifiedSession,
            metrics = listOf(metric()),
            completedSet = set("set-u1", "s-unmodified"),
            repMetrics = emptyList(),
            repBiomechanics = emptyList(),
        )
        assertEquals(
            AdmissionResult.Accepted,
            finalizer.requestFinalize(
                capture(),
                listOf(childRef("set-u1", IntervalsChildKind.COMPLETED_SET, "s-unmodified", pending = true)),
                listOf(PendingChildReplay("set-u1", write)),
            ),
        )

        val outcomes = finalizer.finalizePending()
        assertEquals(1, outcomes.size)
        val row = database.phoenixDatabaseQueries.selectSessionById("s-unmodified").executeAsOne()
        assertEquals(0L, row.routineModifierApplied, "unmodified provenance must survive journal replay for 1RM eligibility")
        assertFalse(CompletionJournalCodec.decode(CompletionJournalCodec.encode(write))!!.session.routineModifierApplied)
    }

    private companion object {
        const val PROFILE = "default"
        const val COMPLETION_ID = "routine-1384"
        const val KIND = "ROUTINE"
        const val EXERCISE_ID = "bench-press"
        const val START_MS = 1_700_000_000_000L
        const val COMPLETED_MS = 1_700_001_800_000L
        const val NOW = 1_700_001_800_500L
    }
}
