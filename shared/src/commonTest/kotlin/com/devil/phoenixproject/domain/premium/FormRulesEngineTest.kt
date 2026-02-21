package com.devil.phoenixproject.domain.premium

import com.devil.phoenixproject.domain.model.ExerciseFormType
import com.devil.phoenixproject.domain.model.FormAssessment
import com.devil.phoenixproject.domain.model.FormViolationSeverity
import com.devil.phoenixproject.domain.model.JointAngleType
import com.devil.phoenixproject.domain.model.JointAngles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for FormRulesEngine covering all 5 exercise rule sets,
 * confidence gating, form score calculation, rule coverage,
 * and CV-08 architectural compliance.
 */
class FormRulesEngineTest {

    // ========== Helper Functions ==========

    /**
     * Create a JointAngles snapshot from vararg pairs of (JointAngleType, Float).
     */
    private fun createAngles(
        vararg pairs: Pair<JointAngleType, Float>,
        confidence: Float = 1.0f,
        timestamp: Long = 0L
    ): JointAngles = JointAngles(
        angles = mapOf(*pairs),
        timestamp = timestamp,
        confidence = confidence
    )

    /**
     * Create a FormAssessment with the given violations count for form score testing.
     */
    private fun assessmentWithViolations(
        exerciseType: ExerciseFormType,
        angles: JointAngles
    ): FormAssessment = FormRulesEngine.evaluate(angles, exerciseType)

    // ========== Squat Tests ==========

    @Test
    fun `squat with good form returns no violations`() {
        val angles = createAngles(
            JointAngleType.LEFT_KNEE to 95f,
            JointAngleType.RIGHT_KNEE to 95f,
            JointAngleType.TRUNK_LEAN to 20f,
            JointAngleType.KNEE_VALGUS_LEFT to 3f,
            JointAngleType.KNEE_VALGUS_RIGHT to 3f
        )

        val result = FormRulesEngine.evaluate(angles, ExerciseFormType.SQUAT)

        assertEquals(0, result.violations.size, "Good squat form should produce no violations")
        assertEquals(ExerciseFormType.SQUAT, result.exerciseType)
    }

    @Test
    fun `squat with shallow depth triggers INFO violation`() {
        val angles = createAngles(
            JointAngleType.LEFT_KNEE to 165f,
            JointAngleType.RIGHT_KNEE to 165f,
            JointAngleType.TRUNK_LEAN to 20f,
            JointAngleType.KNEE_VALGUS_LEFT to 3f,
            JointAngleType.KNEE_VALGUS_RIGHT to 3f
        )

        val result = FormRulesEngine.evaluate(angles, ExerciseFormType.SQUAT)

        val depthViolations = result.violations.filter {
            it.message.contains("shallow", ignoreCase = true)
        }
        assertTrue(depthViolations.isNotEmpty(), "Shallow squat (165 deg knee) should trigger depth violation")
        assertTrue(
            depthViolations.all { it.severity == FormViolationSeverity.INFO },
            "Shallow depth should be INFO severity"
        )
        assertTrue(
            depthViolations.any { it.correctiveCue.contains("parallel", ignoreCase = true) },
            "Depth violation should have a parallel-to-floor cue"
        )
    }

    @Test
    fun `squat with excessive forward lean triggers WARNING violation`() {
        val angles = createAngles(
            JointAngleType.LEFT_KNEE to 95f,
            JointAngleType.RIGHT_KNEE to 95f,
            JointAngleType.TRUNK_LEAN to 50f,
            JointAngleType.KNEE_VALGUS_LEFT to 3f,
            JointAngleType.KNEE_VALGUS_RIGHT to 3f
        )

        val result = FormRulesEngine.evaluate(angles, ExerciseFormType.SQUAT)

        val leanViolations = result.violations.filter {
            it.message.contains("lean", ignoreCase = true)
        }
        assertTrue(leanViolations.isNotEmpty(), "50 deg forward lean should trigger violation")
        assertTrue(
            leanViolations.all { it.severity == FormViolationSeverity.WARNING },
            "Forward lean should be WARNING severity"
        )
        assertTrue(
            leanViolations.any { it.correctiveCue.contains("chest", ignoreCase = true) },
            "Forward lean violation should have chest-up cue"
        )
    }

