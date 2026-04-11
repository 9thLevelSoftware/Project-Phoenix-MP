# Strength Assessment: Official Vitruvian App vs Project Phoenix

> **Generated**: 2026-02-17
> **Researcher**: Claude Opus 4.6 (AI-assisted decompilation analysis)
> **Scope**: Assessment flow, 1RM estimation, strength scoring, protocol details, results storage, workout recommendations, reassessment

---

## Executive Summary

The official Vitruvian app has a **complete, multi-phase guided strength assessment** that measures peak force via BLE sensors, stores results server-side, and uses them to drive workout weight recommendations across the entire app. Project Phoenix has **no dedicated assessment flow** but possesses several building blocks (1RM formulas, PR system, force tracking) that could be composed into one. The largest gap is not technical capability but *user experience orchestration* -- Phoenix has the raw data infrastructure but no guided workflow to populate it.

---

## 1. Assessment Flow Architecture

### 1.1 Official App: Full Assessment Module

The official app has a dedicated assessment package at `com.vitruvian.app.ui.assessment/` with 20+ files implementing a 7-page guided flow.

#### Key Source Files (Official App)

| File | Location | Purpose |
|------|----------|---------|
| `StrengthAssessmentViewModel.java` | `VitruvianDeobfuscated/.../assessment/` | Main ViewModel: 4-state machine, coroutine orchestration |
| `d.java` | `VitruvianDeobfuscated/.../assessment/` | 7-page Compose UI (switch on `interfaceC6871c.c()` pages 0-6) |
| `InterfaceC6871c.java` | `VitruvianDeobfuscated/.../si/` | AssessmentState interface: 19 callbacks |
| `C6870b.java` | `VitruvianDeobfuscated/.../si/` | AssessmentRepositoryHolder |
| `b.java` | `VitruvianDeobfuscated/.../assessment/` | Navigation handler (Connect, Instructions, WorkoutOverview) |
| `c.java` | `VitruvianDeobfuscated/.../assessment/` | Navigation events sealed class |
| `g.java` | `VitruvianDeobfuscated/.../yi/` | BaseStrengthAssessmentPage composable |
| `i.java` (API) | `VitruvianDeobfuscated/.../Rj/` | `GET user/routines/strength-assessment` and `GET user/strength-score` |

#### State Machine (StrengthAssessmentViewModel.a enum)

```java
public static final class a {
    START,              // ordinal 0 - Initial state
    WAIT_FOR_MOVEMENT,  // ordinal 1 - Waiting for user to begin
    GET_INTO_POSITION,  // ordinal 2 - Positioning prompt with countdown
    MID_SET             // ordinal 3 - Active assessment set
}
```

#### 7-Page Flow (from d.java switch statement)

```
Page 0: Start/Overview
  - Exercise list, skip option, finish button
  - Handles vs no-handles toggle (g() = isHandlesEnabled)
  - Navigate to class preview or workout overview

Page 1: Exercise Selection
  - Assessment routines (A().exercises)
  - Set matrix display (B() method)
  - Use handles toggle (m() = shouldUseHandles)
  - Back, Finish, Start Routine actions

Page 2: Warmup / Calibration
  - "Complete N calibration reps" prompt
  - Skip warmup option
  - ROM calibration before assessment sets

Page 3: Exercise Execution (Pre-Set)
  - Current exercise from routine
  - Phase transitions: START -> WAIT_FOR_MOVEMENT -> GET_INTO_POSITION -> MID_SET
  - Countdown timer for get-into-position
  - Callbacks: onConnected, onWaitForMovement, onGetIntoPosition, onStartExercise

Page 4: Active Workout (BLE Connected)
  - RoutineService integration for actual BLE workout
  - Rep tracking via RoutineSetVolume.Reps
  - **onExercisePeakForceUpdated(Force)** callback - records peak force
  - Finish exercise action

Page 5: Rest Between Exercises
  - Configurable rest timer (from routine definition)
  - Next exercise preview
  - Previous/Next exercise navigation

Page 6: Results Summary
  - Results map: Map<Exercise, Force> (q() method)
  - Per-exercise peak force display
  - Back and Finish actions
```

#### AssessmentState Interface (InterfaceC6871c) - 19 Callbacks

Decoded from obfuscated method names:

| Method | Deobfuscated Name |
|--------|-------------------|
| `b()` | onBackClicked |
| `e()` | onSkipClicked |
| `f()` | onDoneClicked |
| `i()` | onNextClicked |
| `j()` | onFinishExercise |
| `k()` | onStartExercise |
| `n()` | resetPhase |
| `o()` | onSkipWarmup |
| `u()` | onStartRoutine |
| `v()` | onNextExerciseOrResults |
| `w(Ak.a)` | onExercisePeakForceUpdated(Force) |
| `y(boolean)` | onUseHandlesChanged |

### 1.2 Project Phoenix: No Dedicated Assessment

**Gap Severity: CRITICAL**

Phoenix has zero assessment-specific code. Grep for `assessment`, `strengthTest`, `StrengthAssessment` across `shared/src/` returns no matches. The only "assessment-adjacent" feature is `OneRepMaxInputScreen.kt` which is a manual entry form tied exclusively to the 5/3/1 (Wendler) template setup.

#### Existing Building Blocks in Phoenix

| Component | Location | Relevance |
|-----------|----------|-----------|
| `OneRepMaxCalculator` | `shared/.../util/Constants.kt:78-99` | Brzycki & Epley formulas (not wired to assessment) |
| `OneRepMaxInputScreen` | `shared/.../presentation/screen/OneRepMaxInputScreen.kt` | Manual 1RM entry (5/3/1 only) |
| `WorkoutState.SetSummary` | `shared/.../domain/model/Models.kt:59-86` | Peak force fields (concentric/eccentric A/B) |
| `PersonalRecordRepository` | `shared/.../data/repository/PersonalRecordRepository.kt` | Full PR CRUD with weight + volume tracking |
| `WorkoutMetric` | `shared/.../domain/model/Models.kt:266-278` | Real-time loadA, loadB, positionA, positionB from BLE |

---

## 2. 1RM Estimation

### 2.1 Official App: Peak Force, Not 1RM

**The official app does NOT calculate 1RM from submaximal loads.** No Brzycki or Epley formulas exist in the decompiled code.

Instead, the assessment records **peak force** directly via BLE sensors:

```java
// From StrengthAssessmentViewModel.e (state implementation)
// Results are Map<Exercise, Force>
public final Map<C7404b, Ak.a> q()  // Exercise -> Force mapping

// When exercise completes, peak force is recorded
public final void methodMETHODW(Ak.a aVar) {  // aVar = "force"
    q().put(C(), aVar);  // Store force result for current exercise
}
```

The `Force` class (`Ak.a`) wraps a `double` value in kilograms:

```java
// File: VitruvianDeobfuscated/.../Ak/a.java
public final class a implements Comparable<a>, Parcelable {
    public final double f445a;  // Force in kg

    public final a d(a aVar) { return new a(this.f445a + aVar.f445a); }  // Addition
    public final a h(double d10) { return new a(this.f445a * d10); }     // Multiplication
    public final double q(v vVar) {  // Unit conversion
        if (vVar.ordinal() == 0) return f445a;          // kg
        if (vVar.ordinal() == 1) return f445a * 2.20462d; // lb
    }
    public final String toString() { return this.f445a + "kg"; }
}
```

### 2.2 Project Phoenix: Brzycki & Epley Formulas

**Gap Severity: LOW (Phoenix is ahead here)**

Phoenix has both standard 1RM estimation formulas:

```kotlin
// File: shared/.../util/Constants.kt:78-99
object OneRepMaxCalculator {
    fun brzycki(weight: Float, reps: Int): Float {
        if (reps <= 0) return 0f
        if (reps == 1) return weight
        return weight * (36f / (37f - reps))
    }

    fun epley(weight: Float, reps: Int): Float {
        if (reps <= 0) return 0f
        if (reps == 1) return weight
        return weight * (1f + reps / 30f)
    }
}
```

The `Exercise` data class stores 1RM directly:

```kotlin
data class Exercise(
    // ...
    val oneRepMaxKg: Float? = null // User's 1RM for percentage-based programming
)
```

**Key difference**: Phoenix can estimate 1RM from *any* submaximal set (passive data collection), while the official app requires a dedicated assessment session to get peak force data.

