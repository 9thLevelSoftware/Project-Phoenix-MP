package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.data.repository.SqlDelightSyncRepository
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Issue #591: Analytics only records first set of first exercise in
 * multi-exercise routine; per-set drill-down shows 'after v0.2.1'
 * placeholder on v0.9.2.
 *
 * Regression coverage for the LWW preservation guard added to
 * SqlDelightSyncRepository.mergeSessionsLww: when an incoming pull
 * row has null detailed metric columns but the existing local row
 * has captured non-null metrics, the local values must be preserved
 * instead of being overwritten with null. This is the exact
 * regression that triggered the "after v0.2.1" placeholder in
 * Analytics for current v0.9.2 sessions.
 *
 * Mirrors the test scaffolding from ConflictResolutionTest so the
 * pattern stays consistent across the sync suite.
 */
class Issue591SyncLwwTest {

    private val testProfileId = "test-profile"
    private val now = 1_700_000_000_000L

    private lateinit var database: com.devil.phoenixproject.database.VitruvianDatabase
    private lateinit var userProfileRepository: FakeUserProfileRepository
    private lateinit var repository: SqlDelightSyncRepository

    private fun setUp() {
        database = createTestDatabase()
        userProfileRepository = FakeUserProfileRepository()
        userProfileRepository.setActiveProfileForTest(id = testProfileId)
        repository = SqlDelightSyncRepository(database, userProfileRepository)
    }

    @Test
    fun `mergeSessionsLww preserves local peakForceConcentric when incoming is null`() = runTest {
        setUp()

        // GIVEN: A locally recorded session with non-null detailed metrics.
        val sessionId = "issue-591-set-1"
        insertLocalSession(
            WorkoutSession(
                id = sessionId,
                timestamp = now,
                mode = "OldSchool",
                reps = 8,
                weightPerCableKg = 30f,
                duration = 90_000L,
                totalReps = 8,
                warmupReps = 0,
                workingReps = 8,
                exerciseId = "exercise-incline-bench",
                exerciseName = "Incline Bench Press",
                routineSessionId = "routine-1",
                routineName = "4.Scaffold.Pull/Press",
                peakForceConcentricA = 50f,
                peakForceConcentricB = 55f,
                peakForceEccentricA = 60f,
                peakForceEccentricB = 62f,
                avgForceConcentricA = 35f,
                avgForceConcentricB = 38f,
                avgForceEccentricA = 40f,
                avgForceEccentricB = 42f,
                heaviestLiftKg = 32f,
                totalVolumeKg = 240f,
                cableCount = 1,
                profileId = testProfileId,
            ),
            updatedAt = now - 60_000L, // older than incoming
        )

        // WHEN: A pull arrives with the same id, newer updatedAt, and
        // ALL detailed metric columns null (current pull DTO does not
        // hydrate them for every session).
        val incoming = WorkoutSession(
            id = sessionId,
            timestamp = now,
            mode = "OldSchool",
            reps = 8,
            weightPerCableKg = 30f,
            duration = 90_000L,
            totalReps = 8,
            warmupReps = 0,
            workingReps = 8,
            exerciseId = "exercise-incline-bench",
            exerciseName = "Incline Bench Press",
            routineSessionId = "routine-1",
            routineName = "4.Scaffold.Pull/Press",
            // All metric fields are null on the incoming pull.
            profileId = testProfileId,
        )
        repository.mergeSessionsLww(
            sessions = listOf(incoming),
            updatedAtBySessionId = mapOf(sessionId to now + 60_000L),
        )

        // THEN: Local detailed metric columns are preserved.
        val after = database.vitruvianDatabaseQueries
            .selectSessionById(sessionId)
            .executeAsOneOrNull()
        assertNotNull(after, "session must exist after merge")
        assertEquals(50f, after.peakForceConcentricA?.toFloat())
        assertEquals(55f, after.peakForceConcentricB?.toFloat())
        assertEquals(60f, after.peakForceEccentricA?.toFloat())
        assertEquals(62f, after.peakForceEccentricB?.toFloat())
        assertEquals(35f, after.avgForceConcentricA?.toFloat())
        assertEquals(38f, after.avgForceConcentricB?.toFloat())
        assertEquals(40f, after.avgForceEccentricA?.toFloat())
        assertEquals(42f, after.avgForceEccentricB?.toFloat())
        assertEquals(32f, after.heaviestLiftKg?.toFloat())
        assertEquals(240f, after.totalVolumeKg?.toFloat())
        // cableCount should also be preserved (LWW guard applies).
        assertEquals(1L, after.cableCount)
    }

