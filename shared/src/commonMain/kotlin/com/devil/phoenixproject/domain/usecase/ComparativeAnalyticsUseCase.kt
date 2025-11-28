package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.*
import kotlin.math.abs

/**
 * Use case for comparing metrics across different time periods
 */
class ComparativeAnalyticsUseCase {

    /**
     * Compare two time periods
     */
    fun comparePeriods(
        currentPeriod: List<TrendPoint>,
        previousPeriod: List<TrendPoint>,
        metricType: TrendMetricType
    ): ComparisonResult {
        val currentData = calculatePeriodData(currentPeriod)
        val previousData = calculatePeriodData(previousPeriod)

        val changePercentage = if (previousData.averageValue > 0) {
            ((currentData.averageValue - previousData.averageValue) / previousData.averageValue) * 100f
        } else {
            0f
        }

        val changeDirection = when {
            changePercentage > 1f -> ChangeDirection.INCREASE
            changePercentage < -1f -> ChangeDirection.DECREASE
            else -> ChangeDirection.NO_CHANGE
        }

        // Statistical significance: change > 5% is considered significant
        val isSignificant = abs(changePercentage) > 5f

        return ComparisonResult(
            metricType = metricType,
            currentPeriod = currentData,
            previousPeriod = previousData,
            changePercentage = changePercentage,
            changeDirection = changeDirection,
            isSignificant = isSignificant
        )
    }

    /**
     * Calculate aggregated data for a period
     */
    private fun calculatePeriodData(
        dataPoints: List<TrendPoint>
    ): PeriodData {
        if (dataPoints.isEmpty()) {
            return PeriodData(
                startDate = 0L,
                endDate = 0L,
                totalValue = 0f,
                averageValue = 0f,
                dataPoints = emptyList()
            )
        }

        val sortedPoints = dataPoints.sortedBy { it.timestamp }
        val startDate = sortedPoints.first().timestamp
        val endDate = sortedPoints.last().timestamp
        val totalValue = sortedPoints.sumOf { it.value.toDouble() }.toFloat()
        val averageValue = if (sortedPoints.isNotEmpty()) {
            totalValue / sortedPoints.size
        } else {
            0f
        }

        return PeriodData(
            startDate = startDate,
            endDate = endDate,
            totalValue = totalValue,
            averageValue = averageValue,
            dataPoints = sortedPoints
        )
    }

    /**
     * Compare this week vs last week
     */
    fun compareThisWeekVsLastWeek(
        thisWeek: List<TrendPoint>,
        lastWeek: List<TrendPoint>,
        metricType: TrendMetricType
    ): ComparisonResult {
        return comparePeriods(thisWeek, lastWeek, metricType)
    }

    /**
     * Compare this month vs last month
     */
    fun compareThisMonthVsLastMonth(
        thisMonth: List<TrendPoint>,
        lastMonth: List<TrendPoint>,
        metricType: TrendMetricType
    ): ComparisonResult {
        return comparePeriods(thisMonth, lastMonth, metricType)
    }
}
