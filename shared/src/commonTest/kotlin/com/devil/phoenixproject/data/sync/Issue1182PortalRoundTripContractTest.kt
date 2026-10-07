package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.data.repository.SqlDelightSyncRepository
import com.devil.phoenixproject.data.repository.SqlDelightWorkoutRepository
import com.devil.phoenixproject.data.repository.SyncRepository
import com.devil.phoenixproject.domain.model.RepMetricSummary
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.usecase.EchoAchievedLoadResolver
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakePortalServer
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import com.devil.phoenixproject.util.OneRepMaxCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * Issue #1182 portal round-trip contract (merge-gate R5d).
 *
 * The earlier `PortalSyncAdapterEchoAchievedLoadTest` exercised outbound DTO CONSTRUCTION
 * only and called that push-pull characterization. This runs the real chain instead:
 *
 *   local session -> push adapter -> [wire JSON] -> portal (replace_session_children)
 *                -> pull DTO -> [wire JSON] -> pull adapter -> SQLDelight merge -> read back
 *
 * and asserts the achieved Echo load (80 kg/cable against a 5 kg configured seed) and the
 * unavailable sentinel both survive it, that the rep-based estimate is derived from the
 * achieved load and suppressed when it is unavailable, and that re-push keeps child identity
 * stable while replacing children exactly once (no duplicate sets, no invented ids).
 *
 * The fixtures mirror production: the push ALWAYS carries scalar `repSummaries` (only the
 * 50 Hz force curves are gated behind `includeTelemetry`), and the portal returns them plus
 * the session-level config. Those returned rep summaries hydrate the row's concentric force
 * columns, but they are NOT measurement provenance: the wire carries one `weightKg`, and a
 * pre-#1182 push put the configured seed there next to the same rep summaries. A pulled-only
 * row therefore reports "Load unavailable"; the device that recorded the set keeps its local
 * row (a pull never projects over a locally originated row) and its achieved load. The
 * configured seed is never a fallback: a missing load stays missing across sync.
 *
 * NOTE: helpers are `suspend` and the tests use `runTest`, not `runBlocking` - this file lives
 * in commonTest and `runBlocking` does not exist in the common source set (it broke the iOS
 * compile).
 */
class Issue1182PortalRoundTripContractTest {

    // ===== fixtures =====

    /**
     * Production-shaped scalar rep summary. `avgForceConcentricA/B` become the wire's
     * `leftForceAvg`/`rightForceAvg`, which the pull adapter hydrates back into the row's
     * peak force columns - the only provenance channel the resolver trusts.
     */
    private fun repSummary(repNumber: Int = 1) = RepMetricSummary(
        repNumber = repNumber,
        peakForceA = 80f,
        peakForceB = 80f,
        avgForceConcentricA = 60f,
        avgForceConcentricB = 60f,
        peakVelocity = 900f,
        avgVelocityConcentric = 700f,
        rangeOfMotionMm = 620f,
        peakPowerWatts = 420f,
        avgPowerWatts = 310f,
        concentricDurationMs = 800L,
        eccentricDurationMs = 1_200L,
    )

    /** The reporter's set: configured 5 kg seed, machine-measured peak 80 kg/cable. */
    private fun achievedEchoSession(
        id: String = "echo-session",
        routineSessionId: String? = null,
    ) = WorkoutSession(
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
        routineSessionId = routineSessionId,
    )

    /** An Echo set with no accepted working telemetry: the non-null 0 sentinel. */
    private fun unmeasuredEchoSession(id: String = "echo-unmeasured") = WorkoutSession(
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
    )

    private fun pushResultFor(
        session: WorkoutSession,
        withRepSummaries: Boolean = true,
    ): PortalSyncAdapter.PortalSessionBuildResult =
        PortalSyncAdapter.toPortalWorkoutSessionsWithTelemetry(
            sessionsWithReps = listOf(
                PortalSyncAdapter.SessionWithReps(
                    session = session,
                    repSummaries = if (withRepSummaries) listOf(repSummary()) else emptyList(),
                ),
            ),
            userId = "user-1",
            includeTelemetry = false,
        )

