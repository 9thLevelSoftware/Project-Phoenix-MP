# Architecture Patterns: v0.5.0 Premium Mobile Features

**Domain:** KMP fitness app - CV pose estimation, biomechanics persistence, premium mobile UI
**Researched:** 2026-02-20
**Overall confidence:** HIGH (direct codebase analysis + official docs + design doc alignment)

---

## Current Architecture (v0.4.7 Baseline)

### Existing Component Map

```
MainViewModel (420L, thin facade)
  |-- SettingsManager
  |-- HistoryManager
  |-- GamificationManager
  |-- BleConnectionManager
  |-- DefaultWorkoutSessionManager (449L, coordinator + delegation)
        |-- WorkoutCoordinator (306L, shared state bus)
        |     |-- BiomechanicsEngine (stateful, per-set lifecycle)
        |     |-- RepQualityScorer (stateful, per-set lifecycle)
        |     |-- LedFeedbackController (nullable, set via DI)
        |     |-- collectedMetrics: MutableList<WorkoutMetric>
        |     |-- setRepMetrics: MutableList<RepMetricData>
        |     |-- repBoundaryTimestamps: MutableList<Long>
        |
        |-- RoutineFlowManager (1,091L)
        |-- ActiveSessionEngine (~2,600L)
              |-- ExerciseDetectionManager (optional, per-session)
```

### Existing Data Flow (Rep Processing)

```
BLE Device --> MetricSample --> WorkoutCoordinator.collectedMetrics
                                       |
                 ActiveSessionEngine.handleMonitorMetric()
                                       |
                          Rep boundary detected?
                                /            \
                             No               Yes
                              |                |
                         accumulate      processRepCompletion()
                                               |
                         +---------------------+---------------------+
                         |                     |                     |
                  RepQualityScorer    BiomechanicsEngine    RepBoundaryDetector
                  .scoreRep()         .processRep()         (timestamps)
                         |                     |                     |
                  _latestRepQuality    _latestRepResult      setRepMetrics
                  (StateFlow)          (StateFlow)           (List<RepMetricData>)
                                                                     |
                                                        saveRepMetrics() at set end
                                                                     |
                                                        RepMetricRepository
                                                                     |
                                                        SQLDelight RepMetric table
```

### Existing Module Boundaries

```
commonMain/
  domain/premium/       -- BiomechanicsEngine, RepQualityScorer, FeatureGate, SubscriptionTier
  domain/detection/     -- SignatureExtractor, ExerciseClassifier
  domain/assessment/    -- AssessmentEngine
  domain/replay/        -- RepBoundaryDetector
  domain/model/         -- BiomechanicsModels, WorkoutModels, ExerciseModels
  data/repository/      -- RepMetricRepository, WorkoutRepository, etc.
  presentation/manager/ -- ActiveSessionEngine, WorkoutCoordinator, ExerciseDetectionManager
  di/                   -- dataModule, domainModule, presentationModule, syncModule

androidMain/
  data/ble/             -- KableBleRepository (Nordic BLE)
  data/local/           -- DriverFactory (SQLite)
  di/                   -- platformModule (BLE, SQLite driver)

iosMain/
  data/local/           -- DriverFactory (Native SQLite)
  di/                   -- platformModule
```

### Key Architectural Patterns (established)

| Pattern | Example | Where Used |
|---------|---------|------------|
| Stateless pure-function engines | BiomechanicsEngine.computeVelocity() | Domain engines |
| StateFlow-based reactive state | WorkoutCoordinator._latestRepQuality | All managers |
| Interface + SQLDelight impl | RepMetricRepository / SqlDelightRepMetricRepository | Data layer |
| Manual JSON serialization | FloatArray.toJsonString() for RepMetric curves | Array persistence |
| Feature-scoped Koin modules | dataModule, domainModule, presentationModule | DI |
| Nullable injection for optional features | LedFeedbackController?, ExerciseDetectionManager? | WorkoutCoordinator |
| Single upstream gate pattern | Gate at data collection, null propagation to UI | FeatureGate |
| Data capture for all tiers (GATE-04) | RepMetricRepository has no tier checks | Repositories |
| Per-set lifecycle with reset() | BiomechanicsEngine.reset() between sets | Domain engines |

---

## New Features: Integration Architecture

### Feature 1: Biomechanics Persistence

**Status:** Data is computed but NOT persisted. BiomechanicsEngine produces per-rep VelocityResult, ForceCurveResult, AsymmetryResult. At set end, getSetSummary() returns BiomechanicsSetSummary. Currently both are used only for live UI display and discarded.

