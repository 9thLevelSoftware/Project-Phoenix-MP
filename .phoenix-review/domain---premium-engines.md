# Domain - Premium Engines Review

Reviewed scope: premium engines (VBT, force curve, readiness, RPG, asymmetry, rep quality), assessment, velocity 1RM estimation, and replay boundary detection.

Note: Four assigned source paths do not exist in `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/`: `AsymmetryEngine.kt`, `ForceCurveEngine.kt`, `VbtEngine.kt`, and `VbtThreshold.kt`. Their tests and implementation appear to have been consolidated into `BiomechanicsEngine.kt`, so those missing paths are reported explicitly below and implementation-specific findings reference the consolidated file where applicable.

## Summary

- Total findings: 20
- Critical: 0
- High: 0
- Medium: 14
- Low: 6

## shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/AsymmetryEngine.kt

### Finding 1
- Category: failure-point
- Severity: medium
- Line numbers: N/A (file missing)
- Description: The assigned file does not exist in commonMain. The asymmetry implementation appears to live in `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/BiomechanicsEngine.kt` (`computeAsymmetry`), while tests are named `AsymmetryEngineTest`. This stale path can break review/build tooling, documentation, or imports expecting a standalone `AsymmetryEngine`.
- Suggested fix direction: Either restore a thin `AsymmetryEngine` wrapper/adapter around the consolidated implementation or update task specs, docs, tests, and any references to point to `BiomechanicsEngine.computeAsymmetry`.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/ForceCurveEngine.kt

### Finding 2
- Category: failure-point
- Severity: medium
- Line numbers: N/A (file missing)
- Description: The assigned file does not exist in commonMain. Force curve logic appears to be consolidated into `BiomechanicsEngine.computeForceCurve`, while the test class remains named `ForceCurveEngineTest`.
- Suggested fix direction: Restore a compatibility wrapper or update references/review manifests to the actual consolidated implementation file.

### Finding 3
- Category: bug
- Severity: medium
- Line numbers: consolidated implementation at `BiomechanicsEngine.kt:336-341`; model contract at `BiomechanicsModels.kt:89,95-100`
- Description: `ForceCurveResult.normalizedForceN` is named and documented as Newtons, but `computeForceCurve` calculates force as `loadA + loadB` where both inputs are kg. This produces kilogram-force/load values while downstream code and model names imply Newtons, underreporting actual force by about 9.81x if consumers expect SI force.
- Suggested fix direction: Decide whether the field represents kg load or Newtons. If Newtons, multiply total kg by standard gravity before storing and update tests accordingly. If kg is intended, rename fields/docs away from `ForceN` to avoid unit bugs in UI/export/analytics.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/ReadinessEngine.kt

### Finding 4
- Category: failure-point
- Severity: medium
- Line numbers: 57-72
- Description: The readiness windows only check `timestamp >= cutoff` and do not cap sessions at `timestamp <= nowMs`. Future-dated sessions can count as recent sessions and inflate acute/chronic volume, producing a readiness score based on workouts that have not happened yet.
- Suggested fix direction: Filter all time windows with bounded ranges such as `timestamp in cutoffMs..nowMs`, or explicitly discard future timestamps before computing history, recent count, acute volume, and chronic volume.

### Finding 5
- Category: bug
- Severity: low
- Line numbers: 118-122
- Description: The ACWR sweet-spot comment says the 0.8 and 1.3 edges should score around 70, but the formula uses a single divisor of `0.3f`. At ACWR 0.8 the score is about 80, not 70, so low-end readiness is overstated relative to the documented policy.
- Suggested fix direction: Use asymmetric scaling from the peak: divide by `0.2f` on the 0.8-1.0 side and by `0.3f` on the 1.0-1.3 side, or update the documented score zones/tests if 80 at 0.8 is intentional.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/RepQualityScorer.kt

### Finding 6
- Category: bug
- Severity: medium
- Line numbers: 166-173
- Description: Smoothness scoring divides the standard deviation by the signed mean velocity. If concentric velocity samples are negative because of sensor direction conventions, `coeffOfVariation` becomes negative and the score is clamped to the maximum, making a noisy negative-velocity rep look perfectly smooth.
- Suggested fix direction: Normalize velocity direction before scoring or compute coefficient of variation using `abs(mean)` and/or absolute velocity magnitudes. Add tests with signed velocity arrays.

### Finding 7
- Category: failure-point
- Severity: low
- Line numbers: 166-169
- Description: Missing or unusable concentric velocity samples receive a neutral smoothness score of 10/20 instead of being marked unavailable or penalized. A rep with no smoothness data can still receive a high composite score, masking capture failures.
- Suggested fix direction: Return an explicit insufficient-data result, score missing smoothness as 0, or expose a confidence flag so downstream UI does not treat incomplete rep data as valid quality scoring.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/RpgAttributeEngine.kt

No issues found in this file during this pass.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/SmartSuggestionsEngine.kt

### Finding 8
- Category: failure-point
- Severity: medium
- Line numbers: 90-97, 317-334
- Description: Unknown muscle groups default to `CORE`, and core is excluded from push/pull/legs balance ratios. Any taxonomy mismatch such as a new group label can silently disappear from balance analysis instead of surfacing as unknown or unclassified volume.
- Suggested fix direction: Return/report an UNKNOWN category, include unknown volume in the analysis result, or have `analyzeBalance` pass an `onUnknownGroup` collector and expose warnings to callers/tests.

