package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.ChangeDirection
import com.devil.phoenixproject.domain.model.TrendMetricType
import com.devil.phoenixproject.domain.model.TrendPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComparativeAnalyticsUseCaseTest {

    private val useCase = ComparativeAnalyticsUseCase()

    @Test
    fun `comparePeriods detects increase and significance`() {
        val current = listOf(
            TrendPoint(1L, 10f),
            TrendPoint(2L, 12f)
        )
        val previous = listOf(
            TrendPoint(1L, 5f),
            TrendPoint(2L, 5f)
        )

        val result = useCase.comparePeriods(current, previous, TrendMetricType.WEIGHT_PR)

        assertEquals(ChangeDirection.INCREASE, result.changeDirection)
        assertTrue(result.isSignificant)
    }

    @Test
    fun `comparePeriods handles zero previous values`() {
        val current = listOf(TrendPoint(1L, 10f))
        val previous = emptyList<TrendPoint>()

        val result = useCase.comparePeriods(current, previous, TrendMetricType.VOLUME_WEEKLY)

        assertEquals(ChangeDirection.NO_CHANGE, result.changeDirection)
        assertFalse(result.isSignificant)
    }
}
