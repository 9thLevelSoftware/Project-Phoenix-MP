package com.devil.phoenixproject.data.integration

import com.devil.phoenixproject.data.sync.IntegrationActivityDto
import com.devil.phoenixproject.data.sync.IntegrationBodyMeasurementDto
import com.devil.phoenixproject.data.sync.IntegrationExerciseTemplateDto
import com.devil.phoenixproject.data.sync.IntegrationProgramDto
import com.devil.phoenixproject.data.sync.IntegrationProgramStatsDto
import com.devil.phoenixproject.data.sync.IntegrationRoutineDto
import com.devil.phoenixproject.data.sync.IntegrationRoutineExerciseDto
import com.devil.phoenixproject.data.sync.IntegrationRoutineFolderDto
import com.devil.phoenixproject.data.sync.IntegrationRoutineSetDto
import com.devil.phoenixproject.data.sync.IntegrationSyncResponse
import com.devil.phoenixproject.data.sync.PortalApiException
import com.devil.phoenixproject.domain.model.ConnectionStatus
import com.devil.phoenixproject.domain.model.ExternalActivity
import com.devil.phoenixproject.domain.model.ExternalBodyMeasurement
import com.devil.phoenixproject.domain.model.ExternalExerciseTemplate
import com.devil.phoenixproject.domain.model.ExternalProgram
import com.devil.phoenixproject.domain.model.ExternalRoutine
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.testutil.FakeExternalActivityRepository
import com.devil.phoenixproject.testutil.FakeExternalExerciseTemplateRepository
import com.devil.phoenixproject.testutil.FakeExternalMeasurementRepository
import com.devil.phoenixproject.testutil.FakeExternalProgramRepository
import com.devil.phoenixproject.testutil.FakeExternalRoutineRepository
import com.devil.phoenixproject.testutil.FakeIntegrationSyncCursorRepository
import com.devil.phoenixproject.testutil.FakePortalApiClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [IntegrationManager].
 * Uses [FakePortalApiClient] and [FakeExternalActivityRepository] to verify
 * connect/sync/disconnect orchestration without HTTP or database I/O.
 */
class IntegrationManagerTest {

    private val fakeApi = FakePortalApiClient()
    private val fakeRepo = FakeExternalActivityRepository()
    private val fakeRoutineRepo = FakeExternalRoutineRepository()
    private val fakeProgramRepo = FakeExternalProgramRepository()
    private val fakeMeasurementRepo = FakeExternalMeasurementRepository()
    private val fakeTemplateRepo = FakeExternalExerciseTemplateRepository()
    private val fakeCursorRepo = FakeIntegrationSyncCursorRepository()

    private fun createManager() = IntegrationManager(
        apiClient = fakeApi,
        activityRepository = fakeRepo,
        routineRepository = fakeRoutineRepo,
        programRepository = fakeProgramRepo,
        measurementRepository = fakeMeasurementRepo,
        templateRepository = fakeTemplateRepo,
        cursorRepository = fakeCursorRepo,
    )

    // ===== connectProvider — happy path (paid user) =====

