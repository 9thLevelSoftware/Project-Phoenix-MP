package com.devil.phoenixproject.cv

import com.devil.phoenixproject.domain.model.JointAngleType
import com.devil.phoenixproject.domain.model.JointAngles
import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Converts MediaPipe pose landmarks to the [JointAngles] domain model.
 *
 * Uses world landmarks (3D meters) for angle calculation when available,
 * as they are more robust to camera angle than normalized 2D landmarks.
 *
 * Landmark indices follow MediaPipe Pose Landmarker documentation:
 * https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker
 */
object LandmarkAngleCalculator {

    // MediaPipe Pose Landmark indices
    // Upper body
    private const val LEFT_SHOULDER = 11
    private const val RIGHT_SHOULDER = 12
    private const val LEFT_ELBOW = 13
    private const val RIGHT_ELBOW = 14
    private const val LEFT_WRIST = 15
    private const val RIGHT_WRIST = 16

    // Lower body
    private const val LEFT_HIP = 23
    private const val RIGHT_HIP = 24
    private const val LEFT_KNEE = 25
    private const val RIGHT_KNEE = 26
    private const val LEFT_ANKLE = 27
    private const val RIGHT_ANKLE = 28

    // Key indices for confidence calculation
    private val KEY_LANDMARK_INDICES = listOf(
        LEFT_SHOULDER, RIGHT_SHOULDER,
        LEFT_HIP, RIGHT_HIP,
        LEFT_KNEE, RIGHT_KNEE
    )

    /**
     * Convert MediaPipe pose landmarks to domain [JointAngles].
     *
     * Prefers world landmarks (3D, camera-angle-robust) when available.
     * Falls back to normalized landmarks scaled to approximate 3D if world
     * landmarks are not provided.
     *
     * @param normalizedLandmarks 2D landmarks normalized to image dimensions
     * @param worldLandmarks 3D landmarks in meters (preferred for angles)
     * @param timestampMs Frame timestamp
     * @return [JointAngles] with all calculable joint angles and average confidence
     */
    fun convert(
        normalizedLandmarks: List<NormalizedLandmark>,
        worldLandmarks: List<Landmark>?,
        timestampMs: Long
    ): JointAngles {
        val angles = mutableMapOf<JointAngleType, Float>()

        // Prefer world landmarks for angle calculation (3D, camera-robust)
        // Fall back to normalized landmarks if world landmarks unavailable
        if (worldLandmarks != null && worldLandmarks.size >= 33) {
            calculateAnglesFromWorld(worldLandmarks, angles)
        } else {
            calculateAnglesFromNormalized(normalizedLandmarks, angles)
        }

        // Calculate overall confidence from key landmark visibility
        val avgConfidence = calculateConfidence(normalizedLandmarks)

        return JointAngles(
            angles = angles,
            timestamp = timestampMs,
            confidence = avgConfidence
        )
    }

    private fun calculateAnglesFromWorld(
        wl: List<Landmark>,
        angles: MutableMap<JointAngleType, Float>
    ) {
        // Knee angles: hip-knee-ankle
        angles[JointAngleType.LEFT_KNEE] = angleBetween3D(
            wl[LEFT_HIP], wl[LEFT_KNEE], wl[LEFT_ANKLE]
        )
        angles[JointAngleType.RIGHT_KNEE] = angleBetween3D(
            wl[RIGHT_HIP], wl[RIGHT_KNEE], wl[RIGHT_ANKLE]
        )

        // Hip angles: shoulder-hip-knee
        angles[JointAngleType.LEFT_HIP] = angleBetween3D(
            wl[LEFT_SHOULDER], wl[LEFT_HIP], wl[LEFT_KNEE]
        )
        angles[JointAngleType.RIGHT_HIP] = angleBetween3D(
            wl[RIGHT_SHOULDER], wl[RIGHT_HIP], wl[RIGHT_KNEE]
        )

        // Elbow angles: shoulder-elbow-wrist
        angles[JointAngleType.LEFT_ELBOW] = angleBetween3D(
            wl[LEFT_SHOULDER], wl[LEFT_ELBOW], wl[LEFT_WRIST]
        )
        angles[JointAngleType.RIGHT_ELBOW] = angleBetween3D(
            wl[RIGHT_SHOULDER], wl[RIGHT_ELBOW], wl[RIGHT_WRIST]
        )

        // Shoulder angles: elbow-shoulder-hip (flexion)
        angles[JointAngleType.LEFT_SHOULDER] = angleBetween3D(
            wl[LEFT_ELBOW], wl[LEFT_SHOULDER], wl[LEFT_HIP]
        )
        angles[JointAngleType.RIGHT_SHOULDER] = angleBetween3D(
            wl[RIGHT_ELBOW], wl[RIGHT_SHOULDER], wl[RIGHT_HIP]
        )

        // Trunk lean: angle from vertical
        angles[JointAngleType.TRUNK_LEAN] = calculateTrunkLean3D(wl)

        // Knee valgus: frontal plane deviation
        angles[JointAngleType.KNEE_VALGUS_LEFT] = calculateKneeValgus3D(
            wl[LEFT_HIP], wl[LEFT_KNEE], wl[LEFT_ANKLE]
        )
        angles[JointAngleType.KNEE_VALGUS_RIGHT] = calculateKneeValgus3D(
            wl[RIGHT_HIP], wl[RIGHT_KNEE], wl[RIGHT_ANKLE]
        )
    }

