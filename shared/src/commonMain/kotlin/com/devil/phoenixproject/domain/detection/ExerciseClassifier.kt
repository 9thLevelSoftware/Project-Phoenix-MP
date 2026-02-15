package com.devil.phoenixproject.domain.detection

import kotlin.math.abs
import kotlin.math.max

/**
 * Classifies exercises based on movement signatures.
 *
 * Uses two approaches:
 * 1. History matching: Compare against user's previous exercise signatures
 * 2. Rule-based: Use decision tree based on ROM, duration, symmetry, etc.
 *
 * History matching takes precedence when confidence is > 0.85.
 */
class ExerciseClassifier {

    companion object {
        /** Minimum similarity for history match to be accepted */
        private const val HISTORY_MATCH_THRESHOLD = 0.85f

        /** Component weights for similarity calculation */
        private const val ROM_WEIGHT = 0.40f
        private const val DURATION_WEIGHT = 0.20f
        private const val SYMMETRY_WEIGHT = 0.25f
        private const val SHAPE_WEIGHT = 0.15f

        /** ROM thresholds in mm */
        private const val SHORT_ROM_THRESHOLD = 300f
        private const val SHORT_ROM_DUAL_THRESHOLD = 400f
        private const val MEDIUM_ROM_LOWER = 400f
        private const val MEDIUM_ROM_UPPER = 600f
        private const val LONG_ROM_THRESHOLD = 600f
        private const val SINGLE_MEDIUM_ROM_LOWER = 300f
        private const val SINGLE_MEDIUM_ROM_UPPER = 500f

        /** Duration threshold in ms */
        private const val FAST_DURATION_THRESHOLD = 3000L
    }

    /**
     * Classify an exercise from its signature.
     *
     * @param signature The extracted exercise signature
     * @param history Map of exerciseId to stored signature from user's history
     * @return Classification result with confidence and source
     */
    fun classify(
        signature: ExerciseSignature,
        history: Map<String, ExerciseSignature>
    ): ExerciseClassification {
        // First, try history matching
        if (history.isNotEmpty()) {
            val historyMatch = findBestHistoryMatch(signature, history)
            if (historyMatch != null && historyMatch.second >= HISTORY_MATCH_THRESHOLD) {
                return ExerciseClassification(
                    exerciseId = historyMatch.first,
                    exerciseName = historyMatch.first,
                    confidence = historyMatch.second,
                    alternates = emptyList(),
                    source = ClassificationSource.HISTORY_MATCH
                )
            }
        }

        // Fall back to rule-based classification
        return classifyByRules(signature)
    }

    /**
     * Find the best matching exercise from history.
     * @return Pair of exerciseId and similarity, or null if history is empty
     */
    private fun findBestHistoryMatch(
        signature: ExerciseSignature,
        history: Map<String, ExerciseSignature>
    ): Pair<String, Float>? {
        if (history.isEmpty()) return null

        return history
            .map { (id, stored) -> id to computeSimilarity(signature, stored) }
            .maxByOrNull { it.second }
    }

    /**
     * Classify using the rule-based decision tree.
     * Based on ROM, duration, symmetry, and velocity profile.
     */
    private fun classifyByRules(signature: ExerciseSignature): ExerciseClassification {
        val rom = signature.romMm
        val duration = signature.durationMs
        val isExplosive = signature.velocityProfile == VelocityShape.EXPLOSIVE_START
        val isSingleCable = signature.cableConfig == CableUsage.SINGLE_LEFT ||
                signature.cableConfig == CableUsage.SINGLE_RIGHT
        val isDualSymmetric = signature.cableConfig == CableUsage.DUAL_SYMMETRIC

        // Single cable exercises
        if (isSingleCable) {
            return when {
                rom < SHORT_ROM_THRESHOLD -> ExerciseClassification(
                    exerciseId = null,
                    exerciseName = "Bicep Curl",
                    confidence = 0.6f,
                    alternates = listOf("Hammer Curl", "Concentration Curl"),
                    source = ClassificationSource.RULE_BASED
                )
                rom in SINGLE_MEDIUM_ROM_LOWER..SINGLE_MEDIUM_ROM_UPPER -> ExerciseClassification(
                    exerciseId = null,
                    exerciseName = "Lateral Raise",
                    confidence = 0.5f,
                    alternates = listOf("Front Raise", "Cable Fly"),
                    source = ClassificationSource.RULE_BASED
                )
                rom > SINGLE_MEDIUM_ROM_UPPER -> ExerciseClassification(
                    exerciseId = null,
                    exerciseName = "Single Arm Row",
                    confidence = 0.5f,
                    alternates = listOf("Single Arm Pulldown"),
                    source = ClassificationSource.RULE_BASED
                )
                else -> unknownClassification()
            }
        }

        // Dual symmetric exercises
        if (isDualSymmetric) {
            return when {
                // Short ROM + Fast + Explosive -> Chest Press
                rom < SHORT_ROM_DUAL_THRESHOLD && duration < FAST_DURATION_THRESHOLD && isExplosive ->
                    ExerciseClassification(
                        exerciseId = null,
                        exerciseName = "Chest Press",
                        confidence = 0.7f,
                        alternates = listOf("Incline Press", "Decline Press"),
                        source = ClassificationSource.RULE_BASED
                    )

                // Short ROM + Fast + Not Explosive -> Shoulder Press
                rom < SHORT_ROM_DUAL_THRESHOLD && duration < FAST_DURATION_THRESHOLD && !isExplosive ->
                    ExerciseClassification(
                        exerciseId = null,
                        exerciseName = "Shoulder Press",
                        confidence = 0.65f,
                        alternates = listOf("Military Press", "Arnold Press"),
                        source = ClassificationSource.RULE_BASED
                    )

                // Short ROM + Slow -> Bicep Curl (dual cable version)
                rom < SHORT_ROM_THRESHOLD && duration >= FAST_DURATION_THRESHOLD ->
                    ExerciseClassification(
                        exerciseId = null,
                        exerciseName = "Bicep Curl",
                        confidence = 0.6f,
                        alternates = listOf("Hammer Curl"),
                        source = ClassificationSource.RULE_BASED
                    )

                // Medium ROM -> Bent-Over Row
                rom in MEDIUM_ROM_LOWER..MEDIUM_ROM_UPPER ->
                    ExerciseClassification(
                        exerciseId = null,
                        exerciseName = "Bent-Over Row",
                        confidence = 0.6f,
                        alternates = listOf("Seated Row", "Upright Row"),
                        source = ClassificationSource.RULE_BASED
                    )

                // Long ROM -> Squat
                rom > LONG_ROM_THRESHOLD ->
                    ExerciseClassification(
                        exerciseId = null,
                        exerciseName = "Squat",
                        confidence = 0.6f,
                        alternates = listOf("Deadlift", "Romanian Deadlift"),
                        source = ClassificationSource.RULE_BASED
                    )

                else -> unknownClassification()
            }
        }

        // Dual asymmetric or other - return unknown with low confidence
        return unknownClassification()
    }