    @Test
    fun connectPaidUserStoresActivitiesWithNeedsSyncTrue() = runTest {
        fakeApi.integrationSyncResult = Result.success(
            IntegrationSyncResponse(
                status = "ok",
                activities = listOf(
                    IntegrationActivityDto(
                        externalId = "hevy-1",
                        provider = "hevy",
                        name = "Push Day",
                        startedAt = "2026-03-20T10:00:00Z",
                    ),
                    IntegrationActivityDto(
                        externalId = "hevy-2",
                        provider = "hevy",
                        name = "Pull Day",
                        startedAt = "2026-03-21T10:00:00Z",
                    ),
                ),
            ),
        )
        val manager = createManager()

        val result = manager.connectProvider(
            provider = IntegrationProvider.HEVY,
            apiKey = "test-api-key",
            profileId = "profile-1",
            isPaidUser = true,
        )

        assertTrue(result.isSuccess, "Connect should succeed")
        val syncResult = result.getOrThrow()
        val activities = fakeRepo.activities
        assertEquals(2, syncResult.progress.activitiesImported, "Should return 2 imported activities")
        assertEquals(2, activities.size, "Should store 2 activities")
        assertTrue(
            activities.all {
                it.needsSync
            },
            "Paid user activities should have needsSync=true",
        )
        assertTrue(
            activities.all {
                it.provider == IntegrationProvider.HEVY
            },
            "Provider should be HEVY",
        )
        assertTrue(activities.all { it.profileId == "profile-1" }, "ProfileId should match")

        // Verify activities were upserted to the repository
        assertEquals(1, fakeRepo.upsertCallCount, "upsertActivities should be called once")
        assertEquals(2, fakeRepo.activities.size, "Repository should contain 2 activities")

        // Verify integration status was set to CONNECTED
        val statusUpdate = fakeRepo.statusUpdates.last()
        assertEquals(IntegrationProvider.HEVY, statusUpdate.provider)
        assertEquals(ConnectionStatus.CONNECTED, statusUpdate.status)
        assertEquals("profile-1", statusUpdate.profileId)
        assertNotNull(statusUpdate.lastSyncAt, "lastSyncAt should be set on connect")

        // Verify the API was called with correct request
        assertEquals(1, fakeApi.integrationSyncCallCount)
        val request = fakeApi.lastIntegrationSyncRequest
        assertNotNull(request)
        assertEquals("hevy", request.provider)
        assertEquals("connect", request.action)
        assertEquals("test-api-key", request.apiKey)
    }

    // ===== connectProvider — free user =====

    @Test
    fun connectFreeUserStoresActivitiesWithNeedsSyncFalse() = runTest {
        fakeApi.integrationSyncResult = Result.success(
            IntegrationSyncResponse(
                status = "ok",
                activities = listOf(
                    IntegrationActivityDto(
                        externalId = "lift-1",
                        provider = "liftosaur",
                        name = "Leg Day",
                        startedAt = "2026-03-20T14:00:00Z",
                    ),
                ),
            ),
        )
        val manager = createManager()

        val result = manager.connectProvider(
            provider = IntegrationProvider.LIFTOSAUR,
            apiKey = "free-key",
            profileId = "profile-free",
            isPaidUser = false,
        )

        assertTrue(result.isSuccess)
        val activities = fakeRepo.activities
        assertEquals(1, activities.size)
        assertFalse(
            activities.single().needsSync,
            "Free user activities should have needsSync=false",
        )

        // Status should still be CONNECTED
        val statusUpdate = fakeRepo.statusUpdates.last()
        assertEquals(ConnectionStatus.CONNECTED, statusUpdate.status)
    }

    // ===== connectProvider — API returns error response =====

    @Test
    fun connectProviderApiErrorSetsStatusToError() = runTest {
        fakeApi.integrationSyncResult = Result.success(
            IntegrationSyncResponse(
                status = "error",
                error = "Invalid API key",
            ),
        )
        val manager = createManager()

        val result = manager.connectProvider(
            provider = IntegrationProvider.HEVY,
            apiKey = "bad-key",
            profileId = "profile-1",
            isPaidUser = true,
        )

        assertTrue(result.isFailure, "Connect should fail when API returns error status")
        assertEquals("Invalid API key", result.exceptionOrNull()?.message)

        // Status should be ERROR with message
        val statusUpdate = fakeRepo.statusUpdates.last()
        assertEquals(ConnectionStatus.ERROR, statusUpdate.status)
        assertEquals("Invalid API key", statusUpdate.errorMessage)
        assertEquals(IntegrationProvider.HEVY, statusUpdate.provider)

        // No activities should be stored
        assertEquals(0, fakeRepo.upsertCallCount, "No upsert when API returns error")
        assertTrue(fakeRepo.activities.isEmpty())
    }

    // ===== connectProvider — network/transport failure =====

