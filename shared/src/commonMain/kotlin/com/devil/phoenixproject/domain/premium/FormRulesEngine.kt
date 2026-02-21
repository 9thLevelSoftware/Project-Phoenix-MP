package com.devil.phoenixproject.domain.premium

import com.devil.phoenixproject.domain.model.ExerciseFormType
import com.devil.phoenixproject.domain.model.FormAssessment
import com.devil.phoenixproject.domain.model.FormRule
import com.devil.phoenixproject.domain.model.FormViolation
import com.devil.phoenixproject.domain.model.FormViolationSeverity
import com.devil.phoenixproject.domain.model.JointAngleType
import com.devil.phoenixproject.domain.model.JointAngles

/**
 * Pure domain engine for exercise form evaluation.
 *
 * Evaluates joint angles against exercise-specific rules and produces
 * form violations with severity and corrective cues. The engine is
 * stateless -- each [evaluate] call is independent.
 *
 * Follows the same pattern as [SmartSuggestionsEngine] and [RepQualityScorer]:
 * pure domain logic in `domain/premium/`, models in `domain/model/`.
 *
 * **IMPORTANT (CV-08): This engine is advisory only.** It produces display
 * data for the UI. No code path exists to adjust weight, stop the machine,
 * or modify workout parameters based on form data. This file MUST NOT
 * import anything from `data/ble/`, `BleRepository`, `WorkoutCoordinator`,
 * or any weight/machine control interface.
 *
 * Supported exercises: Squat, Deadlift/RDL, Overhead Press, Curl, Row.
 * Threshold values are based on exercise science biomechanics literature
 * (see 14-RESEARCH.md for sources) and may need tuning for Vitruvian
 * cable-specific movement patterns.
 */
object FormRulesEngine {

    private val rulesByExercise: Map<ExerciseFormType, List<FormRule>> = mapOf(
        ExerciseFormType.SQUAT to squatRules(),
        ExerciseFormType.DEADLIFT_RDL to deadliftRdlRules(),
        ExerciseFormType.OVERHEAD_PRESS to overheadPressRules(),
        ExerciseFormType.CURL to curlRules(),
        ExerciseFormType.ROW to rowRules()
    )

    /**
     * Evaluate joint angles against exercise-specific form rules.
     *
     * For each rule associated with the given exercise type, checks whether
     * the corresponding angle in [angles] falls outside the acceptable range.
     * Rules for joint angles not present in [angles] are silently skipped
     * (the pose estimation may not have detected that landmark).
     *
     * Returns an empty assessment (no violations) when [angles] confidence
     * is below [minConfidence], preventing noisy evaluations on poor pose data.
     *
     * @param angles Current joint angle snapshot from pose estimation
     * @param exerciseType Which exercise is being performed
     * @param minConfidence Minimum pose confidence to evaluate (default 0.5).
     *   Frames below this threshold are skipped entirely.
     * @return Assessment with any detected violations
     */
    fun evaluate(
        angles: JointAngles,
        exerciseType: ExerciseFormType,
        minConfidence: Float = 0.5f
    ): FormAssessment {
        // Skip evaluation if pose confidence is too low
        if (angles.confidence < minConfidence) {
            return FormAssessment(
                violations = emptyList(),
                exerciseType = exerciseType,
                timestamp = angles.timestamp
            )
        }

        val rules = rulesByExercise[exerciseType] ?: emptyList()
        val violations = rules.mapNotNull { rule ->
            val actual = angles.angles[rule.jointAngle] ?: return@mapNotNull null
            if (actual < rule.minDegrees || actual > rule.maxDegrees) {
                FormViolation(
                    rule = rule,
                    actualDegrees = actual,
                    severity = rule.severity,
                    message = rule.violationMessage,
                    correctiveCue = rule.correctiveCue,
                    timestamp = angles.timestamp
                )
            } else null
        }

        return FormAssessment(
            violations = violations,
            exerciseType = exerciseType,
            timestamp = angles.timestamp
        )
    }

    /**
     * Calculate form score (0-100) from a series of frame assessments.
     *
     * Scoring starts at 100 and deducts points per violation weighted by severity:
     * - [FormViolationSeverity.INFO]: 1 point per violation per frame
     * - [FormViolationSeverity.WARNING]: 3 points per violation per frame
     * - [FormViolationSeverity.CRITICAL]: 5 points per violation per frame
     *
     * The average deduction per frame is scaled so that approximately 2 average
     * deductions per frame yields a score of 50.
     *
     * @param assessments List of frame assessments to score
     * @return Form score from 0 (terrible form) to 100 (perfect form)
     */
    fun calculateFormScore(assessments: List<FormAssessment>): Int {
        if (assessments.isEmpty()) return 100

        val totalFrames = assessments.size
        var totalDeductions = 0f

        for (assessment in assessments) {
            for (violation in assessment.violations) {
                totalDeductions += when (violation.severity) {
                    FormViolationSeverity.INFO -> 1f
                    FormViolationSeverity.WARNING -> 3f
                    FormViolationSeverity.CRITICAL -> 5f
                }
            }
        }

        val avgDeductionPerFrame = totalDeductions / totalFrames
        // Scale: ~2 avg deductions/frame = score of 50
        val score = (100f - avgDeductionPerFrame * 25f).coerceIn(0f, 100f)
        return score.toInt()
    }