**Goal:** Persist per-rep biomechanics data alongside existing RepMetricData so analytics, history, and portal sync can access it.

#### Architecture Decision: Extend RepMetric vs. New Table

**Recommendation: Extend the existing RepMetric table with biomechanics columns.**

Rationale:
- Per-rep biomechanics data is 1:1 with RepMetricData (same sessionId + repNumber)
- Avoids a second table join for every rep query
- Follows the existing pattern of RepMetricData being the "fat" per-rep record
- Adding columns to RepMetric is a schema migration (v16), not a structural change
- The existing `RepMetricRepository.saveRepMetrics()` call site in ActiveSessionEngine already has access to biomechanics results

#### New Columns on RepMetric

```sql
-- VBT metrics
meanConcentricVelocityMmS REAL,
peakVelocityMmS REAL,
velocityZone TEXT,                -- enum as string
velocityLossPercent REAL,

-- Force curve (101 points, stored as JSON array)
normalizedForceCurve TEXT,        -- "[1.2,3.4,...]" (101 floats)
stickingPointPct REAL,
strengthProfile TEXT,             -- enum as string

-- Asymmetry
asymmetryPercent REAL,
dominantSide TEXT                 -- "A", "B", or "BALANCED"
```

#### Data Flow Change

```
BiomechanicsEngine.processRep()
       |
  BiomechanicsRepResult
       |
  ActiveSessionEngine stores in WorkoutCoordinator.setRepMetrics
       |                                    |
  (NEW) Merge biomechanics fields     (EXISTING) raw curve data
  into RepMetricData                   already stored
       |
  RepMetricRepository.saveRepMetrics()  <-- single write, now includes biomechanics
       |
  SQLDelight RepMetric table (v16 schema with new columns)
```

#### Modified Files

| File | Change Type | What Changes |
|------|-------------|--------------|
| `domain/model/RepMetricData.kt` or wherever RepMetricData is defined | MODIFY | Add biomechanics fields (nullable for backward compat) |
| `VitruvianDatabase.sq` | MODIFY | Add columns to RepMetric table + migration v16 |
| `data/repository/RepMetricRepository.kt` | MODIFY | Map new fields in save/load |
| `presentation/manager/ActiveSessionEngine.kt` | MODIFY | Populate biomechanics fields when building RepMetricData |
| `di/DataModule.kt` | NO CHANGE | Repository already wired |

#### Set-Level Summary Persistence

For set-level aggregated biomechanics (BiomechanicsSetSummary), two options:

**Recommendation: Add biomechanics columns to WorkoutSession table.**

The set summary is 1:1 with WorkoutSession. Adding `avgMcvMmS`, `peakVelocityMmS`, `totalVelocityLossPercent`, `avgAsymmetryPercent`, `dominantSide`, `strengthProfile` to WorkoutSession keeps queries simple. The avgForceCurve (101-point JSON) can also be stored as a TEXT column on WorkoutSession.

---

### Feature 2: CV Pose Estimation (MediaPipe + CameraX)

**This is an Android-only feature (androidMain).** iOS support (AVCapture + MediaPipe iOS SDK) is out of scope for v0.5.0 but the architecture should support it via expect/actual.

#### Component Boundary: commonMain vs androidMain

```
commonMain/ (pure Kotlin, no platform dependencies)
  domain/cv/
    |-- JointAngleCalculator.kt      -- Pure math: 3D landmark coords -> joint angles
    |-- FormRuleEngine.kt            -- Rule evaluation: angles + thresholds -> violations
    |-- ExerciseFormRules.kt         -- Per-exercise rule definitions (data)
    |-- CvModels.kt                  -- PoseLandmark, JointAngle, FormViolation, FormScore

androidMain/ (Android SDK dependencies)
  cv/
    |-- PoseAnalyzerHelper.kt        -- MediaPipe PoseLandmarker wrapper
    |-- CameraXProvider.kt           -- CameraX lifecycle + ImageAnalysis setup
    |-- PoseOverlayView.kt           -- Compose Canvas drawing skeleton overlay
```

#### Key Architecture Decision: Where Pose Processing Lives

**Recommendation: All ML inference stays in androidMain. Only computed joint angles cross into commonMain.**

Rationale:
- MediaPipe Tasks SDK is Android-only (Java/Kotlin API)
- CameraX is Android-only (Jetpack library)
- The `expect/actual` boundary is at the "joint angles" level, not the "camera frame" level
- commonMain gets `List<JointAngle>` and runs `FormRuleEngine` against it
- This keeps ~80% of the form-checking logic cross-platform (rules, scoring, violation tracking)