    @Test
    fun connectProviderNetworkFailureSetsStatusToError() = runTest {
        fakeApi.integrationSyncResult = Result.failure(
            PortalApiException("Network timeout", null, 500),
        )
        val manager = createManager()

        val result = manager.connectProvider(
            provider = IntegrationProvider.LIFTOSAUR,
            apiKey = "some-key",
            profileId = "profile-1",
            isPaidUser = true,
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Network timeout") == true)

        val statusUpdate = fakeRepo.statusUpdates.last()
        assertEquals(ConnectionStatus.ERROR, statusUpdate.status)
        assertEquals("Network timeout", statusUpdate.errorMessage)
        assertEquals(0, fakeRepo.upsertCallCount)
    }

    // ===== syncProvider — happy path =====

    @Test
    fun syncProviderFetchesAndUpsertsActivities() = runTest {
        fakeApi.integrationSyncResult = Result.success(
            IntegrationSyncResponse(
                status = "ok",
                activities = listOf(
                    IntegrationActivityDto(
                        externalId = "hevy-3",
                        provider = "hevy",
                        name = "Shoulder Press",
                        startedAt = "2026-03-22T09:00:00Z",
                        durationSeconds = 3600,
                        calories = 250,
                    ),
                ),
            ),
        )
        val manager = createManager()

        val result = manager.syncProvider(
            provider = IntegrationProvider.HEVY,
            profileId = "profile-1",
            isPaidUser = true,
        )

        assertTrue(result.isSuccess)
        val syncResult = result.getOrThrow()
        val activities = fakeRepo.activities
        assertEquals(1, syncResult.progress.activitiesImported)
        assertEquals(1, activities.size)
        assertEquals("hevy-3", activities.single().externalId)
        assertEquals("Shoulder Press", activities.single().name)
        assertEquals(3600, activities.single().durationSeconds)
        assertEquals(250, activities.single().calories)
        assertTrue(activities.single().needsSync, "Paid user sync activities should need sync")

        assertEquals(1, fakeRepo.upsertCallCount)
        assertEquals(1, fakeRepo.activities.size)

        // Verify status updated to CONNECTED
        val statusUpdate = fakeRepo.statusUpdates.last()
        assertEquals(ConnectionStatus.CONNECTED, statusUpdate.status)
        assertNotNull(statusUpdate.lastSyncAt)

        // Verify the request used "sync" action with no apiKey
        val request = fakeApi.lastIntegrationSyncRequest
        assertNotNull(request)
        assertEquals("sync", request.action)
        assertEquals("hevy", request.provider)
        assertEquals(null, request.apiKey, "Sync action should not send apiKey")
    }

    // ===== syncProvider — empty response =====

    @Test
    fun syncProviderEmptyResponseUpdatesStatusWithoutUpsert() = runTest {
        fakeApi.integrationSyncResult = Result.success(
            IntegrationSyncResponse(
                status = "ok",
                activities = emptyList(),
            ),
        )
        val manager = createManager()

        val result = manager.syncProvider(
            provider = IntegrationProvider.HEVY,
            profileId = "profile-1",
            isPaidUser = true,
        )

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow().progress.activitiesImported, "Should return empty activity count")

        // No upsert call when activities list is empty
        assertEquals(0, fakeRepo.upsertCallCount, "Should not call upsert for empty activities")

        // Status should still be updated to CONNECTED
        val statusUpdate = fakeRepo.statusUpdates.last()
        assertEquals(ConnectionStatus.CONNECTED, statusUpdate.status)
        assertNotNull(statusUpdate.lastSyncAt)
    }

    // ===== disconnectProvider =====

