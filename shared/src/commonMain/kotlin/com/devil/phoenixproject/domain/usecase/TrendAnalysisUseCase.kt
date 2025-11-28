package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.*
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Use case for analyzing trends in workout data
 * Implements linear regression, moving averages, and trend detection
 */
class TrendAnalysisUseCase {

    /**
     * Perform linear regression on data points to predict future values
     * Returns slope, intercept, and R-squared value
     */
    fun linearRegression(
        dataPoints: List<TrendPoint>
    ): LinearRegressionResult {
        if (dataPoints.size < 2) {
            return LinearRegressionResult(0f, 0f, 0f, 0f)
        }

        val n = dataPoints.size.toFloat()
        val xValues = dataPoints.mapIndexed { index, _ -> index.toFloat() }
        val yValues = dataPoints.map { it.value }

        val sumX = xValues.sum()
        val sumY = yValues.sum()
        val sumXY = xValues.zip(yValues).sumOf { (x, y) -> (x * y).toDouble() }
        val sumX2 = xValues.sumOf { (it * it).toDouble() }
        val sumY2 = yValues.sumOf { (it * it).toDouble() }

        val slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX)
        val intercept = (sumY - slope * sumX) / n

        // Calculate R-squared (coefficient of determination)
        val yMean = sumY / n
        val ssTotal = yValues.sumOf { (it - yMean).pow(2).toDouble() }.toFloat()
        val ssResidual = xValues.zip(yValues).sumOf { (x, y) ->
            val predicted = slope * x + intercept
            (y - predicted).pow(2).toDouble()
        }.toFloat()
        val rSquared = if (ssTotal.compareTo(0f) > 0) {
            1f - (ssResidual / ssTotal)
        } else {
            0f
        }

        return LinearRegressionResult(slope.toFloat(), intercept.toFloat(), rSquared, yMean)
    }

    /**
     * Calculate moving average for smoothing trend lines
     */
    fun movingAverage(
        dataPoints: List<TrendPoint>,
        windowSize: Int = 3
    ): List<TrendPoint> {
        if (dataPoints.size < windowSize) return dataPoints

        return dataPoints.mapIndexed { index, point ->
            val start = (index - windowSize / 2).coerceAtLeast(0)
            val end = (index + windowSize / 2 + 1).coerceAtMost(dataPoints.size)
            val window = dataPoints.subList(start, end)
            val avgValue = window.map { it.value }.average().toFloat()

            TrendPoint(
                timestamp = point.timestamp,
                value = avgValue,
                label = point.label
            )
        }
    }

    /**
     * Detect trend direction and strength
     */
    fun detectTrend(dataPoints: List<TrendPoint>): Pair<TrendDirection, Float> {
        if (dataPoints.size < 2) {
            return Pair(TrendDirection.STABLE, 0f)
        }

        val regression = linearRegression(dataPoints)
        val slope = regression.slope
        val rSquared = regression.rSquared

        val direction = when {
            slope > 0.01f -> TrendDirection.INCREASING
            slope < -0.01f -> TrendDirection.DECREASING
            else -> TrendDirection.STABLE
        }

        // Check for plateau (low variance, stable values)
        val values = dataPoints.map { it.value }
        val mean = values.average().toFloat()
        val variance = values.map { (it - mean).pow(2) }.average().toFloat()
        val stdDev = sqrt(variance)

        val isPlateau = stdDev < mean * 0.1f && abs(slope) < 0.01f

        val finalDirection = if (isPlateau) TrendDirection.PLATEAU else direction
        val strength = rSquared.coerceIn(0f, 1f)

        return Pair(finalDirection, strength)
    }

    /**
     * Predict future value using linear regression
     */
    fun predictValue(
        dataPoints: List<TrendPoint>,
        daysAhead: Int
    ): Prediction? {
        if (dataPoints.size < 2) return null

        val regression = linearRegression(dataPoints)
        val lastIndex = dataPoints.size - 1
        val futureIndex = lastIndex + daysAhead
        val predictedValue = regression.slope * futureIndex + regression.intercept

        val lastTimestamp = dataPoints.last().timestamp
        val predictedDate = lastTimestamp + (daysAhead * 24 * 60 * 60 * 1000L)

        val confidence = regression.rSquared.coerceIn(0f, 1f)

        return Prediction(
            metricType = TrendMetricType.WEIGHT_PR, // Will be set by caller
            currentValue = dataPoints.last().value,
            predictedValue = predictedValue,
            predictedDate = predictedDate,
            confidence = confidence,
            method = PredictionMethod.LINEAR_REGRESSION
        )
    }

    /**
     * Detect anomalies in data (values significantly different from trend)
     */
    fun detectAnomalies(
        dataPoints: List<TrendPoint>,
        threshold: Float = 2.0f // Standard deviations
    ): List<Anomaly> {
        if (dataPoints.size < 3) return emptyList()

        val regression = linearRegression(dataPoints)
        val values = dataPoints.map { it.value }
        val mean = values.average().toFloat()
        val stdDev = sqrt(values.map { (it - mean).pow(2) }.average().toFloat())

        return dataPoints.mapIndexedNotNull { index, point ->
            val predicted = regression.slope * index + regression.intercept
            val deviation = abs(point.value - predicted) / stdDev

            if (deviation > threshold) {
                val severity = when {
                    deviation > 3.0f -> AnomalySeverity.HIGH
                    deviation > 2.5f -> AnomalySeverity.MEDIUM
                    else -> AnomalySeverity.LOW
                }

                Anomaly(
                    timestamp = point.timestamp,
                    metricType = TrendMetricType.WEIGHT_PR, // Will be set by caller
                    actualValue = point.value,
                    expectedValue = predicted,
                    deviation = deviation,
                    severity = severity,
                    description = "Value ${if (point.value > predicted) "above" else "below"} expected trend"
                )
            } else {
                null
            }
        }
    }

    /**
     * Detect plateaus (periods of no progress)
     */
    fun detectPlateau(
        dataPoints: List<TrendPoint>,
        exerciseId: String,
        minDurationDays: Int = 14
    ): Plateau? {
        if (dataPoints.size < minDurationDays) return null

        val regression = linearRegression(dataPoints)
        val isPlateau = abs(regression.slope) < 0.01f && regression.rSquared < 0.3f

        if (!isPlateau) return null

        val startDate = dataPoints.first().timestamp
        val endDate = dataPoints.last().timestamp
        val durationDays = ((endDate - startDate) / (24 * 60 * 60 * 1000L)).toInt()

        if (durationDays < minDurationDays) return null

        val averageValue = dataPoints.map { it.value }.average().toFloat()

        return Plateau(
            exerciseId = exerciseId,
            metricType = TrendMetricType.WEIGHT_PR,
            startDate = startDate,
            endDate = endDate,
            averageValue = averageValue,
            durationDays = durationDays,
            recommendation = "Consider deloading or changing workout variables to break through plateau"
        )
    }
}

/**
 * Result of linear regression calculation
 */
data class LinearRegressionResult(
    val slope: Float,
    val intercept: Float,
    val rSquared: Float,
    val yMean: Float
)