    @Test
    fun `mergeSessionsLww preserves local true peaks but applies incoming average metrics`() = runTest {
        setUp()

        // GIVEN: A locally recorded session with true peak values and
        // average-force values captured by the mobile recorder.
        val sessionId = "issue-591-set-2"
        insertLocalSession(
            WorkoutSession(
                id = sessionId,
                timestamp = now,
                mode = "OldSchool",
                weightPerCableKg = 25f,
                duration = 80_000L,
                totalReps = 6,
                workingReps = 6,
                exerciseName = "Squat",
                peakForceConcentricA = 30f,
                peakForceConcentricB = 32f,
                avgForceConcentricA = 20f,
                avgForceConcentricB = 21f,
                profileId = testProfileId,
            ),
            updatedAt = now - 60_000L,
        )

        // WHEN: A newer portal pull arrives. PortalPullAdapter can only
        // reconstruct peakForceConcentricA/B from leftForceAvg/rightForceAvg,
        // so those incoming peak fields are proxies and must not overwrite
        // true local peaks. Average-force fields are real pull-side values and
        // still follow normal incoming-wins LWW semantics.
        val incoming = WorkoutSession(
            id = sessionId,
            timestamp = now,
            mode = "OldSchool",
            weightPerCableKg = 25f,
            duration = 80_000L,
            totalReps = 6,
            workingReps = 6,
            exerciseName = "Squat",
            peakForceConcentricA = 45f, // Pull-side proxy, not a true per-cable peak.
            peakForceConcentricB = 46f,
            avgForceConcentricA = 31f,
            avgForceConcentricB = 33f,
            profileId = testProfileId,
        )
        repository.mergeSessionsLww(
            sessions = listOf(incoming),
            updatedAtBySessionId = mapOf(sessionId to now + 60_000L),
        )

        // THEN: True local peaks are preserved, while incoming non-peak
        // metrics still apply.
        val after = database.vitruvianDatabaseQueries
            .selectSessionById(sessionId)
            .executeAsOneOrNull()
        assertNotNull(after)
        assertEquals(30f, after.peakForceConcentricA?.toFloat())
        assertEquals(32f, after.peakForceConcentricB?.toFloat())
        assertEquals(31f, after.avgForceConcentricA?.toFloat())
        assertEquals(33f, after.avgForceConcentricB?.toFloat())
    }

    @Test
    fun `mergeSessionsLww preserves biomechanics fields when incoming is null`() = runTest {
        setUp()

        val sessionId = "issue-591-set-3"
        insertLocalSession(
            WorkoutSession(
                id = sessionId,
                timestamp = now,
                mode = "OldSchool",
                weightPerCableKg = 25f,
                duration = 60_000L,
                totalReps = 10,
                workingReps = 10,
                exerciseName = "Bench Press",
                avgMcvMmS = 0.45f,
                avgAsymmetryPercent = 2.5f,
                totalVelocityLossPercent = 15f,
                dominantSide = "RIGHT",
                strengthProfile = "BALANCED",
                profileId = testProfileId,
            ),
            updatedAt = now - 60_000L,
        )

        val incoming = WorkoutSession(
            id = sessionId,
            timestamp = now,
            mode = "OldSchool",
            weightPerCableKg = 25f,
            duration = 60_000L,
            totalReps = 10,
            workingReps = 10,
            exerciseName = "Bench Press",
            profileId = testProfileId,
        )
        repository.mergeSessionsLww(
            sessions = listOf(incoming),
            updatedAtBySessionId = mapOf(sessionId to now + 60_000L),
        )

        val after = database.vitruvianDatabaseQueries
            .selectSessionById(sessionId)
            .executeAsOneOrNull()
        assertNotNull(after)
        assertEquals(0.45f, after.avgMcvMmS?.toFloat())
        assertEquals(2.5f, after.avgAsymmetryPercent?.toFloat())
        assertEquals(15f, after.totalVelocityLossPercent?.toFloat())
        assertEquals("RIGHT", after.dominantSide)
        assertEquals("BALANCED", after.strengthProfile)
    }