    @Test
    fun `squat with knee valgus triggers CRITICAL violation`() {
        val angles = createAngles(
            JointAngleType.LEFT_KNEE to 95f,
            JointAngleType.RIGHT_KNEE to 95f,
            JointAngleType.TRUNK_LEAN to 20f,
            JointAngleType.KNEE_VALGUS_LEFT to 15f,
            JointAngleType.KNEE_VALGUS_RIGHT to 15f
        )

        val result = FormRulesEngine.evaluate(angles, ExerciseFormType.SQUAT)

        val valgusViolations = result.violations.filter {
            it.message.contains("knee", ignoreCase = true) && it.message.contains("collapsing", ignoreCase = true)
        }
        assertTrue(valgusViolations.isNotEmpty(), "15 deg knee valgus should trigger violation")
        assertTrue(
            valgusViolations.all { it.severity == FormViolationSeverity.CRITICAL },
            "Knee valgus should be CRITICAL severity"
        )
        assertTrue(
            valgusViolations.any { it.correctiveCue.contains("knees", ignoreCase = true) },
            "Valgus violation should have knees-out cue"
        )
    }

    @Test
    fun `squat with multiple issues returns all violations`() {
        val angles = createAngles(
            JointAngleType.LEFT_KNEE to 165f,   // shallow
            JointAngleType.RIGHT_KNEE to 165f,  // shallow
            JointAngleType.TRUNK_LEAN to 50f,   // forward lean
            JointAngleType.KNEE_VALGUS_LEFT to 15f,  // valgus
            JointAngleType.KNEE_VALGUS_RIGHT to 15f  // valgus
        )

        val result = FormRulesEngine.evaluate(angles, ExerciseFormType.SQUAT)

        // Should have violations from all three categories: depth, lean, valgus
        assertTrue(result.violations.size >= 3,
            "Multiple issues should produce multiple violations, got ${result.violations.size}")

        val severities = result.violations.map { it.severity }.toSet()
        assertTrue(FormViolationSeverity.INFO in severities, "Should include INFO (depth)")
        assertTrue(FormViolationSeverity.WARNING in severities, "Should include WARNING (lean)")
        assertTrue(FormViolationSeverity.CRITICAL in severities, "Should include CRITICAL (valgus)")
    }

    // ========== Deadlift/RDL Tests ==========

    @Test
    fun `deadlift with good form returns no violations`() {
        val angles = createAngles(
            JointAngleType.TRUNK_LEAN to 40f,
            JointAngleType.LEFT_KNEE to 155f,
            JointAngleType.RIGHT_KNEE to 155f
        )

        val result = FormRulesEngine.evaluate(angles, ExerciseFormType.DEADLIFT_RDL)

        assertEquals(0, result.violations.size, "Good deadlift form should produce no violations")
    }

    @Test
    fun `deadlift with back rounding triggers CRITICAL violation`() {
        val angles = createAngles(
            JointAngleType.TRUNK_LEAN to 80f,
            JointAngleType.LEFT_KNEE to 155f,
            JointAngleType.RIGHT_KNEE to 155f
        )

        val result = FormRulesEngine.evaluate(angles, ExerciseFormType.DEADLIFT_RDL)

        val backViolations = result.violations.filter {
            it.message.contains("back", ignoreCase = true) || it.message.contains("rounding", ignoreCase = true)
        }
        assertTrue(backViolations.isNotEmpty(), "80 deg trunk lean should trigger back rounding violation")
        assertTrue(
            backViolations.all { it.severity == FormViolationSeverity.CRITICAL },
            "Back rounding should be CRITICAL severity"
        )
    }

    @Test
    fun `deadlift with excessive knee bend triggers WARNING violation`() {
        val angles = createAngles(
            JointAngleType.TRUNK_LEAN to 40f,
            JointAngleType.LEFT_KNEE to 120f,
            JointAngleType.RIGHT_KNEE to 120f
        )

        val result = FormRulesEngine.evaluate(angles, ExerciseFormType.DEADLIFT_RDL)

        val kneeViolations = result.violations.filter {
            it.message.contains("knee", ignoreCase = true)
        }
        assertTrue(kneeViolations.isNotEmpty(), "120 deg knee bend should trigger excessive knee bend violation")
        assertTrue(
            kneeViolations.all { it.severity == FormViolationSeverity.WARNING },
            "Excessive knee bend should be WARNING severity"
        )
    }

    // ========== Overhead Press Tests ==========