#### Integration with Existing Architecture

```
                       androidMain                                commonMain
                    +-----------------+                      +-------------------+
CameraX             |                 |   List<JointAngle>   |                   |
ImageAnalysis ----->| PoseAnalyzer    |--------------------->| FormRuleEngine    |
  (30fps)           | Helper          |                      | .evaluate()       |
                    |   MediaPipe     |                      |                   |
                    |   PoseLandmarker|                      | FormViolation[]   |
                    +-----------------+                      | FormScore         |
                                                             +-------------------+
                                                                      |
                                                                      v
                                                             WorkoutCoordinator
                                                             ._latestFormResult
                                                             (StateFlow)
                                                                      |
                                                                      v
                                                             Compose UI
                                                             (form warnings,
                                                              skeleton overlay)
```

#### expect/actual Pattern for CV

```kotlin
// commonMain
expect class PoseEstimator {
    fun isAvailable(): Boolean
    fun start()
    fun stop()
    val latestJointAngles: StateFlow<List<JointAngle>>
}

// androidMain
actual class PoseEstimator(
    private val context: Context
) {
    private val helper = PoseAnalyzerHelper(context)
    // ... wraps MediaPipe + CameraX
}

// iosMain (stub for now)
actual class PoseEstimator {
    fun isAvailable(): Boolean = false  // Not implemented yet
    // ...
}
```

**Alternative (recommended for v0.5.0):** Skip expect/actual entirely. Use nullable injection via Koin. The PoseEstimator is only created in androidMain's platformModule and injected as `PoseEstimator?` into the domain layer. iOS simply doesn't provide it.

```kotlin
// androidMain platformModule
single<PoseEstimator> { AndroidPoseEstimator(get()) }

// commonMain - consumer
class CvFormCheckManager(
    private val poseEstimator: PoseEstimator?,  // null on iOS
    private val formRuleEngine: FormRuleEngine
)
```

This matches the existing pattern used for `LedFeedbackController?` and `ExerciseDetectionManager?`.

#### MediaPipe Integration Details

**Dependencies (androidApp/build.gradle.kts):**
```kotlin
// MediaPipe Pose Landmarker
implementation("com.google.mediapipe:tasks-vision:0.10.20")

// CameraX (already compatible with min SDK 26)
implementation("androidx.camera:camera-core:1.5.1")
implementation("androidx.camera:camera-camera2:1.5.1")
implementation("androidx.camera:camera-lifecycle:1.5.1")
implementation("androidx.camera:camera-compose:1.5.1")
```

**Model asset:** `pose_landmarker_lite.task` (~5MB) placed in `androidApp/src/main/assets/`.

**Running mode:** `LIVE_STREAM` for real-time workout form checking.

**Threading:** MediaPipe LIVE_STREAM mode delivers results asynchronously via listener callback. CameraX ImageAnalysis can use a dedicated executor thread. Results flow into a StateFlow for Compose consumption.

#### New Manager: CvFormCheckManager

```kotlin
// commonMain - domain/cv/
class CvFormCheckManager(
    private val formRuleEngine: FormRuleEngine,
    private val exerciseFormRules: ExerciseFormRules
) {
    private val _formState = MutableStateFlow<FormCheckState>(FormCheckState.Disabled)
    val formState: StateFlow<FormCheckState> = _formState.asStateFlow()

    private val _latestViolations = MutableStateFlow<List<FormViolation>>(emptyList())
    val latestViolations: StateFlow<List<FormViolation>> = _latestViolations.asStateFlow()

    fun onJointAnglesReceived(angles: List<JointAngle>, exerciseId: String?) {
        val rules = exerciseFormRules.getRules(exerciseId)
        val violations = formRuleEngine.evaluate(angles, rules)
        _latestViolations.value = violations
    }

    fun reset() {
        _latestViolations.value = emptyList()
        _formState.value = FormCheckState.Disabled
    }
}
```

#### WorkoutCoordinator Integration

```kotlin
// WorkoutCoordinator additions
class WorkoutCoordinator(...) {
    // ... existing fields ...

    // ===== CV Form Check =====
    /**
     * CV form check manager. Null when CV is not available (iOS, older devices).
     * Set during DI construction.
     */
    var cvFormCheckManager: CvFormCheckManager? = null

    /**
     * Latest form violations for HUD display.
     * Delegates to cvFormCheckManager if available.
     */
    val latestFormViolations: StateFlow<List<FormViolation>>
        get() = cvFormCheckManager?.latestViolations
            ?: MutableStateFlow(emptyList())
}
```

