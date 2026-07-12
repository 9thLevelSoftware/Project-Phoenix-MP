package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.sync.GoTrueAuthResponse
import com.devil.phoenixproject.data.sync.IntegrationPlaygroundSimulationRequest
import com.devil.phoenixproject.data.sync.IntegrationPlaygroundSimulationResponse
import com.devil.phoenixproject.data.sync.IntegrationSyncRequest
import com.devil.phoenixproject.data.sync.IntegrationSyncResponse
import com.devil.phoenixproject.data.sync.KnownEntityIds
import com.devil.phoenixproject.data.sync.PortalApiClient
import com.devil.phoenixproject.data.sync.PortalApiException
import com.devil.phoenixproject.data.sync.PortalSyncPayload
import com.devil.phoenixproject.data.sync.PortalSyncPullResponse
import com.devil.phoenixproject.data.sync.PortalSyncPushResponse
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.devil.phoenixproject.data.sync.SupabaseConfig
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.russhwolf.settings.MapSettings

/**
 * Fake PortalApiClient for testing SyncManager without HTTP.
 * Extends the open PortalApiClient with dummy config; overrides all 4 methods
 * used by SyncManager. Provides configurable Result returns and call counters.
 */
open class FakePortalApiClient :
    PortalApiClient(
        supabaseConfig = SupabaseConfig(url = "https://fake.supabase.co", anonKey = "fake-anon-key"),
        tokenStorage = PortalTokenStorage(MapSettings()),
    ) {
    // Configurable results
    var pushResult: Result<PortalSyncPushResponse> = Result.success(
        PortalSyncPushResponse(
            syncTime = "2026-03-02T12:00:00Z",
            sessionsInserted = 0,
            exercisesInserted = 0,
            setsInserted = 0,
            repSummariesInserted = 0,
            routinesUpserted = 0,
            badgesUpserted = 0,
            exerciseProgressInserted = 0,
            personalRecordsInserted = 0,
        ),
    )

    var pullResult: Result<PortalSyncPullResponse> = Result.success(
        PortalSyncPullResponse(
            syncTime = 1740916800000L,
            sessions = emptyList(),
            routines = emptyList(),
            rpgAttributes = null,
            badges = emptyList(),
            gamificationStats = null,
        ),
    )

    var signInResult: Result<GoTrueAuthResponse>? = null
    var signUpResult: Result<GoTrueAuthResponse>? = null

    var integrationSyncResult: Result<IntegrationSyncResponse> = Result.success(
        IntegrationSyncResponse(status = "ok"),
    )
    var playgroundSimulationResult: Result<IntegrationPlaygroundSimulationResponse> = Result.success(
        IntegrationPlaygroundSimulationResponse(status = "ok"),
    )

    // Call counters and captures
    var pushCallCount = 0
    var pullCallCount = 0
    var signInCallCount = 0
    var signUpCallCount = 0
    var integrationSyncCallCount = 0
    var playgroundSimulationCallCount = 0
    var lastPushPayload: PortalSyncPayload? = null
    val pushPayloads: MutableList<PortalSyncPayload> = mutableListOf()
    var pushResultsQueue: MutableList<Result<PortalSyncPushResponse>>? = null
    var lastPullKnownEntityIds: KnownEntityIds? = null
    var lastPullDeviceId: String? = null
    var lastPullProfileId: String? = null
    var lastPullCursor: String? = null
    var lastPullPageSize: Int? = null
    val pullCallCursors: MutableList<String?> = mutableListOf()
    val pullCallProfileIds: MutableList<String?> = mutableListOf()
    val pullCallTimestampsMs: MutableList<Long> = mutableListOf()
    var pullTimestampSourceMs: () -> Long = { currentTimeMillis() }
    var lastIntegrationSyncRequest: IntegrationSyncRequest? = null
    var lastPlaygroundSimulationRequest: IntegrationPlaygroundSimulationRequest? = null

    // A configured queue describes every expected logical call and fails when exhausted.
    var pullResultsQueue: MutableList<Result<PortalSyncPullResponse>>? = null
    var integrationSyncResultsQueue: MutableList<Result<IntegrationSyncResponse>>? = null

    override suspend fun signIn(email: String, password: String): Result<GoTrueAuthResponse> {
        signInCallCount++
        return signInResult ?: Result.failure(PortalApiException("signIn not configured"))
    }

    override suspend fun signUp(email: String, password: String, displayName: String?): Result<GoTrueAuthResponse> {
        signUpCallCount++
        return signUpResult ?: Result.failure(PortalApiException("signUp not configured"))
    }

    override suspend fun pushPortalPayload(payload: PortalSyncPayload): Result<PortalSyncPushResponse> {
        pushCallCount++
        lastPushPayload = payload
        pushPayloads += payload
        return pushResultsQueue?.removeExpectedResult() ?: pushResult
    }

    override suspend fun pullPortalPayload(
        knownEntityIds: KnownEntityIds,
        deviceId: String,
        profileId: String?,
        cursor: String?,
        pageSize: Int?,
    ): Result<PortalSyncPullResponse> {
        pullCallCount++
        lastPullKnownEntityIds = knownEntityIds
        lastPullDeviceId = deviceId
        lastPullProfileId = profileId
        pullCallProfileIds += profileId
        lastPullCursor = cursor
        lastPullPageSize = pageSize
        pullCallCursors += cursor
        pullCallTimestampsMs += pullTimestampSourceMs()

        return pullResultsQueue?.removeExpectedResult() ?: pullResult
    }

    override suspend fun callIntegrationSync(request: IntegrationSyncRequest): Result<IntegrationSyncResponse> {
        integrationSyncCallCount++
        lastIntegrationSyncRequest = request
        return integrationSyncResultsQueue?.removeFirstOrNull() ?: integrationSyncResult
    }

    override suspend fun callIntegrationPlaygroundSimulation(
        request: IntegrationPlaygroundSimulationRequest,
    ): Result<IntegrationPlaygroundSimulationResponse> {
        playgroundSimulationCallCount++
        lastPlaygroundSimulationRequest = request
        return playgroundSimulationResult
    }
}

private fun <T> MutableList<Result<T>>.removeExpectedResult(): Result<T> {
    check(isNotEmpty()) { "FAKE_PORTAL_RESULT_QUEUE_EXHAUSTED" }
    return removeAt(0)
}
