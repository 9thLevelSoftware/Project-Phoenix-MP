# Phase 14: CV Form Check -- Domain Logic - Research

**Researched:** 2026-02-20
**Domain:** Exercise biomechanics rules engine, joint angle evaluation, KMP pure domain logic
**Confidence:** HIGH

## Summary

Phase 14 builds a form rules engine entirely in `commonMain` that evaluates joint angles from pose landmarks and produces form violations with severity and corrective cues. This is pure domain logic with zero platform dependencies -- no camera, no MediaPipe, no UI. The engine receives joint angle data (which Phase 15 will extract from MediaPipe landmarks) and returns structured violation results.

The existing codebase has strong precedent for this pattern: `BiomechanicsEngine`, `RepQualityScorer`, `SmartSuggestionsEngine`, and `AssessmentEngine` are all pure domain engines in `shared/src/commonMain/kotlin/.../domain/premium/` with full `commonTest` coverage. The form rules engine follows the same shape: stateless pure functions that accept structured input and return structured output, testable without any platform or DI dependencies.

The five supported exercises (squat, deadlift/RDL, overhead press, curl, row) each have well-documented biomechanical form rules from exercise science literature. Joint angle thresholds can be defined as constants with named semantic meaning. The critical design constraint is **CV-08: warnings are advisory only** -- the engine must never produce output that feeds into weight adjustment, machine control, or workout modification code paths.

**Primary recommendation:** Build a `FormRulesEngine` in `domain/premium/` with per-exercise rule sets defined as data-driven configuration objects, a `JointAngles` input model in `domain/model/`, and `FormViolation`/`FormAssessment` output models. Use the strategy pattern for exercise-specific rules, matching the project's existing engine patterns. All code in `commonMain`, all tests in `commonTest`.

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| CV-07 | Exercise-specific form rules defined for squat, deadlift/RDL, overhead press, curl, and row | Joint angle thresholds from biomechanics literature for all 5 exercises; strategy pattern for per-exercise rule sets; `FormRule` data class with threshold, severity, and corrective cue |
| CV-08 | Warnings are advisory only -- no automatic weight or machine adjustments | Output model (`FormViolation`) contains only display data (message, severity, cue); no reference to `BleRepository`, `WorkoutCoordinator`, or any weight/machine control interface; verified by architectural test |
</phase_requirements>

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Kotlin stdlib | 2.0.21 | Pure domain logic, data classes, enums | Already in project; all domain engines use it |
| kotlin.math | (stdlib) | Angle calculations (`abs`, `atan2`, trig) | Standard for geometric calculations |
| kotlin.test | (stdlib) | commonTest assertions | All existing domain engine tests use it |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| None needed | - | - | This phase is 100% pure Kotlin -- no additional dependencies |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Hand-coded angle math | Apache Commons Math | Overkill; 3 lines of `atan2` + conversion is cleaner than a JVM-only dependency in KMP |
| Strategy pattern per exercise | Single giant `when` block | Strategy is cleaner, matches project pattern (BiomechanicsEngine uses internal functions per concern) |
| Sealed class hierarchy for rules | Enum-based rules | Sealed classes allow per-rule data (thresholds, cues); enums would need separate lookup maps |

**Installation:** No new dependencies required. Zero `build.gradle.kts` changes.

## Architecture Patterns

### Recommended Project Structure

```
shared/src/commonMain/kotlin/com/devil/phoenixproject/
├── domain/
│   ├── model/
│   │   └── FormCheckModels.kt          # JointAngles, FormViolation, FormAssessment, etc.
│   └── premium/
│       └── FormRulesEngine.kt          # Engine + per-exercise rule sets
│
shared/src/commonTest/kotlin/com/devil/phoenixproject/
└── domain/
    └── premium/
        └── FormRulesEngineTest.kt      # Comprehensive tests for all 5 exercises
```

### Pattern 1: Data-Driven Rule Sets (Strategy Pattern)

**What:** Each exercise type has a list of `FormRule` objects that define what joint angle to check, the acceptable range, violation severity, and a corrective cue string. The engine iterates rules and collects violations.

**When to use:** When you have multiple exercise types each with different but structurally identical checks.