This follows the exact same pattern as `ledFeedbackController` and `biomechanicsEngine`.

---

### Feature 3: Ghost Racing Overlay (Mobile, Stub Data)

**Scope:** Compose overlay composable that displays real-time vs. historical cable position. Uses stub data until portal sync ships in v0.5.5+.

#### Architecture: Data Model

```kotlin
// commonMain - domain/model/
data class GhostRaceData(
    val ghostPositions: List<Float>,      // Historical cable positions, time-indexed
    val ghostTimestamps: List<Long>,      // Timestamps for each position
    val ghostMcvPerRep: List<Float>,      // Per-rep MCV from historical session
    val exerciseName: String,
    val weightKg: Float,
    val totalReps: Int
)

data class GhostRaceState(
    val isActive: Boolean = false,
    val ghostData: GhostRaceData? = null,
    val currentRepComparison: RepComparison? = null  // "AHEAD" or "BEHIND"
)

data class RepComparison(
    val repNumber: Int,
    val currentMcv: Float,
    val ghostMcv: Float,
    val verdict: GhostVerdict  // AHEAD, BEHIND, TIED
)
```

#### Integration Point

```kotlin
// commonMain - presentation/manager/
class GhostRaceManager {
    private val _raceState = MutableStateFlow(GhostRaceState())
    val raceState: StateFlow<GhostRaceState> = _raceState.asStateFlow()

    // Called by ActiveSessionEngine when ghost racing is active
    fun onCurrentMetric(position: Float, timestamp: Long) { ... }
    fun onRepCompleted(repNumber: Int, mcv: Float) { ... }
    fun startRace(ghostData: GhostRaceData) { ... }
    fun stopRace() { ... }
}
```

**Data source for v0.5.0:** Stub/demo data baked into the app. No portal query needed. The `GhostRaceManager` has a `loadStubData()` method that creates synthetic ghost race data from the last completed set in the current session, enabling dogfooding without backend infrastructure.

#### UI Component Location

```
commonMain/presentation/components/premium/
  |-- GhostRaceOverlay.kt         -- Side-by-side animated bars
  |-- RepComparisonBadge.kt       -- "AHEAD +12mm/s" or "BEHIND -8mm/s"
```

These are standard Compose components in commonMain (no platform-specific code needed for the overlay).

---

### Feature 4: RPG Attribute Card (Mobile, Stub Data)

**Scope:** Compact composable card showing 5 RPG attributes (Strength, Power, Stamina, Consistency, Mastery), character class, and overall level. Stub data until portal sync ships.

#### Data Model

```kotlin
// commonMain - domain/model/
data class RpgAttributes(
    val strengthLevel: Int = 1,
    val strengthXp: Long = 0,
    val powerLevel: Int = 1,
    val powerXp: Long = 0,
    val staminaLevel: Int = 1,
    val staminaXp: Long = 0,
    val consistencyLevel: Int = 1,
    val consistencyXp: Long = 0,
    val masteryLevel: Int = 1,
    val masteryXp: Long = 0,
    val characterClass: String = "Initiate",
    val overallLevel: Int = 1
)
```

#### Architecture Decision: Local Computation vs Portal-Only

**For v0.5.0: Stub data with local computation fallback.**

The RPG XP formulas are defined in the design doc (portal `rpg.ts`). Implement a Kotlin equivalent in `commonMain/domain/rpg/RpgCalculator.kt` that computes attributes from local workout history. This enables the card to show real (approximate) data even before portal sync exists.

```kotlin
// commonMain/domain/rpg/RpgCalculator.kt
class RpgCalculator {
    fun calculateAttributes(
        recentSessions: List<WorkoutSession>,
        recentRepMetrics: List<RepMetricData>,
        currentStreak: Int,
        avgQualityScore: Float
    ): RpgAttributes { ... }
}
```

#### UI Component

```
commonMain/presentation/components/premium/
  |-- RpgAttributeCard.kt       -- Radar chart + level numbers + class badge
```

Placed on the Profile/Gamification screen, gated behind PHOENIX tier via `FeatureGate.Feature.RPG_ATTRIBUTES` (new enum value).

---

### Feature 5: Pre-Workout Briefing (Mobile, Stub Data)

**Scope:** Card shown before starting a workout that displays readiness score and weight recommendations. Stub data until portal fatigue model ships.

