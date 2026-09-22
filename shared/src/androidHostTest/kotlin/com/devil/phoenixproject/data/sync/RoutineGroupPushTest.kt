package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.data.repository.SqlDelightSyncRepository
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.testutil.FakeCompletedSetRepository
import com.devil.phoenixproject.testutil.FakeExternalActivityRepository
import com.devil.phoenixproject.testutil.FakeGamificationRepository
import com.devil.phoenixproject.testutil.FakePortalServer
import com.devil.phoenixproject.testutil.FakeProfilePreferenceSyncRepository
import com.devil.phoenixproject.testutil.FakeRepMetricRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.FakeVelocityOneRepMaxRepository
import com.devil.phoenixproject.testutil.PortalServerApiClient
import com.devil.phoenixproject.testutil.createTestDatabase
import com.russhwolf.settings.MapSettings
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * AF-3 / AF-4 regression suite, against a real foreign-keys-ON database and a fake
 * portal that models `replace_session_children`, the server-clock `updated_at` trigger
 * and the LWW gate.
 *
 * The bug these pin down: a tracked routine writes one WorkoutSession row per set and
 * syncs after every one. The push sent only the rows changed since `lastSync`, and the
 * portal deletes a workout's whole exercise list before re-inserting the payload — so
 * each set's sync destroyed the sets uploaded before it, and the website ended up
 * showing "1 exercise, 1 set" for a six-set workout.
 */
class RoutineGroupPushTest {

    private lateinit var database: PhoenixDatabase
    private lateinit var userProfileRepository: FakeUserProfileRepository
    private lateinit var syncRepository: SqlDelightSyncRepository
    private lateinit var server: FakePortalServer
    private lateinit var apiClient: PortalServerApiClient
    private lateinit var tokenStorage: PortalTokenStorage
    private lateinit var repMetricRepository: FakeRepMetricRepository
    private lateinit var manager: SyncManager

    private val profileId = "active-profile"

    /**
     * The fake portal runs on the real device clock plus [serverSkewMs]. It has to move
     * with the device clock, not sit on a fixed epoch: the push stamps rows with device
     * time while `lastSync` becomes the pull's server time, so a frozen server clock
     * would leave every stamped row permanently "newer than lastSync".
     *
     * [serverSkewMs] is the lever the LWW tests pull — a server clock ahead of the
     * device is what makes a routine's later sets get rejected (A-010).
     */
    private var serverSkewMs = 0L

    private val baseTime = com.devil.phoenixproject.domain.model.currentTimeMillis()

    @Before
    fun setup() {
        database = createTestDatabase()
        userProfileRepository = FakeUserProfileRepository()
        userProfileRepository.setActiveProfileForTest(id = profileId)
        syncRepository = SqlDelightSyncRepository(database, userProfileRepository)
        server = FakePortalServer(
            serverNow = { com.devil.phoenixproject.domain.model.currentTimeMillis() + serverSkewMs },
        )
        apiClient = PortalServerApiClient(server)
        tokenStorage = PortalTokenStorage(MapSettings())
        tokenStorage.saveGoTrueAuth(
            GoTrueAuthResponse(
                accessToken = "token",
                tokenType = "bearer",
                expiresIn = 3600,
                expiresAt = com.devil.phoenixproject.domain.model.currentTimeMillis() / 1000 + 3600,
                refreshToken = "refresh",
                user = GoTrueUser(id = "user-1", email = "a@b.c"),
            ),
        )
        // The repair push is a one-time history rebuild; the ordinary-push tests must
        // not have it dragging extra groups into their payloads.
        tokenStorage.setRoutineGroupRepairCursor(profileId, 0L)
        repMetricRepository = FakeRepMetricRepository()
        manager = SyncManager(
            apiClient = apiClient,
            tokenStorage = tokenStorage,
            syncRepository = syncRepository,
            gamificationRepository = FakeGamificationRepository(),
            repMetricRepository = repMetricRepository,
            userProfileRepository = userProfileRepository,
            profilePreferenceSyncRepository = FakeProfilePreferenceSyncRepository(),
            externalActivityRepository = FakeExternalActivityRepository(),
            velocityOneRepMaxRepository = FakeVelocityOneRepMaxRepository(),
            isProfilePreferenceMigrationReady = { true },
            completedSetRepository = FakeCompletedSetRepository(),
        )
    }

