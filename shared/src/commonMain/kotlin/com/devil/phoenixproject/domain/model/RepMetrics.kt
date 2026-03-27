package com.devil.phoenixproject.domain.model

/**
 * Complete metric data for a single rep.
 * Captured in real-time during workout, persisted at set completion.
 *
 * Curve arrays store sampled data at the capture rate (downsampled to 25Hz for DB storage).
 * JSON serialization for DB storage is handled in the repository layer (Plan 02).
 */
data class RepMetricData(
    val repNumber: Int, // 1-indexed within the set
    val isWarmup: Boolean, // true for warmup reps
    val startTimestamp: Long, // ms since epoch
    val endTimestamp: Long, // ms since epoch
    val durationMs: Long, // total rep duration

    // Concentric phase (lifting)
    val concentricDurationMs: Long,
    val concentricPositions: FloatArray, // mm, sampled at capture rate
    val concentricLoadsA: FloatArray, // kg per sample
    val concentricLoadsB: FloatArray, // kg per sample
    val concentricVelocities: FloatArray, // mm/s per sample
    val concentricTimestamps: LongArray, // ms offsets from rep start

    // Eccentric phase (lowering)
    val eccentricDurationMs: Long,
    val eccentricPositions: FloatArray,
    val eccentricLoadsA: FloatArray,
    val eccentricLoadsB: FloatArray,
    val eccentricVelocities: FloatArray,
    val eccentricTimestamps: LongArray,

    // Computed summary (calculated at capture time)
    val peakForceA: Float, // kg, max during concentric
    val peakForceB: Float, // kg, max during concentric
    val avgForceConcentricA: Float, // kg
    val avgForceConcentricB: Float, // kg
    val avgForceEccentricA: Float, // kg
    val avgForceEccentricB: Float, // kg
    val peakVelocity: Float, // mm/s, max during concentric
    val avgVelocityConcentric: Float, // mm/s
    val avgVelocityEccentric: Float, // mm/s
    val rangeOfMotionMm: Float, // max position - min position
    val peakPowerWatts: Float, // max(force * velocity)
    val avgPowerWatts: Float, // mean(force * velocity) over concentric
) {
    // Custom equals/hashCode needed because data classes don't deep-compare arrays
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RepMetricData) return false

        return repNumber == other.repNumber &&
            isWarmup == other.isWarmup &&
            startTimestamp == other.startTimestamp &&
            endTimestamp == other.endTimestamp &&
            durationMs == other.durationMs &&
            concentricDurationMs == other.concentricDurationMs &&
            concentricPositions.contentEquals(other.concentricPositions) &&
            concentricLoadsA.contentEquals(other.concentricLoadsA) &&
            concentricLoadsB.contentEquals(other.concentricLoadsB) &&
            concentricVelocities.contentEquals(other.concentricVelocities) &&
            concentricTimestamps.contentEquals(other.concentricTimestamps) &&
            eccentricDurationMs == other.eccentricDurationMs &&
            eccentricPositions.contentEquals(other.eccentricPositions) &&
            eccentricLoadsA.contentEquals(other.eccentricLoadsA) &&
            eccentricLoadsB.contentEquals(other.eccentricLoadsB) &&
            eccentricVelocities.contentEquals(other.eccentricVelocities) &&
            eccentricTimestamps.contentEquals(other.eccentricTimestamps) &&
            peakForceA == other.peakForceA &&
            peakForceB == other.peakForceB &&
            avgForceConcentricA == other.avgForceConcentricA &&
            avgForceConcentricB == other.avgForceConcentricB &&
            avgForceEccentricA == other.avgForceEccentricA &&
            avgForceEccentricB == other.avgForceEccentricB &&
            peakVelocity == other.peakVelocity &&
            avgVelocityConcentric == other.avgVelocityConcentric &&
            avgVelocityEccentric == other.avgVelocityEccentric &&
            rangeOfMotionMm == other.rangeOfMotionMm &&
            peakPowerWatts == other.peakPowerWatts &&
            avgPowerWatts == other.avgPowerWatts
    }

    override fun hashCode(): Int {
        var result = repNumber
        result = 31 * result + isWarmup.hashCode()
        result = 31 * result + startTimestamp.hashCode()
        result = 31 * result + endTimestamp.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + concentricPositions.contentHashCode()
        result = 31 * result + concentricLoadsA.contentHashCode()
        result = 31 * result + concentricLoadsB.contentHashCode()
        result = 31 * result + concentricVelocities.contentHashCode()
        result = 31 * result + concentricTimestamps.contentHashCode()
        result = 31 * result + eccentricPositions.contentHashCode()
        result = 31 * result + eccentricLoadsA.contentHashCode()
        result = 31 * result + eccentricLoadsB.contentHashCode()
        result = 31 * result + eccentricVelocities.contentHashCode()
        result = 31 * result + eccentricTimestamps.contentHashCode()
        result = 31 * result + peakForceA.hashCode()
        result = 31 * result + peakForceB.hashCode()
        return result
    }
}