#### Data Model

```kotlin
// commonMain - domain/model/
data class PreWorkoutBriefing(
    val readinessScore: Int,                    // 0-100
    val readinessLevel: ReadinessLevel,         // GREEN, YELLOW, RED
    val muscleGroupReadiness: Map<String, Int>, // per-muscle scores
    val recommendation: String,                 // "Execute as planned" etc.
    val weightAdjustmentPercent: Int?,          // null = no adjustment, -10 = reduce 10%
    val isStubData: Boolean = true              // Flag for UI badge "Preview"
)

enum class ReadinessLevel { GREEN, YELLOW, RED }
```

#### Architecture

```kotlin
// commonMain - domain/readiness/
class ReadinessBriefingProvider(
    private val workoutRepository: WorkoutRepository,
    private val gamificationRepository: GamificationRepository
) {
    suspend fun getBriefing(exerciseId: String?): PreWorkoutBriefing {
        // v0.5.0: Local heuristic based on:
        // - Time since last workout targeting same muscle group
        // - Recent volume trend
        // - Streak status
        // Returns stub-flagged data
    }
}
```

#### UI Component

```
commonMain/presentation/components/premium/
  |-- PreWorkoutBriefingCard.kt  -- Readiness gauge + recommendation + weight hint
```

Shown on the SetReady screen (before starting a set), gated behind ELITE tier.

---

## FeatureGate Extensions

### New Feature Enum Values

```kotlin
enum class Feature {
    // Existing...
    FORCE_CURVES, PER_REP_METRICS, VBT_METRICS, PORTAL_SYNC,
    LED_BIOFEEDBACK, REP_QUALITY_SCORE,
    ASYMMETRY_ANALYSIS, AUTO_REGULATION, SMART_SUGGESTIONS,
    WORKOUT_REPLAY, STRENGTH_ASSESSMENT, PORTAL_ADVANCED_ANALYTICS,

    // NEW for v0.5.0
    CV_FORM_CHECK,          // PHOENIX tier - basic form warnings
    CV_ANALYTICS,           // ELITE tier - full postural analytics
    GHOST_RACING_BASIC,     // PHOENIX tier - rep summary comparison
    GHOST_RACING_FULL,      // ELITE tier - 50Hz position overlay
    RPG_ATTRIBUTES,         // PHOENIX tier - attribute card
    PRE_WORKOUT_BRIEFING    // ELITE tier - readiness + AI suggestions
}
```

**Gating pattern remains the same:** Data capture for all tiers, UI display gated at composable level. CV camera frames are never stored (privacy). Only computed FormScore and FormViolation records are persisted.

---

## Koin DI Changes

### domainModule Additions

```kotlin
val domainModule = module {
    // ... existing ...

    // CV Form Check (commonMain logic)
    single { FormRuleEngine() }
    single { JointAngleCalculator() }
    single { ExerciseFormRules() }
    factory { CvFormCheckManager(get(), get()) }

    // RPG Calculator
    single { RpgCalculator() }

    // Readiness Briefing
    factory { ReadinessBriefingProvider(get(), get()) }

    // Ghost Race
    factory { GhostRaceManager() }
}
```

### androidMain platformModule Additions

```kotlin
// androidMain platformModule
val platformModule = module {
    // ... existing BLE, SQLite driver ...

    // MediaPipe Pose Estimator (Android-only)
    single { AndroidPoseEstimator(androidContext()) }
}
```

### presentationModule Additions

No new ViewModels needed. The new managers integrate into WorkoutCoordinator (same as BiomechanicsEngine pattern). The composables consume StateFlows from existing ViewModel hierarchy.

---

## Database Schema Changes (v16)

### RepMetric Table Extensions

```sql
-- New columns on RepMetric (migration v16)
ALTER TABLE RepMetric ADD COLUMN meanConcentricVelocityMmS REAL;
ALTER TABLE RepMetric ADD COLUMN peakVelocityMmS REAL;
ALTER TABLE RepMetric ADD COLUMN velocityZone TEXT;
ALTER TABLE RepMetric ADD COLUMN velocityLossPercent REAL;
ALTER TABLE RepMetric ADD COLUMN normalizedForceCurve TEXT;
ALTER TABLE RepMetric ADD COLUMN stickingPointPct REAL;
ALTER TABLE RepMetric ADD COLUMN strengthProfile TEXT;
ALTER TABLE RepMetric ADD COLUMN asymmetryPercent REAL;
ALTER TABLE RepMetric ADD COLUMN dominantSide TEXT;
```