    // ===== AF-3: the group-complete gather =====

    @Test
    fun `a routine group is pushed whole even when only one of its rows changed`() = runTest {
        // Set 1 already reached the portal on an earlier sync and was stamped out of
        // the delta window. Set 2 is new.
        insertRoutineSet("set-1", groupId = GROUP, timestamp = baseTime, stampedAt = baseTime + 1_000)
        insertRoutineSet("set-2", groupId = GROUP, timestamp = baseTime + 60_000)
        tokenStorage.setLastSyncTimestamp(baseTime + 30_000)

        manager.sync()

        val sent = lastSessionPayload(GROUP)
        assertEquals(2, sent.exercises.size, "the whole routine group must be sent, not just the changed set")
        assertEquals(2, sent.exerciseCount)
        assertEquals(listOf("set-1", "set-2"), sent.exercises.map { it.id }.sorted())
        // And the portal, which replaces a workout's children wholesale, keeps both.
        assertEquals(listOf("set-1", "set-2"), server.exerciseIds(GROUP).sorted())
    }

    @Test
    fun `syncing set by set leaves every set on the portal`() = runTest {
        // Every set of a tracked routine is its own row and triggers its own sync.
        insertRoutineSet("set-1", groupId = GROUP, timestamp = baseTime)
        manager.sync()
        assertEquals(1, server.setCount(GROUP))

        insertRoutineSet("set-2", groupId = GROUP, timestamp = baseTime + 1_000)
        manager.sync()

        insertRoutineSet("set-3", groupId = GROUP, timestamp = baseTime + 2_000)
        manager.sync()

        assertEquals(listOf("set-1", "set-2", "set-3"), server.exerciseIds(GROUP).sorted())
        assertEquals(3, server.setCount(GROUP))
        assertEquals(3, server.session(GROUP)?.exerciseCount)
    }

    @Test
    fun `a set whose row predates the portal's server clock is re-pushed with the server's own stamp`() = runTest {
        insertRoutineSet("set-1", groupId = GROUP, timestamp = baseTime)
        manager.sync()

        // The portal's clock now runs ten minutes ahead of the phone (A-010). Every
        // accepted update stamps THAT clock, and a routine row carries the moment its
        // set STARTED, so from here on the group's pushes are behind the server.
        serverSkewMs = 10 * 60_000L
        insertRoutineSet("set-2", groupId = GROUP, timestamp = baseTime + 1_000)
        manager.sync()

        insertRoutineSet("set-3", groupId = GROUP, timestamp = baseTime + 2_000)
        manager.sync()

        assertTrue(GROUP in server.rejectedIds, "the LWW gate should have turned the group away at least once")
        assertEquals(
            listOf("set-1", "set-2", "set-3"),
            server.exerciseIds(GROUP).sorted(),
            "a rejected group must be re-pushed with the server's own timestamp + 1 ms",
        )
        assertStamped("set-3")
    }

    // ===== AF-4: web notes =====

    @Test
    fun `a note written on the website survives the next set's sync`() = runTest {
        insertRoutineSet("set-1", groupId = GROUP, timestamp = baseTime)
        manager.sync()

        // Written after set 1's push. Its trigger time is ahead of anything the phone
        // will send for set 2, so the first push of the group is rejected; the pull
        // then brings the note to the phone and the re-push hands it back untouched.
        server.writeWebNote(GROUP, "felt strong today", updatedAt = baseTime + 10 * 60_000L)

        insertRoutineSet("set-2", groupId = GROUP, timestamp = baseTime + 1_000)
        manager.sync()

        assertEquals("felt strong today", server.session(GROUP)?.notes)
        assertEquals(listOf("set-1", "set-2"), server.exerciseIds(GROUP).sorted())
    }

