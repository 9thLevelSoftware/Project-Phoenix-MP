package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.sync.KnownEntityIds
import com.devil.phoenixproject.data.sync.PortalSyncPayload
import com.devil.phoenixproject.data.sync.PortalSyncPullResponse
import com.devil.phoenixproject.data.sync.PortalSyncPushResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class FakePortalApiClientTest {
    private fun pushResponse(time: String) =
        Result.success(PortalSyncPushResponse(syncTime = time))

    @Test
    fun `configured push queue preserves exact history and fails when exhausted`() = runTest {
        val fake = FakePortalApiClient()
        val first = PortalSyncPayload(deviceId = "first", lastSync = 0)
        val second = PortalSyncPayload(deviceId = "second", lastSync = 0)
        fake.pushResultsQueue = mutableListOf(
            pushResponse("2026-07-11T12:00:00Z"),
            pushResponse("2026-07-11T12:00:01Z"),
        )

        assertTrue(fake.pushPortalPayload(first).isSuccess)
        assertTrue(fake.pushPortalPayload(second).isSuccess)

        assertEquals(listOf(first, second), fake.pushPayloads)
        assertEquals(second, fake.lastPushPayload)
        assertEquals(2, fake.pushCallCount)
        assertTrue(fake.pushResultsQueue.orEmpty().isEmpty())
        assertFailsWith<IllegalStateException> {
            fake.pushPortalPayload(PortalSyncPayload(deviceId = "unexpected", lastSync = 0))
        }
    }

    @Test
    fun `configured pull queue preserves profile history and fails when exhausted`() = runTest {
        val fake = FakePortalApiClient()
        fake.pullResultsQueue = mutableListOf(
            Result.success(PortalSyncPullResponse(syncTime = 1)),
            Result.success(PortalSyncPullResponse(syncTime = 2)),
        )

        listOf("profile-a", "profile-b").forEach { profileId ->
            assertTrue(
                fake.pullPortalPayload(
                    knownEntityIds = KnownEntityIds(),
                    deviceId = "device",
                    profileId = profileId,
                    cursor = null,
                    pageSize = 100,
                ).isSuccess,
            )
        }

        assertEquals(listOf<String?>("profile-a", "profile-b"), fake.pullCallProfileIds)
        assertEquals("profile-b", fake.lastPullProfileId)
        assertEquals(2, fake.pullCallCount)
        assertTrue(fake.pullResultsQueue.orEmpty().isEmpty())
        assertFailsWith<IllegalStateException> {
            fake.pullPortalPayload(
                KnownEntityIds(),
                "device",
                "unexpected",
                null,
                100,
            )
        }
    }
}