    @Test
    fun disconnectProviderRemovesActivitiesAndClearsStatus() = runTest {
        // Pre-populate some activities from a previous connect
        fakeRepo.activities += com.devil.phoenixproject.domain.model.ExternalActivity(
            externalId = "hevy-1",
            provider = IntegrationProvider.HEVY,
            name = "Push Day",
            startedAt = 1000L,
            profileId = "profile-1",
            needsSync = true,
        )
        fakeRepo.activities += com.devil.phoenixproject.domain.model.ExternalActivity(
            externalId = "hevy-2",
            provider = IntegrationProvider.HEVY,
            name = "Pull Day",
            startedAt = 2000L,
            profileId = "profile-1",
            needsSync = false,
        )
        // Also add an activity from a different provider that should NOT be deleted
        fakeRepo.activities += com.devil.phoenixproject.domain.model.ExternalActivity(
            externalId = "lift-1",
            provider = IntegrationProvider.LIFTOSAUR,
            name = "Leg Day",
            startedAt = 3000L,
            profileId = "profile-1",
            needsSync = true,
        )

        fakeApi.integrationSyncResult = Result.success(
            IntegrationSyncResponse(status = "ok"),
        )
        val manager = createManager()

        val result = manager.disconnectProvider(
            provider = IntegrationProvider.HEVY,
            profileId = "profile-1",
        )

        assertTrue(result.isSuccess)

        // HEVY activities should be removed
        assertEquals(1, fakeRepo.activities.size, "Only Liftosaur activity should remain")
        assertEquals(IntegrationProvider.LIFTOSAUR, fakeRepo.activities.single().provider)

        // Status should be DISCONNECTED
        val statusUpdate = fakeRepo.statusUpdates.last()
        assertEquals(ConnectionStatus.DISCONNECTED, statusUpdate.status)
        assertEquals(IntegrationProvider.HEVY, statusUpdate.provider)
        assertEquals("profile-1", statusUpdate.profileId)

        // Verify API was called with disconnect action
        val request = fakeApi.lastIntegrationSyncRequest
        assertNotNull(request)
        assertEquals("disconnect", request.action)
    }

    @Test
    fun disconnectProviderFailsClosedAndPreservesLocalStateWhenApiCallFails() = runTest {
        fakeRepo.activities += ExternalActivity(
            externalId = "hevy-1",
            provider = IntegrationProvider.HEVY,
            name = "Push Day",
            startedAt = 1000L,
            profileId = "profile-1",
        )
        fakeRoutineRepo.upsertRoutines(
            listOf(
                ExternalRoutine(
                    externalId = "r1",
                    provider = IntegrationProvider.HEVY,
                    title = "Upper",
                    profileId = "profile-1",
                ),
            ),
        )
        fakeProgramRepo.upsertPrograms(
            listOf(
                ExternalProgram(
                    externalId = "p1",
                    provider = IntegrationProvider.HEVY,
                    name = "Prog",
                    profileId = "profile-1",
                ),
            ),
        )
        fakeMeasurementRepo.upsertMeasurements(
            listOf(
                ExternalBodyMeasurement(
                    externalId = "m1",
                    provider = IntegrationProvider.HEVY,
                    measurementType = "weight",
                    value = 80.0,
                    unit = "kg",
                    measuredAt = 1000L,
                    profileId = "profile-1",
                ),
            ),
        )
        fakeTemplateRepo.upsertTemplates(
            listOf(
                ExternalExerciseTemplate(
                    externalId = "t1",
                    provider = IntegrationProvider.HEVY,
                    title = "Bench",
                    profileId = "profile-1",
                ),
            ),
        )
        fakeCursorRepo.upsertCursor(
            IntegrationSyncCursor(
                provider = IntegrationProvider.HEVY,
                profileId = "profile-1",
                cursorType = "integration_sync",
                cursorValue = "cursor-1",
                updatedAt = 1000L,
            ),
        )

        fakeApi.integrationSyncResult = Result.failure(
            PortalApiException("Server down", null, 503),
        )
        val manager = createManager()

        val result = manager.disconnectProvider(
            provider = IntegrationProvider.HEVY,
            profileId = "profile-1",
        )

        assertTrue(result.isFailure, "Disconnect must fail closed when API call fails")
        assertEquals(503, (result.exceptionOrNull() as? PortalApiException)?.statusCode)
        assertEquals(1, fakeRepo.activities.size, "Activities must be preserved on failed disconnect")
        assertEquals(1, fakeRoutineRepo.routines.size, "Routines must be preserved on failed disconnect")
        assertEquals(1, fakeProgramRepo.programs.size, "Programs must be preserved on failed disconnect")
        assertEquals(1, fakeMeasurementRepo.measurements.size, "Measurements must be preserved on failed disconnect")
        assertEquals(1, fakeTemplateRepo.templates.size, "Templates must be preserved on failed disconnect")
        assertEquals(1, fakeCursorRepo.cursors.size, "Cursors must be preserved on failed disconnect")
        assertTrue(
            fakeRepo.statusUpdates.none { it.status == ConnectionStatus.DISCONNECTED },
            "Status must not transition to DISCONNECTED on failed disconnect",
        )
    }