    @Test
    fun `an accepted push hands back the note the phone already knows`() = runTest {
        insertRoutineSet("set-1", groupId = GROUP, timestamp = baseTime)
        manager.sync()

        // Get the note onto the phone the ordinary way: it is written far enough ahead
        // that set 2's push is rejected, the pull stores it, and the re-push keeps it.
        server.writeWebNote(GROUP, "shoulder felt off", updatedAt = baseTime + 10 * 60_000L)
        insertRoutineSet("set-2", groupId = GROUP, timestamp = baseTime + 1_000)
        manager.sync()
        assertEquals("shoulder felt off", database.phoenixDatabaseQueries.getSessionNotes(GROUP).executeAsOne().notes)

        // Set 3's push is ACCEPTED outright -- no rejection, no re-push. The portal
        // overwrites `notes` with whatever the payload carries, so the session DTO
        // itself has to carry the note.
        apiClient.pushPayloads.clear()
        insertRoutineSet("set-3", groupId = GROUP, timestamp = baseTime + 2_000)
        manager.sync()

        assertEquals("shoulder felt off", lastSessionPayload(GROUP).notes, "the pushed session DTO must carry the note")
        assertEquals("shoulder felt off", server.session(GROUP)?.notes)
        assertEquals(3, server.exerciseIds(GROUP).size)
    }

    @Test
    fun `a note cleared on the website is not resurrected by the next push`() = runTest {
        insertRoutineSet("set-1", groupId = GROUP, timestamp = baseTime)
        manager.sync()
        server.writeWebNote(GROUP, "first draft", updatedAt = baseTime + 10 * 60_000L)
        insertRoutineSet("set-2", groupId = GROUP, timestamp = baseTime + 1_000)
        manager.sync()
        assertEquals("first draft", database.phoenixDatabaseQueries.getSessionNotes(GROUP).executeAsOne().notes)

        // The user deletes the note on the website. The portal stores NULL.
        server.writeWebNote(GROUP, null, updatedAt = baseTime + 20 * 60_000L)

        insertRoutineSet("set-3", groupId = GROUP, timestamp = baseTime + 2_000)
        manager.sync()

        assertNull(server.session(GROUP)?.notes, "the push must not re-send a note the user deleted on the web")
        assertNull(database.phoenixDatabaseQueries.getSessionNotes(GROUP).executeAsOneOrNull()?.notes)
    }

    // ===== The hold guard: never push a partial group =====

    @Test
    fun `a group with a pulled sibling this device has no data for is not pushed at all`() = runTest {
        // Sibling recorded on another device: the portal holds its exercise and sets,
        // this device only ever pulled the row and has no metrics for it.
        server.seedSession(
            id = GROUP,
            exercises = listOf(
                FakePortalServer.StoredExercise(
                    id = "other-device-set",
                    name = "Row",
                    sets = listOf(FakePortalServer.StoredSet("portal-set-a", 42f, 8)),
                ),
            ),
        )
        insertRoutineSet("other-device-set", groupId = GROUP, timestamp = baseTime, stampedAt = baseTime + 10)
        database.phoenixDatabaseQueries.markSessionPulled("other-device-set")
        insertRoutineSet("local-set", groupId = GROUP, timestamp = baseTime + 60_000)

        manager.sync()

        assertTrue(
            apiClient.pushPayloads.none { payload -> payload.sessions.any { it.id == GROUP } },
            "the group must be held, never sent without the sibling",
        )
        // The portal keeps exactly what it had.
        assertEquals(listOf("other-device-set"), server.exerciseIds(GROUP))
        assertEquals(1, server.setCount(GROUP))
        assertEquals("portal-set-a", server.session(GROUP)!!.exercises.single().sets.single().id)
        // And nothing in the held group is marked as synced.
        assertNotStamped("local-set")
    }

    // ===== The one-time repair push =====