    /**
     * Get the form rules defined for a specific exercise type.
     *
     * Useful for UI display of what the engine checks, or for
     * testing that rules are properly configured.
     *
     * @param exerciseType The exercise to get rules for
     * @return List of form rules (empty if exercise type is unknown)
     */
    fun getRulesForExercise(exerciseType: ExerciseFormType): List<FormRule> =
        rulesByExercise[exerciseType] ?: emptyList()

    // =========================================================================
    // Per-exercise rule sets
    //
    // Each function returns the form rules for a specific exercise.
    // Thresholds are based on exercise science biomechanics literature.
    // See 14-RESEARCH.md for detailed sources and rationale.
    // =========================================================================

    /**
     * Squat form rules.
     *
     * Sources: NASM Overhead Squat Assessment, IJSPT Biomechanical Review of the Squat.
     * - Knee depth: parallel squat should reach ~90-110 deg knee flexion
     * - Forward lean: >45 deg shifts load to lower back
     * - Knee valgus: >10 deg inward collapse risks knee injury
     */
    private fun squatRules(): List<FormRule> = listOf(
        // Knee flexion depth check (left)
        FormRule(
            jointAngle = JointAngleType.LEFT_KNEE,
            minDegrees = 0f,
            maxDegrees = 160f,
            severity = FormViolationSeverity.INFO,
            violationMessage = "Squat depth is shallow",
            correctiveCue = "Try to lower your hips until thighs are parallel to the floor"
        ),
        // Knee flexion depth check (right)
        FormRule(
            jointAngle = JointAngleType.RIGHT_KNEE,
            minDegrees = 0f,
            maxDegrees = 160f,
            severity = FormViolationSeverity.INFO,
            violationMessage = "Squat depth is shallow",
            correctiveCue = "Try to lower your hips until thighs are parallel to the floor"
        ),
        // Excessive forward lean
        FormRule(
            jointAngle = JointAngleType.TRUNK_LEAN,
            minDegrees = 0f,
            maxDegrees = 45f,
            severity = FormViolationSeverity.WARNING,
            violationMessage = "Excessive forward lean",
            correctiveCue = "Keep your chest up and core braced"
        ),
        // Knee valgus (left)
        FormRule(
            jointAngle = JointAngleType.KNEE_VALGUS_LEFT,
            minDegrees = 0f,
            maxDegrees = 10f,
            severity = FormViolationSeverity.CRITICAL,
            violationMessage = "Left knee collapsing inward",
            correctiveCue = "Push your knees out over your toes"
        ),
        // Knee valgus (right)
        FormRule(
            jointAngle = JointAngleType.KNEE_VALGUS_RIGHT,
            minDegrees = 0f,
            maxDegrees = 10f,
            severity = FormViolationSeverity.CRITICAL,
            violationMessage = "Right knee collapsing inward",
            correctiveCue = "Push your knees out over your toes"
        )
    )

    /**
     * Deadlift/RDL form rules.
     *
     * Sources: Stronger by Science deadlift guide, SimpliFaster RDL technique.
     * - Back rounding: excessive trunk lean with spine flexion
     * - Knee bend: RDL keeps knees at 15-30 deg bend (130-180 deg measured)
     */
    private fun deadliftRdlRules(): List<FormRule> = listOf(
        // Excessive back rounding / trunk lean
        FormRule(
            jointAngle = JointAngleType.TRUNK_LEAN,
            minDegrees = 0f,
            maxDegrees = 75f,
            severity = FormViolationSeverity.CRITICAL,
            violationMessage = "Excessive back rounding",
            correctiveCue = "Keep your chest proud and back flat -- hinge at hips, not spine"
        ),
        // Too much knee bend (left) -- turning into a squat
        FormRule(
            jointAngle = JointAngleType.LEFT_KNEE,
            minDegrees = 130f,
            maxDegrees = 180f,
            severity = FormViolationSeverity.WARNING,
            violationMessage = "Too much knee bend for deadlift/RDL",
            correctiveCue = "Keep a slight knee bend -- push your hips back instead of bending knees"
        ),
        // Too much knee bend (right)
        FormRule(
            jointAngle = JointAngleType.RIGHT_KNEE,
            minDegrees = 130f,
            maxDegrees = 180f,
            severity = FormViolationSeverity.WARNING,
            violationMessage = "Too much knee bend for deadlift/RDL",
            correctiveCue = "Keep a slight knee bend -- push your hips back instead of bending knees"
        )
    )