    @Test
    fun disconnectProviderFailsClosedWhenPortalReturnsErrorStatus() = runTest {
        fakeRepo.activities += ExternalActivity(
            externalId = "lift-1",
            provider = IntegrationProvider.LIFTOSAUR,
            name = "Workout",
            startedAt = 1000L,
            profileId = "profile-1",
        )
        fakeApi.integrationSyncResult = Result.success(
            IntegrationSyncResponse(status = "error", error = "Disconnect rejected"),
        )
        val manager = createManager()

        val result = manager.disconnectProvider(IntegrationProvider.LIFTOSAUR, "profile-1")

        assertTrue(result.isFailure)
        assertEquals("Disconnect rejected", result.exceptionOrNull()?.message)
        assertEquals(1, fakeRepo.activities.size)
        assertTrue(fakeRepo.statusUpdates.none { it.status == ConnectionStatus.DISCONNECTED })
    }

    // ===== connectProvider — empty API key =====

    @Test
    fun connectProviderWithEmptyApiKeyPassesEmptyStringToApi() = runTest {
        // The IntegrationManager does not validate the API key itself -- it delegates
        // to the portal. Simulate the portal rejecting the empty key.
        fakeApi.integrationSyncResult = Result.success(
            IntegrationSyncResponse(
                status = "error",
                error = "API key is required",
            ),
        )
        val manager = createManager()

        val result = manager.connectProvider(
            provider = IntegrationProvider.HEVY,
            apiKey = "",
            profileId = "profile-1",
            isPaidUser = true,
        )

        assertTrue(result.isFailure, "Connect with empty API key should fail")
        assertEquals("API key is required", result.exceptionOrNull()?.message)

        val statusUpdate = fakeRepo.statusUpdates.last()
        assertEquals(ConnectionStatus.ERROR, statusUpdate.status)
        assertEquals("API key is required", statusUpdate.errorMessage)

        // Verify the empty key was passed through to the API
        assertEquals("", fakeApi.lastIntegrationSyncRequest?.apiKey)
    }

    // ===== DTO-to-domain mapping edge cases =====

    @Test
    fun connectProviderMapsAllDtoFieldsToDomain() = runTest {
        fakeApi.integrationSyncResult = Result.success(
            IntegrationSyncResponse(
                status = "ok",
                activities = listOf(
                    IntegrationActivityDto(
                        externalId = "full-1",
                        provider = "hevy",
                        name = "Full Activity",
                        activityType = "cardio",
                        startedAt = "2026-03-20T10:30:00Z",
                        durationSeconds = 1800,
                        distanceMeters = 5000.0,
                        calories = 400,
                        avgHeartRate = 145,
                        maxHeartRate = 175,
                        elevationGainMeters = 50.0,
                        rawData = """{"source":"hevy"}""",
                    ),
                ),
            ),
        )
        val manager = createManager()

        val result = manager.connectProvider(
            provider = IntegrationProvider.HEVY,
            apiKey = "key",
            profileId = "profile-1",
            isPaidUser = false,
        )

        assertTrue(result.isSuccess)
        val activity = fakeRepo.activities.single()
        assertEquals("full-1", activity.externalId)
        assertEquals(IntegrationProvider.HEVY, activity.provider)
        assertEquals("Full Activity", activity.name)
        assertEquals("cardio", activity.activityType)
        assertEquals(1800, activity.durationSeconds)
        assertEquals(5000.0, activity.distanceMeters)
        assertEquals(400, activity.calories)
        assertEquals(145, activity.avgHeartRate)
        assertEquals(175, activity.maxHeartRate)
        assertEquals(50.0, activity.elevationGainMeters)
        assertEquals("""{"source":"hevy"}""", activity.rawData)
        assertEquals("profile-1", activity.profileId)
        assertFalse(activity.needsSync, "Free user should have needsSync=false")
    }