**Key gap**: Phoenix's `OneRepMaxCalculator` is implemented but **not wired into the set completion flow**. It is only used in the manual 1RM input screen for Wendler 5/3/1 setup.

---

## 3. Strength Scoring

### 3.1 Official App: Server-Side Strength Scoring

**Gap Severity: HIGH**

The official app has a server-backed strength scoring system:

#### API Endpoint
```java
// File: VitruvianDeobfuscated/.../Rj/i.java
@Qo.f("user/strength-score")
Object J(InterfaceC6585d<? super u> interfaceC6585d);
```

#### Data Models

**UserStrengthScore** (`Vk/u.java`):
```java
// Serialized name: "com.vitruvian.data.model.UserStrengthScore"
public final class u {
    public final Map<String, r> f66847a;  // Map<exerciseId, UserExerciseStrengthScore>
}
```

**UserExerciseStrengthScore** (`Vk/r.java`):
```java
// Serialized name: "com.vitruvian.data.model.UserExerciseStrengthScore"
public final class r implements Parcelable {
    public final Ak.a f66826a;  // Force value (single field)
}
```

**ReferenceForceLookups** (`Xj/P.java`) - The 3-map architecture:
```java
public final class P implements Parcelable {
    public final Map<String, zk.g> f22620a;     // pbByExerciseId
    public final Map<wk.f, zk.g> f22621b;       // pbByMovement
    public final Map<String, vk.r> f22622c;      // strengthScoreByExerciseId

    public final String toString() {
        return "ReferenceForceLookups(pbByExerciseId=" + f22620a +
               ", pbByMovement=" + f22621b +
               ", strengthScoreByExerciseId=" + f22622c + ")";
    }
}
```

#### Feature Gating

Strength assessment is subscription-gated and feature-flagged:

```java
// File: VitruvianDeobfuscated/.../Vk/o.java
// toString() reveals: "SubscriptionFeatureSet(...strengthAssessment=..., workoutSounds=...)"
public final C7285b f66804g;  // strengthAssessment field (boolean wrapper)

// LaunchDarkly feature flag
// File: VitruvianDeobfuscated/.../Ik/n.java
this.f48934q = U5.r.i("use-strength-score", false, arrayList);
```

### 3.2 Project Phoenix: Local Composite Score

**Gap Severity: MEDIUM**

Phoenix has a local strength score calculation, but it is a simplistic composite with no server backing:

```kotlin
// File: shared/.../presentation/components/DashboardComponents.kt:520-561
private fun calculateStrengthScore(
    personalRecords: List<PersonalRecord>,
    workoutSessions: List<WorkoutSession>
): Int {
    // PR Score: Sum of top weights per exercise * 10
    val prScore = personalRecords
        .groupBy { it.exerciseId }
        .mapValues { (_, prs) -> prs.maxOf { it.weightPerCableKg } }
        .values.sumOf { it.toDouble() } * 10

    // Volume Score: Recent volume (last 30 days) * 0.5
    val volumeScore = workoutSessions
        .filter { it.timestamp >= thirtyDaysAgo }
        .sumOf { (it.weightPerCableKg * it.totalReps * 0.5).toDouble() }

    // Consistency Score: Number of workouts in last 30 days * 5
    val consistencyScore = workoutSessions
        .count { it.timestamp >= thirtyDaysAgo } * 5

    return (prScore + volumeScore + consistencyScore).toInt()
}
```

**Key differences**:
- Official app: per-exercise force-based score from assessment, server-calculated
- Phoenix: composite of PR weights + recent volume + consistency, locally computed
- Phoenix's score rewards *activity* not just *strength*, which is a design choice, not necessarily a flaw

---

## 4. Assessment Protocol Details

### 4.1 Official App: BLE Assessment Mode (Ordinal 7)

**Gap Severity: HIGH**

The official app uses a dedicated **Assessment training mode** (ordinal 7 in the 12-mode protocol):

From `TRAINING_MODES_COMPLETE_ANALYSIS.md`:
- Mode ordinal: 7 (ASSESSMENT)
- Internal-only, not user-facing as a workout mode
- Uses **static load** (no auto-adjustment during the set)
- Records **force profiles throughout ROM**
- Only counts **complete ROM reps** (strict validation)
- Single rep validation with strict ROM boundaries

#### Progressive Effort Protocol