    @Test
    fun `the repair push re-sends a truncated group, caps the batch and skips held groups`() = runTest {
        // The portal is left as the old per-set push left it: one exercise per workout.
        // Timestamps run BACKWARDS from the present so the repair's newest-first walk
        // starts with group 1 and leaves the oldest groups for a later sync.
        val repairable = (1..SyncManager.ROUTINE_GROUP_REPAIR_BATCH + 2).map { index ->
            val groupId = "repair-group-$index"
            val timestamp = baseTime - index * 100_000L
            server.seedSession(
                id = groupId,
                exercises = listOf(
                    FakePortalServer.StoredExercise(
                        id = "$groupId-set-2",
                        name = "Squat",
                        sets = listOf(FakePortalServer.StoredSet("stale-set-$index", 60f, 5)),
                    ),
                ),
                updatedAt = baseTime,
            )
            insertRoutineSet(
                "$groupId-set-1",
                groupId = groupId,
                timestamp = timestamp,
                stampedAt = timestamp + 10,
                withLocalData = true,
            )
            insertRoutineSet(
                "$groupId-set-2",
                groupId = groupId,
                timestamp = timestamp + 1,
                stampedAt = timestamp + 11,
                withLocalData = true,
            )
            groupId
        }
        // One group carries a pulled sibling with no local data: the repair must leave
        // it exactly as the portal has it rather than half-rebuild it.
        val heldGroup = "repair-held"
        server.seedSession(
            id = heldGroup,
            exercises = listOf(
                FakePortalServer.StoredExercise(
                    id = "held-remote-set",
                    name = "Row",
                    sets = listOf(FakePortalServer.StoredSet("held-portal-set", 30f, 10)),
                ),
            ),
            updatedAt = baseTime,
        )
        // Sits between groups 1 and 2, so it is inside the first capped batch.
        val heldTimestamp = baseTime - 150_000L
        insertRoutineSet("held-remote-set", groupId = heldGroup, timestamp = heldTimestamp, stampedAt = heldTimestamp + 10)
        database.phoenixDatabaseQueries.markSessionPulled("held-remote-set")
        insertRoutineSet(
            "held-local-set",
            groupId = heldGroup,
            timestamp = heldTimestamp + 1,
            stampedAt = heldTimestamp + 11,
            withLocalData = true,
        )

        tokenStorage.setRoutineGroupRepairCursor(profileId, Long.MAX_VALUE)
        tokenStorage.setLastSyncTimestamp(baseTime + 100_000_000L)
        manager.sync()

        val firstBatchIds = apiClient.pushPayloads.flatMap { it.sessions }.map { it.id }.toSet()
        assertEquals(
            SyncManager.ROUTINE_GROUP_REPAIR_BATCH - 1,
            firstBatchIds.size,
            "the repair must stop at its per-sync cap, and the held group must not consume a push slot",
        )
        assertTrue(heldGroup !in firstBatchIds, "a group with a blocking sibling must be skipped, not repaired")
        assertEquals(listOf("held-remote-set"), server.exerciseIds(heldGroup))

        // Every repaired group now has both of its sets back on the portal.
        firstBatchIds.forEach { groupId ->
            assertEquals(2, server.exerciseIds(groupId).size, "$groupId should have been rebuilt whole")
        }

        // The cursor walked backwards, so a second sync picks up the older remainder.
        apiClient.pushPayloads.clear()
        manager.sync()
        val secondBatchIds = apiClient.pushPayloads.flatMap { it.sessions }.map { it.id }.toSet()
        assertTrue(secondBatchIds.isNotEmpty(), "the remaining groups must be repaired on a later sync")
        assertTrue(secondBatchIds.none { it in firstBatchIds }, "the cursor must not re-walk repaired groups")
        repairable.forEach { groupId ->
            assertEquals(2, server.exerciseIds(groupId).size, "$groupId should have been rebuilt whole")
        }
    }

    // ===== Stamping exactly what went out =====

    @Test
    fun `a session saved between gather and stamp is not marked as synced`() = runTest {
        insertRoutineSet("set-1", groupId = null, timestamp = baseTime)
        // A set finishing while the push is in flight: written after the gather, so the
        // portal never saw it.
        apiClient.onPush = {
            insertRoutineSet("set-mid-push", groupId = null, timestamp = baseTime + 1_000)
        }

        manager.sync()

        assertStamped("set-1")
        assertNotStamped("set-mid-push")
    }

    @Test
    fun `a pushed session edited during the push keeps its newer stamp and is re-sent`() = runTest {
        insertRoutineSet("set-1", groupId = null, timestamp = baseTime)
        val editTime = com.devil.phoenixproject.domain.model.currentTimeMillis() + 60_000
        apiClient.onPush = {
            database.phoenixDatabaseQueries.updateSessionTimestamp(editTime, "set-1")
        }

        manager.sync()

        assertEquals(editTime, stampOf("set-1"), "the post-push stamp must not overwrite an edit the portal never saw")

        apiClient.onPush = null
        apiClient.pushPayloads.clear()
        tokenStorage.setLastSyncTimestamp(editTime - 1)
        manager.sync()
        assertTrue(
            apiClient.pushPayloads.flatMap { it.sessions }.any { it.id == "set-1" },
            "the edited row must come back in the next delta",
        )
    }