**Example:**
```kotlin
// domain/model/FormCheckModels.kt

/**
 * Which body joint angle is being evaluated.
 * Maps to angles computable from MediaPipe pose landmarks.
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
    TRUNK_LEAN,        // Angle of torso from vertical
    KNEE_VALGUS_LEFT,  // Inward collapse angle
    KNEE_VALGUS_RIGHT
}

/**
 * Severity of a form violation.
 */
enum class FormViolationSeverity {
    INFO,       // Minor deviation, informational only
    WARNING,    // Significant deviation, should correct
    CRITICAL    // Major form breakdown, risk of injury
}

/**
 * Joint angle snapshot for a single frame.
 * Produced by pose estimation (Phase 15), consumed by this engine.
 */
data class JointAngles(
    val angles: Map<JointAngleType, Float>,  // Degrees
    val timestamp: Long,
    val confidence: Float = 1.0f  // Overall pose confidence [0-1]
)

/**
 * A single form rule: "if joint X is outside [min, max] degrees, emit violation"
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
 * A detected form violation.
 * Advisory only -- contains display data, no machine control references.
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
 * Assessment result for a single frame evaluation.
 */
data class FormAssessment(
    val violations: List<FormViolation>,
    val exerciseType: ExerciseFormType,
    val timestamp: Long
)

/**
 * Supported exercise types for form checking.
 */
enum class ExerciseFormType {
    SQUAT,
    DEADLIFT_RDL,
    OVERHEAD_PRESS,
    CURL,
    ROW
}
```

### Pattern 2: Stateless Engine with Per-Exercise Rule Registry

**What:** The engine is a stateless object (like `SmartSuggestionsEngine`) or a simple class (like `RepQualityScorer`). Each exercise type maps to a list of `FormRule` objects. Evaluation is a pure function: `(JointAngles, ExerciseFormType) -> FormAssessment`.

**When to use:** For the main evaluation entry point.

**Example:**
```kotlin
// domain/premium/FormRulesEngine.kt

/**
 * Pure domain engine for exercise form evaluation.
 *
 * Evaluates joint angles against exercise-specific rules and produces
 * form violations with severity and corrective cues.
 *
 * IMPORTANT: This engine is advisory only (CV-08). It produces display
 * data for the UI. No code path exists to adjust weight, stop the
 * machine, or modify workout parameters based on form data.
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
     * @param angles Current joint angle snapshot
     * @param exerciseType Which exercise is being performed
     * @param minConfidence Minimum pose confidence to evaluate (default 0.5)
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

    fun getRulesForExercise(exerciseType: ExerciseFormType): List<FormRule> =
        rulesByExercise[exerciseType] ?: emptyList()
}
```

### Pattern 3: Form Score Calculation (Composite Scoring)

**What:** Form score (0-100) calculated from the ratio of frames with violations to total evaluated frames, weighted by severity. Follows `RepQualityScorer` pattern (composite from weighted sub-scores).

**When to use:** Phase 16 will use this for CV-05 (form score per exercise), but the calculation logic belongs in the domain engine here.

**Example:**
```kotlin
/**
 * Calculate form score (0-100) from a series of frame assessments.
 *
 * Scoring: Start at 100, deduct points per violation weighted by severity.
 * - INFO: -1 point per frame
 * - WARNING: -3 points per frame
 * - CRITICAL: -5 points per frame
 * Score = max(0, 100 - totalDeductions / frameCount * scaleFactor)
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
    // Scale so that ~2 avg deductions/frame = score of 50
    val score = (100f - avgDeductionPerFrame * 25f).coerceIn(0f, 100f)
    return score.toInt()
}
```

### Anti-Patterns to Avoid