    /**
     * Overhead press form rules.
     *
     * Sources: ISSA Overhead Press guide, Muscle&Motion shoulder press guide.
     * - Elbow hyperextension at lockout
     * - Excessive backward lean during press
     */
    private fun overheadPressRules(): List<FormRule> = listOf(
        // Elbow hyperextension at lockout (left)
        FormRule(
            jointAngle = JointAngleType.LEFT_ELBOW,
            minDegrees = 0f,
            maxDegrees = 170f,
            severity = FormViolationSeverity.WARNING,
            violationMessage = "Elbow hyperextending at lockout",
            correctiveCue = "Keep a slight bend at lockout -- don't slam into full extension"
        ),
        // Elbow hyperextension at lockout (right)
        FormRule(
            jointAngle = JointAngleType.RIGHT_ELBOW,
            minDegrees = 0f,
            maxDegrees = 170f,
            severity = FormViolationSeverity.WARNING,
            violationMessage = "Elbow hyperextending at lockout",
            correctiveCue = "Keep a slight bend at lockout -- don't slam into full extension"
        ),
        // Excessive backward lean
        FormRule(
            jointAngle = JointAngleType.TRUNK_LEAN,
            minDegrees = -15f,
            maxDegrees = 15f,
            severity = FormViolationSeverity.CRITICAL,
            violationMessage = "Excessive backward lean during press",
            correctiveCue = "Brace your core and keep torso upright -- don't lean back to push the weight"
        )
    )

    /**
     * Curl form rules.
     *
     * Sources: ATHLEAN-X curl guide, ACE Exercise Library, Fitbod bicep curl guide.
     * - Shoulder drift forward during curl (cheating)
     * - Body swing / momentum
     */
    private fun curlRules(): List<FormRule> = listOf(
        // Shoulder drift (left)
        FormRule(
            jointAngle = JointAngleType.LEFT_SHOULDER,
            minDegrees = 0f,
            maxDegrees = 25f,
            severity = FormViolationSeverity.WARNING,
            violationMessage = "Left shoulder drifting forward",
            correctiveCue = "Keep elbows pinned to your sides -- curl with biceps only"
        ),
        // Shoulder drift (right)
        FormRule(
            jointAngle = JointAngleType.RIGHT_SHOULDER,
            minDegrees = 0f,
            maxDegrees = 25f,
            severity = FormViolationSeverity.WARNING,
            violationMessage = "Right shoulder drifting forward",
            correctiveCue = "Keep elbows pinned to your sides -- curl with biceps only"
        ),
        // Body swing / momentum
        FormRule(
            jointAngle = JointAngleType.TRUNK_LEAN,
            minDegrees = -10f,
            maxDegrees = 15f,
            severity = FormViolationSeverity.WARNING,
            violationMessage = "Body swinging during curl",
            correctiveCue = "Brace your core -- don't use momentum to lift the weight"
        )
    )

    /**
     * Row form rules.
     *
     * Sources: MasterClass cable row guide, Bodyrecomposition cable row technique.
     * - Excessive forward lean during row
     * - Incomplete pull (elbows not pulling back enough)
     */
    private fun rowRules(): List<FormRule> = listOf(
        // Excessive forward lean
        FormRule(
            jointAngle = JointAngleType.TRUNK_LEAN,
            minDegrees = -15f,
            maxDegrees = 30f,
            severity = FormViolationSeverity.WARNING,
            violationMessage = "Excessive forward lean during row",
            correctiveCue = "Keep your torso upright -- pull with your back, not momentum"
        ),
        // Incomplete pull (left elbow)
        FormRule(
            jointAngle = JointAngleType.LEFT_ELBOW,
            minDegrees = 30f,
            maxDegrees = 160f,
            severity = FormViolationSeverity.INFO,
            violationMessage = "Incomplete row -- elbows not pulling back enough",
            correctiveCue = "Pull elbows behind your body and squeeze shoulder blades together"
        ),
        // Incomplete pull (right elbow)
        FormRule(
            jointAngle = JointAngleType.RIGHT_ELBOW,
            minDegrees = 30f,
            maxDegrees = 160f,
            severity = FormViolationSeverity.INFO,
            violationMessage = "Incomplete row -- elbows not pulling back enough",
            correctiveCue = "Pull elbows behind your body and squeeze shoulder blades together"
        )
    )
}