### WorkoutSession Set Summary Extensions

```sql
-- Set-level biomechanics summary (migration v16)
ALTER TABLE WorkoutSession ADD COLUMN avgMcvMmS REAL;
ALTER TABLE WorkoutSession ADD COLUMN sessionPeakVelocityMmS REAL;
ALTER TABLE WorkoutSession ADD COLUMN totalVelocityLossPercent REAL;
ALTER TABLE WorkoutSession ADD COLUMN avgAsymmetryPercent REAL;
ALTER TABLE WorkoutSession ADD COLUMN sessionDominantSide TEXT;
ALTER TABLE WorkoutSession ADD COLUMN sessionStrengthProfile TEXT;
ALTER TABLE WorkoutSession ADD COLUMN avgForceCurve TEXT;
```

### CV Form Data (New Table)

```sql
-- Form assessment per session+exercise (migration v16)
CREATE TABLE FormAssessment (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sessionId TEXT NOT NULL,
    exerciseId TEXT,
    formScore REAL NOT NULL,
    violationCount INTEGER NOT NULL DEFAULT 0,
    criticalViolationCount INTEGER NOT NULL DEFAULT 0,
    jointAnglesSummary TEXT,          -- JSON: {"knee": {"min": 85, "max": 170, "avg": 120}, ...}
    createdAt INTEGER NOT NULL,
    FOREIGN KEY (sessionId) REFERENCES WorkoutSession(id) ON DELETE CASCADE
);

CREATE INDEX idx_form_assessment_session ON FormAssessment(sessionId);

-- Individual form violations (migration v16)
CREATE TABLE FormViolation (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    assessmentId INTEGER NOT NULL,
    repNumber INTEGER NOT NULL,
    joint TEXT NOT NULL,
    angleDegrees REAL NOT NULL,
    thresholdDegrees REAL NOT NULL,
    severity TEXT NOT NULL,           -- "INFO", "WARNING", "CRITICAL"
    message TEXT NOT NULL,
    timestampMs INTEGER NOT NULL,
    FOREIGN KEY (assessmentId) REFERENCES FormAssessment(id) ON DELETE CASCADE
);

CREATE INDEX idx_form_violation_assessment ON FormViolation(assessmentId);
```

---

## Complete New Component Inventory

### New Files (CREATE)

| File | Location | Purpose |
|------|----------|---------|
| `JointAngleCalculator.kt` | commonMain/domain/cv/ | Pure math: landmarks -> angles |
| `FormRuleEngine.kt` | commonMain/domain/cv/ | Rule evaluation against angles |
| `ExerciseFormRules.kt` | commonMain/domain/cv/ | Per-exercise rule data |
| `CvModels.kt` | commonMain/domain/cv/ | PoseLandmark, JointAngle, FormViolation, FormScore |
| `CvFormCheckManager.kt` | commonMain/domain/cv/ | Orchestrates form checking from angles |
| `RpgCalculator.kt` | commonMain/domain/rpg/ | Local RPG attribute computation |
| `RpgModels.kt` | commonMain/domain/model/ | RpgAttributes, CharacterClass |
| `ReadinessBriefingProvider.kt` | commonMain/domain/readiness/ | Local readiness heuristic |
| `ReadinessModels.kt` | commonMain/domain/model/ | PreWorkoutBriefing, ReadinessLevel |
| `GhostRaceManager.kt` | commonMain/presentation/manager/ | Ghost race state + comparison |
| `GhostRaceModels.kt` | commonMain/domain/model/ | GhostRaceData, GhostRaceState |
| `GhostRaceOverlay.kt` | commonMain/presentation/components/premium/ | Overlay composable |
| `RepComparisonBadge.kt` | commonMain/presentation/components/premium/ | Ahead/Behind indicator |
| `RpgAttributeCard.kt` | commonMain/presentation/components/premium/ | Attribute radar chart |
| `PreWorkoutBriefingCard.kt` | commonMain/presentation/components/premium/ | Readiness card |
| `PoseAnalyzerHelper.kt` | androidMain/cv/ | MediaPipe PoseLandmarker wrapper |
| `CameraXProvider.kt` | androidMain/cv/ | CameraX lifecycle management |
| `PoseOverlayView.kt` | androidMain/cv/ | Skeleton overlay Compose Canvas |
| `AndroidPoseEstimator.kt` | androidMain/cv/ | Platform impl connecting CameraX -> MediaPipe |
| `FormAssessmentRepository.kt` | commonMain/data/repository/ | Interface for form data CRUD |
| `SqlDelightFormAssessmentRepository.kt` | commonMain/data/repository/ | SQLDelight impl |