- **Coupling to MediaPipe landmarks directly:** The domain model must use `JointAngles` (a map of semantic angle types to degrees), NOT raw landmark coordinates. Phase 15 handles the landmark-to-angle conversion.
- **Weight/machine control in form engine:** CV-08 explicitly forbids this. The engine output must contain only display data (`FormViolation` with message/cue strings). No imports from `data/ble/`, `BleRepository`, `WorkoutCoordinator`, or any weight-setting code.
- **Mutable state in the engine:** Unlike `BiomechanicsEngine` which tracks per-set state, the form rules engine should be stateless. Each `evaluate()` call is independent. Form score aggregation (stateful) can be a separate accumulator class if needed.
- **Hard-coded angle strings instead of enums:** Use `JointAngleType` enum for type safety. Strings are error-prone across the domain boundary.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Joint angle calculation from 3D coordinates | Custom trig in domain layer | Let Phase 15 (MediaPipe integration) compute angles in `androidMain` | Domain layer should receive pre-computed angles, not raw landmarks |
| Exercise type detection from form rules | Pattern matching against joint angles | Existing exercise auto-detection (Phase 11, `ExerciseClassifier`) | Exercise type is already known from the workout context |
| Per-frame smoothing/filtering | Custom Kalman filter for angle data | Phase 15 responsibility (MediaPipe has built-in temporal smoothing) | Domain rules operate on clean angle data |
| Audio/visual warning delivery | TTS or notification system in domain | Phase 16 UI layer responsibility | Domain layer produces violation data, UI layer renders it |

**Key insight:** The form rules engine is a pure evaluator. It receives clean angles, applies threshold rules, and returns violations. All signal processing (smoothing, noise reduction) and rendering (audio, visual, haptic) belong to other phases.

## Common Pitfalls

### Pitfall 1: Angle Range Wrap-Around

**What goes wrong:** Joint angles computed from `atan2` can wrap around 0/360 degrees. A rule checking "knee angle between 80-100 degrees" fails if the angle wraps to 260 degrees instead of being reported as -100.
**Why it happens:** Different pose estimation coordinate systems report angles differently (0-360 vs -180 to 180).
**How to avoid:** Standardize all angles to 0-180 degrees for joint flexion angles in the `JointAngles` model. Document the convention. Phase 15 normalizes before passing to the engine.
**Warning signs:** Tests pass with synthetic data but fail with real MediaPipe output.

### Pitfall 2: Overly Strict Thresholds

**What goes wrong:** Thresholds set too tightly cause constant false-positive violations, making the feature annoying and untrustworthy.
**Why it happens:** Biomechanics literature reports ideal angles, but real-world execution has natural variance. Camera angle also affects perceived joint angles.
**How to avoid:** Use generous thresholds initially. Start with CRITICAL-only violations for clear form breakdowns, then tune WARNING/INFO levels based on user feedback. Include a `minConfidence` gate to skip evaluation on low-quality pose data.
**Warning signs:** Every rep triggers multiple violations even with good form.

### Pitfall 3: Missing Laterality Handling

**What goes wrong:** Rules only check left-side joints, missing right-side violations (or vice versa).
**Why it happens:** MediaPipe reports LEFT_KNEE and RIGHT_KNEE as separate landmarks. Rules must check both.
**How to avoid:** Define rules for both sides (LEFT_KNEE and RIGHT_KNEE, LEFT_HIP and RIGHT_HIP, etc.). Consider a helper that generates bilateral rule pairs from a single template.
**Warning signs:** User faces camera from one side and gets no violations; turns around and gets violations.

### Pitfall 4: Leaking Machine Control Through Form Data

**What goes wrong:** A well-intentioned developer adds a `shouldReduceWeight: Boolean` field to `FormViolation`, violating CV-08.
**Why it happens:** Natural instinct to act on detected problems.
**How to avoid:** Architectural test: grep the form check source files for any import of `ble`, `BleRepository`, `WorkoutCoordinator`, or `weight`. The output model should only contain strings and enums -- no action commands.
**Warning signs:** PR review shows imports from `data/ble/` or `presentation/manager/` in form check code.

### Pitfall 5: Camera Viewing Angle Sensitivity

**What goes wrong:** Rules assume a frontal or sagittal camera view. Angles appear different from different camera positions.
**Why it happens:** 2D pose estimation from a single camera has inherent depth ambiguity.
**How to avoid:** Design rules to be robust to camera angle. Focus on relative angles (knee-hip-ankle) rather than absolute positions. Consider that the Vitruvian trainer has a fixed position, so camera placement is somewhat predictable (typically frontal or 45-degree). This is primarily Phase 15's concern, but domain rules should be angle-range-generous.
**Warning signs:** Rules work in testing (controlled camera position) but fail in real-world usage.

## Code Examples

### Exercise-Specific Rule Definitions