From `strings.xml` (lines 1305-1361):
```xml
<string name="strength_assessment_push_50">Push with 50% strength</string>
<string name="strength_assessment_push_75">Push with 75% strength</string>
<string name="strength_assessment_push_100">Push with 100% strength</string>
<string name="strength_assessment_push_harder">Push harder</string>
<string name="strength_assessment_calibration_reps">Complete %1$s calibration reps</string>
<string name="take_strength_assessment_explanation">
    It's a short workout (~20 minutes) to measure your strength level.
</string>
<string name="strength_assessment_your_results">
    Your results will act as a performance benchmark, enabling precise weight
    recommendations for a variety of related exercises.
</string>
```

This confirms the assessment uses a **progressive ramp**: 50% -> 75% -> 100% -> "push harder" to find peak force.

#### Calibration Reps

Before each exercise, the user performs calibration reps to establish their range of motion. This ensures the machine's ROM boundaries match the user's actual movement for accurate rep counting.

#### Assessment Routines

Assessment routines are **cloud-backed** and fetched from Vitruvian servers:
```java
// File: VitruvianDeobfuscated/.../Rj/i.java
@Qo.f("user/routines/strength-assessment")
Object I(InterfaceC6585d<? super List<C7607d>> interfaceC6585d);
```

These are pre-defined routines (not user-created) with:
- Specific exercises in sequence
- Defined sets with rep targets (RoutineSetVolume.Reps)
- Handles/no-handles variants
- ~20 minute total duration

### 4.2 Project Phoenix: No Assessment Protocol

Phoenix has no equivalent protocol. The closest infrastructure is the existing workout execution flow which tracks all necessary data points:

```kotlin
// File: shared/.../domain/model/Models.kt:59-86
data class SetSummary(
    // ...
    val peakForceConcentricA: Float = 0f,  // Peak during lifting (velocity > 0)
    val peakForceConcentricB: Float = 0f,
    val peakForceEccentricA: Float = 0f,   // Peak during lowering (velocity < 0)
    val peakForceEccentricB: Float = 0f,
    val avgForceConcentricA: Float = 0f,
    val avgForceConcentricB: Float = 0f,
    val avgForceEccentricA: Float = 0f,
    val avgForceEccentricB: Float = 0f,
    // ...
)
```

Phoenix already captures **more granular force data** than the official assessment (concentric/eccentric, per-cable, peak and average), but only during regular workouts, not in a structured assessment context.

---

## 5. Results Storage

### 5.1 Official App: PB Lookups + Server Storage

**Gap Severity: MEDIUM**

Results follow a 3-tier storage architecture:

1. **In-Memory**: `Map<Exercise, Force>` during assessment session
2. **Local Cache**: `ReferenceForceLookups` Parcelable with 3 maps:
   - `pbByExerciseId`: Per-exercise personal bests
   - `pbByMovement`: PBs by movement type (allows cross-exercise recommendations)
   - `strengthScoreByExerciseId`: Per-exercise strength scores
3. **Server**: `GET user/strength-score` returns `UserStrengthScore` (Map of exerciseId -> Force)

The `UserExerciseRepository` (`Xj/d0.java`) provides access via:
- `pbsByExerciseId()` - Individual exercise PBs
- `referenceForceLookups()` - Full lookup structure
- `sortedExercises()` - Exercise list ordered by score

### 5.2 Project Phoenix: Local SQLite + In-Memory

Phoenix stores results locally with no server sync for assessment data:

```sql
-- File: shared/.../sqldelight/.../VitruvianDatabase.sq
CREATE TABLE PersonalRecord (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    exerciseId TEXT NOT NULL,
    exerciseName TEXT NOT NULL,
    weight REAL NOT NULL,
    reps INTEGER NOT NULL,
    oneRepMax REAL NOT NULL,
    achievedAt INTEGER NOT NULL,
    workoutMode TEXT NOT NULL,
    prType TEXT NOT NULL DEFAULT 'MAX_WEIGHT',
    volume REAL NOT NULL DEFAULT 0.0,
    updatedAt INTEGER,
    serverId TEXT,
    deletedAt INTEGER
);
CREATE UNIQUE INDEX idx_pr_unique ON PersonalRecord(exerciseId, workoutMode, prType);
```