### Modified Files (EDIT)

| File | What Changes |
|------|-------------|
| `VitruvianDatabase.sq` | Migration v16: new columns + new tables |
| `domain/model/RepMetricData.kt` (or Models.kt) | Add biomechanics fields |
| `data/repository/RepMetricRepository.kt` | Map new biomechanics columns |
| `presentation/manager/ActiveSessionEngine.kt` | Populate biomechanics in RepMetricData, integrate CvFormCheckManager |
| `presentation/manager/WorkoutCoordinator.kt` | Add cvFormCheckManager, ghostRaceManager refs |
| `domain/premium/FeatureGate.kt` | Add new Feature enum values + tier mapping |
| `di/DomainModule.kt` | Wire new engines/managers |
| `di/DataModule.kt` | Wire FormAssessmentRepository |
| `androidApp/build.gradle.kts` | Add MediaPipe + CameraX dependencies |
| `androidMain/di/platformModule` | Provide AndroidPoseEstimator |
| `iosMain/di/platformModule` | No-op for CV (null injection) |

---

## Suggested Build Order

Build order follows dependency chain and leaves app buildable after each phase.

### Phase 1: Biomechanics Persistence (foundation, no new dependencies)
1. Schema migration v16 (add columns to RepMetric + WorkoutSession)
2. Extend RepMetricData model with biomechanics fields
3. Update SqlDelightRepMetricRepository to map new columns
4. Modify ActiveSessionEngine to populate biomechanics fields
5. Verify with existing characterization tests
6. Update saveWorkoutSession to include set-level biomechanics

**Rationale:** Zero risk. No new libraries. Existing data flow, just persisting what was already computed. All other features can build on persisted data.

### Phase 2: CV Form Check - Domain Logic (no platform dependencies)
1. Create CvModels.kt (PoseLandmark, JointAngle, FormViolation, FormScore)
2. Create JointAngleCalculator.kt (pure math, heavily testable)
3. Create ExerciseFormRules.kt (data definitions per exercise)
4. Create FormRuleEngine.kt (rule evaluation)
5. Create CvFormCheckManager.kt (orchestration)
6. Wire into DomainModule
7. Unit tests for angle calculations and rule evaluation

**Rationale:** All pure Kotlin in commonMain. Can be developed and tested without camera hardware. Establishes the interface contract that androidMain will implement against.

### Phase 3: CV Form Check - Android Integration
1. Add MediaPipe + CameraX dependencies to androidApp/build.gradle.kts
2. Create PoseAnalyzerHelper.kt (MediaPipe wrapper)
3. Create CameraXProvider.kt (camera lifecycle)
4. Create AndroidPoseEstimator.kt (connects CameraX -> MediaPipe -> JointAngles)
5. Create PoseOverlayView.kt (skeleton drawing)
6. Wire into platformModule
7. Integration test with device camera

**Rationale:** Depends on Phase 2 interfaces. Heavy platform work isolated in androidMain. CameraX lifecycle management is the trickiest part.

### Phase 4: CV Form Check - UI + Persistence
1. Create FormAssessmentRepository + SqlDelight impl
2. Add FormAssessment/FormViolation tables to schema v16
3. Create HUD warning overlay composable
4. Create PiP camera preview composable
5. Integrate "Enable Form Check" toggle on Active Workout Screen
6. Wire violation persistence at set completion
7. Add FeatureGate.Feature.CV_FORM_CHECK

**Rationale:** Depends on Phases 2-3. Persistence and UI are the final layer.

### Phase 5: Premium UI Composables (parallel, independent)
1. Create GhostRaceOverlay + GhostRaceManager with stub data
2. Create RpgAttributeCard + RpgCalculator with local computation
3. Create PreWorkoutBriefingCard + ReadinessBriefingProvider with local heuristic
4. Add FeatureGate entries for all new features
5. Place components on appropriate screens with tier gating

**Rationale:** These are self-contained UI features with stub backends. No dependencies on each other or on CV. Can be built in parallel by multiple developers.

---

## Anti-Patterns to Avoid

### Anti-Pattern 1: Putting MediaPipe in commonMain
**What:** Trying to abstract MediaPipe behind expect/actual at the SDK level
**Why bad:** MediaPipe Android SDK has complex lifecycle (GPU delegates, model loading, context requirements). Abstracting at this level creates a leaky abstraction that breaks on every SDK update.
**Instead:** Abstract at the "joint angles" level. MediaPipe is an implementation detail of androidMain. CommonMain only sees `List<JointAngle>`.

