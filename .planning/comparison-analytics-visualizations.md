# Analytics, Data Collection & Visualization: Official App vs Project Phoenix

**Date**: 2026-02-17
**Scope**: Deep source-code comparison of analytics, data collection, and data visualization logic
**Method**: Decompiled Java source analysis (actual files, not finaldocs summaries)

---

## Table of Contents

1. [Data Models & Collection Fields](#1-data-models--collection-fields)
2. [Data Storage & Persistence](#2-data-storage--persistence)
3. [Workout History Views & Queries](#3-workout-history-views--queries)
4. [Charts & Visualizations](#4-charts--visualizations)
5. [Computed Statistics & Calculations](#5-computed-statistics--calculations)
6. [API Models & Serialization](#6-api-models--serialization)
7. [Comparison Table](#7-comparison-table)
8. [Actionable Recommendations](#8-actionable-recommendations)

---

## 1. Data Models & Collection Fields

### Official Vitruvian App

#### Session (`zk.d` -> `com.vitruvian.data.model.sessions.Session`)

**File**: `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\zk\d.java`

8 serialized fields discovered from static initializer (line ~50):

```java
// zk/d.java - Session serializer descriptor
C5237v0 c5237v0 = new C5237v0("com.vitruvian.data.model.sessions.Session", obj, 8);
c5237v0.m("id", false);          // String - session unique ID
c5237v0.m("created", false);     // Instant - creation timestamp
c5237v0.m("user", true);         // User reference (nullable)
c5237v0.m("routine", true);      // Routine reference (nullable)
c5237v0.m("workouts", false);    // List<Workout> - exercises within session
c5237v0.m("score", true);        // WorkoutScore (nullable)
c5237v0.m("maxForce", true);     // Double (nullable) - session max force
c5237v0.m("subscribed", true);   // Subscription status (nullable)
```

Computed methods on Session:
- `b()` - average force from LEFT handle statistics (line ~180)
- `c()` - average force from RIGHT handle statistics (line ~200)
- `d()` - session duration (difference between first/last workout startTime)
- `e()` - end time (last workout's start + duration)
- `f()` - **total volume** across all workouts (summing `workout.volume`)
- `g()` - max force from left handle
- `h()` - max force from right handle
- `i()` - **total points** across all workouts
- `l()` - start time (first workout's startTime)

**Key insight**: A Session is a *container* for multiple Workouts (exercises). Volume is aggregated across all exercises in a session.

#### Workout (`zk.g` -> `com.vitruvian.data.model.sessions.Workout`)

**File**: `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\zk\g.java`

17 serialized fields (line ~120):

```java
// zk/g.java - Workout serializer descriptor
C5237v0 c5237v0 = new C5237v0("com.vitruvian.data.model.sessions.Workout", obj, 17);
c5237v0.m("id", false);           // String
c5237v0.m("reps", true);          // Integer (nullable)
c5237v0.m("exercise", true);      // Exercise object (nullable)
c5237v0.m("startTime", true);     // Instant (nullable)
c5237v0.m("duration", true);      // Duration (nullable)
c5237v0.m("points", true);        // Integer (nullable)
c5237v0.m("score", true);         // WorkoutScore - total + breakdown
c5237v0.m("device", true);        // Device info (nullable)
c5237v0.m("samples", true);       // WorkoutSamples - per-cable raw data
c5237v0.m("mode", true);          // Workout mode enum
c5237v0.m("statistics", true);    // WorkoutStatistics - force & speed stats
c5237v0.m("settings", true);      // Workout settings
c5237v0.m("subscribed", true);    // Subscription status
c5237v0.m("timezone", true);      // ZoneId
c5237v0.m("formula", true);       // Formula type
c5237v0.m("freestyle", true);     // Boolean - freestyle mode
c5237v0.m("volume", true);        // Double - calculated volume
```

Computed method (`e()`, line ~280): total volume calculated from left+right cable samples.

#### WorkoutStatistics (`zk.t` -> `com.vitruvian.data.model.sessions.WorkoutStatistics`)

**File**: `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\zk\t.java`

```java
// zk/t.java line 42
c5237v0.m("force", false);   // WorkoutMetrics - force stats
c5237v0.m("speed", false);   // WorkoutMetrics - speed stats
```

Each field is a `WorkoutMetrics` (`zk.k`) object.

#### WorkoutMetrics (`zk.k` -> `com.vitruvian.data.model.sessions.WorkoutMetrics`)

**File**: `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\zk\k.java`

```java
// zk/k.java line 50-53
c5237v0.m("max", true);        // WorkoutMetricPhases (nullable)
c5237v0.m("average", true);    // WorkoutMetricPhases (nullable)
c5237v0.m("deviation", true);  // WorkoutMetricPhases (nullable)

// toString() at line 238:
"WorkoutMetrics(max=" + this.f69123a + ", average=" + this.f69124b + ", deviation=" + this.f69125c + ")"
```

Three statistical measures (max, average, deviation), each containing a `WorkoutMetricPhases`.

#### WorkoutMetricPhases (`zk.j` -> `com.vitruvian.data.model.sessions.WorkoutMetricPhases`)

**File**: `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\zk\j.java`

```java
// zk/j.java - serialized as a triple [concentric, eccentric, total]
// Deserializer at line 80-83:
if (list.size() == 3) {
    return new j(
        ((Number) list.get(0)).doubleValue(),   // concentric
        ((Number) list.get(1)).doubleValue(),   // eccentric
        ((Number) list.get(2)).doubleValue()    // total
    );
}
throw new IllegalStateException("workout metric phase must be a (concentric, eccentric, total) triple");

// toString() at line 121:
"WorkoutMetricPhases(concentric=" + f69117a + ", eccentric=" + f69118b + ", total=" + f69119c + ")"
```

**Complete statistics hierarchy**: `WorkoutStatistics` > `force`/`speed` > each has `max`/`average`/`deviation` > each has `concentric`/`eccentric`/`total`.

This means for a single workout the official app tracks:
- Force max (concentric, eccentric, total)
- Force average (concentric, eccentric, total)
- Force deviation (concentric, eccentric, total)
- Speed max (concentric, eccentric, total)
- Speed average (concentric, eccentric, total)
- Speed deviation (concentric, eccentric, total)

= **18 distinct statistical values** per workout.

#### WorkoutCableSamples (`zk.h` -> `com.vitruvian.data.model.sessions.WorkoutCableSamples`)

**File**: `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\zk\h.java`

```java
// zk/h.java line 57-60
c5237v0.m("force", false);      // List<Ak.a> - force samples per tick
c5237v0.m("position", false);   // List<Ak.b> - position samples per tick
c5237v0.m("velocity", false);   // List<Ak.c> - velocity samples per tick

// toString() at line 374:
"WorkoutCableSamples(force=" + f69106a + ", position=" + f69107b + ", velocity=" + f69108c + ")"
```

Per-cable sample streams of force, position, and velocity.

The `e()` method (line 275) computes full WorkoutStatistics (force and speed) from raw samples. The `c()` method (line 232) computes **calories** using a physics-based formula:

```java
// zk/h.java line 265 - Calorie calculation
d10 += Double.compare(bVar3.f448a, bVar4.f448a) > 0
    ? ((((mVar2.f69132a.f445a * i10) / 1000) * 9.81d) / 4184) * 5
    : 0.0d;
// Translation: calories = (force_kg * position_m / 1000 * gravity / calories_per_joule) * multiplier
// Only counts positive work (when position increases from minimum)
```

This is a **work-energy theorem** based calorie calculation: `Work (Joules) = Force (N) * Distance (m)`, converted to kcal with a 5x metabolic multiplier.

#### HeuristicStatistics (BLE Real-Time)

**File**: `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\com\vitruvian\formtrainer\HeuristicPhaseStatistics.java`

```java
// HeuristicPhaseStatistics.java - 6 floats per phase (concentric/eccentric)
public float kgAvg;    // Average force in kg
public float kgMax;    // Maximum force in kg
public float velAvg;   // Average velocity
public float velMax;   // Maximum velocity
public float wattAvg;  // Average power in watts
public float wattMax;  // Maximum power in watts
```

Read from BLE characteristic UUID `c7b73007-b245-4503-a1ed-9e4e97eb9802`, LITTLE_ENDIAN ByteBuffer. 6 floats x 4 bytes x 2 phases (concentric/eccentric) x 2 cables (left/right) = **96 bytes** per BLE notification.

---

### Project Phoenix

#### WorkoutSession

**File**: `C:\Users\dasbl\AndroidStudioProjects\Project-Phoenix-MP\shared\src\commonMain\kotlin\com\devil\phoenixproject\domain\model\Models.kt`

```kotlin
data class WorkoutSession(
    val id: String,
    val timestamp: Long,
    val mode: String,
    val reps: Int,
    val weightPerCableKg: Float,
    val progressionKg: Float = 0f,
    val duration: Long = 0,
    val totalReps: Int = 0,
    val warmupReps: Int = 0,
    val workingReps: Int = 0,
    val isJustLift: Boolean = false,
    val stopAtTop: Boolean = false,
    val eccentricLoad: Int = 100,
    val echoLevel: Int = 1,
    val exerciseId: String? = null,
    val exerciseName: String? = null,
    val routineSessionId: String? = null,
    val routineName: String? = null,
    // Safety tracking
    val safetyFlags: Int = 0,
    val deloadWarningCount: Int = 0,
    val romViolationCount: Int = 0,
    val spotterActivations: Int = 0,
    // Set summary metrics
    val peakForceConcentricA: Float? = null,
    val peakForceConcentricB: Float? = null,
    val peakForceEccentricA: Float? = null,
    val peakForceEccentricB: Float? = null,
    val avgForceConcentricA: Float? = null,
    val avgForceConcentricB: Float? = null,
    val avgForceEccentricA: Float? = null,
    val avgForceEccentricB: Float? = null,
    val heaviestLiftKg: Float? = null,
    val totalVolumeKg: Float? = null,
    val estimatedCalories: Float? = null,
    val warmupAvgWeightKg: Float? = null,
    val workingAvgWeightKg: Float? = null,
    val burnoutAvgWeightKg: Float? = null,
    val peakWeightKg: Float? = null,
    val rpe: Int? = null,
)
```

#### WorkoutMetric (time-series samples)

```kotlin
data class WorkoutMetric(
    val timestamp: Long,
    val loadA: Float = 0f,
    val loadB: Float = 0f,
    val positionA: Float = 0f,
    val positionB: Float = 0f,
    val ticks: Int = 0,
    val velocityA: Float = 0f,
    val velocityB: Float = 0f,
    val status: Int = 0
)
```

#### PersonalRecord

```kotlin
data class PersonalRecord(
    val id: Long = 0,
    val exerciseId: String,
    val exerciseName: String,
    val weightPerCableKg: Float,
    val reps: Int,
    val oneRepMax: Float,
    val timestamp: Long,
    val workoutMode: String,
    val prType: String = "MAX_WEIGHT",
    val volume: Float = 0f
)
```

### Key Differences - Data Models

| Aspect | Official App | Project Phoenix |
|--------|-------------|-----------------|
| Session/Workout hierarchy | Session contains `List<Workout>` (multi-exercise sessions) | Flat WorkoutSession (one exercise per record) |
| Statistics granularity | 18 values: force+speed x max+avg+dev x concentric+eccentric+total | 8 force values: peak+avg x concentric+eccentric x A+B cable. No speed/deviation. |
| Sample data per cable | 3 streams: force[], position[], velocity[] | 8 channels: loadA, loadB, positionA, positionB, velocityA, velocityB, ticks, status |
| Score/gamification | `WorkoutScore` with `total` + `breakdown` map, points system | No score. Gamification via badges (separate table) |
| Calorie tracking | Physics-based from samples (work-energy theorem) | Estimated at session level (`estimatedCalories` field) |
| Safety tracking | None | safetyFlags, deloadWarningCount, romViolationCount, spotterActivations |
| PR tracking | Server-side (no local PR model found) | Local DB with 2 types: MAX_WEIGHT, MAX_VOLUME |
| Rep segmentation | warmup/working implicit in samples | Explicit: warmupReps, workingReps, warmupAvgWeightKg, workingAvgWeightKg, burnoutAvgWeightKg |

---

## 2. Data Storage & Persistence

### Official Vitruvian App

**No local database found.** The official app was entirely cloud-dependent:
- Models use `kotlinx.serialization` annotations for JSON API communication
- Models implement `Parcelable` for Android IPC (activity/fragment transfers)
- No Room, SQLite, or SQLDelight references in `com.vitruvian.*` packages
- Session data fetched from API via `sessionRepository`
- This cloud dependency is why the app became non-functional after company bankruptcy

### Project Phoenix

**File**: `C:\Users\dasbl\AndroidStudioProjects\Project-Phoenix-MP\shared\src\commonMain\sqldelight\com\devil\phoenixproject\database\VitruvianDatabase.sq`

Full offline-first SQLDelight schema with **18 tables**:

| Table | Purpose |
|-------|---------|
| `Exercise` | 32 columns, exercise library with sync fields |
| `ExerciseVideo` | Video URLs per exercise |
| `WorkoutSession` | 41 columns (including sync fields), primary workout data |
| `MetricSample` | Time-series samples (load, position, velocity per cable) |
| `PersonalRecord` | PR tracking with MAX_WEIGHT and MAX_VOLUME types |
| `PhaseStatistics` | Concentric/eccentric phase stats (12 metrics: kg/vel/watt x avg/max x con/ecc) |
| `Routine` | Custom workout routines |
| `RoutineExercise` | Exercises within routines (27 columns) |
| `Superset` | Grouped exercise containers |
| `ConnectionLog` | BLE connection debug logs |
| `DiagnosticsHistory` | Machine diagnostics snapshots |
| `EarnedBadge` | Gamification badges |
| `StreakHistory` | Workout streak tracking |
| `GamificationStats` | Aggregated stats (singleton row) |
| `TrainingCycle` | Rolling training programs |
| `CycleDay` | Days within training cycles |
| `CycleProgress` | Current position in cycle |
| `CompletedSet` | Per-set tracking within workouts |

Key analytics queries defined in the schema:

```sql
-- Volume history
selectVolumeHistory:
SELECT timestamp, totalReps, weightPerCableKg FROM WorkoutSession ORDER BY timestamp ASC;

-- Gamification aggregates
countTotalWorkouts: SELECT COUNT(*) FROM WorkoutSession;
countTotalReps:     SELECT SUM(totalReps) FROM WorkoutSession;
countTotalVolume:   SELECT SUM(COALESCE(totalVolumeKg, totalReps * weightPerCableKg * 2)) FROM WorkoutSession;

-- Workout distribution
countWorkoutsByMode: SELECT mode, COUNT(*) AS count FROM WorkoutSession GROUP BY mode;
```

**Key advantage**: Phoenix operates fully offline. All data is local-first with optional cloud sync via Supabase.

---

## 3. Workout History Views & Queries

### Official Vitruvian App

#### ExerciseHistoryScreenViewModel

**File**: `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\com\vitruvian\app\ui\dashboard\ExerciseHistoryScreenViewModel.java`

- Lists workouts with exercise filtering and favourites toggle
- Sort types via `ResultSortType` enum
- Tracks user action events: "Updated results sort"
- Exercises map: `Map<String, Exercise>` for name lookup

#### ExerciseDetailScreenViewModel

**File**: `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\com\vitruvian\app\ui\dashboard\ExerciseDetailScreenViewModel.java`

```java
// ExerciseDetailScreenViewModel.java - State fields (line ~220-260)
// State contains:
// - workout (zk.g): The workout data
// - sessionId: Parent session ID
// - exercise (C7404b): Exercise definition
// - isPb (boolean): Personal Best flag
// - canRetake (boolean): Can retry this workout
// - downloadCSV: CSV export capability (line 251: "downloadCVS=" + this.f39394g)
// - deleteWorkout: Delete functionality
```

Notable: The official app supported **CSV export** of workout data and had a **personal best** flag on individual workouts.

#### ProfileScreenViewModel

**File**: `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\com\vitruvian\app\ui\profile\ProfileScreenViewModel.java`

Comprehensive analytics dashboard:

```java
// ProfileScreenViewModel.java - Key analytics fields
// - Workout history filtered by time periods: 7d, 14d, 29d, all-time
//   (using LocalDateTime.now().minusDays())
// - volumeHistoryByDay: Map<LocalDate, List<w>>
// - volumeHistoryByYearMonth: Map<YearMonth, List<w>>
// - usageBreakdownByMuscleGroup
// - leastUsedMuscleGroups
// - Leaderboard integration with user summary
// - Points progress tracking
```

### Project Phoenix

#### HistoryTab

**File**: `C:\Users\dasbl\AndroidStudioProjects\Project-Phoenix-MP\shared\src\commonMain\kotlin\com\devil\phoenixproject\presentation\screen\HistoryTab.kt`

- Grouped workout history list with delete capability
- Exercise repository integration for name lookup
- Sorted by timestamp descending
- No filtering by exercise, time period, or favourites

#### InsightsTab (Dashboard)

**File**: `C:\Users\dasbl\AndroidStudioProjects\Project-Phoenix-MP\shared\src\commonMain\kotlin\com\devil\phoenixproject\presentation\screen\InsightsTab.kt`

```kotlin
// InsightsTab.kt - Dashboard cards
// 1. ThisWeekSummaryCard - week-over-week comparison
// 2. MuscleBalanceRadarCard - radar chart of muscle group focus
// 3. ConsistencyGaugeCard - monthly workout consistency
// 4. VolumeVsIntensityCard - volume and max weight combo chart
// 5. TotalVolumeCard - daily volume bar chart
// 6. WorkoutModeDistributionCard - donut chart of workout modes
```

---

## 4. Charts & Visualizations

### Official Vitruvian App

The official app used a **custom Compose Canvas chart library** (not MPAndroidChart as initially suspected). The chart code is in the `Mj` package.

#### Volume Bar Chart (Pageable)

**File**: `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\Mj\b.java`

Page annotation confirms origin: `PageableVolumeChart.kt` (line 97).

**Features**:
- **Pageable/swipeable** - navigate through weeks/months/years
- **Time period selector**: Week, Month, Year tabs (`EnumC4251Q`)
- Week view: Monday-Sunday bars with day-of-week labels
- Month view: Day-by-day bars for entire month
- Year view: Monthly aggregate bars (Jan-Dec)
- Title shows date range (e.g., "25 February - 3 March")
- Total volume display below chart
- Animated transitions between pages

#### VolumeBarChartEntry data model

**File**: `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\Mj\e.java`

```java
// Mj/e.java - toString() at line 39
"VolumeBarChartEntry(label=" + f11921a + ", total=" + f11922b + ", date=" + f11923c + ")"
// Fields: label (String), total (double), date (String)
```

#### VolumeBarChartState

**File**: `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\Mj\g.java`

```java
// Mj/g.java - toString() at line 46
"VolumeBarChartState(title=" + f11933a + ", date=" + f11934b + ", data=" + f11935c + ", maxY=" + f11936d + ")"
// Fields: title (String), date (LocalDate), data (List<VolumeBarChartEntry>), maxY (double)
```

#### Volume Chart State Management (Real Data)

**File**: `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\Mj\j.java`

```java
// Mj/j.java - Real data state (vs preview in Mj/b.java$h)
// Constructor at line 57 - receives:
// - volumeHistoryByDay: Map<LocalDate, List<w>>  (w = volume summary)
// - volumeHistoryByYearMonth: Map<YearMonth, List<w>>
// - timePeriod state (Week/Month/Year)
// - date navigation state

// Week calculation (line 98):
LocalDate plusWeeks = y1Var.getValue().plusWeeks(i10);
LocalDate plusDays = plusWeeks.plusDays(6L);
List<LocalDate> a10 = C4250P.a(plusWeeks, plusDays);

// Volume aggregation per day (line 111-114):
Iterator<T> it = list.iterator();
double d10 = 0.0d;
while (it.hasNext()) {
    Double d11 = ((w) it.next()).f69194b;  // w.volume field
    d10 += d11 != null ? d11.doubleValue() : 0.0d;
}
```

#### Individual Bar Chart Rendering

**File**: `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\Mj\f.java`

The `VolumeChartKt` composable renders individual bars. Key features:
- **Unit conversion** for imperial (line ~573): `d18 = 2.20462d * d18` (kg to lbs)
- **Y-axis labels** at maxY, 66%, 33%, 0% (line 342-347)
- **Bar selection** with tap interaction (stores selected index in state)
- **Selected bar details**: Shows formatted weight value and date label
- Labels every 5th bar when >20 bars (line 716): `(i13 == 0 || i13 % 5 == 0) ? eVar8.f11921a : ""`
- "No data" state with localized string (line 819)

#### Pie Chart (Muscle Group Distribution)

**File**: `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\Fi\S.java`

Page annotation: `PieChart.kt` (line 176).

```java
// Fi/S.java - PieChart composable
// Animated pie chart with 2000ms animation (line 214, 278)
// Slice rendering via Canvas with arc drawing (line 300-305)
// Labels positioned using trigonometry:
//   x = cos(midAngle) * radius, y = sin(midAngle) * radius (line 474)
// Color coding: index 0-7 = green, 7-12 = yellow, 13+ = blue (line 381)
// Labels flip orientation when angle is 90-270 degrees (bottom half, line 500-506)
// Percentage display next to labels (line 129)
```

### Project Phoenix

#### VolumeTrendChart

**File**: `C:\Users\dasbl\AndroidStudioProjects\Project-Phoenix-MP\shared\src\commonMain\kotlin\com\devil\phoenixproject\presentation\components\charts\VolumeTrendChart.kt`

- Compose Canvas bar chart showing daily volume over **last 14 days**
- Groups sessions by calendar day, sums `effectiveTotalVolumeKg()`
- Supports kg/lb conversion
- Animated bars (800ms tween)
- Responsive layout (compact/medium/expanded)
- Scrollable horizontally
- Y-axis labels at max, max/2, 0

#### WorkoutMetricsDetailChart

**File**: `C:\Users\dasbl\AndroidStudioProjects\Project-Phoenix-MP\shared\src\commonMain\kotlin\com\devil\phoenixproject\presentation\components\charts\WorkoutMetricsDetailChart.kt`

- Compose Canvas line chart for real-time workout metrics
- Plots Load A, Load B, Position A, Position B as time-series
- Filter chips to toggle each metric
- Animated drawing, grid lines
- Summary: Max Load, Samples count, Max ROM

#### InsightCards Charts

**File**: `C:\Users\dasbl\AndroidStudioProjects\Project-Phoenix-MP\shared\src\commonMain\kotlin\com\devil\phoenixproject\presentation\components\InsightCards.kt`

Multiple chart types implemented:
1. **RadarChart** (MuscleBalanceRadarCard) - 6-axis radar for muscle group balance
2. **GaugeChart** (ConsistencyGaugeCard) - semicircular gauge for monthly consistency
3. **ComboChart** (VolumeVsIntensityCard) - column + line overlay for volume vs max weight
4. **MuscleGroupCircleChart** (WorkoutModeDistributionCard) - donut chart for workout modes
5. **CalendarHeatmapCard** - GitHub-style contribution graph (13 weeks, 91 days)

### Key Differences - Charts

| Feature | Official App | Project Phoenix |
|---------|-------------|-----------------|
| Volume chart time periods | Week / Month / Year (pageable) | Last 14 days only (scrollable) |
| Volume chart navigation | Swipe between pages (weeks/months/years) | No navigation, fixed window |
| Bar chart interactivity | Tap to select bar, shows details | No tap interaction |
| Pie/donut chart | Animated pie with Canvas arcs, labels with percentages | MuscleGroupCircleChart donut |
| Real-time workout chart | N/A (not found in decompiled UI code) | WorkoutMetricsDetailChart (line chart) |
| Radar chart | Not found | MuscleBalanceRadarCard (6-axis) |
| Heatmap | Not found | CalendarHeatmapCard (GitHub-style) |
| Gauge chart | Not found | ConsistencyGaugeCard (semicircular) |
| Combo chart | Not found | VolumeVsIntensityCard (columns + line) |
| Unit conversion in charts | `2.20462d * d18` (line ~573 in Mj/f.java) | `totalVolume * 2.20462f` (line 224 in VolumeTrendChart.kt) |

---

## 5. Computed Statistics & Calculations

### Official Vitruvian App

#### Volume Computation

**File**: `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\zk\g.java`
Method `e()` - computes total volume from left and right cable samples.

**File**: `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\zk\d.java`
Method `f()` - aggregates volume across all workouts in a session:

```java
// zk/d.java - Session total volume
// Sums workout.volume (f69083N) for all workouts in the session
```

#### Force/Speed Statistics from Samples

**File**: `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\zk\h.java`

Static method `a()` (line 177) - accumulates max, sum, and sum-of-squares for deviation:

```java
// zk/h.java line 177-193 - Force statistics accumulation
public static void a(k kVar, double d10, double d11) {
    // MAX phase: track running maximum for concentric and eccentric
    j a10 = kVar.a(f.f69066a);  // MAX metric
    a10.f69117a = Math.max(a10.f69117a, d10);   // max concentric
    a10.f69118b = Math.max(a10.f69118b, d11);    // max eccentric
    a10.f69119c = Math.max(a10.f69117a, max);    // max total

    // AVERAGE phase: accumulate sums (divided later)
    j a11 = kVar.a(f.f69068c);  // AVERAGE metric
    a11.f69117a += d10;          // sum concentric
    a11.f69118b += d11;          // sum eccentric
    a11.f69119c = d10 + d11 + a11.f69119c;  // sum total

    // DEVIATION phase: accumulate sum of squares
    j a12 = kVar.a(f.f69067b);  // DEVIATION metric
    a12.f69117a += d10 * d10;
    a12.f69118b += d11 * d11;
    a12.f69119c = d10 * d10 + d11 * d11 + a12.f69119c;
}
```

Static method `d()` (line 195) - finalizes deviation as variance (Var = E[X^2] - (E[X])^2):

```java
// zk/h.java line 195-217 - Finalize statistics (compute averages and variance)
public static void d(k kVar, int i10, int i11) {
    // Divide averages by sample count
    jVar.a(i10, i11);  // j.a() divides each value by count

    // Compute variance: E[X^2] - (E[X])^2
    jVar3.f69117a = d10 - (d11 * d11);  // variance = sumSq/n - mean^2
    jVar3.f69118b = d12 - (d13 * d13);
    jVar3.f69119c = d14 - (d15 * d15);
}
```

#### Calorie Calculation

**File**: `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\zk\h.java`

Method `c()` (line 232) - physics-based calorie estimation:

```java
// zk/h.java line 265 - Calorie formula
d10 += Double.compare(bVar3.f448a, bVar4.f448a) > 0
    ? ((((mVar2.f69132a.f445a * i10) / 1000) * 9.81d) / 4184) * 5
    : 0.0d;

// Decoded formula:
// if (position > min_position):
//   calories += (force_kg * distance_m / 1000 * 9.81 / 4184) * 5
// Where:
//   force_kg = sample force value
//   distance_m / 1000 = cable position in meters
//   9.81 = gravitational acceleration (m/s^2)
//   4184 = joules per kilocalorie
//   5 = metabolic multiplier (accounts for eccentric, stabilization, etc.)
```

#### Time Period Analytics (Profile)

**File**: `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\com\vitruvian\app\ui\profile\ProfileScreenViewModel.java`

- Volume grouped by day (`volumeHistoryByDay`) and by month (`volumeHistoryByYearMonth`)
- Muscle group usage breakdown with "least used" identification
- Time period filters: 7d, 14d, 29d, all-time
- Points progress tracking for leaderboard

### Project Phoenix

#### Volume Computation

**File**: `C:\Users\dasbl\AndroidStudioProjects\Project-Phoenix-MP\shared\src\commonMain\kotlin\com\devil\phoenixproject\domain\model\Models.kt`

```kotlin
fun WorkoutSession.effectiveTotalVolumeKg(): Float {
    return totalVolumeKg ?: (weightPerCableKg * totalReps * 2f)
}
```

Two paths: precomputed `totalVolumeKg` (from set summary metrics) OR fallback to simple calculation.

#### Week-over-Week Comparison

**File**: `C:\Users\dasbl\AndroidStudioProjects\Project-Phoenix-MP\shared\src\commonMain\kotlin\com\devil\phoenixproject\presentation\components\InsightCards.kt`

```kotlin
// InsightCards.kt line 88-117 - Week-over-week summary
val thisWeekSessions = workoutSessions.filter { it.timestamp >= thisWeekStart }
val lastWeekSessions = workoutSessions.filter {
    it.timestamp >= lastWeekStart && it.timestamp < lastWeekEnd
}

val thisWeekSummary = WeekSummary(
    workouts = thisWeekSessions.size,
    totalVolume = thisWeekSessions.sumOf { it.effectiveTotalVolumeKg().toDouble() }.toFloat(),
    totalReps = thisWeekSessions.sumOf { it.totalReps },
    prsHit = thisWeekPRs.size
)
```

Four comparison metrics: workouts, volume, reps, PRs.

#### Strength Score

**File**: `C:\Users\dasbl\AndroidStudioProjects\Project-Phoenix-MP\shared\src\commonMain\kotlin\com\devil\phoenixproject\presentation\components\DashboardComponents.kt`

```kotlin
// DashboardComponents.kt line 520-561 - Strength Score calculation
private fun calculateStrengthScore(personalRecords, workoutSessions): Int {
    // PR Score: Sum of top weights per exercise * 10
    val prScore = personalRecords
        .groupBy { it.exerciseId }
        .mapValues { (_, prs) -> prs.maxOf { it.weightPerCableKg } }
        .values.sumOf { it.toDouble() } * 10

    // Volume Score: Last 30 days volume * 0.5
    val volumeScore = workoutSessions
        .filter { it.timestamp >= thirtyDaysAgo }
        .sumOf { (it.weightPerCableKg * it.totalReps * 0.5).toDouble() }

    // Consistency Score: Workouts in last 30 days * 5
    val consistencyScore = workoutSessions
        .count { it.timestamp >= thirtyDaysAgo } * 5

    return (prScore + volumeScore + consistencyScore).toInt()
}
```

### Key Differences - Computed Stats

| Statistic | Official App | Project Phoenix |
|-----------|-------------|-----------------|
| Volume formula | Complex: sum from per-sample force*distance | Simple: `weightPerCableKg * totalReps * 2` OR precomputed `totalVolumeKg` |
| Calorie formula | Physics-based: work-energy theorem with 5x metabolic multiplier | Estimated at session level (formula not traced) |
| Force statistics | 18 values: max/avg/dev x con/ecc/total x force/speed | 8 values: peak/avg x con/ecc x A/B cable |
| Standard deviation | Computed from raw samples (Var = E[X^2] - E[X]^2) | Not tracked |
| Speed statistics | Tracked (max/avg/deviation x con/ecc/total) | Velocity in MetricSample but not aggregated into stats |
| Time period filtering | Server-side with 7d/14d/29d/all-time | Client-side with week-over-week only |
| PR detection | Server-side `isPb` flag on workouts | Local detection with Brzycki 1RM formula |
| Muscle group breakdown | `usageBreakdownByMuscleGroup`, `leastUsedMuscleGroups` | Derived from PR exercise lookup (InsightCards) |
| Strength score | Not found (gamification was points-based) | Custom composite: PR weight + 30d volume + consistency |

---

## 6. API Models & Serialization

### Official Vitruvian App

All models use `kotlinx.serialization` (`@fo.k` annotation in decompiled code):

| Model | Serialized Name | Transport |
|-------|----------------|-----------|
| `zk.d` | `com.vitruvian.data.model.sessions.Session` | JSON API |
| `zk.g` | `com.vitruvian.data.model.sessions.Workout` | JSON API + Parcelable |
| `zk.h` | `com.vitruvian.data.model.sessions.WorkoutCableSamples` | JSON API + Parcelable |
| `zk.k` | `com.vitruvian.data.model.sessions.WorkoutMetrics` | JSON API + Parcelable |
| `zk.j` | `com.vitruvian.data.model.sessions.WorkoutMetricPhases` | JSON (as triple array) + Parcelable |
| `zk.n` | `com.vitruvian.data.model.sessions.WorkoutSamples` | JSON API + Parcelable |
| `zk.o` | `com.vitruvian.data.model.sessions.WorkoutScore` | JSON API + Parcelable |
| `zk.t` | `com.vitruvian.data.model.sessions.WorkoutStatistics` | JSON API + Parcelable |
| `wk.C7404b` | `com.vitruvian.data.model.exercise.Exercise` | JSON API + Parcelable |

The `WorkoutSamples` model (`zk.n`) includes a `url` field - the official app could download full sample data from the server for detailed analysis.

### Project Phoenix

All models are Kotlin data classes stored locally via SQLDelight:

| Model | Storage | Sync Transport |
|-------|---------|----------------|
| `WorkoutSession` | SQLDelight (41 cols) | Supabase REST API |
| `WorkoutMetric` -> `MetricSample` | SQLDelight | Local backup JSON |
| `PersonalRecord` | SQLDelight | Supabase REST API |
| `Exercise` | SQLDelight (32 cols) | Supabase REST API |
| `PhaseStatistics` | SQLDelight | Local only |

---

## 7. Comparison Table

| Feature Category | Official Vitruvian App | Project Phoenix | Gap Assessment |
|-----------------|----------------------|-----------------|----------------|
| **Data Architecture** | Cloud-first, no local DB | Offline-first, SQLDelight | **Phoenix advantage** |
| **Session Model** | Hierarchical (Session > Workouts) | Flat (one exercise per row) | Different design, both valid |
| **Force Statistics** | 18 values (force+speed x max+avg+dev x 3 phases) | 8 values (peak+avg x 2 phases x 2 cables) | Gap: speed stats, deviation |
| **Volume Chart** | Pageable: Week/Month/Year | Fixed 14-day window | **Major gap** |
| **Chart Interactivity** | Tap to select bars, detail display | No tap interaction | Gap |
| **Muscle Group Analytics** | Built-in usage + least-used tracking | Radar chart from PR data | Similar capability |
| **Pie/Donut Chart** | Animated Canvas with positioned labels | MuscleGroupCircleChart | Comparable |
| **Calorie Tracking** | Physics-based (work-energy theorem) | Session-level estimate | Gap: accuracy |
| **Time Period Filtering** | 7d/14d/29d/all-time | Week-over-week only | Gap |
| **CSV Export** | Supported (downloadCSV field) | Not found | Gap |
| **PR Detection** | Server-side `isPb` flag | Local with 1RM + volume types | **Phoenix advantage** (richer) |
| **Heatmap** | Not found | CalendarHeatmapCard (13 weeks) | **Phoenix advantage** |
| **Radar Chart** | Not found | MuscleBalanceRadarCard | **Phoenix advantage** |
| **Combo Chart** | Not found | VolumeVsIntensityCard | **Phoenix advantage** |
| **Gauge Chart** | Not found | ConsistencyGaugeCard | **Phoenix advantage** |
| **Strength Score** | Points-based gamification | Composite score formula | Different approach, both valid |
| **Real-time Workout Chart** | Heuristic overlay (BLE) | WorkoutMetricsDetailChart (line chart) | Comparable |
| **Gamification** | Points + leaderboard (server) | Badges + streaks (local) | Different approach |
| **Safety Tracking** | None found | Safety flags, deload warnings, ROM violations, spotter activations | **Phoenix advantage** |
| **Training Programs** | Not found in UI code | TrainingCycles, CycleDays, CycleProgress, PlannedSets | **Phoenix advantage** |
| **Phase Statistics** | Heuristic BLE (kg/vel/watt x avg/max x con/ecc) | PhaseStatistics table (same 12 metrics) | **Parity achieved** |

---

## 8. Actionable Recommendations

### HIGH Priority (Feature Parity Gaps)

1. **Pageable Volume Chart with Time Period Selector**
   - The official app's most polished analytics feature was the Week/Month/Year volume chart with swipe navigation
   - Phoenix currently shows only last 14 days with no navigation
   - **Action**: Add a time period selector (Week/Month/Year) to VolumeTrendChart. Implement pageable navigation using `HorizontalPager`. For weekly view, use Monday-Sunday grouping matching the official app's `TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)` approach.
   - Reference: `Mj/j.java` method `b()` (line 88-224) for the complete Week/Month/Year data preparation logic.

2. **Chart Bar Selection/Interactivity**
   - The official app allowed tapping bars to see exact volume and date
   - **Action**: Add `pointerInput` or `clickable` modifier to chart bars. Store selected index in state. Display formatted weight value and date label below chart when a bar is selected.
   - Reference: `Mj/f.java` line ~524-687 for the selection/detail display logic.

3. **Speed Statistics Collection**
   - The official app tracked velocity max/avg/deviation for concentric/eccentric phases
   - Phoenix collects velocity in MetricSample but does not aggregate into session stats
   - **Action**: Add `peakVelocityConcentricA/B`, `avgVelocityConcentricA/B`, etc. to WorkoutSession. Compute from MetricSample data during workout completion.

### MEDIUM Priority (Enhanced Analytics)

4. **Time Period Filtering for History**
   - Official app supported 7d/14d/29d/all-time filtering
   - **Action**: Add filter chips to HistoryTab and InsightsTab for time period selection. Apply to all charts and stats calculations.

5. **Statistical Deviation Tracking**
   - Official app computed variance using `Var = E[X^2] - E[X]^2` (Welford-like approach)
   - **Action**: Add deviation fields to PhaseStatistics or WorkoutSession. Compute during workout using the two-pass approach from `zk/h.java` method `a()` and `d()`.

6. **Physics-Based Calorie Formula**
   - Official app used work-energy theorem: `(force * distance * gravity / 4184) * 5`
   - Phoenix has `estimatedCalories` but formula source unclear
   - **Action**: Implement the physics-based calorie formula from `zk/h.java` method `c()` (line 232-267). Use MetricSample force and position data to compute actual work done, then apply the 5x metabolic multiplier.

7. **CSV Export**
   - Official app had `downloadCSV` capability in ExerciseDetailScreenViewModel
   - **Action**: Add CSV export for individual workout sessions and bulk history. Include all MetricSample data.

### LOW Priority (Polish)

8. **Volume Chart Y-Axis Labels at 33%/66%/100%**
   - Official app showed 4 labels: 0, 33%, 66%, 100% (line 342-347 in Mj/f.java)
   - Phoenix shows 3 labels: 0, max/2, max
   - **Action**: Update formatVolumeLabel in VolumeTrendChart.kt to use 0/33%/66%/100% divisions.

9. **Smart Label Density**
   - Official app showed every 5th label when >20 bars (line 716 in Mj/f.java)
   - **Action**: Add label density logic to X-axis in VolumeTrendChart when data points exceed threshold.

10. **Leaderboard Data Model Preparation**
    - Official app had full leaderboard integration via ProfileScreenViewModel
    - **Action**: No immediate implementation needed, but consider data model preparation for community features via Supabase sync. The official app's leaderboard structure (`UserSummary`, `pointsProgress`) could inform future design.

### Already Ahead of Official App (No Action Needed)

- **Offline-first architecture** (SQLDelight vs cloud-only)
- **Local PR tracking** with 1RM calculations and dual PR types
- **CalendarHeatmapCard** (GitHub-style activity visualization)
- **RadarChart** for muscle balance
- **ComboChart** for volume vs intensity
- **GaugeChart** for consistency
- **Safety tracking** (deload, ROM violations, spotter activations)
- **Training cycle** system (periodization)
- **Phase statistics** parity with BLE heuristic data
