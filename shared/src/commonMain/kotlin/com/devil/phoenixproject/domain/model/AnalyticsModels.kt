package com.devil.phoenixproject.domain.model

/**
 * Trend analysis result for a metric over time
 */
data class TrendData(
    val metricType: TrendMetricType,
    val dataPoints: List<TrendPoint>,
    val trendDirection: TrendDirection,
    val trendStrength: Float, // 0.0 to 1.0
    val projectedValue: Float? = null,
    val projectedDate: Long? = null
)

/**
 * Single data point in a trend
 */
data class TrendPoint(
    val timestamp: Long,
    val value: Float,
    val label: String? = null
)

/**
 * Type of metric being analyzed
 */
enum class TrendMetricType {
    WEIGHT_PR,           // Personal record weight progression
    VOLUME_WEEKLY,       // Weekly total volume
    VOLUME_MONTHLY,      // Monthly total volume
    CONSISTENCY_SCORE,   // Workout consistency (0-1)
    AVERAGE_POWER,       // Average power per workout
    WORKOUT_FREQUENCY    // Workouts per week
}

/**
 * Direction of trend
 */
enum class TrendDirection {
    INCREASING,
    DECREASING,
    STABLE,
    PLATEAU
}

/**
 * Prediction result for future values
 */
data class Prediction(
    val metricType: TrendMetricType,
    val currentValue: Float,
    val predictedValue: Float,
    val predictedDate: Long,
    val confidence: Float, // 0.0 to 1.0
    val method: PredictionMethod
)

/**
 * Prediction method used
 */
enum class PredictionMethod {
    LINEAR_REGRESSION,
    MOVING_AVERAGE,
    EXPONENTIAL_SMOOTHING
}

/**
 * Comparative analytics result
 */
data class ComparisonResult(
    val metricType: TrendMetricType,
    val currentPeriod: PeriodData,
    val previousPeriod: PeriodData,
    val changePercentage: Float,
    val changeDirection: ChangeDirection,
    val isSignificant: Boolean // Statistically significant change
)

/**
 * Data for a specific time period
 */
data class PeriodData(
    val startDate: Long,
    val endDate: Long,
    val totalValue: Float,
    val averageValue: Float,
    val dataPoints: List<TrendPoint>
)

/**
 * Direction of change
 */
enum class ChangeDirection {
    INCREASE,
    DECREASE,
    NO_CHANGE
}

/**
 * Anomaly detection result
 */
data class Anomaly(
    val timestamp: Long,
    val metricType: TrendMetricType,
    val actualValue: Float,
    val expectedValue: Float,
    val deviation: Float,
    val severity: AnomalySeverity,
    val description: String
)

/**
 * Severity of anomaly
 */
enum class AnomalySeverity {
    LOW,
    MEDIUM,
    HIGH
}

/**
 * Plateau detection result
 */
data class Plateau(
    val exerciseId: String,
    val metricType: TrendMetricType,
    val startDate: Long,
    val endDate: Long?,
    val averageValue: Float,
    val durationDays: Int,
    val recommendation: String
)
