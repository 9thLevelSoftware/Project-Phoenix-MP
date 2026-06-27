package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.onerepmax.VelocityOneRepMaxResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private fun dummyResult() = VelocityOneRepMaxResult(
    estimatedPerCableKg = 100f,
    mvtUsedMs = 300f,
    r2 = 0.95f,
    distinctLoads = 3,
    passedQualityGate = true,
)

class BackfillVelocityOneRepMaxUseCaseTest {

    @Test
    fun `backfills only exercises without an existing estimate`() = runTest {
        val computed = mutableListOf<String>()
        val useCase = BackfillVelocityOneRepMaxUseCase(
            exerciseIds = { _ -> listOf("exA", "exB", "exC") },
            hasEstimates = { id, _ -> id == "exB" }, // exB already has one
            computeAllTime = { id, _, _ -> computed += id; if (id == "exC") null else dummyResult() }, // exC ineligible
        )
        val created = useCase("default", nowMs = 1_000L)
        assertEquals(listOf("exA", "exC"), computed) // exB skipped
        assertEquals(1, created)                      // only exA produced an estimate
    }
}
