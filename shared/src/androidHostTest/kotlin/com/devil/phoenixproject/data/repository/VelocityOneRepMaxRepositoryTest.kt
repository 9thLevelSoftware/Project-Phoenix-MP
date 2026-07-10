package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.onerepmax.VelocityOneRepMaxResult
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest

class VelocityOneRepMaxRepositoryTest {

    @Test fun `latest passing skips newer floor row and returns older usable estimate`() = runTest {
        val repository = SqlDelightVelocityOneRepMaxRepository(createTestDatabase())

        repository.insert(result(100f), exerciseId = "row", computedAt = 1_000L, profileId = "default")
        repository.insert(result(1f), exerciseId = "row", computedAt = 2_000L, profileId = "default")

        val latest = repository.getLatestPassing("row", "default")

        assertNotNull(latest)
        assertEquals(100f, latest.estimatedPerCableKg)
    }

    private fun result(estimatedPerCableKg: Float) = VelocityOneRepMaxResult(
        estimatedPerCableKg = estimatedPerCableKg,
        mvtUsedMs = 0.5f,
        r2 = 0.9f,
        distinctLoads = 3,
        passedQualityGate = true,
    )
}