    @Test
    fun `an LWW-rejected session that cannot be retried stays unstamped`() = runTest {
        // The portal already holds a newer copy and returns no server timestamp, so
        // there is nothing to re-push against.
        server.seedSession(
            id = "solo-session",
            exercises = emptyList(),
            updatedAt = baseTime + 60 * 60_000L,
            routineSessionId = null,
        )
        insertRoutineSet("solo-session", groupId = null, timestamp = baseTime)
        apiClient.stripRejectionTimestamps = true

        manager.sync()

        assertNotStamped("solo-session")
    }

    // ===== Review fix round (round 2) =====

    @Test
    fun `the repair push skips a group that holds a row with no local measurements`() = runTest {
        // Group A is the security R-1 population: a locally created row whose children
        // are gone (F-001's cascade, or a pre-migration set saved without metrics). It
        // is NOT portal-pulled (portalOrigin != 1), so the ordinary hold guard does not see it —
        // but the repair would rebuild it as an empty exercise and destroy the portal's
        // copy of that set's rep summaries, which is the last surviving copy.
        val atRisk = "repair-childless"
        server.seedSession(
            id = atRisk,
            exercises = listOf(
                FakePortalServer.StoredExercise(
                    id = "at-risk-portal-set",
                    name = "Squat",
                    sets = listOf(FakePortalServer.StoredSet("portal-rep-summaries-here", 60f, 5)),
                ),
            ),
            updatedAt = baseTime,
        )
        val atRiskTime = baseTime - 300_000L
        insertRoutineSet("at-risk-1", groupId = atRisk, timestamp = atRiskTime, stampedAt = atRiskTime + 10, withLocalData = true)
        insertRoutineSet("at-risk-2", groupId = atRisk, timestamp = atRiskTime + 1, stampedAt = atRiskTime + 11)

        // Group B is healthy: both rows have local data, so the repair rebuilds it.
        val healthy = "repair-healthy"
        server.seedSession(
            id = healthy,
            exercises = listOf(
                FakePortalServer.StoredExercise(
                    id = "healthy-stale",
                    name = "Bench",
                    sets = listOf(FakePortalServer.StoredSet("stale", 40f, 5)),
                ),
            ),
            updatedAt = baseTime,
        )
        val healthyTime = baseTime - 200_000L
        insertRoutineSet("healthy-1", groupId = healthy, timestamp = healthyTime, stampedAt = healthyTime + 10, withLocalData = true)
        insertRoutineSet("healthy-2", groupId = healthy, timestamp = healthyTime + 1, stampedAt = healthyTime + 11, withLocalData = true)

        tokenStorage.setRoutineGroupRepairCursor(profileId, Long.MAX_VALUE)
        tokenStorage.setLastSyncTimestamp(baseTime + 100_000_000L)
        manager.sync()

        val pushed = apiClient.pushPayloads.flatMap { it.sessions }.map { it.id }.toSet()
        assertTrue(atRisk !in pushed, "a repair group holding a childless row must not be re-sent")
        assertEquals(listOf("at-risk-portal-set"), server.exerciseIds(atRisk), "the portal keeps its last surviving copy")
        assertEquals(listOf("healthy-1", "healthy-2"), server.exerciseIds(healthy).sorted())

        // The cursor still walks past the skipped group, so a second sync does not
        // re-attempt it (and with only these two candidates, the repair completes).
        apiClient.pushPayloads.clear()
        manager.sync()
        assertTrue(
            apiClient.pushPayloads.flatMap { it.sessions }.none { it.id == atRisk },
            "the cursor must walk past a skipped childless group so the repair terminates",
        )
    }

    @Test
    fun `a standalone LWW-rejected session is not re-pushed and keeps its web note`() = runTest {
        // A standalone session's portal id IS its local row id. The re-push used to run
        // for these too, and `notes = freshNotes[dto.id]` would send back whatever
        // (usually nothing) this device has under that id — erasing the website's note.
        server.seedSession(
            id = "solo-keep-note",
            exercises = listOf(
                FakePortalServer.StoredExercise(
                    id = "solo-ex",
                    name = "Curl",
                    sets = listOf(FakePortalServer.StoredSet("solo-set", 20f, 10)),
                ),
            ),
            notes = "typed on the website",
            updatedAt = baseTime + 60 * 60_000L,
            routineSessionId = null,
        )
        insertRoutineSet("solo-keep-note", groupId = null, timestamp = baseTime, withLocalData = true)

        manager.sync()

        assertTrue("solo-keep-note" in server.rejectedIds, "the LWW gate should have turned it away")
        assertEquals(1, apiClient.pushPayloads.size, "a standalone rejection must not trigger a re-push")
        assertEquals(
            "typed on the website",
            server.session("solo-keep-note")?.notes,
            "the re-push must not null out a note the website wrote",
        )
        assertNotStamped("solo-keep-note")
    }