    @Test
    fun `overhead press with good form returns no violations`() {
        val angles = createAngles(
            JointAngleType.LEFT_ELBOW to 120f,
            JointAngleType.RIGHT_ELBOW to 120f,
            JointAngleType.TRUNK_LEAN to 5f
        )

        val result = FormRulesEngine.evaluate(angles, ExerciseFormType.OVERHEAD_PRESS)

        assertEquals(0, result.violations.size, "Good overhead press form should produce no violations")
    }

    @Test
    fun `overhead press with elbow hyperextension triggers WARNING violation`() {
        val angles = createAngles(
            JointAngleType.LEFT_ELBOW to 175f,
            JointAngleType.RIGHT_ELBOW to 175f,
            JointAngleType.TRUNK_LEAN to 5f
        )

        val result = FormRulesEngine.evaluate(angles, ExerciseFormType.OVERHEAD_PRESS)

        val elbowViolations = result.violations.filter {
            it.message.contains("elbow", ignoreCase = true) || it.message.contains("hyperextend", ignoreCase = true)
        }
        assertTrue(elbowViolations.isNotEmpty(), "175 deg elbow should trigger hyperextension violation")
        assertTrue(
            elbowViolations.all { it.severity == FormViolationSeverity.WARNING },
            "Elbow hyperextension should be WARNING severity"
        )
    }

    @Test
    fun `overhead press with backward lean triggers CRITICAL violation`() {
        val angles = createAngles(
            JointAngleType.LEFT_ELBOW to 120f,
            JointAngleType.RIGHT_ELBOW to 120f,
            JointAngleType.TRUNK_LEAN to 20f
        )

        val result = FormRulesEngine.evaluate(angles, ExerciseFormType.OVERHEAD_PRESS)

        val leanViolations = result.violations.filter {
            it.message.contains("lean", ignoreCase = true) || it.message.contains("backward", ignoreCase = true)
        }
        assertTrue(leanViolations.isNotEmpty(), "20 deg backward lean should trigger violation")
        assertTrue(
            leanViolations.all { it.severity == FormViolationSeverity.CRITICAL },
            "Backward lean during press should be CRITICAL severity"
        )
    }

    // ========== Curl Tests ==========

    @Test
    fun `curl with good form returns no violations`() {
        val angles = createAngles(
            JointAngleType.LEFT_SHOULDER to 10f,
            JointAngleType.RIGHT_SHOULDER to 10f,
            JointAngleType.TRUNK_LEAN to 5f
        )

        val result = FormRulesEngine.evaluate(angles, ExerciseFormType.CURL)

        assertEquals(0, result.violations.size, "Good curl form should produce no violations")
    }

    @Test
    fun `curl with shoulder drift triggers WARNING violation`() {
        val angles = createAngles(
            JointAngleType.LEFT_SHOULDER to 30f,
            JointAngleType.RIGHT_SHOULDER to 30f,
            JointAngleType.TRUNK_LEAN to 5f
        )

        val result = FormRulesEngine.evaluate(angles, ExerciseFormType.CURL)

        val shoulderViolations = result.violations.filter {
            it.message.contains("shoulder", ignoreCase = true)
        }
        assertTrue(shoulderViolations.isNotEmpty(), "30 deg shoulder drift should trigger violation")
        assertTrue(
            shoulderViolations.all { it.severity == FormViolationSeverity.WARNING },
            "Shoulder drift should be WARNING severity"
        )
    }

    @Test
    fun `curl with body swing triggers WARNING violation`() {
        val angles = createAngles(
            JointAngleType.LEFT_SHOULDER to 10f,
            JointAngleType.RIGHT_SHOULDER to 10f,
            JointAngleType.TRUNK_LEAN to 20f
        )

        val result = FormRulesEngine.evaluate(angles, ExerciseFormType.CURL)

        val swingViolations = result.violations.filter {
            it.message.contains("swing", ignoreCase = true) || it.message.contains("body", ignoreCase = true)
        }
        assertTrue(swingViolations.isNotEmpty(), "20 deg trunk lean during curl should trigger body swing violation")
        assertTrue(
            swingViolations.all { it.severity == FormViolationSeverity.WARNING },
            "Body swing should be WARNING severity"
        )
    }

    // ========== Row Tests ==========

    @Test
    fun `row with good form returns no violations`() {
        val angles = createAngles(
            JointAngleType.TRUNK_LEAN to 10f,
            JointAngleType.LEFT_ELBOW to 70f,
            JointAngleType.RIGHT_ELBOW to 70f
        )

        val result = FormRulesEngine.evaluate(angles, ExerciseFormType.ROW)

        assertEquals(0, result.violations.size, "Good row form should produce no violations")
    }