    @Test
    fun `mergeSessionsLww first-time pull with null metrics stores nulls - no preservation possible`() = runTest {
        setUp()

        // No existing local row.
        val sessionId = "issue-591-set-4"
        val incoming = WorkoutSession(
            id = sessionId,
            timestamp = now,
            mode = "OldSchool",
            weightPerCableKg = 25f,
            duration = 60_000L,
            totalReps = 5,
            workingReps = 5,
            exerciseName = "Press",
            // No metrics.
            profileId = testProfileId,
        )
        repository.mergeSessionsLww(
            sessions = listOf(incoming),
            updatedAtBySessionId = mapOf(sessionId to now),
        )

        val after = database.vitruvianDatabaseQueries
            .selectSessionById(sessionId)
            .executeAsOneOrNull()
        assertNotNull(after, "first-time pull must insert the row")
        assertNull(after.peakForceConcentricA)
        assertEquals(5L, after.workingReps)
    }

    private fun insertLocalSession(session: WorkoutSession, updatedAt: Long) {
        database.vitruvianDatabaseQueries.insertSessionIgnore(
            id = session.id,
            timestamp = session.timestamp,
            mode = session.mode,
            targetReps = session.reps.toLong(),
            weightPerCableKg = session.weightPerCableKg.toDouble(),
            progressionKg = session.progressionKg.toDouble(),
            duration = session.duration,
            totalReps = session.totalReps.toLong(),
            warmupReps = session.warmupReps.toLong(),
            workingReps = session.workingReps.toLong(),
            isJustLift = if (session.isJustLift) 1L else 0L,
            stopAtTop = if (session.stopAtTop) 1L else 0L,
            eccentricLoad = session.eccentricLoad.toLong(),
            echoLevel = session.echoLevel.toLong(),
            exerciseId = session.exerciseId,
            exerciseName = session.exerciseName,
            routineSessionId = session.routineSessionId,
            routineName = session.routineName,
            routineId = session.routineId,
            safetyFlags = session.safetyFlags.toLong(),
            deloadWarningCount = session.deloadWarningCount.toLong(),
            romViolationCount = session.romViolationCount.toLong(),
            spotterActivations = session.spotterActivations.toLong(),
            peakForceConcentricA = session.peakForceConcentricA?.toDouble(),
            peakForceConcentricB = session.peakForceConcentricB?.toDouble(),
            peakForceEccentricA = session.peakForceEccentricA?.toDouble(),
            peakForceEccentricB = session.peakForceEccentricB?.toDouble(),
            avgForceConcentricA = session.avgForceConcentricA?.toDouble(),
            avgForceConcentricB = session.avgForceConcentricB?.toDouble(),
            avgForceEccentricA = session.avgForceEccentricA?.toDouble(),
            avgForceEccentricB = session.avgForceEccentricB?.toDouble(),
            heaviestLiftKg = session.heaviestLiftKg?.toDouble(),
            totalVolumeKg = session.totalVolumeKg?.toDouble(),
            cableCount = session.cableCount?.toLong(),
            estimatedCalories = session.estimatedCalories?.toDouble(),
            warmupAvgWeightKg = session.warmupAvgWeightKg?.toDouble(),
            workingAvgWeightKg = session.workingAvgWeightKg?.toDouble(),
            burnoutAvgWeightKg = session.burnoutAvgWeightKg?.toDouble(),
            peakWeightKg = session.peakWeightKg?.toDouble(),
            rpe = session.rpe?.toLong(),
            avgMcvMmS = session.avgMcvMmS?.toDouble(),
            avgAsymmetryPercent = session.avgAsymmetryPercent?.toDouble(),
            totalVelocityLossPercent = session.totalVelocityLossPercent?.toDouble(),
            dominantSide = session.dominantSide,
            strengthProfile = session.strengthProfile,
            formScore = session.formScore?.toLong(),
            updatedAt = updatedAt,
            profile_id = session.profileId,
            display_multiplier = session.displayMultiplier?.toLong(),
            externalAddedLoadKg = session.externalAddedLoadKg.toDouble(),
            counterweightKg = session.counterweightKg.toDouble(),
            rackItemsJson = session.rackItemsJson,
        )
    }