    /**
     * Return an unknown classification for ambiguous signatures.
     */
    private fun unknownClassification(): ExerciseClassification {
        return ExerciseClassification(
            exerciseId = null,
            exerciseName = "Unknown",
            confidence = 0.3f,
            alternates = emptyList(),
            source = ClassificationSource.RULE_BASED
        )
    }

    /**
     * Compute similarity between two signatures using weighted components.
     *
     * Weights: ROM 40%, duration 20%, symmetry 25%, shape 15%
     *
     * @return Similarity score from 0.0 to 1.0
     */
    fun computeSimilarity(a: ExerciseSignature, b: ExerciseSignature): Float {
        // ROM similarity: 1 - |a.rom - b.rom| / max(a.rom, b.rom)
        val maxRom = max(a.romMm, b.romMm)
        val romSimilarity = if (maxRom > 0) {
            1f - abs(a.romMm - b.romMm) / maxRom
        } else {
            1f
        }

        // Duration similarity: 1 - |a.duration - b.duration| / max(a.duration, b.duration)
        val maxDuration = max(a.durationMs, b.durationMs)
        val durationSimilarity = if (maxDuration > 0) {
            1f - abs(a.durationMs - b.durationMs).toFloat() / maxDuration
        } else {
            1f
        }

        // Symmetry similarity: 1 - |a.symmetry - b.symmetry|
        val symmetrySimilarity = 1f - abs(a.symmetryRatio - b.symmetryRatio)

        // Shape similarity: 1.0 if same, 0.0 if different
        val shapeSimilarity = if (a.velocityProfile == b.velocityProfile) 1f else 0f

        // Weighted sum
        return ROM_WEIGHT * romSimilarity +
                DURATION_WEIGHT * durationSimilarity +
                SYMMETRY_WEIGHT * symmetrySimilarity +
                SHAPE_WEIGHT * shapeSimilarity
    }

    /**
     * Evolve a stored signature with a new observation using EMA.
     *
     * EMA formula: new_value = existing * (1 - alpha) + observation * alpha
     *
     * @param existing The currently stored signature
     * @param newObservation The new signature from recent workout
     * @param alpha EMA smoothing factor (default 0.3)
     * @return Updated signature combining old and new data
     */
    fun evolveSignature(
        existing: ExerciseSignature,
        newObservation: ExerciseSignature,
        alpha: Float = 0.3f
    ): ExerciseSignature {
        val beta = 1f - alpha

        return ExerciseSignature(
            // EMA for continuous values
            romMm = existing.romMm * beta + newObservation.romMm * alpha,
            durationMs = (existing.durationMs * beta + newObservation.durationMs * alpha).toLong(),
            symmetryRatio = existing.symmetryRatio * beta + newObservation.symmetryRatio * alpha,

            // Use latest observation for categorical values
            velocityProfile = newObservation.velocityProfile,
            cableConfig = newObservation.cableConfig,

            // Increment sample count
            sampleCount = existing.sampleCount + 1,

            // Confidence could be updated based on sample count, but keeping it simple for now
            confidence = existing.confidence
        )
    }
}
