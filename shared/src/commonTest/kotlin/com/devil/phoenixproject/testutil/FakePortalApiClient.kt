package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.sync.GoTrueAuthResponse
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

    // Call counters and captures
    var pushCallCount = 0
    var pullCallCount = 0
    var signInCallCount = 0
    var signUpCallCount = 0
    var integrationSyncCallCount = 0
    var lastPushPayload: PortalSyncPayload? = null
    var lastPullKnownEntityIds: KnownEntityIds? = null
    var lastPullDeviceId: String? = null
    var lastPullCursor: String? = null
    var lastPullPageSize: Int? = null
    var lastIntegrationSyncRequest: IntegrationSyncRequest? = null

    // Pagination support: list of results to return for successive pull calls
    // If set, returns results from this list in order; when exhausted, falls back to pullResult
    var pullResultsQueue: MutableList<Result<PortalSyncPullResponse>>? = null

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
        return pushResult
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
        lastPullCursor = cursor
        lastPullPageSize = pageSize

        // Use queue if available, otherwise fallback to pullResult
        val result = pullResultsQueue?.removeFirstOrNull() ?: pullResult
        return result
    }

    override suspend fun callIntegrationSync(request: IntegrationSyncRequest): Result<IntegrationSyncResponse> {
        integrationSyncCallCount++
        lastIntegrationSyncRequest = request
        return integrationSyncResult
    }
}
