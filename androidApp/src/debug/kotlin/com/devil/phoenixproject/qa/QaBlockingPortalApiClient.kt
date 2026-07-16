package com.devil.phoenixproject.qa

import com.devil.phoenixproject.data.sync.KnownEntityIds
import com.devil.phoenixproject.data.sync.PortalApiClient
import com.devil.phoenixproject.data.sync.PortalApiException
import com.devil.phoenixproject.data.sync.PortalSyncPayload
import com.devil.phoenixproject.data.sync.PortalSyncPullResponse
import com.devil.phoenixproject.data.sync.PortalSyncPushResponse
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.devil.phoenixproject.data.sync.SupabaseConfig
import io.ktor.client.engine.HttpClientEngine

class QaBlockingPortalApiClient(
    supabaseConfig: SupabaseConfig,
    tokenStorage: PortalTokenStorage,
    private val fixtureGate: ProfileQaFixtureGate,
    httpClientEngine: HttpClientEngine? = null,
) : PortalApiClient(supabaseConfig, tokenStorage, httpClientEngine) {
    override suspend fun pushPortalPayload(
        payload: PortalSyncPayload,
    ): Result<PortalSyncPushResponse> = if (fixtureGate.isEnabled()) {
        localOnlyFailure()
    } else {
        super.pushPortalPayload(payload)
    }

    override suspend fun pullPortalPayload(
        knownEntityIds: KnownEntityIds,
        deviceId: String,
        profileId: String?,
        cursor: String?,
        pageSize: Int?,
    ): Result<PortalSyncPullResponse> = if (fixtureGate.isEnabled()) {
        localOnlyFailure()
    } else {
        super.pullPortalPayload(knownEntityIds, deviceId, profileId, cursor, pageSize)
    }

    private fun <T> localOnlyFailure(): Result<T> =
        Result.failure(PortalApiException("QA fixture is local-only"))
}