    @Test
    fun `the LWW re-push carries personalRecords so the portal cannot derive id-less rows`() = runTest {
        // PORTAL ROW-DUPLICATION HAZARD: an empty `personalRecords` makes the portal
        // derive id-less rows from every `set.isPr` and INSERT them (no upsert).
        database.phoenixDatabaseQueries.insertRecord(
            exerciseId = "bench",
            exerciseName = "Bench Press",
            weight = 40.0,
            reps = 8L,
            oneRepMax = 45.0,
            achievedAt = baseTime,
            workoutMode = "OldSchool",
            prType = "MAX_WEIGHT",
            volume = 320.0,
            phase = "COMBINED",
            profile_id = profileId,
            cable_count = 2L,
            uuid = "pr-uuid-1",
        )
        server.seedSession(
            id = GROUP,
            exercises = listOf(
                FakePortalServer.StoredExercise(
                    id = "stale",
                    name = "Bench",
                    sets = listOf(FakePortalServer.StoredSet("stale-set", 40f, 5)),
                ),
            ),
            updatedAt = baseTime + 60 * 60_000L,
        )
        insertRoutineSet("set-1", groupId = GROUP, timestamp = baseTime, withLocalData = true)

        manager.sync()

        assertTrue(apiClient.pushPayloads.size >= 2, "the grouped rejection should have produced a re-push")
        apiClient.pushPayloads.forEachIndexed { index, payload ->
            assertTrue(
                payload.personalRecords.isNotEmpty(),
                "push #$index must carry personalRecords (empty arms the portal row-duplication hazard)",
            )
        }
    }

    @Test
    fun `a still-rejected repair-group row is re-armed by clearing its stamp`() = runTest {
        // Repair candidates are already-stamped rows. If the portal rejects them and
        // the re-push cannot run (no server timestamp here), those stamps would
        // otherwise keep them out of the next delta while the repair cursor has
        // already walked past the group — permanently stranded. Clearing `updatedAt`
        // puts them back in the ordinary delta.
        val stranded = "repair-stranded"
        server.seedSession(
            id = stranded,
            exercises = listOf(
                FakePortalServer.StoredExercise(
                    id = "portal-copy",
                    name = "Squat",
                    sets = listOf(FakePortalServer.StoredSet("portal-set", 60f, 5)),
                ),
            ),
            updatedAt = baseTime + 60 * 60_000L,
        )
        val t = baseTime - 400_000L
        insertRoutineSet("stranded-1", groupId = stranded, timestamp = t, stampedAt = t + 10, withLocalData = true)
        insertRoutineSet("stranded-2", groupId = stranded, timestamp = t + 1, stampedAt = t + 11, withLocalData = true)

        tokenStorage.setRoutineGroupRepairCursor(profileId, Long.MAX_VALUE)
        tokenStorage.setLastSyncTimestamp(baseTime + 100_000_000L)
        apiClient.stripRejectionTimestamps = true
        manager.sync()

        assertTrue(stranded in server.rejectedIds, "the LWW gate should have turned the repair away")
        assertNotStamped("stranded-1")
        assertNotStamped("stranded-2")
    }

