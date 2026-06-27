package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.VelocityOneRepMaxEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class CountVelocityOneRepMaxImprovementsUseCaseTest {
    private val useCase = CountVelocityOneRepMaxImprovementsUseCase()
    private fun e(ex: String, kg: Float, t: Long) =
        VelocityOneRepMaxEntity(0, ex, kg, 0.3f, 0.95f, 3, true, t, "default")

    @Test fun `counts improvements above 2_5 percent per exercise`() {
        // exA: 100 (base) -> 103 (+3% improvement) -> 104 (<2.5% over 103, no) -> 110 (improvement over 104)
        val points = listOf(
            e("exA", 100f, 1), e("exA", 103f, 2), e("exA", 104f, 3), e("exA", 110f, 4),
            e("exB", 50f, 1), e("exB", 60f, 2), // exB: base -> +20% improvement
        )
        assertEquals(3, useCase(points)) // exA:2 + exB:1
    }

    @Test fun `single estimate per exercise is not an improvement`() {
        assertEquals(0, useCase(listOf(e("exA", 100f, 1), e("exB", 80f, 1))))
    }

    @Test fun `empty input is zero`() = assertEquals(0, useCase(emptyList()))
}