    @Test
    fun syncProviderErrorResponseSetsStatusToError() = runTest {
        fakeApi.integrationSyncResult = Result.success(
            IntegrationSyncResponse(
                status = "error",
                error = "Token expired",
            ),
        )
        val manager = createManager()

        val result = manager.syncProvider(
            provider = IntegrationProvider.LIFTOSAUR,
            profileId = "profile-1",
            isPaidUser = true,
        )

        assertTrue(result.isFailure)
        assertEquals("Token expired", result.exceptionOrNull()?.message)

        val statusUpdate = fakeRepo.statusUpdates.last()
        assertEquals(ConnectionStatus.ERROR, statusUpdate.status)
        assertEquals("Token expired", statusUpdate.errorMessage)
        assertEquals(IntegrationProvider.LIFTOSAUR, statusUpdate.provider)
    }

    @Test
    fun connectHevyStoresExpandedEntities() = runTest {
        fakeApi.integrationSyncResult = Result.success(
            IntegrationSyncResponse(
                status = "ok",
                routines = listOf(
                    IntegrationRoutineDto(
                        externalId = "routine-1",
                        provider = "hevy",
                        title = "Upper",
                        folderExternalId = "folder-1",
                        folderName = "Main",
                        exercises = listOf(
                            IntegrationRoutineExerciseDto(
                                externalExerciseTemplateId = "template-1",
                                title = "Bench",
                                primaryMuscleGroups = listOf("chest"),
                                sets = listOf(IntegrationRoutineSetDto(index = 0, reps = 8, weightKg = 80.0)),
                            ),
                        ),
                    ),
                ),
                routineFolders = listOf(
                    IntegrationRoutineFolderDto("folder-1", "hevy", "Main"),
                ),
                exerciseTemplates = listOf(
                    IntegrationExerciseTemplateDto("template-1", "hevy", "Bench"),
                ),
                bodyMeasurements = listOf(
                    IntegrationBodyMeasurementDto(
                        externalId = "measurement-1",
                        provider = "hevy",
                        measurementType = "weight",
                        value = 82.5,
                        unit = "kg",
                        measuredAt = "2026-03-20T10:00:00Z",
                    ),
                ),
            ),
        )
        val manager = createManager()

        val result = manager.connectProvider(IntegrationProvider.HEVY, "key", "profile-1", true).getOrThrow()

        assertEquals(1, result.progress.routinesImported)
        assertEquals(1, result.progress.routineFoldersImported)
        assertEquals(1, result.progress.exerciseTemplatesImported)
        assertEquals(1, result.progress.measurementsImported)
        assertEquals(1, fakeRoutineRepo.routines.size)
        assertEquals(1, fakeRoutineRepo.folders.size)
        assertEquals(1, fakeTemplateRepo.templates.size)
        assertEquals(1, fakeMeasurementRepo.measurements.size)
        assertEquals("profile-1", fakeRoutineRepo.routines.single().profileId)
    }

    @Test
    fun connectLiftosaurStoresProgramsAndStats() = runTest {
        fakeApi.integrationSyncResult = Result.success(
            IntegrationSyncResponse(
                status = "ok",
                programs = listOf(
                    IntegrationProgramDto(
                        externalId = "program-1",
                        provider = "liftosaur",
                        name = "Linear Progression",
                        isCurrent = true,
                    ),
                ),
                programStats = listOf(
                    IntegrationProgramStatsDto(
                        externalProgramId = "program-1",
                        days = 4,
                        approximateMinutes = 60,
                        setCount = 24,
                    ),
                ),
            ),
        )
        val manager = createManager()

        val result = manager.connectProvider(IntegrationProvider.LIFTOSAUR, "key", "profile-1", true).getOrThrow()

        assertEquals(1, result.progress.programsImported)
        assertEquals(1, result.progress.programStatsImported)
        assertEquals(1, fakeProgramRepo.programs.size)
        assertEquals(1, fakeProgramRepo.stats.size)
        assertEquals(fakeProgramRepo.programs.single().id, fakeProgramRepo.stats.single().externalProgramId)
    }