    private fun calculateAnglesFromNormalized(
        nl: List<NormalizedLandmark>,
        angles: MutableMap<JointAngleType, Float>
    ) {
        // Use 2D angle calculation (less accurate but fallback)
        angles[JointAngleType.LEFT_KNEE] = angleBetween2D(
            nl[LEFT_HIP], nl[LEFT_KNEE], nl[LEFT_ANKLE]
        )
        angles[JointAngleType.RIGHT_KNEE] = angleBetween2D(
            nl[RIGHT_HIP], nl[RIGHT_KNEE], nl[RIGHT_ANKLE]
        )

        angles[JointAngleType.LEFT_HIP] = angleBetween2D(
            nl[LEFT_SHOULDER], nl[LEFT_HIP], nl[LEFT_KNEE]
        )
        angles[JointAngleType.RIGHT_HIP] = angleBetween2D(
            nl[RIGHT_SHOULDER], nl[RIGHT_HIP], nl[RIGHT_KNEE]
        )

        angles[JointAngleType.LEFT_ELBOW] = angleBetween2D(
            nl[LEFT_SHOULDER], nl[LEFT_ELBOW], nl[LEFT_WRIST]
        )
        angles[JointAngleType.RIGHT_ELBOW] = angleBetween2D(
            nl[RIGHT_SHOULDER], nl[RIGHT_ELBOW], nl[RIGHT_WRIST]
        )

        angles[JointAngleType.LEFT_SHOULDER] = angleBetween2D(
            nl[LEFT_ELBOW], nl[LEFT_SHOULDER], nl[LEFT_HIP]
        )
        angles[JointAngleType.RIGHT_SHOULDER] = angleBetween2D(
            nl[RIGHT_ELBOW], nl[RIGHT_SHOULDER], nl[RIGHT_HIP]
        )

        angles[JointAngleType.TRUNK_LEAN] = calculateTrunkLean2D(nl)

        // Valgus is harder to detect in 2D - report 0 (neutral)
        angles[JointAngleType.KNEE_VALGUS_LEFT] = 0f
        angles[JointAngleType.KNEE_VALGUS_RIGHT] = 0f
    }

    /**
     * Calculate angle at midPoint formed by firstPoint-midPoint-lastPoint (3D).
     * Returns degrees in range [0, 180].
     */
    private fun angleBetween3D(first: Landmark, mid: Landmark, last: Landmark): Float {
        // Vectors from mid to first and mid to last
        val v1x = first.x() - mid.x()
        val v1y = first.y() - mid.y()
        val v1z = first.z() - mid.z()

        val v2x = last.x() - mid.x()
        val v2y = last.y() - mid.y()
        val v2z = last.z() - mid.z()

        // Dot product
        val dot = v1x * v2x + v1y * v2y + v1z * v2z

        // Magnitudes
        val mag1 = sqrt((v1x * v1x + v1y * v1y + v1z * v1z).toDouble())
        val mag2 = sqrt((v2x * v2x + v2y * v2y + v2z * v2z).toDouble())

        if (mag1 < 0.0001 || mag2 < 0.0001) return 0f

        // Clamp cosine to [-1, 1] to handle floating point errors
        val cosAngle = (dot / (mag1 * mag2)).coerceIn(-1.0, 1.0)

        return Math.toDegrees(kotlin.math.acos(cosAngle)).toFloat()
    }