    @Test
    fun `row with excessive forward lean triggers WARNING violation`() {
        val angles = createAngles(
            JointAngleType.TRUNK_LEAN to 35f,
            JointAngleType.LEFT_ELBOW to 70f,
            JointAngleType.RIGHT_ELBOW to 70f
        )

        val result = FormRulesEngine.evaluate(angles, ExerciseFormType.ROW)

        val leanViolations = result.violations.filter {
            it.message.contains("lean", ignoreCase = true)
        }
        assertTrue(leanViolations.isNotEmpty(), "35 deg forward lean during row should trigger violation")
        assertTrue(
            leanViolations.all { it.severity == FormViolationSeverity.WARNING },
            "Forward lean during row should be WARNING severity"
        )
    }

    @Test
    fun `row with incomplete pull triggers INFO violation`() {
        val angles = createAngles(
            JointAngleType.TRUNK_LEAN to 10f,
            JointAngleType.LEFT_ELBOW to 165f,
            JointAngleType.RIGHT_ELBOW to 165f
        )

        val result = FormRulesEngine.evaluate(angles, ExerciseFormType.ROW)

        val pullViolations = result.violations.filter {
            it.message.contains("incomplete", ignoreCase = true) || it.message.contains("pull", ignoreCase = true)
        }
        assertTrue(pullViolations.isNotEmpty(), "165 deg elbow (arms nearly straight) should trigger incomplete pull violation")
        assertTrue(
            pullViolations.all { it.severity == FormViolationSeverity.INFO },
            "Incomplete pull should be INFO severity"
        )
    }

    // ========== Confidence Gating Tests ==========

    @Test
    fun `low confidence skips evaluation and returns no violations`() {
        // Even with terrible form angles, low confidence should skip
        val angles = createAngles(
            JointAngleType.LEFT_KNEE to 175f,   // terrible depth
            JointAngleType.TRUNK_LEAN to 60f,   // terrible lean
            JointAngleType.KNEE_VALGUS_LEFT to 20f, // terrible valgus
            confidence = 0.3f
        )

        val result = FormRulesEngine.evaluate(angles, ExerciseFormType.SQUAT)

        assertEquals(0, result.violations.size,
            "Low confidence (0.3) should skip evaluation entirely, returning 0 violations")
    }

    @Test
    fun `confidence exactly at threshold evaluates normally`() {
        val angles = createAngles(
            JointAngleType.LEFT_KNEE to 165f,
            JointAngleType.RIGHT_KNEE to 165f,
            confidence = 0.5f
        )

        val result = FormRulesEngine.evaluate(angles, ExerciseFormType.SQUAT)

        assertTrue(result.violations.isNotEmpty(),
            "Confidence exactly at threshold (0.5) should evaluate and find violations")
    }

    @Test
    fun `confidence above threshold evaluates normally`() {
        val angles = createAngles(
            JointAngleType.LEFT_KNEE to 165f,
            JointAngleType.RIGHT_KNEE to 165f,
            confidence = 0.8f
        )

        val result = FormRulesEngine.evaluate(angles, ExerciseFormType.SQUAT)

        assertTrue(result.violations.isNotEmpty(),
            "Confidence above threshold (0.8) should evaluate and find violations")
    }

    @Test
    fun `confidence just below threshold skips evaluation`() {
        val angles = createAngles(
            JointAngleType.LEFT_KNEE to 165f,
            JointAngleType.RIGHT_KNEE to 165f,
            confidence = 0.49f
        )

        val result = FormRulesEngine.evaluate(angles, ExerciseFormType.SQUAT)

        assertEquals(0, result.violations.size,
            "Confidence just below threshold (0.49) should skip evaluation")
    }

    // ========== Form Score Calculation Tests ==========

    @Test
    fun `form score for empty assessments returns 100`() {
        val score = FormRulesEngine.calculateFormScore(emptyList())

        assertEquals(100, score, "Empty assessments should return perfect score of 100")
    }