    @Test
    fun requiresUpgradeResponseKeepsProviderConnected() = runTest {
        fakeApi.integrationSyncResult = Result.success(
            IntegrationSyncResponse(
                status = "error",
                requiresUpgrade = true,
                upgradeReason = "Program stats require Premium",
                entitlementStatus = "requires_upgrade",
            ),
        )
        val manager = createManager()

        val result = manager.syncProvider(IntegrationProvider.LIFTOSAUR, "profile-1", true)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().entitlementState?.requiresUpgrade == true)
        val statusUpdate = fakeRepo.statusUpdates.last()
        assertEquals(ConnectionStatus.CONNECTED, statusUpdate.status)
    }

    @Test
    fun paginationLoopsUntilHasMoreFalse() = runTest {
        fakeApi.integrationSyncResultsQueue = mutableListOf(
            Result.success(
                IntegrationSyncResponse(
                    status = "ok",
                    activities = listOf(
                        IntegrationActivityDto("a1", "hevy", "First", startedAt = "2026-03-20T10:00:00Z"),
                    ),
                    hasMore = true,
                    nextCursor = "page-2",
                ),
            ),
            Result.success(
                IntegrationSyncResponse(
                    status = "ok",
                    activities = listOf(
                        IntegrationActivityDto("a2", "hevy", "Second", startedAt = "2026-03-21T10:00:00Z"),
                    ),
                    hasMore = false,
                ),
            ),
        )
        val manager = createManager()

        val result = manager.syncProvider(IntegrationProvider.HEVY, "profile-1", true).getOrThrow()

        assertEquals(2, fakeApi.integrationSyncCallCount)
        assertEquals(2, result.progress.activitiesImported)
        assertEquals(2, fakeRepo.activities.size)
        assertEquals(emptyList(), fakeCursorRepo.cursors, "Transient page cursors must not be stored as durable sync cursors")
    }

    @Test
    fun syncPersistsProviderSyncCursorWithoutPersistingPageCursor() = runTest {
        fakeApi.integrationSyncResultsQueue = mutableListOf(
            Result.success(
                IntegrationSyncResponse(
                    status = "ok",
                    activities = listOf(
                        IntegrationActivityDto("a1", "hevy", "First", startedAt = "2026-03-20T10:00:00Z"),
                    ),
                    hasMore = true,
                    nextCursor = "page-2",
                    providerSyncCursor = "provider-checkpoint-1",
                ),
            ),
            Result.success(
                IntegrationSyncResponse(
                    status = "ok",
                    activities = listOf(
                        IntegrationActivityDto("a2", "hevy", "Second", startedAt = "2026-03-21T10:00:00Z"),
                    ),
                    hasMore = false,
                    providerSyncCursor = "provider-checkpoint-2",
                ),
            ),
        )
        val manager = createManager()

        manager.syncProvider(IntegrationProvider.HEVY, "profile-1", true).getOrThrow()

        assertEquals(2, fakeApi.integrationSyncCallCount)
        assertEquals("page-2", fakeApi.lastIntegrationSyncRequest?.cursor, "Pagination should still use nextCursor within the loop")
        assertEquals("provider-checkpoint-2", fakeCursorRepo.cursors.single().cursorValue)
    }

    @Test
    fun connectProviderContinuesWhenLiftosaurReturnsTruncatedContinuingThenCompletes() = runTest {
        val requests = mutableListOf<com.devil.phoenixproject.data.sync.IntegrationSyncRequest>()
        val recordingApi = object : FakePortalApiClient() {
            override suspend fun callIntegrationSync(
                request: com.devil.phoenixproject.data.sync.IntegrationSyncRequest,
            ): Result<IntegrationSyncResponse> {
                requests += request
                return super.callIntegrationSync(request)
            }
        }
        recordingApi.integrationSyncResultsQueue = mutableListOf(
            Result.success(
                IntegrationSyncResponse(
                    status = "connected",
                    activities = listOf(
                        IntegrationActivityDto("l1", "liftosaur", "Day 1", startedAt = "2026-03-20T10:00:00Z"),
                    ),
                    truncated = true,
                    continuing = true,
                    message = "Importing Liftosaur history: 1 records stored this run; older records are imported by the next run.",
                ),
            ),
            Result.success(
                IntegrationSyncResponse(
                    status = "synced",
                    activities = listOf(
                        IntegrationActivityDto("l2", "liftosaur", "Day 2", startedAt = "2026-03-19T10:00:00Z"),
                    ),
                    truncated = false,
                    continuing = false,
                ),
            ),
        )
        val manager = IntegrationManager(
            apiClient = recordingApi,
            activityRepository = fakeRepo,
            routineRepository = fakeRoutineRepo,
            programRepository = fakeProgramRepo,
            measurementRepository = fakeMeasurementRepo,
            templateRepository = fakeTemplateRepo,
            cursorRepository = fakeCursorRepo,
        )

        val result = manager.connectProvider(IntegrationProvider.LIFTOSAUR, "lift-key", "profile-1", true).getOrThrow()

        assertEquals(2, requests.size)
        assertEquals("connect", requests[0].action)
        assertEquals("lift-key", requests[0].apiKey)
        assertEquals("sync", requests[1].action, "Continuation call must use sync so portal does not reset backfill cursor")
        assertEquals(null, requests[1].apiKey)
        assertEquals(2, result.progress.activitiesImported)
        assertFalse(result.partial)
        assertTrue(result.warnings.isEmpty())
        assertEquals(null, fakeRepo.statusUpdates.last().errorMessage)
    }

    @Test
    fun syncProviderCapsContinuationLoopAndPreservesTruncatedWarning() = runTest {
        fakeApi.integrationSyncResult = Result.success(
            IntegrationSyncResponse(
                status = "synced",
                activities = listOf(
                    IntegrationActivityDto("l1", "liftosaur", "Day 1", startedAt = "2026-03-20T10:00:00Z"),
                ),
                truncated = true,
                continuing = true,
                message = "Importing Liftosaur history; older records are imported by the next run.",
            ),
        )
        val manager = createManager()

        val result = manager.syncProvider(IntegrationProvider.LIFTOSAUR, "profile-1", true).getOrThrow()

        assertEquals(50, fakeApi.integrationSyncCallCount)
        assertTrue(result.partial)
        assertTrue(result.warnings.any { it.message.contains("Importing Liftosaur history") })
        assertTrue(result.warnings.any { it.code == "page_cap_reached" })
        val statusUpdate = fakeRepo.statusUpdates.last()
        assertEquals(ConnectionStatus.CONNECTED, statusUpdate.status)
        assertNotNull(statusUpdate.errorMessage)
        assertTrue(statusUpdate.errorMessage!!.contains("Importing Liftosaur history"))
    }

    @Test
    fun syncProviderTruncatedWithResumeAtStopsAndMarksPartialWithoutClearingStatusError() = runTest {
        fakeApi.integrationSyncResult = Result.success(
            IntegrationSyncResponse(
                status = "synced",
                activities = listOf(
                    IntegrationActivityDto("l1", "liftosaur", "Day 1", startedAt = "2026-03-20T10:00:00Z"),
                ),
                truncated = true,
                continuing = false,
                resumeAt = "2026-11-27T02:00:00.000Z",
                message = "Liftosaur history is larger than one run can read; resuming from 2026-11-27T02:00:00.000Z",
            ),
        )
        val manager = createManager()

        val result = manager.syncProvider(IntegrationProvider.LIFTOSAUR, "profile-1", true).getOrThrow()

        assertEquals(1, fakeApi.integrationSyncCallCount)
        assertTrue(result.partial)
        val warning = result.warnings.single()
        assertEquals("rate_limited", warning.code)
        assertTrue(warning.message.contains("2026-11-27T02:00:00.000Z"))
        val statusUpdate = fakeRepo.statusUpdates.last()
        assertEquals(ConnectionStatus.CONNECTED, statusUpdate.status)
        assertEquals(warning.message, statusUpdate.errorMessage)
    }

    @Test
    fun syncRequestsActivitiesOnlyAndPlaygroundIsGatedOffByDefault() = runTest {
        fakeApi.integrationSyncResult = Result.success(IntegrationSyncResponse(status = "ok"))
        val manager = createManager()

        manager.syncProvider(IntegrationProvider.HEVY, "profile-1", true).getOrThrow()
        val playgroundResult = manager.simulatePlayground(
            provider = IntegrationProvider.LIFTOSAUR,
            externalProgramId = "prog-1",
            scriptText = "Squat / 3x5",
            commands = emptyList(),
            profileId = "profile-1",
        )

        assertEquals(listOf("activities"), fakeApi.lastIntegrationSyncRequest?.entityTypes)
        assertTrue(playgroundResult.isFailure)
        assertEquals(0, fakeApi.playgroundSimulationCallCount)
    }
}
