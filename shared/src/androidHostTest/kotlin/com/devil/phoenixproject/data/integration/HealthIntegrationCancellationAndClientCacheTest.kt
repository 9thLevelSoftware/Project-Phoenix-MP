package com.devil.phoenixproject.data.integration

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Health Connect suspend paths catch [Exception], and [CancellationException] is an
 * [Exception]. Those catches must rethrow cancellation. A null client lookup must also
 * stay uncached so a later install or update can succeed in the same process.
 */
class HealthIntegrationCancellationAndClientCacheTest {
    @Test
    fun hasPermissions_rethrowsCancellation() = runTest {
        val cancellation = CancellationException("permission check cancelled")
        val integration = integration(client = clientThrowingPermissions(cancellation))

        val thrown = assertFailsWith<CancellationException> { integration.hasPermissions() }

        assertEquals(cancellation.message, thrown.message)
    }

    @Test
    fun hasPermissions_returnsFalseWhenThePermissionCheckFails() = runTest {
        val integration = integration(
            client = clientThrowingPermissions(IllegalStateException("binder died")),
        )

        assertFalse(integration.hasPermissions())
    }

    @Test
    fun readLatestScaleBodyWeight_rethrowsCancellation() = runTest {
        val cancellation = CancellationException("read cancelled")
        val client = grantedClient(bodyWeightReadHealthPermissions)
        coEvery { client.readRecords(any<ReadRecordsRequest<WeightRecord>>()) } throws cancellation
        val integration = integration(client = client)

        val thrown = assertFailsWith<CancellationException> {
            integration.readLatestScaleBodyWeight()
        }

        assertEquals(cancellation.message, thrown.message)
    }

    @Test
    fun readLatestScaleBodyWeight_returnsFailureWhenTheReadFails() = runTest {
        val client = grantedClient(bodyWeightReadHealthPermissions)
        coEvery { client.readRecords(any<ReadRecordsRequest<WeightRecord>>()) } throws
            IllegalStateException("read failed")
        val integration = integration(client = client)

        val result = integration.readLatestScaleBodyWeight()

        assertTrue(result.isFailure)
        assertIs<IllegalStateException>(result.exceptionOrNull())
    }

    @Test
    fun writeHealthWorkout_rethrowsCancellation() = runTest {
        val cancellation = CancellationException("insert cancelled")
        val client = grantedClient(requestedHealthPermissions)
        coEvery { client.insertRecords(any()) } throws cancellation
        val integration = integration(client = client)

        val thrown = assertFailsWith<CancellationException> {
            integration.writeHealthWorkout(sampleWorkout())
        }

        assertEquals(cancellation.message, thrown.message)
    }

    @Test
    fun writeHealthWorkout_returnsFailureWhenTheInsertFails() = runTest {
        val client = grantedClient(requestedHealthPermissions)
        coEvery { client.insertRecords(any()) } throws IllegalStateException("insert failed")
        val integration = integration(client = client)

        val result = integration.writeHealthWorkout(sampleWorkout())

        assertTrue(result.isFailure)
        assertIs<IllegalStateException>(result.exceptionOrNull())
    }

    @Test
    fun isAvailable_rethrowsCancellation() = runTest {
        val cancellation = CancellationException("status cancelled")
        val integration = HealthIntegration(
            sdkStatus = { throw cancellation },
            openClient = { error("client must not be opened") },
        )

        val thrown = assertFailsWith<CancellationException> { integration.isAvailable() }

        assertEquals(cancellation.message, thrown.message)
    }

    @Test
    fun isAvailable_returnsFalseWhenTheStatusCheckFails() = runTest {
        val integration = HealthIntegration(
            sdkStatus = { throw IllegalStateException("status failed") },
            openClient = { error("client must not be opened") },
        )

        assertFalse(integration.isAvailable())
    }

    @Test
    fun openingTheClient_rethrowsCancellation() = runTest {
        val cancellation = CancellationException("lookup cancelled")
        val integration = HealthIntegration(
            sdkStatus = { HealthConnectClient.SDK_AVAILABLE },
            openClient = { throw cancellation },
        )

        val thrown = assertFailsWith<CancellationException> { integration.hasPermissions() }

        assertEquals(cancellation.message, thrown.message)
    }

    @Test
    fun unavailableClientLookupIsRetriedAndARealClientIsCached() = runTest {
        var status = HealthConnectClient.SDK_UNAVAILABLE
        var statusChecks = 0
        var opens = 0
        val integration = HealthIntegration(
            sdkStatus = {
                statusChecks += 1
                status
            },
            openClient = {
                opens += 1
                grantedClient(requiredHealthPermissions)
            },
        )

        assertFalse(integration.hasPermissions())
        assertEquals(0, opens, "an unavailable provider must not be opened or cached")

        status = HealthConnectClient.SDK_AVAILABLE
        assertTrue(integration.hasPermissions())
        assertEquals(1, opens)
        assertTrue(statusChecks >= 2, "availability must be checked again after a miss")

        val statusChecksAfterSuccess = statusChecks
        val opensAfterSuccess = opens
        assertTrue(integration.hasPermissions())
        assertEquals(statusChecksAfterSuccess, statusChecks, "a real client must stay cached")
        assertEquals(opensAfterSuccess, opens, "a real client must not be opened again")
    }

    @Test
    fun failedClientCreationIsRetried() = runTest {
        var fail = true
        var opens = 0
        val integration = HealthIntegration(
            sdkStatus = { HealthConnectClient.SDK_AVAILABLE },
            openClient = {
                opens += 1
                if (fail) throw IllegalStateException("provider missing")
                grantedClient(requiredHealthPermissions)
            },
        )

        assertFalse(integration.hasPermissions())
        fail = false

        assertTrue(integration.hasPermissions())
        assertEquals(2, opens, "a failed open must not be cached")
        assertTrue(integration.hasPermissions())
        assertEquals(2, opens, "a client opened after a failure must stay cached")
    }

    private fun integration(client: HealthConnectClient): HealthIntegration =
        HealthIntegration(
            sdkStatus = { HealthConnectClient.SDK_AVAILABLE },
            openClient = { client },
        )

    private fun grantedClient(granted: Set<String>): HealthConnectClient {
        val permissions = mockk<PermissionController>()
        coEvery { permissions.getGrantedPermissions() } returns granted
        return mockk<HealthConnectClient>().also { client ->
            every { client.permissionController } returns permissions
        }
    }

    private fun clientThrowingPermissions(error: Throwable): HealthConnectClient {
        val permissions = mockk<PermissionController>()
        coEvery { permissions.getGrantedPermissions() } throws error
        return mockk<HealthConnectClient>().also { client ->
            every { client.permissionController } returns permissions
        }
    }

    private fun sampleWorkout(): HealthWorkoutData = HealthWorkoutData(
        title = "Phoenix Workout",
        externalId = "phoenix:session:test",
        startTimeMs = 1_700_000_000_000,
        endTimeMs = 1_700_000_060_000,
        totalCalories = null,
        segments = emptyList(),
    )
}