Key differences from official app:
- Phoenix has a `oneRepMax` field that the official app lacks (since official uses raw force)
- Phoenix has `volume` (weight x reps) and dual PR types that the official app lacks
- Phoenix has `workoutMode` per PR, recognizing mode-specific records
- Phoenix lacks the `pbByMovement` cross-exercise lookup the official app uses
- Phoenix's `serverId` field exists for future sync but is unused

---

## 6. Workout Weight Recommendations

### 6.1 Official App: PB-Based Recommendations

**Gap Severity: MEDIUM**

The official app uses `ReferenceForceLookups.pbByExerciseId` and `pbByMovement` to suggest weights:
- Workout builder references `pbLookups` for weight suggestions
- Just Lift mode references `pbLookups`
- Exercise detail views show PB data
- The `pbByMovement` map enables **cross-exercise inference** (e.g., knowing your squat PB helps estimate leg press recommendations)

### 6.2 Project Phoenix: 1RM-Percentage and Last-Used

Phoenix uses two approaches:

**1. 1RM percentage (for 5/3/1 templates)**:
```kotlin
// File: shared/.../domain/model/TemplateModels.kt
fun calculateSetWeight(oneRepMaxKg: Float, percentageSet: PercentageSet): Float {
    val trainingMax = oneRepMaxKg * 0.9f  // 90% training max
    val rawWeight = trainingMax * percentageSet.percent
    return (rawWeight * 2).toInt() / 2f   // Round to 0.5kg
}
```

**2. Default weight from 1RM**:
```kotlin
// File: shared/.../domain/model/ExerciseConfig.kt
val weight = oneRepMaxKg?.let { (it * 0.70f * 2).toInt() / 2f } ?: 0f
// Default: 70% of 1RM, rounded to 0.5kg
```

**3. Workout parameters with PR/last-used weight**:
```kotlin
// File: shared/.../domain/model/Models.kt
data class WorkoutParameters(
    // ...
    val lastUsedWeightKg: Float? = null,  // Last used weight for quick preset
    val prWeightKg: Float? = null,        // PR weight for quick preset
)
```

**Key difference**: Phoenix's recommendations are more principled (training max calculations, percentage-based programming) but only work if the user has manually entered 1RM data. The official app's assessment auto-populates the PB system, making recommendations work immediately.

---

## 7. Reassessment

### 7.1 Official App: Checklist-Based

**Gap Severity: LOW**

The official app prompts reassessment via:
- Dashboard onboarding: `dashboard_onboarding_strength_assessment_title`
- User checklist: `checklist_strength_assessment_title`
- Direct navigation: `strengthassessment/instructions?exerciseId=...`

No periodic/automatic reassessment trigger was found in the decompiled code. It appears to be a one-time onboarding action with manual re-triggers.

### 7.2 Project Phoenix: No Reassessment

No reassessment flow exists. There is also no onboarding checklist that would prompt an initial assessment.

---

## 8. Reps Data Model (BLE Protocol)

Both apps read from the same BLE characteristic for rep tracking:

```java
// UUID: 8308f2a6-0875-4a94-a86f-5c5c5e1b068a
// 24-byte packets, little-endian
public final class Reps {
    private final int up;              // Concentric rep count
    private final int down;            // Eccentric rep count
    private final float rangeTop;      // ROM ceiling (default 300.0)
    private final float rangeBottom;   // ROM floor (default 0.0)
    private final Short repsRomCount;  // Current ROM-validated rep count
    private final Short repsRomTotal;  // Total ROM-validated reps target
    private final Short repsSetCount;  // Current set rep count
    private final Short repsSetTotal;  // Total set reps target
}
```

Phoenix reads the same BLE data but does not have the `repsRomCount`/`repsRomTotal` fields surfaced in its domain model -- it relies on its own position-based rep detection algorithm.

---

## 9. Comparison Table