    /**
     * Calculate angle at midPoint formed by firstPoint-midPoint-lastPoint (2D).
     * Returns degrees in range [0, 180].
     */
    private fun angleBetween2D(
        first: NormalizedLandmark,
        mid: NormalizedLandmark,
        last: NormalizedLandmark
    ): Float {
        val radians = atan2(
            (last.y() - mid.y()).toDouble(),
            (last.x() - mid.x()).toDouble()
        ) - atan2(
            (first.y() - mid.y()).toDouble(),
            (first.x() - mid.x()).toDouble()
        )
        var degrees = Math.toDegrees(radians).toFloat()
        degrees = abs(degrees)
        if (degrees > 180f) degrees = 360f - degrees
        return degrees
    }

    /**
     * Calculate trunk lean angle from vertical (3D world landmarks).
     * Positive = forward lean, negative = backward lean.
     */
    private fun calculateTrunkLean3D(wl: List<Landmark>): Float {
        // Midpoint of shoulders
        val shoulderMidX = (wl[LEFT_SHOULDER].x() + wl[RIGHT_SHOULDER].x()) / 2f
        val shoulderMidY = (wl[LEFT_SHOULDER].y() + wl[RIGHT_SHOULDER].y()) / 2f

        // Midpoint of hips
        val hipMidX = (wl[LEFT_HIP].x() + wl[RIGHT_HIP].x()) / 2f
        val hipMidY = (wl[LEFT_HIP].y() + wl[RIGHT_HIP].y()) / 2f

        // Trunk vector from hip to shoulder
        val dx = (shoulderMidX - hipMidX).toDouble()
        val dy = (hipMidY - shoulderMidY).toDouble() // Y inverted: up is negative

        // Angle from vertical (Y-axis)
        val radians = atan2(dx, dy)
        return Math.toDegrees(radians).toFloat()
    }

    /**
     * Calculate trunk lean angle from vertical (2D normalized landmarks).
     */
    private fun calculateTrunkLean2D(nl: List<NormalizedLandmark>): Float {
        val shoulderMidX = (nl[LEFT_SHOULDER].x() + nl[RIGHT_SHOULDER].x()) / 2f
        val shoulderMidY = (nl[LEFT_SHOULDER].y() + nl[RIGHT_SHOULDER].y()) / 2f

        val hipMidX = (nl[LEFT_HIP].x() + nl[RIGHT_HIP].x()) / 2f
        val hipMidY = (nl[LEFT_HIP].y() + nl[RIGHT_HIP].y()) / 2f

        val dx = (shoulderMidX - hipMidX).toDouble()
        val dy = (hipMidY - shoulderMidY).toDouble()

        val radians = atan2(dx, dy)
        return Math.toDegrees(radians).toFloat()
    }

    /**
     * Calculate knee valgus (inward collapse) angle (3D).
     * Uses frontal plane deviation of knee from hip-ankle line.
     */
    private fun calculateKneeValgus3D(hip: Landmark, knee: Landmark, ankle: Landmark): Float {
        // Expected knee X position (linear interpolation hip to ankle)
        val hipToAnkleX = ankle.x() - hip.x()
        val hipToAnkleY = ankle.y() - hip.y()
        val hipToKneeY = knee.y() - hip.y()

        // Progress along the line from hip to ankle
        val t = if (abs(hipToAnkleY) > 0.0001f) {
            hipToKneeY / hipToAnkleY
        } else {
            0.5f
        }

        val expectedKneeX = hip.x() + hipToAnkleX * t
        val deviation = knee.x() - expectedKneeX

        // Convert deviation to approximate degrees
        val legLength = sqrt(
            ((knee.y() - hip.y()) * (knee.y() - hip.y()) +
             (knee.x() - hip.x()) * (knee.x() - hip.x())).toDouble()
        ).toFloat()

        if (legLength < 0.0001f) return 0f

        return Math.toDegrees(atan2(deviation.toDouble(), legLength.toDouble())).toFloat()
            .let { abs(it) }
    }

    /**
     * Calculate average confidence from key landmark visibility scores.
     */
    private fun calculateConfidence(landmarks: List<NormalizedLandmark>): Float {
        if (landmarks.size < 33) return 0f

        val visibilities = KEY_LANDMARK_INDICES.map { index ->
            landmarks[index].visibility().orElse(0f)
        }

        return visibilities.average().toFloat()
    }
}
