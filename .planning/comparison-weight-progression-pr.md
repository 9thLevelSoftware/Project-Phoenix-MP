# Weight Progression/Regression & Personal Record Logic Comparison

**Official Vitruvian App** vs **Project Phoenix**

Date: 2026-02-17

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [In-Workout Weight Progression/Regression (BLE Protocol)](#1-in-workout-weight-progressionregression-ble-protocol)
3. [Session-to-Session (Auto) Progression](#2-session-to-session-auto-progression)
4. [Weight Regression / Deload](#3-weight-regression--deload)
5. [Personal Record Detection](#4-personal-record-detection)
6. [1RM Estimation](#5-1rm-estimation)
7. [PR Storage / Database Schema](#6-pr-storage--database-schema)
8. [PR Notification / Celebration UI](#7-pr-notification--celebration-ui)
9. [Trend Analysis & Prediction](#8-trend-analysis--prediction)
10. [Comparison Table](#comparison-table)
11. [Key Recommendations for Phoenix](#key-recommendations-for-phoenix)

---

## Executive Summary

The official Vitruvian app implements weight progression/regression as a **real-time, in-workout BLE protocol feature** built into the firmware communication layer. It does NOT have a sophisticated session-over-session auto-progression system or local PR tracking database -- that functionality was cloud-based (now defunct). Personal records were tied to leaderboard/cloud features.

Project Phoenix has gone significantly **beyond** the official app in several areas:
- Full local PR tracking with dual PR types (MAX_WEIGHT and MAX_VOLUME)
- Session-over-session auto-progression engine with RPE and rep-based triggers
- Trend analysis with linear regression, plateau detection, and anomaly detection
- Rich PR celebration UI with Lottie animations

However, Phoenix must still maintain **parity with the in-workout BLE progression/regression** protocol, which it already does via `BlePacketFactory.createProgramParams()`.

---

## 1. In-Workout Weight Progression/Regression (BLE Protocol)

### Official App

The official app's progression/regression operates **within a single set** at the BLE protocol level:

**Source:** `res/raw/trainingmodes.md` (embedded in APK)

- **Progression**: Pause at the top of the rep -> weight increases by +1, +2, or +3 kg per cable per rep
- **Regression**: Pause at the top of the rep -> weight decreases by -1, -2, or -3 kg per cable per rep
- **Available on**: Old School, Time Under Tension, Pump modes
- **NOT available on**: Eccentric Only, Echo modes

**Implementation:**

The `RegularForceConfig` (`Ek/K.java`) has a dedicated `progression` field (float at offset `f4138d`):

```java
// K.java - RegularForceConfig
public final class K implements InterfaceC1508e {
    public final short f4135a;  // spotter
    public final float f4136b;  // concentric force
    public final float f4137c;  // eccentric force
    public final float f4138d;  // progression (kg per rep, can be negative for regression)
    public final J f4139e;      // force curve (linearC1, squareC2)
}
```

The `progression` field is sent to the device firmware via BLE in the RegularPacket. The **firmware** handles the actual per-rep weight adjustment -- the app just configures the increment/decrement value.

**String resources confirm the UI:**
```xml
<string name="progression_description">
  The force per cable will be increased after each rep (up to a total of %1$s)
</string>
<string name="regression_description">
  The force per cable will be reduced after each rep.
</string>
<string name="progression_regression_description">
  The force per cable will be increased or reduced after each rep (up to a total of %1$s)
</string>
```

This tells us:
1. Progression has a **cap** (the `%1$s` placeholder) -- likely `MAX_PROGRESSION_KG`
2. The value is per-cable, not total weight
3. It adjusts **every rep**, not every set

**Key detail**: The official app has a "clear progression dialog" (`R.string.clear_progression_dialog`), suggesting users can reset/clear any progression that has been applied.

### Project Phoenix

Phoenix implements the same BLE protocol mechanism:

**Source:** `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BlePacketFactory.kt`

```kotlin
// BlePacketFactory.createProgramParams() -- Line 177-192
val adjustedWeightPerCable = if (params.progressionRegressionKg != 0f) {
    params.weightPerCableKg - params.progressionRegressionKg
} else {
    params.weightPerCableKg
}
// ...
putFloatLE(frame, 0x5c, params.progressionRegressionKg)  // progression at offset 0x5c
```

**Source:** `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Models.kt`

```kotlin
data class WorkoutParameters(
    val programMode: ProgramMode,
    val reps: Int,
    val weightPerCableKg: Float = 0f,
    val progressionRegressionKg: Float = 0f,  // +ve = progression, -ve = regression
    // ...
)
```

**Source:** `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/Constants.kt`

```kotlin
const val MAX_PROGRESSION_KG = 3f  // matches official app's +/- 1, 2, 3 kg options
```

**Verdict**: Phoenix has FULL PARITY with the official app's in-workout BLE progression/regression protocol. The `progressionRegressionKg` field maps directly to the official `K.f4138d` (progression float) in the RegularForceConfig.

---

## 2. Session-to-Session (Auto) Progression

### Official App

The official app has a `PROGRESSION` mode ordinal (ordinal 2) in its internal mode enum (`vk/n.java`), but this is documented as an **internal mode** used for "progressive overload operations" -- NOT a user-facing feature. The documented algorithm (from training modes analysis):

```
Track rep history per exercise
IF current_reps >= target_reps x 0.9:
    SUGGEST load_increase = +1 to +5 kg
ELSE IF current_reps < historical_baseline:
    SUGGEST load_decrease = -1 to -3 kg
```

However, this algorithm is described in documentation analysis, not found as concrete implementation in the decompiled code. The official app relied heavily on **cloud-based** session tracking and the leaderboard system for cross-session progress. With the cloud infrastructure now dead, this functionality is effectively **lost**.

**Key finding**: There is NO local, on-device auto-progression logic in the official app. Weight selection between sessions was manual or cloud-suggested.

### Project Phoenix

Phoenix implements a full **ProgressionUseCase** engine:

**Source:** `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/ProgressionUseCase.kt`

Two trigger mechanisms:

1. **RPE-Based Progression**: If average logged RPE is >= 2 points below target (default target RPE = 8), AND all recent sets are below target RPE, suggest an increase.

2. **Rep-Based Progression**: If the user hits target reps at the current weight for 2+ consecutive sessions, suggest an increase.

```kotlin
companion object {
    const val SESSIONS_FOR_REP_PROGRESSION = 2
    const val RPE_DIFF_THRESHOLD = 2
    const val DEFAULT_TARGET_RPE = 8
    const val MIN_SETS_FOR_ANALYSIS = 3
}
```

**Data flow:**
1. `ProgressionUseCase.checkForProgression()` analyzes recent `CompletedSet` records
2. Creates a `ProgressionEvent` with reason (REPS_ACHIEVED or LOW_RPE) and suggested weight
3. Stores in `ProgressionEvent` table via `SqlDelightProgressionRepository`
4. User sees `ProgressionSuggestionBanner` UI component with Accept/Modify/Skip options
5. Response recorded for future tuning

**Verdict**: Phoenix is **significantly ahead** of the official app here. The official app had no local auto-progression; Phoenix has a complete, tested, database-backed progression engine.

---

## 3. Weight Regression / Deload

### Official App

In-workout regression is handled identically to progression but with a negative value:
- Pause at top -> -1, -2, or -3 kg per cable per rep
- Firmware handles the decrement automatically

For adaptive modes (TUT, Pump, Beast Mode):
- **Pause at bottom**: Machine reduces load (TUT-specific)
- **Slow movement**: Auto-deload (Pump-specific)
- **Spotter activation**: Gradual load reduction when user struggles

The official app has NO session-over-session deload suggestion system.

### Project Phoenix

- **In-workout**: Same as official app via `progressionRegressionKg` (negative values)
- **Session-over-session**: The `ProgressionUseCase` currently only suggests **increases**, not decreases. There is no explicit deload/regression use case.
- **Trend analysis**: The `TrendAnalysisUseCase` can detect **plateaus** and **decreasing trends**, and provides recommendations like "Consider deloading or changing workout variables."

**Verdict**: Phoenix matches the official app for in-workout regression. For session-over-session deload, neither app has an explicit system, though Phoenix's trend analysis provides the foundation for one.

---

## 4. Personal Record Detection

### Official App

The official app's PR tracking was primarily **cloud-based**:
- Exercise data synced to Vitruvian's servers
- Leaderboard system compared users globally
- "Beats previous PR (personal record) -> 'New Record' badge" was an achievement
- No evidence of local PR database in the decompiled Room schema
- Achievement table exists (`achievements`) with types including PR badges

**Key finding from strings.xml**: No `personal_record` or `new_pr` string resources found. PR celebration was likely handled through the achievement/badge system or leaderboard notifications.

The official app tracks `peakForce` and `averageForce` per session for progress metrics, but does not appear to maintain a local "best ever" PR table per exercise.

### Project Phoenix

Phoenix has a comprehensive local PR system:

**Source:** `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Models.kt`

Two distinct PR types:
```kotlin
enum class PRType {
    MAX_WEIGHT,  // Highest weight in a single rep (strength PR)
    MAX_VOLUME   // Highest weight x reps in a single set (volume PR)
}
```

**PR Detection** happens in `SqlDelightPersonalRecordRepository.updatePRsIfBetterInternal()`:

```kotlin
// Check weight PR: new weight > current best weight for this exercise+mode
val isNewWeightPR = currentWeightPR == null || weightPerCableKg > currentWeightPR.weightPerCableKg

// Check volume PR: new (weight * reps) > current best volume for this exercise+mode
val isNewVolumePR = newVolume > currentVolume
```

**Per-mode tracking**: PRs are tracked PER exercise AND PER workout mode. A PR in Old School doesn't affect your Echo PR for the same exercise. This is a design choice that respects the fundamentally different nature of each mode.

**Verdict**: Phoenix is **far ahead** of the official app. The official app relied on cloud for PR tracking (now dead). Phoenix has a complete local PR system with dual PR types, per-mode tracking, and 1RM syncing.

---

## 5. 1RM Estimation

### Official App

The official app has an `ASSESSMENT` mode (ordinal 7) described as:
- "Designed for 1RM or strength evaluation"
- Static load per rep, no auto-adjustment
- Records force curve throughout ROM

However, no 1RM estimation **formula** (Brzycki, Epley, etc.) was found in the decompiled Java code. The official app likely computed 1RM server-side or used the Assessment mode's direct measurement.

### Project Phoenix

Phoenix implements both major 1RM formulas locally:

**Source:** `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/Constants.kt`

```kotlin
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

The Epley formula is used by default when updating PRs:
```kotlin
// SqlDelightPersonalRecordRepository.updatePRsIfBetterInternal()
val oneRepMax = if (reps == 1) weightPerCableKg else weightPerCableKg * (1 + reps / 30f)
```

And 1RM is synced to the Exercise table for percentage-based training:
```kotlin
queries.updateOneRepMax(one_rep_max_kg = oneRepMax.toDouble(), id = exerciseId)
```

**ExerciseConfig** uses 1RM for default weight suggestions:
```kotlin
// Default weight is 70% of 1RM if available
val weight = oneRepMaxKg?.let { (it * 0.70f * 2).toInt() / 2f } ?: 0f
```

There is also a dedicated `OneRepMaxInputScreen` for manual 1RM entry.

**Verdict**: Phoenix is ahead. The official app likely computed 1RM server-side; Phoenix has complete local 1RM calculation and integration with training features.

---

## 6. PR Storage / Database Schema

### Official App

```sql
-- Inferred from AppDatabase_Impl.java
CREATE TABLE achievements (
    id TEXT PRIMARY KEY,
    userId TEXT NOT NULL,
    type TEXT,          -- Includes "New Record" type
    title TEXT,
    description TEXT,
    value INT,
    unlockedAt LONG,
    FOREIGN KEY(userId) REFERENCES users(id)
);
```

No dedicated `PersonalRecord` table. PRs were tracked through:
1. The `achievements` table (badge-based)
2. Cloud leaderboard system
3. `peakForce` / `averageForce` fields on sessions

### Project Phoenix

```sql
-- VitruvianDatabase.sq
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

**Plus supporting tables:**

```sql
CREATE TABLE CompletedSet (
    id TEXT PRIMARY KEY NOT NULL,
    session_id TEXT NOT NULL,
    planned_set_id TEXT,
    set_number INTEGER NOT NULL,
    set_type TEXT NOT NULL DEFAULT 'STANDARD',
    actual_reps INTEGER NOT NULL,
    actual_weight_kg REAL NOT NULL,
    logged_rpe INTEGER,
    is_pr INTEGER NOT NULL DEFAULT 0,
    completed_at INTEGER NOT NULL
);

CREATE TABLE ProgressionEvent (
    id TEXT PRIMARY KEY NOT NULL,
    exercise_id TEXT NOT NULL,
    suggested_weight_kg REAL NOT NULL,
    previous_weight_kg REAL NOT NULL,
    reason TEXT NOT NULL,
    user_response TEXT,
    actual_weight_kg REAL,
    timestamp INTEGER NOT NULL
);
```

**Unique constraint**: `(exerciseId, workoutMode, prType)` ensures exactly one MAX_WEIGHT and one MAX_VOLUME PR per exercise per mode. Uses `INSERT OR REPLACE` for atomic upserts.

**Verdict**: Phoenix's schema is production-grade and far more comprehensive than the official app's approach.

---

## 7. PR Notification / Celebration UI

### Official App

- PR notification was part of the achievement/badge system
- "Beats previous PR -> 'New Record' badge" listed in achievement triggers
- Leaderboard integration showed ranking changes
- No evidence of animated celebration dialogs in the decompiled UI code

### Project Phoenix

**Components:**

1. **`PRCelebrationDialog`** (`PRCelebrationAnimation.kt`):
   - Full-screen dialog with Lottie confetti animation
   - Pulsing "NEW [MODE] PR!" text with mode context (e.g., "NEW OLD SCHOOL PR!")
   - Trophy/star Lottie animation
   - Exercise name and weight achieved display
   - Auto-dismisses after 3 seconds
   - Sound trigger callback for celebration audio

2. **`PRIndicator`** (`PRIndicator.kt`):
   - Shows current weight as percentage of PR (e.g., "82% PR")
   - Color coding: primary when above PR, tertiary at 90%+
   - Up/down arrow indicator
   - Compact variant for space-constrained layouts

3. **`HapticEvent.PERSONAL_RECORD`** (`Models.kt`):
   - Strong haptic feedback + random PR celebration sound
   - Separate from badge celebration haptics

4. **`PRCelebrationEvent`** data class:
   - Carries exercise name, weight, reps, mode, and which PR types were broken
   - Supports announcing weight PR, volume PR, or both

**Verdict**: Phoenix has a significantly richer celebration experience than the official app.

---

## 8. Trend Analysis & Prediction

### Official App

The official app's `calculateProgress()` method (inferred from repository patterns) computed:
- Strength gain percentage
- Volume gain percentage
- Endurance gain percentage
- Exercise frequency
- Linear regression for strength trend
- Projected strength at future date

This was primarily cloud-computed with results displayed in the app.

### Project Phoenix

**Source:** `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/TrendAnalysisUseCase.kt`

Full local implementation:
- **Linear regression** on workout data points (slope, intercept, R-squared)
- **Moving averages** for trend smoothing
- **Trend detection**: Increasing, Decreasing, Stable, Plateau
- **Value prediction** N days ahead with confidence score
- **Anomaly detection** using standard deviation thresholds
- **Plateau detection** with configurable minimum duration (default 14 days)

All computed locally -- no cloud dependency.

**Verdict**: Phoenix replicates the official app's trend analysis locally, and adds anomaly/plateau detection features.

---

## Comparison Table

| Feature | Official App | Project Phoenix | Phoenix Status |
|---------|-------------|-----------------|----------------|
| **In-workout progression** | +1/2/3 kg per rep via firmware | Same via `BlePacketFactory` | PARITY |
| **In-workout regression** | -1/2/3 kg per rep via firmware | Same via negative `progressionRegressionKg` | PARITY |
| **Adaptive modes** | Pause top/bottom, velocity-based | Same protocol | PARITY |
| **Max progression cap** | 3 kg per cable | `MAX_PROGRESSION_KG = 3f` | PARITY |
| **Session-over-session progression** | Cloud-based (dead) / Internal mode | Local `ProgressionUseCase` with RPE + rep triggers | AHEAD |
| **Progression suggestions UI** | None found (cloud) | `ProgressionSuggestionBanner` with Accept/Modify/Skip | AHEAD |
| **Session-over-session deload** | None | None (trend analysis foundation exists) | EQUAL |
| **PR detection** | Cloud leaderboard + achievement badges | Local dual-type (Weight + Volume) per mode | AHEAD |
| **1RM calculation** | Server-side (dead) / Assessment mode | Local Brzycki + Epley formulas | AHEAD |
| **1RM in training** | Unknown | 70% 1RM default weight suggestion, percentage-based cycles | AHEAD |
| **PR storage** | Cloud + achievements table | `PersonalRecord` table with unique index per exercise/mode/type | AHEAD |
| **PR celebration** | Achievement badge | Animated dialog + Lottie + haptics + sound | AHEAD |
| **PR indicator** | None found | Percentage-of-PR display with color coding | AHEAD |
| **Trend analysis** | Cloud-computed | Local linear regression + moving averages | PARITY |
| **Plateau detection** | None found | Local with configurable thresholds | AHEAD |
| **Anomaly detection** | None found | Standard deviation-based detection | AHEAD |
| **Value prediction** | Cloud-computed | Local linear regression-based prediction | PARITY |

---

## Key Recommendations for Phoenix

### 1. Add Session-over-Session Deload Suggestions (Priority: Medium)

The `ProgressionUseCase` only suggests increases. Add a `DeloadUseCase` or extend `ProgressionUseCase` to suggest weight decreases when:
- User misses target reps for 2+ consecutive sessions at same weight
- RPE consistently above target (e.g., logged RPE >= 9 when target is 8)
- Anomaly detection shows sudden drop in performance
- Plateau detected for 14+ days (already detected, just needs action)

### 2. Implement "Clear Progression" Feature (Priority: Low)

The official app has a "clear progression dialog" (`R.string.clear_progression_dialog`). Phoenix should consider adding the ability to reset the in-workout progression counter mid-set, in case a user accidentally triggers progression via a pause.

### 3. Validate Progression Protocol Against All Modes (Priority: High)

The official app explicitly states progression/regression is NOT available for Eccentric Only and Echo modes. Phoenix should ensure `progressionRegressionKg` is forced to 0 when:
- `programMode == ProgramMode.EccentricOnly`
- `programMode == ProgramMode.Echo`

Current `WorkoutParameters` allows any value for any mode -- adding validation would prevent firmware confusion.

### 4. Consider Beast Mode's Dynamic Progression (Priority: Low)

The official app's Beast Mode (TUT Beast) has a unique progression algorithm:
- If reps > target: increase difficulty (shift toward Pump)
- If reps < baseline: enable spotter (shift toward TUT)
- This is handled by firmware, but the initial TUT Beast mode profile should be verified for correct parameter encoding.

### 5. PR History Timeline (Priority: Medium)

Phoenix tracks PRs with timestamps but doesn't yet appear to have a dedicated PR history timeline view. This would be valuable for visualizing strength progression over time per exercise. The `PersonalRecord` table already has `achievedAt` timestamps to support this.

### 6. Sync PR with Exercise 1RM (Existing, Verify)

Phoenix already syncs 1RM to the Exercise table when a weight PR is set. Verify this is working correctly for all code paths:
```kotlin
queries.updateOneRepMax(one_rep_max_kg = oneRepMax.toDouble(), id = exerciseId)
```

### 7. Assessment Mode for Direct 1RM Testing (Priority: Low, Future)

The official app had a dedicated Assessment mode (ordinal 7) for direct 1RM testing. Phoenix could add a "Test 1RM" flow that uses Old School mode with single-rep sets and records the result directly as a 1RM PR.

---

## File References

### Official Vitruvian App
- `C:/Users/dasbl/AndroidStudioProjects/VitruvianDeobfuscated/res/raw/trainingmodes.md` - Training mode descriptions with progression/regression
- `C:/Users/dasbl/AndroidStudioProjects/VitruvianDeobfuscated/java-decompiled/sources/Ek/K.java` - RegularForceConfig (spotter, concentric, eccentric, **progression**, curve)
- `C:/Users/dasbl/AndroidStudioProjects/VitruvianDeobfuscated/res/values/strings.xml` - Progression/regression UI strings
- `C:/Users/dasbl/AndroidStudioProjects/VitruvianDeobfuscated/finaldocs/TRAINING_MODES_COMPLETE_ANALYSIS.md` - Complete mode analysis
- `C:/Users/dasbl/AndroidStudioProjects/VitruvianDeobfuscated/finaldocs/PROPRIETARY_ALGORITHMS_FOUND.md` - Algorithms documentation
- `C:/Users/dasbl/AndroidStudioProjects/VitruvianDeobfuscated/java-decompiled/sources/vk/n.java` - Mode enum (PROGRESSION = ordinal 2)

### Project Phoenix
- `C:/Users/dasbl/AndroidStudioProjects/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BlePacketFactory.kt` - BLE protocol with progression at offset 0x5c
- `C:/Users/dasbl/AndroidStudioProjects/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/util/Constants.kt` - MAX_PROGRESSION_KG, OneRepMaxCalculator
- `C:/Users/dasbl/AndroidStudioProjects/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Models.kt` - WorkoutParameters, PersonalRecord, PRType, PRCelebrationEvent
- `C:/Users/dasbl/AndroidStudioProjects/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/TrainingCycleModels.kt` - ProgressionEvent, CompletedSet, SetType
- `C:/Users/dasbl/AndroidStudioProjects/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/ProgressionUseCase.kt` - Auto-progression engine
- `C:/Users/dasbl/AndroidStudioProjects/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/TrendAnalysisUseCase.kt` - Trend analysis
- `C:/Users/dasbl/AndroidStudioProjects/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/PersonalRecordRepository.kt` - PR repository interface
- `C:/Users/dasbl/AndroidStudioProjects/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightPersonalRecordRepository.kt` - PR repository implementation
- `C:/Users/dasbl/AndroidStudioProjects/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/ProgressionRepository.kt` - Progression repository interface
- `C:/Users/dasbl/AndroidStudioProjects/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightProgressionRepository.kt` - Progression repository implementation
- `C:/Users/dasbl/AndroidStudioProjects/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/CompletedSetRepository.kt` - Completed set repository
- `C:/Users/dasbl/AndroidStudioProjects/Project-Phoenix-MP/shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq` - Full DB schema
- `C:/Users/dasbl/AndroidStudioProjects/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/PRCelebrationAnimation.kt` - PR celebration UI
- `C:/Users/dasbl/AndroidStudioProjects/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/PRIndicator.kt` - PR percentage indicator
- `C:/Users/dasbl/AndroidStudioProjects/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProgressionSuggestion.kt` - Progression suggestion banner UI
- `C:/Users/dasbl/AndroidStudioProjects/Project-Phoenix-MP/shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/ExerciseConfig.kt` - Exercise config with 1RM integration