### Anti-Pattern 2: Separate BiomechanicsRepMetric table
**What:** Creating a new table for biomechanics data instead of extending RepMetric
**Why bad:** Every per-rep query now requires a JOIN. The biomechanics data is 1:1 with RepMetricData and computed from the same source data in the same code path.
**Instead:** Add columns to RepMetric. NULL values for pre-v16 data are natural and handled by Kotlin nullable types.

### Anti-Pattern 3: Real-time form score in WorkoutCoordinator state
**What:** Adding formScore as a continuously-updated StateFlow that recalculates on every camera frame
**Why bad:** 30fps frame updates would cause excessive recomposition. Form violations are event-driven (only when threshold exceeded), not continuous.
**Instead:** Use event-based violation flow. Only emit when a new violation is detected. Use throttling (max 1 warning per 3 seconds) to avoid alert fatigue.

### Anti-Pattern 4: Camera permission in commonMain
**What:** Trying to handle camera permissions in shared code
**Why bad:** Permissions are fundamentally platform-specific (Android runtime permissions vs iOS Info.plist). No useful abstraction exists.
**Instead:** Handle in Android's MainActivity/Activity. The CvFormCheckManager in commonMain only cares about `isAvailable(): Boolean` which checks both platform support AND permission status.

### Anti-Pattern 5: Blocking camera analysis on main thread
**What:** Processing MediaPipe results synchronously on the UI thread
**Why bad:** MediaPipe inference takes 10-30ms per frame. At 30fps this would cause jank.
**Instead:** CameraX ImageAnalysis runs on a dedicated executor. MediaPipe LIVE_STREAM mode delivers results asynchronously. Results flow into StateFlow which Compose observes on the main thread (lightweight).

---

## Scalability Considerations

| Concern | Current (v0.4.7) | v0.5.0 Impact | Mitigation |
|---------|-------------------|---------------|------------|
| RepMetric row size | ~2KB per rep (curve data) | ~2.5KB per rep (+biomechanics) | Marginal increase, under 25% |
| Camera battery drain | N/A | Significant (CameraX + ML) | Form Check is opt-in toggle. Auto-disable after set ends. |
| Memory: model loading | N/A | ~15-30MB for pose_landmarker_lite.task | Load lazily on first Form Check enable. Unload when disabled. |
| Database migration | 15 migrations | 1 more (v16) | ALTER TABLE ADD COLUMN is non-destructive |
| DI graph complexity | 4 modules, ~30 bindings | ~40 bindings (+10) | All new bindings follow existing patterns |
| WorkoutCoordinator size | 306 lines | ~330 lines (+2 nullable refs) | Minimal growth. Managers contain logic. |
| ActiveSessionEngine size | ~2,600 lines | ~2,700 lines (+biomechanics population) | Most new logic in new manager classes |

---

## Sources

- [MediaPipe Pose Landmarker Android Guide](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker/android) -- Official Google docs, HIGH confidence
- [MediaPipe Samples - PoseLandmarkerHelper.kt](https://github.com/google-ai-edge/mediapipe-samples/blob/main/examples/pose_landmarker/android/app/src/main/java/com/google/mediapipe/examples/poselandmarker/PoseLandmarkerHelper.kt) -- Reference implementation, HIGH confidence
- [AI Vision on Android: CameraX + MediaPipe + Compose](https://www.droidcon.com/2025/01/24/ai-vision-on-android-camerax-imageanalysis-mediapipe-compose/) -- Integration pattern, MEDIUM confidence
- [MediaPiper - KMP MediaPipe Samples](https://github.com/2BAB/mediapiper) -- KMP abstraction pattern reference, MEDIUM confidence
- [CameraX 1.5 Release](https://developer.android.com/jetpack/androidx/releases/camera) -- Version info, HIGH confidence
- [Maven: com.google.mediapipe:tasks-vision](https://mvnrepository.com/artifact/com.google.mediapipe/tasks-vision) -- Version info, HIGH confidence
- Direct codebase analysis of WorkoutCoordinator, ActiveSessionEngine, BiomechanicsEngine, RepMetricRepository, FeatureGate -- PRIMARY source, HIGH confidence
- `docs/plans/2026-02-20-premium-enhancements-design.md` -- Internal design doc, HIGH confidence
