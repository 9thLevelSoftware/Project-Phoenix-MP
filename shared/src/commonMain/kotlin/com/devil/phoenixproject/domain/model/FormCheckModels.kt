/**
 * Form check domain models for exercise form evaluation.
 *
 * These models define the data structures for the CV form check feature:
 * - Joint angle input from pose estimation (Phase 15: MediaPipe integration)
 * - Form rule definitions with thresholds and corrective cues
 * - Violation and assessment output for UI display (Phase 16: UI + persistence)
 *
 * Consumed by [com.devil.phoenixproject.domain.premium.FormRulesEngine] (Phase 14).
 *
 * IMPORTANT (CV-08): All output types in this file contain ONLY display data
 * (strings, enums, numbers). No fields reference weight adjustment, machine
 * control, workout modification, or BLE operations.
 */
package com.devil.phoenixproject.domain.model

/**
 * Body joint angles evaluable from MediaPipe pose landmarks.
 *
 * Each value maps to an angle computable from a triplet of pose landmarks.
 * Angles are reported in degrees (0-180 for joint flexion, negative values
 * possible for lean directions).
 */
enum class JointAngleType {
    LEFT_KNEE,
    RIGHT_KNEE,
    LEFT_HIP,
    RIGHT_HIP,
    LEFT_ELBOW,
    RIGHT_ELBOW,
    LEFT_SHOULDER,
    RIGHT_SHOULDER,
    /** Angle of torso from vertical (degrees). Positive = forward lean. */
    TRUNK_LEAN,
    /** Left knee inward collapse angle (degrees from neutral alignment). */
    KNEE_VALGUS_LEFT,
    /** Right knee inward collapse angle (degrees from neutral alignment). */
    KNEE_VALGUS_RIGHT
}

/**
 * Severity level of a detected form violation.
 *
 * Used to prioritize which violations to display and how urgently
 * to present corrective cues to the user.
 */
enum class FormViolationSeverity {
    /** Minor deviation, informational only. */
    INFO,
    /** Significant deviation that should be corrected. */
    WARNING,
    /** Major form breakdown with potential injury risk. */
    CRITICAL
}

/**
 * Supported exercise types for form checking.
 *
 * Each exercise type maps to a distinct set of form rules with
 * exercise-specific joint angle thresholds and corrective cues.
 */
enum class ExerciseFormType {
    SQUAT,
    DEADLIFT_RDL,
    OVERHEAD_PRESS,
    CURL,
    ROW;

    companion object {
        /**
         * Maps an exercise name to its form type using keyword matching.
         * Returns null for exercises without form rules (form check camera
         * still works but no exercise-specific evaluation occurs).
         *
         * Keyword matching is case-insensitive and checks for substring presence.
         * Order matters: more specific patterns (e.g., "deadlift") are checked
         * before broader ones to avoid false matches.
         */
        fun fromExerciseName(name: String?): ExerciseFormType? {
            if (name == null) return null
            val lower = name.lowercase()
            return when {
                lower.contains("squat") -> SQUAT
                lower.contains("deadlift") || lower.contains("rdl") || lower.contains("romanian") -> DEADLIFT_RDL
                lower.contains("overhead") || (lower.contains("press") && !lower.contains("bench") && !lower.contains("chest")) -> OVERHEAD_PRESS
                lower.contains("curl") -> CURL
                lower.contains("row") -> ROW
                else -> null
            }
        }
    }
}

/**
 * Single-frame joint angle snapshot from pose estimation.
 *
 * Produced by the pose estimation layer (Phase 15) and consumed by
 * [com.devil.phoenixproject.domain.premium.FormRulesEngine.evaluate].
 *
 * @property angles Map of joint angle types to their measured values in degrees
 * @property timestamp Timestamp of the frame capture (ms since epoch)
 * @property confidence Overall pose estimation confidence (0.0 to 1.0).
 *   The engine skips evaluation when confidence is below its threshold.
 */
data class JointAngles(
    val angles: Map<JointAngleType, Float>,
    val timestamp: Long,
    val confidence: Float = 1.0f
)

/**
 * Threshold-based form rule definition.
 *
 * Defines an acceptable range for a specific joint angle during an exercise.
 * If the measured angle falls outside [minDegrees, maxDegrees], a violation
 * is generated with the specified severity and corrective cue.
 *
 * @property jointAngle Which joint angle this rule evaluates
 * @property minDegrees Minimum acceptable angle in degrees (inclusive)
 * @property maxDegrees Maximum acceptable angle in degrees (inclusive)
 * @property severity How severe a violation of this rule is
 * @property violationMessage Human-readable description of the form issue
 * @property correctiveCue Actionable coaching cue to fix the form issue
 */
data class FormRule(
    val jointAngle: JointAngleType,
    val minDegrees: Float,
    val maxDegrees: Float,
    val severity: FormViolationSeverity,
    val violationMessage: String,
    val correctiveCue: String
)

/**
 * A detected form violation (display data only).
 *
 * Represents a single instance where a joint angle exceeded the acceptable
 * range defined by a [FormRule]. Contains all information needed for UI
 * display of the violation.
 *
 * IMPORTANT (CV-08): This class contains ONLY display data -- no fields
 * for weight adjustment, machine control, or workout modification.
 *
 * @property rule The form rule that was violated
 * @property actualDegrees The measured angle that triggered the violation
 * @property severity Violation severity (copied from rule for convenience)
 * @property message Human-readable violation description
 * @property correctiveCue Actionable coaching cue for the user
 * @property timestamp Timestamp of the frame where the violation occurred
 */
data class FormViolation(
    val rule: FormRule,
    val actualDegrees: Float,
    val severity: FormViolationSeverity,
    val message: String,
    val correctiveCue: String,
    val timestamp: Long
)

/**
 * Result of evaluating form for a single frame.
 *
 * Contains all violations detected in a single pose estimation frame
 * for a specific exercise type.
 *
 * @property violations List of detected form violations (empty if form is good)
 * @property exerciseType The exercise type that was evaluated
 * @property timestamp Timestamp of the evaluated frame
 */
data class FormAssessment(
    val violations: List<FormViolation>,
    val exerciseType: ExerciseFormType,
    val timestamp: Long
)