Based on exercise science biomechanics literature:

```kotlin
// Squat form rules
// Sources: NASM Overhead Squat Assessment, IJSPT Biomechanical Review of the Squat
private fun squatRules(): List<FormRule> = listOf(
    // Knee flexion depth: parallel squat should reach ~90-110 degrees knee flexion
    // We check if knees are NOT bending enough (staying too extended)
    FormRule(
        jointAngle = JointAngleType.LEFT_KNEE,
        minDegrees = 0f,    // No minimum -- deep squat is fine
        maxDegrees = 160f,  // If knee stays above 160 degrees, not squatting deep enough
        severity = FormViolationSeverity.INFO,
        violationMessage = "Squat depth is shallow",
        correctiveCue = "Try to lower your hips until thighs are parallel to the floor"
    ),
    // Same for right knee
    FormRule(
        jointAngle = JointAngleType.RIGHT_KNEE,
        minDegrees = 0f,
        maxDegrees = 160f,
        severity = FormViolationSeverity.INFO,
        violationMessage = "Squat depth is shallow",
        correctiveCue = "Try to lower your hips until thighs are parallel to the floor"
    ),
    // Excessive forward lean (trunk angle from vertical)
    // More than 45 degrees forward lean shifts load to lower back
    FormRule(
        jointAngle = JointAngleType.TRUNK_LEAN,
        minDegrees = 0f,
        maxDegrees = 45f,
        severity = FormViolationSeverity.WARNING,
        violationMessage = "Excessive forward lean",
        correctiveCue = "Keep your chest up and core braced"
    ),
    // Knee valgus (inward collapse)
    // More than 10 degrees inward is problematic
    FormRule(
        jointAngle = JointAngleType.KNEE_VALGUS_LEFT,
        minDegrees = 0f,
        maxDegrees = 10f,
        severity = FormViolationSeverity.CRITICAL,
        violationMessage = "Left knee collapsing inward",
        correctiveCue = "Push your knees out over your toes"
    ),
    FormRule(
        jointAngle = JointAngleType.KNEE_VALGUS_RIGHT,
        minDegrees = 0f,
        maxDegrees = 10f,
        severity = FormViolationSeverity.CRITICAL,
        violationMessage = "Right knee collapsing inward",
        correctiveCue = "Push your knees out over your toes"
    )
)

// Deadlift/RDL form rules
// Sources: Stronger by Science deadlift guide, SimpliFaster RDL technique
private fun deadliftRdlRules(): List<FormRule> = listOf(
    // Back rounding: trunk lean is acceptable in deadlift, but
    // the key issue is spinal FLEXION (rounding), detected as
    // excessive hip-shoulder angle deviation from neutral
    FormRule(
        jointAngle = JointAngleType.TRUNK_LEAN,
        minDegrees = 0f,
        maxDegrees = 75f,   // Deadlift allows more forward lean than squat
        severity = FormViolationSeverity.CRITICAL,
        violationMessage = "Excessive back rounding",
        correctiveCue = "Keep your chest proud and back flat -- hinge at hips, not spine"
    ),
    // Knee angle: RDL keeps knees at 15-30 degree bend (150-165 deg measured angle)
    // Less than 130 degrees means excessive knee bend (turning into a squat)
    FormRule(
        jointAngle = JointAngleType.LEFT_KNEE,
        minDegrees = 130f,
        maxDegrees = 180f,
        severity = FormViolationSeverity.WARNING,
        violationMessage = "Too much knee bend for deadlift/RDL",
        correctiveCue = "Keep a slight knee bend -- push your hips back instead of bending knees"
    ),
    FormRule(
        jointAngle = JointAngleType.RIGHT_KNEE,
        minDegrees = 130f,
        maxDegrees = 180f,
        severity = FormViolationSeverity.WARNING,
        violationMessage = "Too much knee bend for deadlift/RDL",
        correctiveCue = "Keep a slight knee bend -- push your hips back instead of bending knees"
    )
)

// Overhead press form rules
// Sources: ISSA Overhead Press guide, Muscle&Motion shoulder press guide
private fun overheadPressRules(): List<FormRule> = listOf(
    // Elbow flare: elbows should stay roughly under the bar
    // Excessive elbow angle behind the body increases shoulder impingement risk
    FormRule(
        jointAngle = JointAngleType.LEFT_ELBOW,
        minDegrees = 0f,     // Full extension at lockout is fine
        maxDegrees = 170f,   // Full hyperextension is a red flag
        severity = FormViolationSeverity.WARNING,
        violationMessage = "Elbow hyperextending at lockout",
        correctiveCue = "Keep a slight bend at lockout -- don't slam into full extension"
    ),
    FormRule(
        jointAngle = JointAngleType.RIGHT_ELBOW,
        minDegrees = 0f,
        maxDegrees = 170f,
        severity = FormViolationSeverity.WARNING,
        violationMessage = "Elbow hyperextending at lockout",
        correctiveCue = "Keep a slight bend at lockout -- don't slam into full extension"
    ),
    // Excessive back lean during press
    FormRule(
        jointAngle = JointAngleType.TRUNK_LEAN,
        minDegrees = -15f,   // Slight backward lean is ok
        maxDegrees = 15f,    // But more than 15 degrees back is dangerous
        severity = FormViolationSeverity.CRITICAL,
        violationMessage = "Excessive backward lean during press",
        correctiveCue = "Brace your core and keep torso upright -- don't lean back to push the weight"
    )
)

// Curl form rules
// Sources: ATHLEAN-X curl guide, ACE Exercise Library, Fitbod bicep curl guide
private fun curlRules(): List<FormRule> = listOf(
    // Shoulder drift: shoulders should stay stationary during curls
    // Shoulder flexion angle moving forward indicates cheating
    FormRule(
        jointAngle = JointAngleType.LEFT_SHOULDER,
        minDegrees = 0f,
        maxDegrees = 25f,     // Shoulder angle shouldn't change much from neutral
        severity = FormViolationSeverity.WARNING,
        violationMessage = "Left shoulder drifting forward",
        correctiveCue = "Keep elbows pinned to your sides -- curl with biceps only"
    ),
    FormRule(
        jointAngle = JointAngleType.RIGHT_SHOULDER,
        minDegrees = 0f,
        maxDegrees = 25f,
        severity = FormViolationSeverity.WARNING,
        violationMessage = "Right shoulder drifting forward",
        correctiveCue = "Keep elbows pinned to your sides -- curl with biceps only"
    ),
    // Trunk swing: excessive body lean indicates momentum/cheating
    FormRule(
        jointAngle = JointAngleType.TRUNK_LEAN,
        minDegrees = -10f,
        maxDegrees = 15f,
        severity = FormViolationSeverity.WARNING,
        violationMessage = "Body swinging during curl",
        correctiveCue = "Brace your core -- don't use momentum to lift the weight"
    )
)

// Row form rules
// Sources: MasterClass cable row guide, Bodyrecomposition cable row technique
private fun rowRules(): List<FormRule> = listOf(
    // Torso should remain relatively upright (10-15 degree lean ok)
    FormRule(
        jointAngle = JointAngleType.TRUNK_LEAN,
        minDegrees = -15f,
        maxDegrees = 30f,    // More than 30 degrees forward lean during row
        severity = FormViolationSeverity.WARNING,
        violationMessage = "Excessive forward lean during row",
        correctiveCue = "Keep your torso upright -- pull with your back, not momentum"
    ),
    // Elbows should pull past torso for full contraction
    // But if elbow goes way behind body, shoulder is taking over
    FormRule(
        jointAngle = JointAngleType.LEFT_ELBOW,
        minDegrees = 30f,     // Should reach contraction (elbow behind torso)
        maxDegrees = 160f,    // Elbow shouldn't stay fully extended (not pulling)
        severity = FormViolationSeverity.INFO,
        violationMessage = "Incomplete row -- elbows not pulling back enough",
        correctiveCue = "Pull elbows behind your body and squeeze shoulder blades together"
    ),
    FormRule(
        jointAngle = JointAngleType.RIGHT_ELBOW,
        minDegrees = 30f,
        maxDegrees = 160f,
        severity = FormViolationSeverity.INFO,
        violationMessage = "Incomplete row -- elbows not pulling back enough",
        correctiveCue = "Pull elbows behind your body and squeeze shoulder blades together"
    )
)
```

