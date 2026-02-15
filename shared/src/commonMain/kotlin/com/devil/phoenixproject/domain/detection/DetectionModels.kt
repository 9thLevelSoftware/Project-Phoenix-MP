package com.devil.phoenixproject.domain.detection

/**
 * Velocity shape classification for rep analysis.
 * Based on velocity distribution across the concentric phase.
 */
enum class VelocityShape {
    /** First third of movement has highest velocity */
    EXPLOSIVE_START,
    /** Velocity roughly equal throughout movement */
    LINEAR,
    /** Last third has lowest velocity (most common) */
    DECELERATING
}

/**
 * Cable usage pattern detection.
 * Vitruvian has two independent cables (A and B).
 */
enum class CableUsage {
    /** Only left cable active (loadB < 1kg throughout) */
    SINGLE_LEFT,
    /** Only right cable active (loadA < 1kg throughout) */
    SINGLE_RIGHT,
    /** Both cables with symmetric load (symmetry 0.4-0.6) */
    DUAL_SYMMETRIC,
    /** Both cables with asymmetric load */
    DUAL_ASYMMETRIC
}

/**
 * Movement signature extracted from WorkoutMetric stream.
 * Captures the biomechanical fingerprint of an exercise.
 */
data class ExerciseSignature(
    /** Range of motion in mm (peak - valley averaged across reps) */
    val romMm: Float,
    /** Average rep duration in milliseconds */
    val durationMs: Long,
    /** Load symmetry ratio: loadA / (loadA + loadB), 0.5 = symmetric */
    val symmetryRatio: Float,
    /** Velocity distribution pattern during concentric phase */
    val velocityProfile: VelocityShape,
    /** Cable configuration detected */
    val cableConfig: CableUsage,
    /** Number of observations that contributed to this signature */
    val sampleCount: Int = 1,
    /** Aggregated confidence (0.0-1.0) */
    val confidence: Float = 0f
)

/**
 * Classification source indicating how exercise was identified.
 */
enum class ClassificationSource {
    /** Matched against user's exercise history */
    HISTORY_MATCH,
    /** Classified using rule-based decision tree */
    RULE_BASED
}

/**
 * Result of exercise classification.
 */
data class ExerciseClassification(
    /** Exercise ID if matched from history, null for rule-based */
    val exerciseId: String?,
    /** Human-readable exercise name */
    val exerciseName: String,
    /** Classification confidence (0.0-1.0) */
    val confidence: Float,
    /** Other likely exercises (alternates) */
    val alternates: List<String>,
    /** How this classification was made */
    val source: ClassificationSource
)