| Feature | Official Vitruvian App | Project Phoenix | Gap Severity |
|---------|----------------------|-----------------|:------------:|
| **Dedicated Assessment Flow** | 7-page guided assessment with state machine | None | CRITICAL |
| **Assessment Trigger** | Onboarding checklist + dashboard prompt | N/A | HIGH |
| **BLE Assessment Mode** | Mode ordinal 7 (static load, force profiling) | Not implemented | HIGH |
| **Progressive Effort** | 50% -> 75% -> 100% -> "push harder" prompts | None | HIGH |
| **Calibration Reps** | ROM calibration before each exercise | None | MEDIUM |
| **Assessment Routines** | Cloud-backed pre-defined routines (~20 min) | None | HIGH |
| **What is Measured** | Peak force per exercise (BLE sensors) | N/A (manual 1RM only) | CRITICAL |
| **1RM Formula** | Not used (peak force based) | Brzycki + Epley (implemented, not wired) | LOW (Phoenix ahead) |
| **1RM Storage** | PB lookup system (server-backed) | Exercise.oneRepMaxKg + PersonalRecord table | MEDIUM |
| **Strength Scoring** | Per-exercise force-based, server-calculated | Composite (PR + volume + consistency), local | HIGH |
| **Score API** | `GET user/strength-score` | None (local only) | MEDIUM |
| **Feature Gating** | Subscription + LaunchDarkly `use-strength-score` | None (always available) | N/A |
| **PR Tracking** | PB lookups per exercise | Dual PR (MAX_WEIGHT + MAX_VOLUME) per mode | LOW (Phoenix ahead) |
| **Cross-Exercise Inference** | `pbByMovement` map enables cross-exercise recs | None | MEDIUM |
| **Weight Recommendations** | PB-based auto-fill | 70% 1RM default + 5/3/1 percentages | MEDIUM |
| **Handles Toggle** | Per-assessment toggle | Supported in workout, not assessment | MEDIUM |
| **Rest Timers** | Configurable per assessment routine | Per routine exercise | LOW |
| **Results Display** | Per-exercise peak force summary | PR history per exercise | MEDIUM |
| **State Machine** | 4-phase enum (START/WAIT/POSITION/MID_SET) | None for assessment | HIGH |
| **Reassessment** | Checklist-based (one-time with manual re-trigger) | None | LOW |
| **Force Granularity** | Peak force per exercise (single value) | Peak + avg, concentric + eccentric, per cable | LOW (Phoenix ahead) |
| **Progression System** | PB-based weight suggestions | ProgressionRule (percentage, 5/3/1, manual) | LOW (Phoenix ahead) |

---

## 10. Key Takeaways

### 10.1 What the Official App Does That Phoenix Lacks

1. **Guided Assessment UX**: A structured 7-page flow walking new users through strength testing. This is the single biggest gap -- without it, users must manually enter 1RM values or Phoenix has no baseline data.

2. **Force-Based Measurement**: Uses the machine's sensors to measure actual peak force, removing user guesswork. Phoenix already captures the same force data during workouts but does not process it for assessment purposes.

3. **Onboarding Integration**: Assessment is part of the new user experience, ensuring weight recommendations work from day one.

4. **Cross-Exercise Movement Mapping**: The `pbByMovement` lookup allows the app to infer recommendations for exercises the user has never tested, based on related movement patterns.

### 10.2 What Phoenix Already Has (Advantages)

1. **Better 1RM Math**: Brzycki and Epley formulas can estimate 1RM from any submaximal set. This enables *passive* strength tracking without requiring a dedicated assessment session.

2. **Richer Force Data**: Phoenix captures concentric/eccentric peak and average force per cable -- 8 force metrics vs the official app's single peak force value. This enables more nuanced analysis.

3. **Mode-Specific PRs**: The dual PR system (MAX_WEIGHT + MAX_VOLUME) per workout mode is more comprehensive than the official app's simple PB lookups.

4. **Sophisticated Progression**: 5/3/1 percentage-based programming with training max (90% of 1RM) is more principled than PB-based weight suggestions.

5. **No Subscription Gate**: Phoenix's strength features are not behind a paywall, unlike the official app's subscription-gated assessment.

---

## 11. Recommended Implementation Path

### Priority 1: Auto-1RM from Workout Data (Quick Win - No New UI)

**Effort: Small | Impact: High**

Wire `OneRepMaxCalculator.brzycki()` into the set completion flow:
- After each completed set, calculate estimated 1RM from `SetSummary.heaviestLiftKgPerCable` and `repCount`
- If estimated 1RM exceeds stored `Exercise.oneRepMaxKg`, update it
- This gives users 1RM data **passively** from regular workouts, zero additional UI
- The `OneRepMaxCalculator` already exists at `shared/.../util/Constants.kt:78-99` -- it just needs to be called