    /**
     * Pushes the session through the real wire encoding into a portal that models
     * `replace_session_children`, then pulls it back through the real wire decoding.
     */
    private suspend fun roundTrip(
        session: WorkoutSession,
        server: FakePortalServer = FakePortalServer(),
        withRepSummaries: Boolean = true,
    ): Pair<WorkoutSession, FakePortalServer> {
        val build = pushResultFor(session, withRepSummaries)

        // Real wire serialization in both directions (the "serialization" leg).
        val payload = PortalWireJson.decodeFromString(
            PortalSyncPayload.serializer(),
            PortalWireJson.encodeToString(
                PortalSyncPayload.serializer(),
                PortalSyncPayload(deviceId = "device-1", lastSync = 0L, sessions = build.sessions, telemetry = build.telemetry),
            ),
        )

        val accepted = server.push(payload)
        assertTrue(
            accepted.acknowledgedWorkoutSessionIds.isNotEmpty(),
            "the portal must acknowledge the pushed session before it can round-trip",
        )

        val pulled = PortalWireJson.decodeFromString(
            PortalSyncPullResponse.serializer(),
            PortalWireJson.encodeToString(PortalSyncPullResponse.serializer(), server.pull(KnownEntityIds(sessionIds = emptyList()))),
        )

        val pullDto = assertNotNull(pulled.sessions.singleOrNull(), "exactly one session comes back from the portal")
        val local = PortalPullAdapter.toWorkoutSessionsWithLookup(pullDto, profileId = "default") { _, _, _ -> null }
            .single()
        return local to server
    }

    /**
     * Persists a pulled row through the REAL pull merge (`SyncRepository.mergeAllPullData`,
     * the path `SyncManager` uses) and reads it back - the merge/read-back leg.
     */
    private suspend fun mergedAndReadBack(
        pulled: WorkoutSession,
        database: PhoenixDatabase = createTestDatabase(),
    ): WorkoutSession {
        mergePulled(database, listOf(pulled))
        return assertNotNull(readBack(database, pulled.id), "the merged row must read back")
    }

    private suspend fun mergePulled(database: PhoenixDatabase, sessions: List<WorkoutSession>) {
        val syncRepository: SyncRepository = SqlDelightSyncRepository(
            database,
            FakeUserProfileRepository().apply { setActiveProfileForTest() },
        )
        syncRepository.mergeAllPullData(
            sessions = sessions,
            routines = emptyList(),
            cycles = emptyList(),
            badges = emptyList(),
            gamificationStats = null,
            personalRecords = emptyList(),
            lastSync = 0L,
            profileId = "default",
        )
    }

    private suspend fun readBack(database: PhoenixDatabase, sessionId: String): WorkoutSession? =
        SqlDelightWorkoutRepository(database, FakeExerciseRepository()).getSession(sessionId)

    // ===== achieved load =====

    @Test
    fun `achieved echo load survives push serialize pull merge and read back`() = runTest {
        val recorded = achievedEchoSession()
        val (pulled, _) = roundTrip(recorded)
        assertEquals(80f, pulled.heaviestLiftKg, "the measured peak is carried through the wire")

        // The recording device: its locally originated row is never projected over by a pull.
        val database = createTestDatabase()
        SqlDelightWorkoutRepository(database, FakeExerciseRepository()).saveSession(recorded)
        mergePulled(database, listOf(pulled))
        val readBack = assertNotNull(readBack(database, recorded.id))

        assertEquals(
            80f,
            EchoAchievedLoadResolver.primaryLoadKg(readBack),
            "the achieved 80 kg peak must survive the sync round trip - never the configured seed",
        )
    }

    @Test
    fun `the configured seed is never rendered as the achieved load after a round trip`() = runTest {
        val (pulled, _) = roundTrip(achievedEchoSession())
        val readBack = mergedAndReadBack(pulled)

        // A pulled-only copy (another device, or a reinstall) has no measurement provenance.
        val reported = EchoAchievedLoadResolver.primaryLoadKg(readBack)
        assertNull(reported, "a pulled-only Echo row reports Load unavailable rather than guessing")
        assertTrue(reported != 5f, "the 5 kg configured seed must never surface as the achieved load")
    }

    @Test
    fun `the round trip keeps the echoed mode so the row is not silently treated as fixed load`() = runTest {
        val (pulled, _) = roundTrip(achievedEchoSession())

        assertEquals("Echo", pulled.mode, "the Echo mode survives the wire - otherwise the row would report a fixed load")
        assertTrue(EchoAchievedLoadResolver.isEcho(pulled), "the pulled row must still be an Echo row")
    }

    @Test
    fun `returned telemetry is not measurement provenance for a pulled legacy seed`() = runTest {
        val (pulled, _) = roundTrip(achievedEchoSession())
        assertTrue(
            (pulled.peakForceConcentricA ?: 0f) > 0f,
            "the returned rep summaries still hydrate the concentric force columns",
        )
        assertTrue(
            !EchoAchievedLoadResolver.hasForceTelemetry(pulled),
            "portal-hydrated forces never vouch for the pulled weight",
        )

        // What a pre-#1182 push looks like once pulled: the configured seed as weightKg,
        // next to genuine rep summaries. It must not resolve to the seed.
        val legacyPull = pulled.copy(weightPerCableKg = 5f, heaviestLiftKg = 5f)
        assertNull(
            EchoAchievedLoadResolver.primaryLoadKg(mergedAndReadBack(legacyPull)),
            "a legacy pulled seed with rep summaries reports Load unavailable, never 5 kg",
        )
    }