### Finding 9
- Category: failure-point
- Severity: medium
- Line numbers: 161-178
- Description: `findNeglectedExercises` groups over all supplied sessions and selects the latest timestamp without excluding future-dated rows. A future timestamp can suppress a legitimately neglected exercise because the computed `daysSinceLastPerformed` becomes negative and is filtered out.
- Suggested fix direction: Ignore sessions with `timestamp > nowMs` before grouping, or clamp/report future data separately.

### Finding 10
- Category: bug
- Severity: low
- Line numbers: 263-305
- Description: `TimeOfDayAnalysis.windowVolumes` is populated with `windowAvgIntensity` rather than volume. The model name promises volume, but the engine stores average kg-per-rep intensity, creating a contract mismatch for any future consumer that reads the field as volume.
- Suggested fix direction: Rename the model field to `windowAvgIntensity`, or populate `windowVolumes` with actual total volume and add a separate intensity field if needed.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/VbtEngine.kt

### Finding 11
- Category: failure-point
- Severity: medium
- Line numbers: N/A (file missing)
- Description: The assigned file does not exist in commonMain. VBT logic appears to be consolidated into `BiomechanicsEngine.computeVelocity`, while tests remain named `VbtEngineTest`.
- Suggested fix direction: Restore a compatibility wrapper or update references/review manifests to the consolidated `BiomechanicsEngine` location.

### Finding 12
- Category: bug
- Severity: medium
- Line numbers: consolidated implementation at `BiomechanicsEngine.kt:226-245`
- Description: The first velocity baseline is initialized whenever `firstRepMcv` is null, regardless of the supplied `repNumber`. If rep 1 is dropped, replayed, or processing starts at rep 2+, that later rep becomes the baseline and its velocity loss is computed as 0, hiding fatigue.
- Suggested fix direction: Only establish the baseline from `repNumber == 1`, or derive the baseline from the first stored rep result with explicit handling when earlier reps are missing.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/VbtThreshold.kt

### Finding 13
- Category: failure-point
- Severity: medium
- Line numbers: N/A (file missing)
- Description: The assigned threshold file does not exist. Threshold behavior is implemented as a mutable float in `BiomechanicsEngine`, so there is no standalone threshold type to validate or document acceptable ranges.
- Suggested fix direction: Restore a `VbtThreshold` value object or central constants file, or update manifests to the consolidated implementation.

### Finding 14
- Category: failure-point
- Severity: low
- Line numbers: consolidated implementation at `BiomechanicsEngine.kt:30-44,250-260`
- Description: `velocityLossThresholdPercent` is accepted and updated without range or finiteness validation. A negative threshold causes any non-null velocity loss to trigger stop immediately; `NaN` prevents threshold comparisons from working.
- Suggested fix direction: Clamp or reject invalid thresholds, for example requiring a finite value in a sensible range such as 1-80%.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/assessment/AssessmentEngine.kt

### Finding 15
- Category: bug
- Severity: medium
- Line numbers: 45-49, 106-107
- Description: Estimated 1RM is clamped to at least 1kg but not to the machine's maximum, while `suggestNextWeight` explicitly clamps future weights to 220kg. A shallow negative slope can return implausible estimates far beyond supported hardware limits.
- Suggested fix direction: Add a configurable maximum load to `AssessmentConfig` and clamp/flag estimates above that maximum instead of returning unsupported values silently.

### Finding 16
- Category: failure-point
- Severity: medium
- Line numbers: 29-58
- Description: Regression inputs are not checked for finite positive loads/velocities or minimum load variance beyond exact zero denominator. NaN, Infinity, negative values, or near-identical loads can leak through as NaN/unstable estimates and R² values outside the documented 0-1 range.
- Suggested fix direction: Filter or reject non-finite/non-positive points, require a minimum load spread, use an epsilon denominator check, and clamp or reject invalid R² values.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/onerepmax/VelocityOneRepMaxEstimator.kt

### Finding 17
- Category: failure-point
- Severity: medium
- Line numbers: 47-57
- Description: The estimator returns a `VelocityOneRepMaxResult` even when `passedQualityGate` is false. The companion use case persists whatever is returned, so low-confidence velocity 1RM estimates can be saved and displayed unless every caller remembers to check the flag.
- Suggested fix direction: Return null or a sealed low-confidence result when the R² gate fails, and ensure persistence/display paths explicitly gate on `passedQualityGate`.

### Finding 18
- Category: failure-point
- Severity: low
- Line numbers: 32,47-53
- Description: `mvtMs` is passed into `AssessmentConfig.oneRmVelocityMs` without finiteness or positive-range validation. A bad override/personal MVT value can produce nonsensical estimates even when load/velocity points are otherwise valid.
- Suggested fix direction: Reject non-finite or non-positive MVT values before regression and constrain user/personal MVT inputs to a realistic m/s range.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/replay/RepBoundaryDetector.kt

### Finding 19
- Category: bug
- Severity: medium
- Line numbers: 174-176
- Description: The detected peak sample is excluded from `concentricIndices` (`startIndex until peakIndex`) and included only in `eccentricIndices`. If downstream rep metrics compute concentric peak velocity/position from `concentricIndices`, the transition peak can be missed.
- Suggested fix direction: Define the boundary contract explicitly and include the peak in the concentric range (`startIndex..peakIndex`) or expose separate inclusive/exclusive ranges with tests for downstream metric extraction.

### Finding 20
- Category: failure-point
- Severity: low
- Line numbers: 56-63,121-146,157-188
- Description: The detector can only emit reps between pairs of detected valleys. If the first or last valley is not prominent enough after smoothing, the first/last rep is silently dropped even if the movement trace contains a valid partial boundary.
- Suggested fix direction: Add fallback boundary handling using trace start/end when only one edge valley is detected, or return diagnostics explaining why a terminal rep was discarded.