### Test Pattern (Matching Existing `VbtEngineTest` / `RepQualityScorerTest`)

```kotlin
class FormRulesEngineTest {

    private fun createAngles(
        vararg pairs: Pair<JointAngleType, Float>,
        confidence: Float = 1.0f,
        timestamp: Long = 0L
    ): JointAngles = JointAngles(
        angles = pairs.toMap(),
        timestamp = timestamp,
        confidence = confidence
    )

    // ========== Squat Tests ==========

    @Test
    fun `squat with good depth and no valgus returns no violations`() {
        val angles = createAngles(
            JointAngleType.LEFT_KNEE to 95f,     // Good depth
            JointAngleType.RIGHT_KNEE to 97f,
            JointAngleType.TRUNK_LEAN to 20f,    // Acceptable lean
            JointAngleType.KNEE_VALGUS_LEFT to 3f,
            JointAngleType.KNEE_VALGUS_RIGHT to 2f
        )

        val result = FormRulesEngine.evaluate(angles, ExerciseFormType.SQUAT)

        assertTrue(result.violations.isEmpty())
    }

    @Test
    fun `squat with knee valgus returns CRITICAL violation`() {
        val angles = createAngles(
            JointAngleType.LEFT_KNEE to 95f,
            JointAngleType.RIGHT_KNEE to 97f,
            JointAngleType.TRUNK_LEAN to 20f,
            JointAngleType.KNEE_VALGUS_LEFT to 15f,  // Bad: > 10
            JointAngleType.KNEE_VALGUS_RIGHT to 2f
        )

        val result = FormRulesEngine.evaluate(angles, ExerciseFormType.SQUAT)

        assertEquals(1, result.violations.size)
        assertEquals(FormViolationSeverity.CRITICAL, result.violations[0].severity)
        assertTrue(result.violations[0].correctiveCue.contains("knees"))
    }

    @Test
    fun `low confidence skips evaluation entirely`() {
        val angles = createAngles(
            JointAngleType.KNEE_VALGUS_LEFT to 20f,  // Would normally violate
            confidence = 0.3f  // Below threshold
        )

        val result = FormRulesEngine.evaluate(angles, ExerciseFormType.SQUAT)

        assertTrue(result.violations.isEmpty())
    }
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Static threshold rules only | ML classification + threshold rules hybrid | 2024-2025 | ML models achieve 83-92% accuracy, but require training data. Threshold rules are simpler, deterministic, and sufficient for v1 |
| Global angle thresholds (same for all exercises) | Exercise-specific rule sets | Standard practice | Different exercises have fundamentally different acceptable ranges |
| 2D angle estimation only | MediaPipe provides 3D world coordinates | MediaPipe Pose v2 (2023) | 3D coordinates reduce camera-angle sensitivity; Phase 15 concern |

**Deprecated/outdated:**
- MediaPipe Legacy Pose (`mediapipe.solutions.pose`): Replaced by Pose Landmarker task API in 2023-2024. Phase 15 must use the task API.
- Single-joint-only evaluation: Modern approaches evaluate joint relationships (e.g., knee valgus is hip-knee-ankle relationship, not just knee angle).

## Open Questions

1. **Exact threshold tuning for Vitruvian cable exercises**
   - What we know: Literature provides general thresholds for barbell/dumbbell exercises. Vitruvian cable machines have a different resistance profile (cables pull from floor platform).
   - What's unclear: Whether cable exercise angles differ meaningfully from free-weight equivalents. The Vitruvian trainer's fixed cable path may constrain movement differently.
   - Recommendation: Start with literature-based thresholds. Plan for a threshold tuning pass after real-world testing in Phase 15/16. Make thresholds constants so they're easy to adjust.

2. **TRUNK_LEAN vs. spine curvature**
   - What we know: MediaPipe landmarks can compute trunk lean (shoulder-hip angle from vertical). True spine curvature (rounding) requires more landmarks or depth data.
   - What's unclear: Whether trunk lean alone is sufficient to detect back rounding in deadlifts, or if we need a separate spine-curvature metric.
   - Recommendation: Use trunk lean as the primary metric for v1. Add a note in the code for potential spine-curvature enhancement. Phase 15 can compute additional derived angles if the landmark data supports it.

3. **Bilateral rule generation**
   - What we know: Most rules need left+right variants. Writing them out twice is verbose.
   - What's unclear: Whether a helper function (`bilateralRule()`) is worth the abstraction cost vs. explicit paired rules.
   - Recommendation: Claude's discretion. Start explicit (easier to understand/test), refactor to helper if the duplication becomes painful.

4. **Form score calculation scope**
   - What we know: CV-05 (Phase 16) requires a form score 0-100. The calculation logic is pure math and could live in the domain engine or in Phase 16.
   - What's unclear: Whether to implement `calculateFormScore()` now (Phase 14) or defer to Phase 16.
   - Recommendation: Include the score calculation in Phase 14 since it's pure domain logic with no platform dependencies. Phase 16 only needs to call it and persist the result.

## Sources

### Primary (HIGH confidence)
- [MediaPipe Pose Landmarker Documentation](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker) -- 33 landmark indices, coordinate system, confidence thresholds
- Project codebase: `BiomechanicsEngine.kt`, `RepQualityScorer.kt`, `SmartSuggestionsEngine.kt` -- established engine patterns
- Project codebase: `VbtEngineTest.kt`, `RepQualityScorerTest.kt` -- established test patterns in `commonTest`

### Secondary (MEDIUM confidence)
- [NASM Overhead Squat Assessment](https://www.ptpioneer.com/personal-training/certifications/study/nasm-overhead-squat-assessment/) -- Squat form compensation patterns (knee valgus, forward lean)
- [IJSPT Biomechanical Review of the Squat](https://pmc.ncbi.nlm.nih.gov/articles/PMC10987311/) -- Squat depth definitions (partial: 40 deg, parallel: 100-110 deg, deep: >100 deg knee flexion)
- [Stronger by Science Deadlift Guide](https://www.strongerbyscience.com/how-to-deadlift/) -- Deadlift form cues, back position
- [SimpliFaster Romanian Deadlift Technique](https://simplifaster.com/articles/romanian-deadlifts-technique-programming/) -- RDL knee angle (20-30 degree bend)
- [ISSA Overhead Press Guide](https://www.issaonline.com/blog/post/overhead-press-proper-form-variations-and-common-mistakes) -- Overhead press form rules
- [ATHLEAN-X Bicep Curl Guide](https://learn.athleanx.com/articles/how-to-do-bicep-curls) -- Curl form: elbow drift, shoulder movement, body swing
- [MasterClass Seated Cable Row Guide](https://www.masterclass.com/articles/seated-cable-row-guide) -- Row form: torso angle, elbow position, scapular retraction
- [LearnOpenCV AI Fitness Trainer](https://learnopencv.com/ai-fitness-trainer-using-mediapipe/) -- MediaPipe angle calculation patterns
- [Real-Time AI Posture Correction for Powerlifting (ResearchGate)](https://www.researchgate.net/publication/387474396_Real-Time_AI_Posture_Correction_for_Powerlifting_Exercises_Using_YOLOv5_and_MediaPipe) -- Multi-exercise form correction approach

### Tertiary (LOW confidence)
- [GitHub: Squats-angle-detection-using-mediapipe](https://github.com/Pradnya1208/Squats-angle-detection-using-OpenCV-and-mediapipe_v1) -- Reference implementation for angle thresholds (Python, not verified for production use)
- [GitHub: AI-Based Deadlift Posture Correction](https://github.com/SiriwanIm/AI-Based-Real-Time-Conventional-Deadlift-Posture-Correction-System-Using-MediaPipe) -- Stage-based threshold evaluation approach

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- No new dependencies; pure Kotlin in commonMain following 4+ existing engine precedents
- Architecture: HIGH -- Strategy pattern with data-driven rules matches project conventions exactly (BiomechanicsEngine, SmartSuggestionsEngine)
- Pitfalls: MEDIUM -- Threshold values are from literature but untested against Vitruvian cable exercises specifically; camera angle sensitivity is a real concern but primarily Phase 15's problem
- Exercise science thresholds: MEDIUM -- Based on biomechanics literature for general resistance training; may need tuning for cable machine specifics

**Research date:** 2026-02-20
**Valid until:** 2026-04-20 (stable domain; exercise biomechanics doesn't change rapidly)