### Priority 2: Simple "Test Your Strength" Flow (MVP Assessment)

**Effort: Medium | Impact: High**

Create a guided assessment that reuses existing workout infrastructure:
- Define 4-6 core exercises as a local assessment routine
- Use existing workout execution flow with modified UI overlay
- Progressive weight ramping: 50%, 75%, max effort sets
- Calculate 1RM from heaviest set using Brzycki formula
- Store results in `Exercise.oneRepMaxKg` and `PersonalRecord`
- Show results summary screen

### Priority 3: Full Assessment (Official App Parity)

**Effort: Large | Impact: Medium**

- 4-state assessment state machine (mirroring `START/WAIT/POSITION/MID_SET`)
- Calibration reps for ROM establishment
- Rest timers between assessment exercises
- Progressive effort prompts (50%, 75%, 100%, "push harder")
- BLE Assessment mode (ordinal 7) integration
- Results summary with per-exercise strength profile
- Onboarding integration

### Priority 4: Advanced Features

**Effort: Large | Impact: Low**

- Periodic reassessment reminders based on training frequency/staleness
- Movement-based cross-exercise inference (equivalent to `pbByMovement`)
- Strength category system (beginner/intermediate/advanced based on bodyweight ratios)
- Assessment history tracking and progress visualization
- Community percentile comparisons

---

## 12. Source File Reference

### Official Vitruvian App (Deobfuscated)

| Absolute Path | Description |
|--------------|-------------|
| `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\com\vitruvian\app\ui\assessment\StrengthAssessmentViewModel.java` | Main assessment ViewModel |
| `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\com\vitruvian\app\ui\assessment\d.java` | 7-page Compose UI |
| `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\si\InterfaceC6871c.java` | AssessmentState interface (19 callbacks) |
| `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\si\C6870b.java` | AssessmentRepositoryHolder |
| `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\Ak\a.java` | Force data class |
| `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\Vk\r.java` | UserExerciseStrengthScore |
| `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\Vk\u.java` | UserStrengthScore |
| `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\Vk\o.java` | SubscriptionFeatureSet (strengthAssessment gate) |
| `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\Xj\P.java` | ReferenceForceLookups (3-map architecture) |
| `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\Xj\d0.java` | UserExerciseRepository |
| `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\Rj\i.java` | VitruvianApi (assessment + strength-score endpoints) |
| `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\Ik\n.java` | LaunchDarkly flags (use-strength-score) |
| `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\yi\g.java` | BaseStrengthAssessmentPage composable |
| `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\res\values\strings.xml` | Assessment UI strings (lines 1305-1361) |

### Project Phoenix

| Absolute Path | Description |
|--------------|-------------|
| `C:\Users\dasbl\AndroidStudioProjects\Project-Phoenix-MP\shared\src\commonMain\kotlin\com\devil\phoenixproject\util\Constants.kt` | OneRepMaxCalculator (lines 78-99) |
| `C:\Users\dasbl\AndroidStudioProjects\Project-Phoenix-MP\shared\src\commonMain\kotlin\com\devil\phoenixproject\domain\model\Models.kt` | PersonalRecord, PRType, WorkoutState.SetSummary, WorkoutMetric |
| `C:\Users\dasbl\AndroidStudioProjects\Project-Phoenix-MP\shared\src\commonMain\kotlin\com\devil\phoenixproject\data\repository\PersonalRecordRepository.kt` | PR repository interface (weight + volume PRs) |
| `C:\Users\dasbl\AndroidStudioProjects\Project-Phoenix-MP\shared\src\commonMain\kotlin\com\devil\phoenixproject\presentation\screen\OneRepMaxInputScreen.kt` | Manual 1RM entry (5/3/1 only) |
| `C:\Users\dasbl\AndroidStudioProjects\Project-Phoenix-MP\shared\src\commonMain\kotlin\com\devil\phoenixproject\presentation\components\DashboardComponents.kt` | calculateStrengthScore() (lines 520-561) |
| `C:\Users\dasbl\AndroidStudioProjects\Project-Phoenix-MP\shared\src\commonMain\sqldelight\com\devil\phoenixproject\database\VitruvianDatabase.sq` | PersonalRecord schema (lines 114-131) |
