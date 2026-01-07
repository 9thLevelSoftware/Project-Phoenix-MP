package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.TrendDirection
import com.devil.phoenixproject.domain.model.TrendPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TrendAnalysisUseCaseTest {

    private val useCase = TrendAnalysisUseCase()

    @Test
    fun `linearRegression returns expected slope and intercept`() {
        val points = listOf(
            TrendPoint(timestamp = 1L, value = 2f),
            TrendPoint(timestamp = 2L, value = 4f),
            TrendPoint(timestamp = 3L, value = 6f)
        )

        val result = useCase.linearRegression(points)

        assertEquals(2f, result.slope, absoluteTolerance = 0.001f)
        assertEquals(2f, result.intercept, absoluteTolerance = 0.001f)
        assertEquals(1f, result.rSquared, absoluteTolerance = 0.001f)
        assertEquals(4f, result.yMean, absoluteTolerance = 0.001f)
    }

    @Test
    fun `movingAverage smooths values with window`() {
        val points = listOf(
            TrendPoint(1L, 1f),
            TrendPoint(2L, 2f),
            TrendPoint(3L, 3f),
            TrendPoint(4L, 4f),
            TrendPoint(5L, 5f)
        )

        val averaged = useCase.movingAverage(points, windowSize = 3)

        assertEquals(5, averaged.size)
        assertEquals(3f, averaged[2].value, absoluteTolerance = 0.001f)
    }

    @Test
    fun `detectTrend identifies increasing and plateau trends`() {
        val increasing = listOf(
            TrendPoint(1L, 1f),
            TrendPoint(2L, 2f),
            TrendPoint(3L, 3f)
        )
        val plateau = listOf(
            TrendPoint(1L, 10f),
            TrendPoint(2L, 10f),
            TrendPoint(3L, 10f),
            TrendPoint(4L, 10f)
        )

        val (incDirection, incStrength) = useCase.detectTrend(increasing)
        val (plateauDirection, plateauStrength) = useCase.detectTrend(plateau)

        assertEquals(TrendDirection.INCREASING, incDirection)
        assertTrue(incStrength > 0.8f)
        assertEquals(TrendDirection.PLATEAU, plateauDirection)
        assertTrue(plateauStrength >= 0f)
    }

    @Test
    fun `predictValue returns future estimate`() {
        val points = listOf(
            TrendPoint(1L, 2f),
            TrendPoint(2L, 4f),
            TrendPoint(3L, 6f)
        )

        val prediction = useCase.predictValue(points, daysAhead = 2)

        assertNotNull(prediction)
        assertEquals(10f, prediction.predictedValue, absoluteTolerance = 0.1f)
    }

    @Test
    fun `detectAnomalies flags outliers`() {
        val points = listOf(
            TrendPoint(1L, 1f),
            TrendPoint(2L, 1f),
            TrendPoint(3L, 1f),
            TrendPoint(4L, 10f),
            TrendPoint(5L, 1f)
        )

        val anomalies = useCase.detectAnomalies(points, threshold = 1.5f)

        assertEquals(1, anomalies.size)
        assertEquals(10f, anomalies.first().actualValue)
        assertTrue(anomalies.first().deviation > 0f)
    }

    @Test
    fun `detectPlateau returns plateau for flat data over duration`() {
        val dayMs = 24 * 60 * 60 * 1000L
        val points = (0..9).map { index ->
            TrendPoint(timestamp = 1_000_000L + (index * dayMs), value = 50f)
        }

        val plateau = useCase.detectPlateau(points, exerciseId = "bench", minDurationDays = 7)

        assertNotNull(plateau)
        assertEquals("bench", plateau.exerciseId)
        assertTrue(plateau.durationDays >= 7)
    }
}
