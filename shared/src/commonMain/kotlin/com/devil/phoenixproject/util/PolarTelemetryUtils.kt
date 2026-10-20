package com.devil.phoenixproject.util

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Biomechanical state derived from Polar H10 3D chest accelerometer.
 *
 * Polar H10 sensor orientation when worn on sternum:
 * - X axis: vertical sternum (~ -1000 mG upright due to gravity)
 * - Y axis: mediolateral (left/right asymmetry)
 * - Z axis: anteroposterior (pointing forward from chest)
 */
data class PolarTorsoBiomechanics(
    val pitchDeg: Double,
    val rollDeg: Double,
    val dynamicAccMg: Int,
    val filteredXMg: Double,
    val filteredYMg: Double,
    val filteredZMg: Double,
)

object PolarTelemetryUtils {

    const val DEFAULT_EMA_ALPHA = 0.2
    const val GRAVITY_1G_MG = 1000.0

    /**
     * Compute torso pitch angle in degrees (forward lean / hip-hinge).
     *
     * 0° = upright standing, positive = forward hinge (e.g. 45° in deadlift/squat).
     */
    fun calculateTorsoPitchDeg(
        xMg: Double,
        yMg: Double,
        zMg: Double,
        tarePitchDeg: Double = 0.0,
    ): Double {
        val planarNorm = sqrt(xMg * xMg + yMg * yMg)
        val rawAngleRad = atan2(-zMg, planarNorm)
        val rawAngleDeg = rawAngleRad * (180.0 / PI)
        return rawAngleDeg - tarePitchDeg
    }

    /**
     * Compute torso roll angle in degrees (lateral tilt / asymmetry).
     *
     * 0° = vertical/centered, positive/negative = tilt left/right.
     */
    fun calculateTorsoRollDeg(
        xMg: Double,
        yMg: Double,
        zMg: Double,
        tareRollDeg: Double = 0.0,
    ): Double {
        val planarNorm = sqrt(xMg * xMg + zMg * zMg)
        val rawAngleRad = atan2(yMg, planarNorm)
        val rawAngleDeg = rawAngleRad * (180.0 / PI)
        return rawAngleDeg - tareRollDeg
    }

    /**
     * Compute dynamic trunk acceleration (heave / jerk / momentum body-English).
     *
     * Isolates dynamic inertial movements from the static 1G (1000 mG) gravity vector.
     */
    fun calculateDynamicAccMg(
        xMg: Double,
        yMg: Double,
        zMg: Double,
    ): Int {
        val totalMagnitude = sqrt(xMg * xMg + yMg * yMg + zMg * zMg)
        return abs(totalMagnitude - GRAVITY_1G_MG).roundToInt()
    }

    /**
     * Compute all torso biomechanics metrics for a single 3D ACC sample.
     */
    fun computeTorsoBiomechanics(
        xMg: Double,
        yMg: Double,
        zMg: Double,
        tarePitchDeg: Double = 0.0,
        tareRollDeg: Double = 0.0,
    ): PolarTorsoBiomechanics {
        val pitch = calculateTorsoPitchDeg(xMg, yMg, zMg, tarePitchDeg)
        val roll = calculateTorsoRollDeg(xMg, yMg, zMg, tareRollDeg)
        val dynamicAcc = calculateDynamicAccMg(xMg, yMg, zMg)
        return PolarTorsoBiomechanics(
            pitchDeg = pitch,
            rollDeg = roll,
            dynamicAccMg = dynamicAcc,
            filteredXMg = xMg,
            filteredYMg = yMg,
            filteredZMg = zMg,
        )
    }

    /**
     * Compute Root Mean Square of Successive Differences (RMSSD) in milliseconds from R-R intervals.
     */
    fun calculateRmssdMs(rrs: List<Double>): Double? {
        if (rrs.size < 2) return null
        var sumSquares = 0.0
        for (i in 1 until rrs.size) {
            val diff = rrs[i] - rrs[i - 1]
            sumSquares += diff * diff
        }
        return sqrt(sumSquares / (rrs.size - 1))
    }
}

/**
 * Stateful exponential moving average (EMA) low-pass filter for 50 Hz Polar ACC stream.
 */
class PolarAccFilter(
    private val alpha: Double = PolarTelemetryUtils.DEFAULT_EMA_ALPHA,
    var tarePitchDeg: Double = 0.0,
    var tareRollDeg: Double = 0.0,
) {
    private var filteredX: Double? = null
    private var filteredY: Double? = null
    private var filteredZ: Double? = null

    fun reset() {
        filteredX = null
        filteredY = null
        filteredZ = null
    }

    /**
     * Tare current standing posture as 0° pitch and 0° roll.
     */
    fun tareCurrentPosture() {
        val fx = filteredX ?: return
        val fy = filteredY ?: return
        val fz = filteredZ ?: return
        tarePitchDeg = PolarTelemetryUtils.calculateTorsoPitchDeg(fx, fy, fz, tarePitchDeg = 0.0)
        tareRollDeg = PolarTelemetryUtils.calculateTorsoRollDeg(fx, fy, fz, tareRollDeg = 0.0)
    }

    fun processSample(xMg: Int, yMg: Int, zMg: Int): PolarTorsoBiomechanics {
        val prevX = filteredX
        val prevY = filteredY
        val prevZ = filteredZ

        val curX = if (prevX == null) xMg.toDouble() else alpha * xMg + (1.0 - alpha) * prevX
        val curY = if (prevY == null) yMg.toDouble() else alpha * yMg + (1.0 - alpha) * prevY
        val curZ = if (prevZ == null) zMg.toDouble() else alpha * zMg + (1.0 - alpha) * prevZ

        filteredX = curX
        filteredY = curY
        filteredZ = curZ

        val pitch = PolarTelemetryUtils.calculateTorsoPitchDeg(curX, curY, curZ, tarePitchDeg)
        val roll = PolarTelemetryUtils.calculateTorsoRollDeg(curX, curY, curZ, tareRollDeg)
        val dynamicAcc = PolarTelemetryUtils.calculateDynamicAccMg(curX, curY, curZ)

        return PolarTorsoBiomechanics(
            pitchDeg = pitch,
            rollDeg = roll,
            dynamicAccMg = dynamicAcc,
            filteredXMg = curX,
            filteredYMg = curY,
            filteredZMg = curZ,
        )
    }
}
