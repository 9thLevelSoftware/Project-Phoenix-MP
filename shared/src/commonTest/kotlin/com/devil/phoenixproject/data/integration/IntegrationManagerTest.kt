package com.devil.phoenixproject.data.integration

import com.devil.phoenixproject.data.sync.IntegrationActivityDto
import com.devil.phoenixproject.data.sync.IntegrationSyncResponse
import com.devil.phoenixproject.data.sync.PortalApiException
import com.devil.phoenixproject.domain.model.ConnectionStatus
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.testutil.FakeExternalActivityRepository
import com.devil.phoenixproject.testutil.FakePortalApiClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [IntegrationManager].
 * Uses [FakePortalApiClient] and [FakeExternalActivityRepository] to verify
 * connect/sync/disconnect orchestration without HTTP or database I/O.
 */
class IntegrationManagerTest {

    private val fakeApi = FakePortalApiClient()
    private val fakeRepo = FakeExternalActivityRepository()

    private fun createManager() = IntegrationManager(
        apiClient = fakeApi,
        repository = fakeRepo
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
                        startedAt = "2026-03-20T10:00:00Z"
                    ),
                    IntegrationActivityDto(
                        externalId = "hevy-2",
                        provider = "hevy",
                        name = "Pull Day",
                        startedAt = "2026-03-21T10:00:00Z"
                    )
                )
            )
        )
        val manager = createManager()

        val result = manager.connectProvider(
            provider = IntegrationProvider.HEVY,
            apiKey = "test-api-key",
            profileId = "profile-1",
            isPaidUser = true
        )

        assertTrue(result.isSuccess, "Connect should succeed")
        val activities = result.getOrThrow()
        assertEquals(2, activities.size, "Should return 2 activities")
        assertTrue(activities.all { it.needsSync }, "Paid user activities should have needsSync=true")
        assertTrue(activities.all { it.provider == IntegrationProvider.HEVY }, "Provider should be HEVY")
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
                        startedAt = "2026-03-20T14:00:00Z"
                    )
                )
            )
        )
        val manager = createManager()

        val result = manager.connectProvider(
            provider = IntegrationProvider.LIFTOSAUR,
            apiKey = "free-key",
            profileId = "profile-free",
            isPaidUser = false
        )

        assertTrue(result.isSuccess)
        val activities = result.getOrThrow()
        assertEquals(1, activities.size)
        assertFalse(activities.single().needsSync, "Free user activities should have needsSync=false")

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
                error = "Invalid API key"
            )
        )
        val manager = createManager()

        val result = manager.connectProvider(
            provider = IntegrationProvider.HEVY,
            apiKey = "bad-key",
            profileId = "profile-1",
            isPaidUser = true
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
            PortalApiException("Network timeout", null, 500)
        )
        val manager = createManager()

        val result = manager.connectProvider(
            provider = IntegrationProvider.LIFTOSAUR,
            apiKey = "some-key",
            profileId = "profile-1",
            isPaidUser = true
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
                        calories = 250
                    )
                )
            )
        )
        val manager = createManager()

        val result = manager.syncProvider(
            provider = IntegrationProvider.HEVY,
            profileId = "profile-1",
            isPaidUser = true
        )

        assertTrue(result.isSuccess)
        val activities = result.getOrThrow()
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
                activities = emptyList()
            )
        )
        val manager = createManager()

        val result = manager.syncProvider(
            provider = IntegrationProvider.HEVY,
            profileId = "profile-1",
            isPaidUser = true
        )

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty(), "Should return empty activity list")

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
            needsSync = true
        )
        fakeRepo.activities += com.devil.phoenixproject.domain.model.ExternalActivity(
            externalId = "hevy-2",
            provider = IntegrationProvider.HEVY,
            name = "Pull Day",
            startedAt = 2000L,
            profileId = "profile-1",
            needsSync = false
        )
        // Also add an activity from a different provider that should NOT be deleted
        fakeRepo.activities += com.devil.phoenixproject.domain.model.ExternalActivity(
            externalId = "lift-1",
            provider = IntegrationProvider.LIFTOSAUR,
            name = "Leg Day",
            startedAt = 3000L,
            profileId = "profile-1",
            needsSync = true
        )

        fakeApi.integrationSyncResult = Result.success(
            IntegrationSyncResponse(status = "ok")
        )
        val manager = createManager()

        val result = manager.disconnectProvider(
            provider = IntegrationProvider.HEVY,
            profileId = "profile-1"
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
    fun disconnectProviderSucceedsEvenWhenApiCallFails() = runTest {
        // Add an activity that should be cleaned up regardless of API failure
        fakeRepo.activities += com.devil.phoenixproject.domain.model.ExternalActivity(
            externalId = "hevy-1",
            provider = IntegrationProvider.HEVY,
            name = "Push Day",
            startedAt = 1000L,
            profileId = "profile-1"
        )

        fakeApi.integrationSyncResult = Result.failure(
            PortalApiException("Server down", null, 503)
        )
        val manager = createManager()

        val result = manager.disconnectProvider(
            provider = IntegrationProvider.HEVY,
            profileId = "profile-1"
        )

        // Disconnect should still succeed (portal failure is non-critical)
        assertTrue(result.isSuccess, "Disconnect should succeed even when API call fails")

        // Local cleanup should still happen
        assertTrue(fakeRepo.activities.isEmpty(), "Activities should be deleted despite API failure")
        val statusUpdate = fakeRepo.statusUpdates.last()
        assertEquals(ConnectionStatus.DISCONNECTED, statusUpdate.status)
    }

    // ===== connectProvider — empty API key =====

    @Test
    fun connectProviderWithEmptyApiKeyPassesEmptyStringToApi() = runTest {
        // The IntegrationManager does not validate the API key itself -- it delegates
        // to the portal. Simulate the portal rejecting the empty key.
        fakeApi.integrationSyncResult = Result.success(
            IntegrationSyncResponse(
                status = "error",
                error = "API key is required"
            )
        )
        val manager = createManager()

        val result = manager.connectProvider(
            provider = IntegrationProvider.HEVY,
            apiKey = "",
            profileId = "profile-1",
            isPaidUser = true
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
                        rawData = """{"source":"hevy"}"""
                    )
                )
            )
        )
        val manager = createManager()

        val result = manager.connectProvider(
            provider = IntegrationProvider.HEVY,
            apiKey = "key",
            profileId = "profile-1",
            isPaidUser = false
        )

        assertTrue(result.isSuccess)
        val activity = result.getOrThrow().single()
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
                error = "Token expired"
            )
        )
        val manager = createManager()

        val result = manager.syncProvider(
            provider = IntegrationProvider.LIFTOSAUR,
            profileId = "profile-1",
            isPaidUser = true
        )

        assertTrue(result.isFailure)
        assertEquals("Token expired", result.exceptionOrNull()?.message)

        val statusUpdate = fakeRepo.statusUpdates.last()
        assertEquals(ConnectionStatus.ERROR, statusUpdate.status)
        assertEquals("Token expired", statusUpdate.errorMessage)
        assertEquals(IntegrationProvider.LIFTOSAUR, statusUpdate.provider)
    }
}