    @Test
    fun `form score for all clean assessments returns 100`() {
        val goodAngles = createAngles(
            JointAngleType.LEFT_KNEE to 95f,
            JointAngleType.RIGHT_KNEE to 95f,
            JointAngleType.TRUNK_LEAN to 20f,
            JointAngleType.KNEE_VALGUS_LEFT to 3f,
            JointAngleType.KNEE_VALGUS_RIGHT to 3f
        )
        val assessments = (1..5).map {
            FormRulesEngine.evaluate(goodAngles, ExerciseFormType.SQUAT)
        }

        // Verify all are clean
        assertTrue(assessments.all { it.violations.isEmpty() }, "All should be clean assessments")

        val score = FormRulesEngine.calculateFormScore(assessments)

        assertEquals(100, score, "All clean assessments should give perfect score 100")
    }

    @Test
    fun `form score with INFO violations is above 75`() {
        // Create assessments with only INFO violations (shallow depth)
        val shallowAngles = createAngles(
            JointAngleType.LEFT_KNEE to 165f,
            JointAngleType.RIGHT_KNEE to 95f,
            JointAngleType.TRUNK_LEAN to 20f,
            JointAngleType.KNEE_VALGUS_LEFT to 3f,
            JointAngleType.KNEE_VALGUS_RIGHT to 3f
        )
        val assessments = (1..5).map {
            FormRulesEngine.evaluate(shallowAngles, ExerciseFormType.SQUAT)
        }

        // Verify we have only INFO violations
        val allViolations = assessments.flatMap { it.violations }
        assertTrue(allViolations.isNotEmpty(), "Should have some violations")
        assertTrue(
            allViolations.all { it.severity == FormViolationSeverity.INFO },
            "Should only have INFO violations"
        )

        val score = FormRulesEngine.calculateFormScore(assessments)

        assertTrue(score >= 75, "Score with only INFO violations should be at least 75, got $score")
    }

    @Test
    fun `form score with CRITICAL violations is below 50`() {
        // Create assessments with CRITICAL violations (knee valgus + back rounding)
        val badAngles = createAngles(
            JointAngleType.LEFT_KNEE to 95f,
            JointAngleType.RIGHT_KNEE to 95f,
            JointAngleType.TRUNK_LEAN to 50f,  // WARNING lean
            JointAngleType.KNEE_VALGUS_LEFT to 15f,  // CRITICAL valgus
            JointAngleType.KNEE_VALGUS_RIGHT to 15f  // CRITICAL valgus
        )
        val assessments = (1..5).map {
            FormRulesEngine.evaluate(badAngles, ExerciseFormType.SQUAT)
        }

        // Verify we have CRITICAL violations
        val criticals = assessments.flatMap { it.violations }
            .filter { it.severity == FormViolationSeverity.CRITICAL }
        assertTrue(criticals.isNotEmpty(), "Should have CRITICAL violations")

        val score = FormRulesEngine.calculateFormScore(assessments)

        assertTrue(score < 50,
            "Score with CRITICAL violations in every frame should be below 50, got $score")
    }

    @Test
    fun `form score with maximum violations approaches 0`() {
        // Create single frame with every possible violation
        val terribleAngles = createAngles(
            JointAngleType.LEFT_KNEE to 175f,   // shallow - INFO
            JointAngleType.RIGHT_KNEE to 175f,  // shallow - INFO
            JointAngleType.TRUNK_LEAN to 60f,   // forward lean - WARNING
            JointAngleType.KNEE_VALGUS_LEFT to 20f,  // valgus - CRITICAL
            JointAngleType.KNEE_VALGUS_RIGHT to 20f  // valgus - CRITICAL
        )
        val assessment = FormRulesEngine.evaluate(terribleAngles, ExerciseFormType.SQUAT)
        assertTrue(assessment.violations.size >= 4, "Should have many violations")

        val score = FormRulesEngine.calculateFormScore(listOf(assessment))

        assertTrue(score < 25,
            "Single frame with max violations should have very low score, got $score")
    }

    // ========== CV-08 Architectural Compliance Tests ==========