    @Test
    fun `a non-INFERNO push never loads the 50 Hz rep metrics`() = runTest {
        // Default tier is not Inferno, so telemetry is gated off. The push still needs
        // the scalar rep summaries, but it must not deserialize the force-curve arrays
        // to get them (plan step 2 / acceptance line 42).
        repMetricRepository.saveRepMetrics(
            "set-1",
            listOf(
                com.devil.phoenixproject.domain.model.RepMetricData(
                    repNumber = 1,
                    isWarmup = false,
                    startTimestamp = baseTime,
                    endTimestamp = baseTime + 800,
                    durationMs = 800,
                    concentricDurationMs = 400,
                    concentricPositions = floatArrayOf(0f, 100f),
                    concentricLoadsA = floatArrayOf(20f, 20f),
                    concentricLoadsB = floatArrayOf(20f, 20f),
                    concentricVelocities = floatArrayOf(500f, 500f),
                    concentricTimestamps = longArrayOf(0L, 100L),
                    eccentricDurationMs = 400,
                    eccentricPositions = floatArrayOf(100f, 0f),
                    eccentricLoadsA = floatArrayOf(20f, 20f),
                    eccentricLoadsB = floatArrayOf(20f, 20f),
                    eccentricVelocities = floatArrayOf(-500f, -500f),
                    eccentricTimestamps = longArrayOf(400L, 500L),
                    peakForceA = 20f,
                    peakForceB = 20f,
                    avgForceConcentricA = 18f,
                    avgForceConcentricB = 18f,
                    avgForceEccentricA = 17f,
                    avgForceEccentricB = 17f,
                    peakVelocity = 600f,
                    avgVelocityConcentric = 500f,
                    avgVelocityEccentric = 500f,
                    rangeOfMotionMm = 400f,
                    peakPowerWatts = 250f,
                    avgPowerWatts = 200f,
                ),
            ),
        )
        insertRoutineSet("set-1", groupId = null, timestamp = baseTime)

        manager.sync()

        assertEquals(0, repMetricRepository.getRepMetricsCalls, "non-INFERNO must not load the curve arrays")
        assertTrue(repMetricRepository.getRepMetricSummariesCalls >= 1, "the scalar summaries must still ship")
    }

    // ===== Helpers =====

    private fun lastSessionPayload(portalSessionId: String): PortalWorkoutSessionDto = apiClient.pushPayloads
        .flatMap { it.sessions }
        .last { it.id == portalSessionId }

    private fun stampOf(sessionId: String): Long? = database.phoenixDatabaseQueries
        .selectSessionById(sessionId)
        .executeAsOne()
        .updatedAt

    private fun assertStamped(sessionId: String) {
        assertNotNull(stampOf(sessionId), "$sessionId should have been stamped as synced")
    }

    private fun assertNotStamped(sessionId: String) {
        assertNull(stampOf(sessionId), "$sessionId must stay pending: the portal never accepted it")
    }

    /**
     * One completed set. [stampedAt] models a row an earlier sync already pushed (and
     * therefore stamped out of the delta window). A row only counts as "pulled from
     * another device" once it is also portal-pulled (portalOrigin = 1) with no local children.
     *
     * [withLocalData] attaches a CompletedSet so the row is not "childless" — the
     * three-table local-data predicate (MetricSample / RepMetric / CompletedSet) that
     * both the hold guard and the repair's childless skip look at.
     */
    private fun insertRoutineSet(
        id: String,
        groupId: String? = GROUP,
        timestamp: Long,
        stampedAt: Long? = null,
        withLocalData: Boolean = false,
    ) {
        val q = database.phoenixDatabaseQueries
        q.insertSession(
            id = id,
            timestamp = timestamp,
            mode = "OldSchool",
            targetReps = 8L,
            weightPerCableKg = 40.0,
            progressionKg = 0.0,
            duration = 45_000L,
            totalReps = 8L,
            warmupReps = 0L,
            workingReps = 8L,
            isJustLift = 0L,
            stopAtTop = 0L,
            eccentricLoad = 100L,
            echoLevel = 0L,
            exerciseId = "bench",
            exerciseName = "Bench Press",
            routineSessionId = groupId,
            routineName = groupId?.let { "Push Day" },
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
            cableCount = 2L,
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
            display_multiplier = 2L,
            externalAddedLoadKg = 0.0,
            counterweightKg = 0.0,
            rackItemsJson = "[]",
        )
        stampedAt?.let { q.updateSessionTimestamp(it, id) }
        if (withLocalData) {
            q.insertCompletedSet(
                id = "cs-$id",
                session_id = id,
                planned_set_id = null,
                routine_exercise_id = null,
                set_number = 1L,
                set_type = "STANDARD",
                attempt_number = 1L,
                actual_reps = 8L,
                actual_weight_kg = 40.0,
                logged_rpe = null,
                is_pr = 0L,
                completed_at = timestamp,
                set_end_reason = "UNKNOWN",
            )
        }
    }

    private companion object {
        const val GROUP = "routine-session-1"
    }
}