    /**
     * Issue #591 follow-up (chatgpt-codex-connector P2): the
     * batched preservation SELECTs must chunk when the incoming id
     * list exceeds SQLite's host-parameter limit (commonly 999 on
     * Android). Build a payload that spans 3 chunks at
     * `BATCH_LOOKUP_CHUNK_SIZE = 500` and assert every session still
     * preserves its locally captured metric column.
     */
    @Test
    fun `mergeSessionsLww chunked batch lookup preserves metrics across all chunks`() = runTest {
        setUp()

        val chunkSize = 500
        val totalCount = chunkSize * 3 + 17 // spans 4 chunks

        val sessions = (0 until totalCount).map { i ->
            val sessionId = "chunked-$i"
            // GIVEN: each session starts as a local row with a unique
            // metric value so we can detect cross-chunk contamination
            // or off-by-one chunking bugs.
            insertLocalSession(
                WorkoutSession(
                    id = sessionId,
                    timestamp = now + i * 1_000L,
                    mode = "OldSchool",
                    reps = 8,
                    weightPerCableKg = 30f,
                    duration = 60_000L,
                    totalReps = 8,
                    warmupReps = 0,
                    workingReps = 8,
                    exerciseName = "Squat",
                    peakForceConcentricA = 40f + i, // unique per session
                    profileId = testProfileId,
                ),
                updatedAt = now - 60_000L,
            )
            // Incoming pull with NEWER updatedAt but null metrics so
            // the LWW preservation guard must restore the local value.
            WorkoutSession(
                id = sessionId,
                timestamp = now + i * 1_000L,
                mode = "OldSchool",
                reps = 8,
                weightPerCableKg = 30f,
                duration = 60_000L,
                totalReps = 8,
                warmupReps = 0,
                workingReps = 8,
                exerciseName = "Squat",
                profileId = testProfileId,
            )
        }
        repository.mergeSessionsLww(
            sessions = sessions,
            updatedAtBySessionId = sessions.associate { it.id to (now + 10_000_000L + it.timestamp) },
        )

        // Spot-check a session from each chunk boundary plus the
        // middle of one chunk. If chunking dropped or reordered ids,
        // one of these will land on the wrong row.
        val samples = listOf(0, chunkSize - 1, chunkSize, chunkSize * 2 - 1, chunkSize * 2, totalCount - 1)
        for (i in samples) {
            val after = database.vitruvianDatabaseQueries
                .selectSessionById("chunked-$i")
                .executeAsOneOrNull()
            assertNotNull(after, "chunked-$i must exist after merge")
            assertEquals(
                40f + i,
                after.peakForceConcentricA?.toFloat(),
                "chunked-$i local metric must survive chunked preservation lookup",
            )
        }
    }
}
