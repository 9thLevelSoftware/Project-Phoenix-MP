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
        tokenStorage.setPushWatermark("user-1", "active-profile", baseTime + 30_000)

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
        tokenStorage.setPushWatermark("user-1", "active-profile", baseTime + 100_000_000L)
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
            // A real mid-push edit both re-stamps the row and bumps its sync generation
            // (every content write calls markWorkoutComponentDirty). The ack then clears
            // only the generation this push gathered, so the edit stays dirty.
            database.phoenixDatabaseQueries.updateSessionTimestamp(editTime, "set-1")
            database.phoenixDatabaseQueries.markWorkoutComponentDirty("set-1")
        }

        manager.sync()

        assertEquals(editTime, stampOf("set-1"), "the post-push stamp must not overwrite an edit the portal never saw")

        apiClient.onPush = null
        apiClient.pushPayloads.clear()
        tokenStorage.setPushWatermark("user-1", "active-profile", editTime - 1)
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
        tokenStorage.setPushWatermark("user-1", "active-profile", baseTime + 100_000_000L)
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
        tokenStorage.setPushWatermark("user-1", "active-profile", baseTime + 100_000_000L)
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

    // ===== Step 11: the LWW content-hash gate =====

    @Test
    fun `an LWW-rejected session with unchanged content is stamped and not re-sent`() = runTest {
        // First sync uploads and is accepted, so the content hash of what actually
        // reached the portal is stored. Then the website edits the row (bumping its
        // updated_at ahead of us) and the row is dirtied again without any content
        // change — the exact "rejected forever" loop the gate exists to break.
        insertRoutineSet("set-1", groupId = GROUP, timestamp = baseTime, withLocalData = true)
        manager.sync()
        assertStamped("set-1")
        assertTrue(apiClient.pushPayloads.flatMap { it.sessions }.any { it.id == GROUP })
        val uploadedHash = tokenStorage.getSessionSentHash("user-1", profileId, GROUP)
        assertNotNull(uploadedHash, "an accepted push must record the content hash")

        server.writeWebNote(GROUP, "typed on the website", updatedAt = baseTime + 60 * 60_000L)
        // Dirty again with byte-identical content: the gather re-sends the same DTO.
        database.phoenixDatabaseQueries.markWorkoutComponentDirty("set-1")
        apiClient.pushPayloads.clear()

        manager.sync()

        assertEquals(1, apiClient.pushPayloads.size, "unchanged content must not trigger a second (re-push) payload")
        assertEquals(
            "typed on the website",
            server.session(GROUP)?.notes,
            "the gate must not re-send and erase the website's note",
        )
        assertStamped("set-1")
    }

    @Test
    fun `an LWW-rejected session with changed content is re-sent`() = runTest {
        insertRoutineSet("set-1", groupId = GROUP, timestamp = baseTime, withLocalData = true)
        manager.sync()
        val uploadedHash = tokenStorage.getSessionSentHash("user-1", profileId, GROUP)
        assertNotNull(uploadedHash)

        server.writeWebNote(GROUP, "typed on the website", updatedAt = baseTime + 60 * 60_000L)
        // A real local edit: another set lands, so the DTO fingerprint changes.
        insertRoutineSet("set-2", groupId = GROUP, timestamp = baseTime + 1_000, withLocalData = true)
        apiClient.pushPayloads.clear()

        manager.sync()

        assertTrue(
            apiClient.pushPayloads.size >= 2,
            "changed content must be re-sent after the LWW rejection (original push + re-push)",
        )
        assertTrue(
            apiClient.pushPayloads.drop(1).flatMap { it.sessions }.any { it.id == GROUP },
            "the re-push payload must carry the group",
        )
        assertStamped("set-2")
        // The re-push is stamped serverUpdatedAt+1ms, so the portal accepts it and
        // keeps the note the website wrote (PR 8 sends notes on every push).
        assertEquals("typed on the website", server.session(GROUP)?.notes)
    }

    @Test
    fun `a never-accepted grouped row is not stamped when the portal rejects it`() = runTest {
        // The portal already holds a newer copy, and this row was never uploaded, so
        // there is no sent hash. The unchanged-content branch must not claim it —
        // stamping here would mark a never-uploaded row as synced and drop its data.
        server.seedSession(
            id = GROUP,
            exercises = listOf(
                FakePortalServer.StoredExercise(
                    id = "portal-copy",
                    name = "Squat",
                    sets = listOf(FakePortalServer.StoredSet("portal-set", 60f, 5)),
                ),
            ),
            updatedAt = baseTime + 60 * 60_000L,
        )
        insertRoutineSet("set-1", groupId = GROUP, timestamp = baseTime, withLocalData = true)
        apiClient.stripRejectionTimestamps = true

        manager.sync()

        assertNull(tokenStorage.getSessionSentHash("user-1", profileId, GROUP), "a never-accepted row must have no sent hash")
        assertNotStamped("set-1")
    }

    @Test
    fun `deleting a synced session removes its sent hash on the next sync`() = runTest {
        // codex #856 P2: fingerprints must not outlive the workout they describe.
        insertRoutineSet("set-1", groupId = GROUP, timestamp = baseTime, withLocalData = true)
        manager.sync()
        assertNotNull(tokenStorage.getSessionSentHash("user-1", profileId, GROUP))

        val now = com.devil.phoenixproject.domain.model.currentTimeMillis()
        database.phoenixDatabaseQueries.softDeleteSession(now, now, "set-1")
        manager.sync()

        assertNull(
            tokenStorage.getSessionSentHash("user-1", profileId, GROUP),
            "a deleted workout's sent hash must be garbage-collected",
        )
        assertTrue(GROUP !in tokenStorage.sessionSentHashIds("user-1", profileId))
    }

    @Test
    fun `a session the portal neither acknowledged nor rejected gets no sent hash`() = runTest {
        // codex #856 P1: a successful response that omits a session from
        // acknowledgedWorkoutSessionIds did NOT apply it. Recording its hash would let a
        // later LWW rejection with the same content stamp never-accepted data as synced.
        insertRoutineSet("set-1", groupId = GROUP, timestamp = baseTime, withLocalData = true)
        apiClient.stripAcknowledgements = true

        manager.sync()

        assertTrue(apiClient.pushPayloads.flatMap { it.sessions }.any { it.id == GROUP })
        assertNull(
            tokenStorage.getSessionSentHash("user-1", profileId, GROUP),
            "an unacknowledged session must have no sent hash",
        )
    }

    // ===== codex #856 P2: accepted PRs/routines are stamped at the watermark =====

    @Test
    fun `a pushed PR and a pushed untimestamped routine are not re-sent by the next sync`() = runTest {
        // The ack stamp must not be newer than the push watermark (gatherStartedAt), or the
        // next gather's `updatedAt > watermark` re-selects and re-stamps them forever.
        insertLivePr(PR_UUID)
        insertUntimestampedRoutine(ACCEPTED_ROUTINE)

        manager.sync()
        assertTrue(apiClient.pushPayloads.flatMap { it.personalRecords }.any { it.id == PR_UUID })
        assertTrue(apiClient.pushPayloads.flatMap { it.routines }.any { it.id == ACCEPTED_ROUTINE })

        apiClient.pushPayloads.clear()
        manager.sync()

        assertTrue(
            apiClient.pushPayloads.flatMap { it.personalRecords }.none { it.id == PR_UUID },
            "an already-accepted PR must not be pushed again",
        )
        assertTrue(
            apiClient.pushPayloads.flatMap { it.routines }.none { it.id == ACCEPTED_ROUTINE },
            "an already-accepted routine must not be pushed again",
        )
    }

    // ===== codex #856: every follow-up push waits for the shared limiter =====

    @Test
    fun `an LWW re-push still reaches the portal when ordinary pushes filled the window`() = runTest {
        var clock = 0L
        val limiter = ClientRateLimiter(nowMs = { clock }, waitFor = { clock += it })
        val limited = SyncManager(
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
            rateLimiter = limiter,
        )
        insertRoutineSet("set-1", groupId = GROUP, timestamp = baseTime, withLocalData = true)
        limited.sync()
        server.writeWebNote(GROUP, "typed on the website", updatedAt = baseTime + 60 * 60_000L)
        insertRoutineSet("set-2", groupId = GROUP, timestamp = baseTime + 1_000, withLocalData = true)
        // Other pushes fill the rest of the window just before it rolls: the ordinary push
        // waits for only the first sync's slot to expire and takes it, leaving the window full,
        // so the LWW retry must WAIT for capacity instead of failing with a local 429.
        clock = 59_000L
        repeat(SyncConfig.PUSH_RATE_LIMIT_PER_MIN - 1) { limiter.tryAcquire("push", SyncConfig.PUSH_RATE_LIMIT_PER_MIN) }
        apiClient.pushPayloads.clear()

        limited.sync()

        assertTrue(
            apiClient.pushPayloads.drop(1).flatMap { it.sessions }.any { it.id == GROUP },
            "the LWW re-push must reach the portal (saw ${apiClient.pushPayloads.size} push(es))",
        )
        assertEquals(listOf("set-1", "set-2"), server.exerciseIds(GROUP).sorted())
    }

    // ===== codex #856 review 5286893178: the LWW retry acknowledges generations =====

    @Test
    fun `the LWW retry carries a bounded non-empty PR subset when the PR list is large`() = runTest {
        // Self-review (#856): the retry used to resend the FULL personalRecords list; past
        // the portal's 10,000-item cap every retry 400s and the group never lands.
        insertRoutineSet("set-1", groupId = GROUP, timestamp = baseTime, withLocalData = true)
        manager.sync()
        // New PRs land in the same push as the rejected group, so its retry must carry some.
        repeat(SyncManager.PR_FULL_LIST_PER_BATCH + 1) { i ->
            insertLivePr("20000000-0000-4000-8000-" + i.toString().padStart(12, '0'), exerciseId = "bulk-$i")
        }
        server.writeWebNote(GROUP, "typed on the website", updatedAt = baseTime + 60 * 60_000L)
        insertRoutineSet("set-2", groupId = GROUP, timestamp = baseTime + 1_000, withLocalData = true)
        apiClient.pushPayloads.clear()

        manager.sync()

        val retry = apiClient.pushPayloads.drop(1).firstOrNull { payload -> payload.sessions.any { it.id == GROUP } }
        assertNotNull(retry, "precondition: the rejected group is retried")
        assertTrue(retry.personalRecords.isNotEmpty(), "PORTAL ROW-DUPLICATION HAZARD: never empty")
        assertTrue(
            retry.personalRecords.size < SyncManager.PR_FULL_LIST_PER_BATCH + 1,
            "the retry must not resend the full PR list (saw ${retry.personalRecords.size})",
        )
    }

    @Test
    fun `a group accepted by the LWW retry is not pushed again by the next sync`() = runTest {
        insertRoutineSet("set-1", groupId = GROUP, timestamp = baseTime, withLocalData = true)
        manager.sync()
        server.writeWebNote(GROUP, "typed on the website", updatedAt = baseTime + 60 * 60_000L)
        insertRoutineSet("set-2", groupId = GROUP, timestamp = baseTime + 1_000, withLocalData = true)
        apiClient.pushPayloads.clear()
        manager.sync()
        assertTrue(
            apiClient.pushPayloads.drop(1).flatMap { it.sessions }.any { it.id == GROUP },
            "precondition: the first push is rejected and the retry carries the group",
        )

        apiClient.pushPayloads.clear()
        manager.sync()

        assertTrue(
            apiClient.pushPayloads.flatMap { it.sessions }.none { it.id == GROUP },
            "a group the retry landed must be acknowledged, not re-pushed before the next pull",
        )
    }

    @Test
    fun `an edit landing between the gather and the retry ack is still re-sent`() = runTest {
        insertRoutineSet("set-1", groupId = GROUP, timestamp = baseTime, withLocalData = true)
        manager.sync()
        server.writeWebNote(GROUP, "typed on the website", updatedAt = baseTime + 60 * 60_000L)
        insertRoutineSet("set-2", groupId = GROUP, timestamp = baseTime + 1_000, withLocalData = true)
        apiClient.onPush = {
            // A local edit after the gather: bumps the row's generation past the snapshot.
            database.phoenixDatabaseQueries.markWorkoutComponentDirty("set-2")
        }
        manager.sync()

        apiClient.pushPayloads.clear()
        manager.sync()

        assertTrue(
            apiClient.pushPayloads.flatMap { it.sessions }.any { it.id == GROUP },
            "an edit made after the gather must stay dirty and be re-sent",
        )
    }

    @Test
    fun `an unchanged LWW-rejected group is acknowledged and not pushed on every later sync`() = runTest {
        insertRoutineSet("set-1", groupId = GROUP, timestamp = baseTime, withLocalData = true)
        manager.sync()
        server.writeWebNote(GROUP, "typed on the website", updatedAt = baseTime + 60 * 60_000L)
        database.phoenixDatabaseQueries.markWorkoutComponentDirty("set-1")
        manager.sync() // rejected; unchanged content → stamped and acknowledged

        apiClient.pushPayloads.clear()
        manager.sync()

        assertTrue(
            apiClient.pushPayloads.flatMap { it.sessions }.none { it.id == GROUP },
            "an unchanged rejected group must not be re-pushed on every sync",
        )
    }

    @Test
    fun `a PR edited between the gather and the ack keeps its newer stamp and is re-sent`() = runTest {
        insertLivePr(PR_UUID)
        val editTime = com.devil.phoenixproject.domain.model.currentTimeMillis() + 60_000
        val prRowId = database.phoenixDatabaseQueries
            .selectPRsModifiedSince(0L, profileId).executeAsList().single { it.uuid == PR_UUID }.id
        apiClient.onPush = {
            // A local edit that lands while the push is in flight.
            database.phoenixDatabaseQueries.updatePRTimestamp(editTime, listOf(prRowId), Long.MAX_VALUE)
        }

        manager.sync()

        val stamp = database.phoenixDatabaseQueries
            .selectPRsModifiedSince(0L, profileId).executeAsList().single { it.uuid == PR_UUID }.updatedAt
        assertEquals(editTime, stamp, "the ack stamp must not overwrite an edit the portal never saw")

        apiClient.pushPayloads.clear()
        manager.sync()
        assertTrue(
            apiClient.pushPayloads.flatMap { it.personalRecords }.any { it.id == PR_UUID },
            "the edited PR must come back in the next delta",
        )
    }

    private fun insertLivePr(uuid: String, exerciseId: String = "bench") {
        database.phoenixDatabaseQueries.insertRecord(
            exerciseId = exerciseId,
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
            uuid = uuid,
        )
    }

    /** Backup-restored / legacy shape: `updatedAt` NULL. */
    private fun insertUntimestampedRoutine(id: String) {
        database.phoenixDatabaseQueries.insertRoutine(
            id = id,
            name = "Split",
            description = "",
            createdAt = baseTime,
            lastUsed = null,
            useCount = 0L,
            profile_id = profileId,
            groupId = null,
            deletedAt = null,
        )
    }

    // ===== Review fix round: G-2 / S-1 / T-3 =====

    @Test
    fun `an LWW-rejected session whose only edit is in targetReps and rpe is re-sent not stamped as unchanged`() = runTest {
        // Accepted upload records the fingerprint of every field the push sends.
        insertRoutineSet("set-1", groupId = GROUP, timestamp = baseTime, withLocalData = true)
        manager.sync()
        assertStamped("set-1")
        val uploadedHash = tokenStorage.getSessionSentHash("user-1", profileId, GROUP)
        assertNotNull(uploadedHash, "an accepted push must record the content hash")

        // The website then holds a newer copy (LWW rejection armed), and the only local
        // change is in two fields the first fingerprint dropped: targetReps and rpe.
        server.writeWebNote(GROUP, "typed on the website", updatedAt = baseTime + 60 * 60_000L)
        database.phoenixDatabaseQueries.updateSessionTargetRepsAndRpe(10L, 9L, "set-1")
        database.phoenixDatabaseQueries.markWorkoutComponentDirty("set-1")
        apiClient.pushPayloads.clear()

        manager.sync()

        assertTrue(
            apiClient.pushPayloads.size >= 2,
            "an edit that only touches targetReps/rpe must still change the fingerprint and force a re-push " +
                "(payloads=${apiClient.pushPayloads.size})",
        )
        assertTrue(
            apiClient.pushPayloads.drop(1).flatMap { it.sessions }.any { it.id == GROUP },
            "the re-push payload must carry the group",
        )
        val resent = apiClient.pushPayloads.drop(1).flatMap { it.sessions }.last { it.id == GROUP }
        val resentSet = resent.exercises.single { it.id == "set-1" }.sets.single()
        assertEquals(10, resentSet.targetReps, "the re-sent set must carry the edited targetReps")
        assertEquals(9, resentSet.rpe, "the re-sent set must carry the edited rpe")
    }

    @Test
    fun `a sent-hash written for one account never satisfies another account's lookup`() = runTest {
        // Account A uploads and is accepted: its content hash is stored under A's key.
        insertRoutineSet("set-1", groupId = GROUP, timestamp = baseTime, withLocalData = true)
        manager.sync()
        assertStamped("set-1")
        assertNotNull(tokenStorage.getSessionSentHash("user-1", profileId, GROUP))

        // A different account on the same device. Same session id, never accepted here.
        tokenStorage.saveGoTrueAuth(
            GoTrueAuthResponse(
                accessToken = "token-b",
                tokenType = "bearer",
                expiresIn = 3600,
                expiresAt = com.devil.phoenixproject.domain.model.currentTimeMillis() / 1000 + 3600,
                refreshToken = "refresh-b",
                user = GoTrueUser(id = "user-2", email = "b@b.c"),
            ),
        )
        // PR 11 pauses sync on an unanswered account switch. Model a switch the user has
        // already answered without excluding anything, so this test isolates the hash key.
        tokenStorage.setLastSyncedPortalUserId("user-2")

        // The first sync bound the profile to user-1 (codex #856). Model the account-switch
        // choice that moves this profile's data to user-2 (PR 11 relinks it), so user-2 may
        // sync the same session id it has never had accepted.
        userProfileRepository.setActiveProfileForTest(id = profileId, supabaseUserId = "user-2")

        // S-1: the hash is namespaced by userId:profileId, so A's accept cannot satisfy B.
        assertNull(
            tokenStorage.getSessionSentHash("user-2", profileId, GROUP),
            "another account's accept must not satisfy this account's sent-hash lookup",
        )

        // And behaviourally: with no hash to match, the LWW-rejected row is re-sent,
        // never short-circuit-stamped as "unchanged content" (which would mark a
        // never-accepted row under this account as synced).
        server.writeWebNote(GROUP, "typed on the website", updatedAt = baseTime + 60 * 60_000L)
        database.phoenixDatabaseQueries.markWorkoutComponentDirty("set-1")
        apiClient.pushPayloads.clear()

        manager.sync()

        assertTrue(
            apiClient.pushPayloads.size >= 2,
            "a row never accepted under this account must be re-sent, not stamped as unchanged " +
                "(payloads=${apiClient.pushPayloads.size})",
        )
    }

    @Test
    fun `an edit made after the last sync is pushed even when the device clock runs behind the server`() = runTest {
        // Close the one-time repair so repairFrom = pushWatermark (a device clock).
        // Left open, repairFrom would be 0 and every row would match regardless of skew.
        tokenStorage.markRoutineCyclePrRepairPushDone("user-1")
        // The device clock at the last push. The server runs 10 minutes ahead (A-010).
        tokenStorage.setPushWatermark("user-1", profileId, baseTime)
        serverSkewMs = 10 * 60_000L

        // A routine edited AFTER the last sync: device clock +5s (server clock +10min5s).
        // The gather is `updatedAt > pushWatermark`; pushWatermark stays a device clock,
        // so the edit is selected even though it lags the server's now.
        database.phoenixDatabaseQueries.insertRoutineIgnore(
            id = LATE_ROUTINE,
            name = "Late",
            description = "",
            createdAt = baseTime + 5_000,
            lastUsed = null,
            useCount = 0L,
            updatedAt = baseTime + 5_000,
            profile_id = profileId,
            groupId = null,
        )

        manager.sync()

        val pushedRoutines = apiClient.pushPayloads.flatMap { it.routines }.map { it.id }
        assertTrue(
            LATE_ROUTINE in pushedRoutines,
            "an edit made after the last sync must be pushed even when the device clock is 10 min behind " +
                "the server (pushWatermark is a device clock; saw $pushedRoutines)",
        )
    }

    // ===== Step 12: a pulled row is stamped with the push watermark =====

    @Test
    fun `a pulled row is stamped with the push watermark and not re-selected by the next push`() = runTest {
        // A row this device previously pulled (portalOrigin = 1) and already synced clean.
        insertRoutineSet("pulled-row", groupId = null, timestamp = baseTime, stampedAt = baseTime + 1_000)
        database.phoenixDatabaseQueries.markSessionPulled("pulled-row")
        val pushWatermark = baseTime + 50_000
        tokenStorage.setPushWatermark("user-1", profileId, pushWatermark)
        // The server clock is 10 minutes ahead; its updated_at must NOT be written
        // into the local updatedAt column (that is what used to re-select the row).
        val serverTs = baseTime + 10 * 60_000L

        syncRepository.mergePulledSessions(
            sessions = listOf(
                com.devil.phoenixproject.domain.model.WorkoutSession(
                    id = "pulled-row",
                    timestamp = serverTs,
                    mode = "OldSchool",
                    reps = 8,
                    weightPerCableKg = 40f,
                    duration = 45_000L,
                    totalReps = 8,
                    exerciseId = "bench",
                    exerciseName = "Bench Press",
                    routineSessionId = null,
                    profileId = profileId,
                ),
            ),
            updatedAtBySessionId = mapOf("pulled-row" to serverTs),
            pushWatermark = pushWatermark,
        )

        assertEquals(
            pushWatermark,
            stampOf("pulled-row"),
            "the pull must stamp with the device push watermark, not the server clock",
        )
        assertTrue(
            syncRepository.getDirtyWorkoutSnapshot(profileId).sessions.none { it.id == "pulled-row" },
            "a pulled row must not come back in the next push",
        )
    }

    @Test
    fun `a pulled projection never overwrites a local edit the portal has not acknowledged`() = runTest {
        // codex #856: a portal-origin row, synced clean, then edited locally (a tag edit)
        // while a push is in flight. The next pull returns the older portal copy.
        insertRoutineSet("pulled-row", groupId = null, timestamp = baseTime, stampedAt = baseTime + 1_000)
        database.phoenixDatabaseQueries.markSessionPulled("pulled-row")
        database.phoenixDatabaseQueries.updateSessionExerciseTag("row", "Row", baseTime + 2_000, "pulled-row")

        syncRepository.mergePulledSessions(
            sessions = listOf(
                com.devil.phoenixproject.domain.model.WorkoutSession(
                    id = "pulled-row",
                    timestamp = baseTime,
                    mode = "OldSchool",
                    reps = 8,
                    weightPerCableKg = 40f,
                    duration = 45_000L,
                    totalReps = 8,
                    exerciseId = "bench",
                    exerciseName = "Bench Press",
                    routineSessionId = null,
                    profileId = profileId,
                ),
            ),
            updatedAtBySessionId = mapOf("pulled-row" to baseTime + 3_000),
            pushWatermark = baseTime + 1_500,
        )

        val row = database.phoenixDatabaseQueries.selectSessionById("pulled-row").executeAsOne()
        assertEquals("row", row.exerciseId, "the unacknowledged local tag edit must survive the pull")
        assertTrue(
            syncRepository.getDirtyWorkoutSnapshot(profileId).sessions.any { it.id == "pulled-row" },
            "the edit stays dirty so the next push sends it",
        )
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
        stampedAt?.let {
            q.updateSessionTimestamp(it, id)
            // Main's gather keys off local_sync_generation > synced_sync_generation,
            // not updatedAt > lastSync. A prior sync is therefore modelled by raising
            // synced_sync_generation as well as stamping updatedAt.
            q.markSessionSynced(id)
        }
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
        const val PR_UUID = "77777777-7777-4777-8777-777777777777"
        const val ACCEPTED_ROUTINE = "88888888-8888-4888-8888-888888888888"

        /** Canonical UUID: SyncManager strips non-UUID routine ids from every push. */
        const val LATE_ROUTINE = "44444444-4444-4444-8444-444444444444"
    }
}