    /**
     * Characterized limit, not a defect: when the portal returns NO rep summaries there is no
     * evidence the carried weight was measured rather than the legacy placeholder, so mobile
     * reports "Load unavailable" instead of guessing. It must never fall back to the seed.
     */
    @Test
    fun `without returned telemetry the round trip reports unavailable and never the seed`() = runTest {
        val (pulled, _) = roundTrip(achievedEchoSession(), withRepSummaries = false)
        val readBack = mergedAndReadBack(pulled)

        assertNull(
            EchoAchievedLoadResolver.primaryLoadKg(readBack),
            "no telemetry evidence -> Load unavailable, never a guessed load",
        )
        assertTrue(readBack.heaviestLiftKg != 5f, "and never the configured seed")
    }

    // ===== unavailable sentinel =====

    @Test
    fun `the unavailable sentinel survives the round trip and is never replaced by the seed`() = runTest {
        val (pulled, _) = roundTrip(unmeasuredEchoSession())
        val readBack = mergedAndReadBack(pulled)

        assertEquals(0f, readBack.heaviestLiftKg, "the non-null 0 sentinel survives the wire")
        assertNull(
            EchoAchievedLoadResolver.primaryLoadKg(readBack),
            "no accepted telemetry -> Load unavailable after sync, never the configured 5 kg / 11.02 lb",
        )
        assertNull(EchoAchievedLoadResolver.fromSession(readBack))
    }

    @Test
    fun `the rep based estimate is suppressed when the load is unavailable`() = runTest {
        val build = pushResultFor(unmeasuredEchoSession())
        val exercise = build.sessions.single().exercises.single()

        assertEquals(0f, exercise.sets.single().weightKg, "the wire ships the authorized 0 sentinel")
        assertNull(
            exercise.estimatedOneRepMaxKg,
            "no estimate may be derived from an unavailable load",
        )
    }

    @Test
    fun `the rep based estimate derives from the achieved load not the configured seed`() = runTest {
        val build = pushResultFor(achievedEchoSession())
        val exercise = build.sessions.single().exercises.single()

        assertEquals(
            OneRepMaxCalculator.estimate(80f, 9),
            exercise.estimatedOneRepMaxKg,
            "the estimate is computed from the achieved 80 kg peak",
        )
        assertTrue(
            exercise.estimatedOneRepMaxKg != OneRepMaxCalculator.estimate(5f, 9),
            "the estimate must never be computed off the configured 5 kg seed",
        )
    }

    // ===== identity / replacement semantics =====

    @Test
    fun `re-push keeps child identity stable and replaces children exactly once`() = runTest {
        val session = achievedEchoSession(id = "echo-stable", routineSessionId = "echo-stable")
        val server = FakePortalServer()
        val build = pushResultFor(session)
        val payload = PortalSyncPayload(deviceId = "device-1", lastSync = 0L, sessions = build.sessions)

        server.push(payload)
        val firstExerciseIds = server.exerciseIds("echo-stable")
        val firstSetIds = server.session("echo-stable")!!.exercises.flatMap { ex -> ex.sets.map { it.id } }

        // Second push of the same session: replace_session_children re-inserts the payload.
        server.push(payload)
        val secondExerciseIds = server.exerciseIds("echo-stable")
        val secondSetIds = server.session("echo-stable")!!.exercises.flatMap { ex -> ex.sets.map { it.id } }

        assertEquals(firstExerciseIds, secondExerciseIds, "exercise identity is stable across a re-push")
        assertEquals(firstSetIds, secondSetIds, "set identity is stable across a re-push - no invented ids")
        assertEquals(1, server.setCount("echo-stable"), "re-push replaces children rather than duplicating them")
    }

    @Test
    fun `a pulled row keeps the same local id so a re-pull cannot duplicate the session`() = runTest {
        val (firstPull, server) = roundTrip(achievedEchoSession(id = "echo-dedupe", routineSessionId = "echo-dedupe"))
        val database = createTestDatabase()

        // The same session pulled twice goes through the real pull merge both times.
        mergePulled(database, listOf(firstPull))
        mergePulled(database, listOf(firstPull))

        val rows = SqlDelightWorkoutRepository(database, FakeExerciseRepository()).getAllSessions("default").first()
        assertEquals(1, rows.size, "a re-pull must merge onto the same session row, not add another")
        assertEquals(firstPull.id, rows.single().id, "the local row id stays the portal component id")
        assertTrue(server.session("echo-dedupe") != null, "the portal still owns exactly one such session")
    }
}