    @Test
    fun `getRulesForExercise returns only FormRule objects with string display data`() {
        // CV-08: All rules produce advisory-only output -- no action commands
        for (exerciseType in ExerciseFormType.entries) {
            val rules = FormRulesEngine.getRulesForExercise(exerciseType)
            for (rule in rules) {
                // FormRule has only: jointAngle (enum), min/max (Float),
                // severity (enum), violationMessage (String), correctiveCue (String)
                // All display data, no action commands
                assertTrue(rule.violationMessage.isNotEmpty(),
                    "Rule for $exerciseType should have a violation message")
                assertTrue(rule.correctiveCue.isNotEmpty(),
                    "Rule for $exerciseType should have a corrective cue")

                // Verify no machine-control language in cues
                val combinedText = "${rule.violationMessage} ${rule.correctiveCue}".lowercase()
                assertTrue(!combinedText.contains("adjust weight"),
                    "Rule should not contain 'adjust weight' (CV-08)")
                assertTrue(!combinedText.contains("stop machine"),
                    "Rule should not contain 'stop machine' (CV-08)")
                assertTrue(!combinedText.contains("reduce load"),
                    "Rule should not contain 'reduce load' (CV-08)")
                assertTrue(!combinedText.contains("ble"),
                    "Rule should not reference BLE (CV-08)")
            }
        }
    }

    @Test
    fun `evaluate produces FormAssessment with only display data`() {
        // CV-08: Assessment output is advisory only
        val angles = createAngles(
            JointAngleType.LEFT_KNEE to 165f,
            JointAngleType.TRUNK_LEAN to 50f,
            JointAngleType.KNEE_VALGUS_LEFT to 15f
        )

        val assessment = FormRulesEngine.evaluate(angles, ExerciseFormType.SQUAT)

        // FormAssessment has: violations (list), exerciseType (enum), timestamp (Long)
        // FormViolation has: rule (FormRule), actualDegrees (Float), severity (enum),
        //   message (String), correctiveCue (String), timestamp (Long)
        // All display data -- no weight, BLE, or machine control fields
        for (violation in assessment.violations) {
            assertTrue(violation.message.isNotEmpty(), "Violation should have display message")
            assertTrue(violation.correctiveCue.isNotEmpty(), "Violation should have corrective cue")
            assertTrue(violation.actualDegrees.isFinite(), "Actual degrees should be finite")
        }
    }

    // ========== Rule Coverage Tests ==========

    @Test
    fun `every ExerciseFormType has at least 2 rules`() {
        for (exerciseType in ExerciseFormType.entries) {
            val rules = FormRulesEngine.getRulesForExercise(exerciseType)
            assertTrue(rules.size >= 2,
                "$exerciseType should have at least 2 rules, got ${rules.size}")
        }
    }

    @Test
    fun `getRulesForExercise returns non-empty for all 5 exercise types`() {
        assertEquals(5, ExerciseFormType.entries.size,
            "Should have exactly 5 exercise types")

        for (exerciseType in ExerciseFormType.entries) {
            val rules = FormRulesEngine.getRulesForExercise(exerciseType)
            assertTrue(rules.isNotEmpty(),
                "$exerciseType should have rules defined")
        }
    }

    @Test
    fun `total rule count across all exercises matches expected`() {
        val totalRules = ExerciseFormType.entries.sumOf {
            FormRulesEngine.getRulesForExercise(it).size
        }

        // 14-01 created 17 rules total
        assertEquals(17, totalRules,
            "Total rules across all exercises should be 17")
    }

    // ========== Edge Cases ==========

    @Test
    fun `evaluate with missing joint angles skips those rules silently`() {
        // Only provide trunk lean, no knee angles -- knee rules should be skipped
        val angles = createAngles(
            JointAngleType.TRUNK_LEAN to 20f
        )

        val result = FormRulesEngine.evaluate(angles, ExerciseFormType.SQUAT)

        // Should not crash, and only evaluate trunk lean rule (which passes at 20 deg)
        assertEquals(0, result.violations.size,
            "Missing joint angles should be silently skipped, not cause errors")
    }

    @Test
    fun `evaluate preserves timestamp from input angles`() {
        val angles = createAngles(
            JointAngleType.LEFT_KNEE to 95f,
            timestamp = 123456789L
        )

        val result = FormRulesEngine.evaluate(angles, ExerciseFormType.SQUAT)

        assertEquals(123456789L, result.timestamp,
            "Assessment timestamp should match input angles timestamp")
    }

    @Test
    fun `violations carry correct actual degrees`() {
        val angles = createAngles(
            JointAngleType.LEFT_KNEE to 165f
        )

        val result = FormRulesEngine.evaluate(angles, ExerciseFormType.SQUAT)

        assertTrue(result.violations.isNotEmpty(), "Should detect shallow squat")
        val violation = result.violations.first()
        assertEquals(165f, violation.actualDegrees,
            "Violation should record the actual measured angle")
    }
}
