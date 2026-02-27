# Architecture Research

**Domain:** KMP Fitness App — v0.5.1 Feature Integration
**Researched:** 2026-02-27
**Confidence:** HIGH — drawn entirely from direct codebase inspection

---

## Standard Architecture

### System Overview

```
┌──────────────────────────────────────────────────────────────────┐
│                    Presentation Layer (commonMain)                │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐           │
│  │ MainViewModel│  │ GamificationVM│  │AssessmentVM  │           │
│  │ (420L facade)│  │              │  │              │           │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘           │
│         │                │                  │                   │
│  ┌──────▼───────────────────────────────────▼───────┐           │
│  │               5 Delegated Managers                │           │
│  │  BleManager  WorkoutSessionManager  ExerciseDetect│           │
│  │  GamificationManager  AnalyticsManager            │           │
│  └──────┬─────────────────────────────────┬──────────┘           │
│         │                                 │                      │
│  ┌──────▼─────────────────────────────────┐                      │
│  │      DefaultWorkoutSessionManager      │                      │
│  │  ┌──────────────────────────────────┐  │                      │
│  │  │  WorkoutCoordinator (state bus)  │  │                      │
│  │  │  RoutineFlowManager (1,091L)     │  │                      │
│  │  │  ActiveSessionEngine (~2,600L)   │  │                      │
│  │  └──────────────────────────────────┘  │                      │
│  └────────────────────────────────────────┘                      │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│                    Domain Layer (commonMain)                      │
│  ┌──────────────┐  ┌─────────────────┐  ┌──────────────────────┐ │
│  │BiomechanicsE │  │ RepQualityScorer │  │SmartSuggestionsEngine│ │
│  │FormRulesEngine│  │ AssessmentEngine │  │(stateless object)    │ │
│  │FeatureGate   │  │ ExerciseClassifier│  │SignatureExtractor     │ │
│  └──────────────┘  └─────────────────┘  └──────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│                      Data Layer (commonMain)                      │
│  ┌──────────────┐  ┌────────────────┐  ┌──────────────────────┐  │
│  │WorkoutRepo   │  │BiomechanicsRepo│  │SmartSuggestionsRepo  │  │
│  │GamificationR │  │RepMetricRepo   │  │AssessmentRepo        │  │
│  │PersonalRecordR│  │ExerciseRepo   │  │PreferencesManager    │  │
│  └──────────────┘  └────────────────┘  └──────────────────────┘  │
│              SQLDelight (schema v16 — 15 migration files)         │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │ WorkoutSession | MetricSample | RepMetric | RepBiomechanics  │ │
│  │ PersonalRecord | GamificationStats(singleton) | EarnedBadge  │ │
│  │ ExerciseSignature | AssessmentResult | Routine | CompletedSet│ │
│  └──────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

### Component Responsibilities

| Component | Responsibility | Communicates With |
|-----------|---------------|-------------------|
| MainViewModel | Thin 420L facade, exposes StateFlows to UI | 5 managers |
| WorkoutCoordinator | Zero-method shared state bus | 3 sub-managers of DWSM |
| ActiveSessionEngine | BLE commands, rep counting, quality scoring, biomechanics | Coordinator, domain engines |
| FeatureGate | Stateless tier-gate utility (object, pure function) | UI composables |
| SmartSuggestionsEngine | Stateless pure computation over `List<SessionSummary>` | Called with data, no DI |
| PreferencesManager | Key-value user settings via multiplatform-settings | UI, managers |
| GamificationStats (DB) | Singleton row (id=1) for aggregate counters | SqlDelightGamificationRepository |
| BiomechanicsRepository | Per-rep VBT/force/asymmetry CRUD | SQLDelight RepBiomechanics table |

---

## v0.5.1 Feature Integration Analysis

### 1. Ghost Racing

**Question:** Where does the matcher/comparator live? How does it access historical session data during an active workout?

**Integration decision:** New `domain/premium/GhostRacingEngine.kt` as a stateless pure-function object. It does NOT live inside `ActiveSessionEngine` — ghost racing is a display-only consumer of workout data with no control responsibility. Placing it in the 2,600L engine would add unrelated logic to an already large class.

**Data access model:**

```
Pre-workout (before first rep):
  PersonalRecord.selectBestWeightPR(exerciseId, workoutMode)
      ↓ returns the PR row containing sessionId reference...
      note: PersonalRecord does NOT store sessionId.

  Alternative: WorkoutRepository.getRecentSessionsSync(limit=N)
      ↓ filter by exerciseId + workoutMode, pick session with highest
        totalVolumeKg or workingReps (best performance proxy)
      ↓ bestSessionId determined

  RepMetricRepository.getRepMetricsBySession(bestSessionId)
      ↓ List<RepMetricRow> already has: concentricVelocities (JSON),
        durationMs, rangeOfMotionMm, avgVelocityConcentric per rep
      ↓ held in GhostRacingEngine memory for session duration

During workout (per-rep):
  ActiveSessionEngine emits repCompleted → WorkoutCoordinator
      ↓ currentRepNumber, currentRepMcv (already computed)
  GhostRacingEngine.compare(currentRepNumber, ghostRepData[n-1])
      ↓ GhostRaceState (mcvDelta, verdict: AHEAD/BEHIND/TIED)
      ↓ WorkoutCoordinator.ghostRaceState StateFlow updated
      ↓ MainViewModel.ghostRaceState → HUD overlay composable
```

**Schema change required:** None. All data needed already exists in `RepMetric` (per-rep `avgVelocityConcentric`, `durationMs`, `rangeOfMotionMm`) keyed by `sessionId`. The `RepBiomechanics` table also has `mcvMmS` per rep if biomechanics data exists.

**Best-session lookup gap:** `PersonalRecord` does not store `sessionId`. The approach is to query `WorkoutSession` rows filtered by `exerciseId` and sort by `totalVolumeKg DESC` to find the best historical session. This query does not exist yet — needs a new named query in `VitruvianDatabase.sq`: `selectBestSessionForExercise`.

**HUD integration:** Ghost racing renders as an overlay on `ExecutionPage` (page 0), not as a new pager page. Users do not scroll during lifting. The `GhostRaceOverlay` composable is conditionally rendered over `ExecutionPage` when `ghostRaceState != null`. This avoids changing `pageCount = { 3 }` in the existing pager.

**WorkoutCoordinator addition:** Add `val ghostRaceState: MutableStateFlow<GhostRaceState?> = MutableStateFlow(null)` to `WorkoutCoordinator`. `ActiveSessionEngine` updates it on each rep completion.

**New files:**
- `shared/.../domain/premium/GhostRacingEngine.kt`
- `shared/.../domain/model/GhostRacingModels.kt`
- `shared/.../presentation/components/GhostRaceOverlay.kt`

**Modified files:**
- `WorkoutCoordinator.kt` — add `ghostRaceState` StateFlow
- `ActiveSessionEngine.kt` — populate ghost state on rep completion, load ghost data pre-session
- `WorkoutHud.kt` — wire overlay into `ExecutionPage` slot
- `FeatureGate.kt` — add `GHOST_RACING` (Phoenix tier)
- `VitruvianDatabase.sq` — add `selectBestSessionForExercise` named query
- `WorkoutRepository.kt` + `SqlDelightWorkoutRepository.kt` — expose best-session query

---

### 2. RPG Attributes

**Question:** Where does the computation engine live? New schema? Interaction with GamificationStats singleton?

**Integration decision:** New `domain/premium/RpgAttributeEngine.kt` as a stateless pure-function object. Follows `SmartSuggestionsEngine` pattern exactly. Takes `GamificationStats`, `List<WorkoutSession>`, `List<PersonalRecord>` as input and returns `RpgAttributes`. No DI dependencies. No DB writes.

**The 5 attributes map to already-persisted data (zero new schema):**

| Attribute | Source Data | Column / Table |
|-----------|-------------|----------------|
| Strength | Best 1RM across exercises | `PersonalRecord.oneRepMax` |
| Endurance | Total volume + session count | `GamificationStats.totalVolumeKg`, `totalWorkouts` |
| Consistency | Streak data | `GamificationStats.currentStreak`, `longestStreak` |
| Power | Average MCV across sessions | `WorkoutSession.avgMcvMmS` (schema v16 column) |
| Balance | Average asymmetry across sessions | `WorkoutSession.avgAsymmetryPercent` (schema v16 column) |

**No new schema tables required.** All five attribute inputs are derivable from data already in the database. Attribute values are computed on-demand and never stored — they are display-only derived values.

**Character class** is determined by whichever attribute ranks highest (pure function, no persistence).

**Interaction with GamificationStats singleton:** The RPG engine reads from `GamificationStats` (singleton row id=1) via `GamificationRepository.getGamificationStats(): Flow<GamificationStats>`. It does NOT write to it. The singleton already tracks `totalVolumeKg`, `longestStreak`, `currentStreak`.

**Data pipeline:**

```
GamificationRepository.getGamificationStats()    → GamificationStats
PersonalRecordRepository.getAllPersonalRecords()  → List<PersonalRecord> (for Strength)
WorkoutRepository.getAllSessions()                → List<WorkoutSession> (Power/Balance)
         ↓ (all three collected in coroutine, combine())
RpgAttributeEngine.compute(stats, sessions, records) → RpgAttributes
         ↓
RpgAttributeCard composable (Phoenix tier gated via FeatureGate.RPG_ATTRIBUTES)
```

**Computation trigger:** Analytics/profile screen load. One-shot computation (not a continuous flow), acceptable latency for an off-workout analytics screen.

**New files:**
- `shared/.../domain/premium/RpgAttributeEngine.kt`
- `shared/.../domain/model/RpgModels.kt` — `RpgAttributes`, `CharacterClass` enum, `RpgAttribute` sealed class
- `shared/.../presentation/components/RpgAttributeCard.kt`

**Modified files:**
- `FeatureGate.kt` — add `RPG_ATTRIBUTES` to Feature enum (Phoenix tier)
- `DomainModule.kt` — no change (stateless object, no Koin registration needed)
- Screen composable that hosts the card (likely analytics or gamification screen)

---

### 3. HUD Customization

**Question:** Preferences storage, how to make StatsPage visibility user-configurable without breaking existing HUD?

**Storage decision:** `PreferencesManager` / `SettingsPreferencesManager` — the established pattern for all user toggles. Uses `multiplatform-settings` (SharedPreferences on Android, NSUserDefaults on iOS). Do NOT use the database — this is a local device preference, not synced data.

**Config model:** Add `HudPageConfig` as a `@Serializable` data class stored as a single JSON blob under one key. This follows the identical pattern used for `SingleExerciseDefaults` and `JustLiftDefaults`.

```kotlin
// commonMain/domain/model/HudPageConfig.kt
@Serializable
data class HudPageConfig(
    val showInstructionPage: Boolean = true,  // page 1 (video/exercise instructions)
    val showStatsPage: Boolean = true          // page 2 (biomechanics stats)
    // ExecutionPage (page 0) is always visible — not toggleable
)
```

**PreferencesManager changes:**
- Add `val hudPageConfig: HudPageConfig` to `UserPreferences` with default `HudPageConfig()`
- Add `suspend fun setHudPageConfig(config: HudPageConfig)` to interface
- Implement with `settings.putString(KEY_HUD_PAGE_CONFIG, json.encodeToString(config))`
- Read back on `loadPreferences()` with null-safe decode

**WorkoutHud.kt change — dynamic page list:**

```kotlin
// WorkoutHud now accepts hudPageConfig parameter
val visiblePages: List<PageType> = buildList {
    add(PageType.EXECUTION)
    if (hudPageConfig.showInstructionPage) add(PageType.INSTRUCTION)
    if (hudPageConfig.showStatsPage) add(PageType.STATS)
}
val pagerState = rememberPagerState(pageCount = { visiblePages.size })
// ...
when (visiblePages[page]) {
    PageType.EXECUTION -> ExecutionPage(...)
    PageType.INSTRUCTION -> InstructionPage(...)
    PageType.STATS -> StatsPage(...)
}
```

**Breaking risk assessment:** Low. `ExecutionPage` (page 0) is always included — cannot be hidden. `StatsPage` (current page 2) becomes conditional. The pager dots update automatically since they're based on `pagerState.pageCount`. If a user hides the stats page, the pager simply has 2 pages instead of 3.

**New files:**
- `shared/.../domain/model/HudPageConfig.kt`
- `shared/.../presentation/screen/HudCustomizationSheet.kt` (bottom sheet or settings entry)

**Modified files:**
- `UserPreferences.kt` — add `hudPageConfig` field with default
- `PreferencesManager.kt` — add `setHudPageConfig` method
- `SettingsPreferencesManager.kt` — implement save/load with JSON
- `WorkoutHud.kt` — accept `hudPageConfig` param, build dynamic page list
- `MainViewModel.kt` or `WorkoutTab.kt` — pass config from preferences to HUD

**No schema migration required.**

---

### 4. Readiness Briefing

**Question:** Where does the heuristic engine live? When does it compute? How does it access recent session history?

**Integration decision:** New `domain/premium/ReadinessBriefingEngine.kt` as a stateless pure-function object. Computes from `List<SessionSummary>` — the exact same type already used by `SmartSuggestionsEngine`. No new repository is needed: existing `SmartSuggestionsRepository.getSessionSummariesSince(timestamp)` provides all required data.

**Trigger timing:** Computes pre-first-set, after exercise selection, before `ActiveSessionEngine.start()`. One-time computation per workout session. Not a continuous or streaming computation.

**Proposed data flow:**

```
User selects exercise / routine day → WorkoutSetupScreen (or wherever pre-set state lives)
  ↓
SmartSuggestionsRepository.getSessionSummariesSince(nowMs - 28 * DAY_MS)
  ↓  List<SessionSummary> (already fetches exerciseId, muscleGroup, timestamp, weightPerCableKg, workingReps)
ReadinessBriefingEngine.compute(sessions, targetMuscleGroup, nowMs)
  ↓ ReadinessBriefing (readinessLevel: GREEN/YELLOW/RED, advisoryText, daysSinceLastTraining)
  ↓
ReadinessBriefingCard composable displayed (Elite tier gated)
User dismisses
  ↓
ActiveSessionEngine.start()
```

**Heuristic inputs from existing data:**
- Days since last training the same muscle group → computed from `SessionSummary.timestamp` filtered by `muscleGroup`
- Volume in last 7 days for target muscle group → `SmartSuggestionsEngine.computeWeeklyVolume()` pattern (can reuse)
- Current streak → `GamificationStats.currentStreak` (read via `GamificationRepository`)

**Advisory only — no machine control.** The briefing is a text/visual card. It does not modify `WorkoutParameters` or send BLE commands. This aligns with constraint CV-08 (no output types reference machine control).

**Elite tier gate:** Add `READINESS_BRIEFING` to `FeatureGate.Feature` enum in the elite features set.

**New files:**
- `shared/.../domain/premium/ReadinessBriefingEngine.kt`
- `shared/.../domain/model/ReadinessBriefingModels.kt` — `ReadinessBriefing`, `ReadinessLevel` enum
- `shared/.../presentation/components/ReadinessBriefingCard.kt`

**Modified files:**
- `FeatureGate.kt` — add `READINESS_BRIEFING` (Elite tier)
- Pre-workout screen composable — render card before session start

**No schema migration required.** Data comes from existing `WorkoutSession` table via `SmartSuggestionsRepository`.

---

### 5. WCAG Color-Blind Fallbacks

**Question:** Theme-level vs component-level approach for color-blind fallbacks?

**Current state of color systems in the codebase:**

The codebase has three distinct color systems:
1. **Theme colors** — `ui/theme/Color.kt` + `Theme.kt` — Material3 `colorScheme`, used by most UI components
2. **Data visualization colors** — `ui/theme/DataColors.kt` — `LoadA` (blue) vs `LoadB` (orange) already avoids red/green conflict. Comment explicitly notes "colorblind-safe" intent.
3. **Velocity zone colors / balance severity** — embedded inline in `WorkoutHud.kt` and `BalanceBar.kt` — these are the primary WCAG gap. Velocity zones use green/yellow/red semantics that fail deuteranopia/protanopia.

**Recommended approach: Hybrid infrastructure**

**Theme-level infrastructure:** Create `ui/theme/AccessibilityColors.kt` with deuteranopia-safe alternatives for all data-status colors:

```kotlin
// ui/theme/AccessibilityColors.kt (NEW FILE)
object AccessibilityColors {
    // Velocity zones — blue/cyan/orange/purple instead of green/yellow/red
    val zoneExplosive  = Color(0xFF0077BB)  // Strong blue
    val zoneFast       = Color(0xFF33BBEE)  // Cyan
    val zoneModerate   = Color(0xFFEE7733)  // Orange
    val zoneSlow       = Color(0xFFAA3377)  // Magenta-purple
    val zoneGrind      = Color(0xFFBBBBBB)  // Neutral grey

    // Balance bar — cable A vs B, already OK in DataColors but provide here for consistency
    val balanceCableA  = Color(0xFF0077BB)  // Blue (same as DataColors.LoadA)
    val balanceCableB  = Color(0xFFEE7733)  // Orange (same as DataColors.LoadB)

    // Readiness card (new feature — design accessible from the start)
    val readinessGood    = Color(0xFF0077BB)  // Blue (not green)
    val readinessCaution = Color(0xFFEE7733)  // Orange (not yellow)
    val readinessFatigue = Color(0xFFAA3377)  // Purple (not red)
}
```

**Color-blind toggle:** Stored in `UserPreferences` as `colorBlindModeEnabled: Boolean = false`. Persisted via `PreferencesManager`. Not in the database.

**Component-level application via CompositionLocal (NOT parameter threading):**

```kotlin
// In Theme.kt or a new CompositionLocals.kt
val LocalColorBlindMode = compositionLocalOf { false }

// In VitruvianTheme / AndroidTheme:
CompositionLocalProvider(
    LocalColorBlindMode provides userPreferences.colorBlindModeEnabled
) {
    content()
}

// In components (BalanceBar, velocity HUD, ReadinessBriefingCard):
val isColorBlindMode = LocalColorBlindMode.current
val zoneColor = if (isColorBlindMode) AccessibilityColors.zoneModerate else DefaultColors.zoneModerate
```

**Why CompositionLocal over parameters:** Threading `colorBlindEnabled: Boolean` through `WorkoutHud` → `ExecutionPage` → `BalanceBar` adds 3+ call-site changes for a cross-cutting concern. `CompositionLocal` is the Compose-idiomatic solution for theme-like cross-cutting values.

**Shape + text as primary indicator:** For the readiness card (new feature), implement the readiness level with both a shape/icon AND color as primary signals. Color-blind users can read the icon (checkmark/warning/stop) independently. This is the correct WCAG approach: color is enhancement, not the only signal.

**Modified files (WCAG):**
- `UserPreferences.kt` — add `colorBlindModeEnabled: Boolean = false`
- `PreferencesManager.kt` — add `setColorBlindModeEnabled` method
- `SettingsPreferencesManager.kt` — implement
- `ui/theme/AccessibilityColors.kt` — new file
- `Theme.kt` or new `CompositionLocals.kt` — inject `LocalColorBlindMode`
- `BalanceBar.kt` — consume `LocalColorBlindMode` for severity colors
- `WorkoutHud.kt` — consume `LocalColorBlindMode` for velocity zone colors inline
- Settings screen — expose toggle

**New files (WCAG):**
- `shared/.../ui/theme/AccessibilityColors.kt`

**No schema migration required.**

---

## Component Boundaries for New v0.5.1 Components

| New Component | Type | Location | Dependencies |
|---------------|------|----------|--------------|
| `GhostRacingEngine` | Stateless object | `domain/premium/` | None (pure functions) |
| `GhostRacingModels` | Data models | `domain/model/` | None |
| `GhostRaceOverlay` | Composable | `presentation/components/` | `GhostRaceState` model |
| `RpgAttributeEngine` | Stateless object | `domain/premium/` | None (pure functions) |
| `RpgModels` | Data models | `domain/model/` | None |
| `RpgAttributeCard` | Composable | `presentation/components/` | `RpgAttributes` model |
| `ReadinessBriefingEngine` | Stateless object | `domain/premium/` | None (pure functions) |
| `ReadinessBriefingModels` | Data models | `domain/model/` | None |
| `ReadinessBriefingCard` | Composable | `presentation/components/` | `ReadinessBriefing` model |
| `HudPageConfig` | Serializable model | `domain/model/` | kotlinx.serialization |
| `HudCustomizationSheet` | Composable | `presentation/screen/` | `PreferencesManager` |
| `AccessibilityColors` | Color object | `ui/theme/` | Compose `Color` |

---

## Data Flow for New Features

### Ghost Racing Data Flow

```
Pre-workout (before first rep, triggered by exercise selection):
  WorkoutRepository.getRecentSessionsSync(limit=50)
      ↓ filter by exerciseId + mode, sort by totalVolumeKg DESC
      ↓ bestSessionId = first match
  RepMetricRepository.getRepMetricsBySession(bestSessionId)
      ↓ List<RepMetricRow> — avgVelocityConcentric, durationMs, rangeOfMotionMm per rep
      ↓ GhostRacingEngine holds as ghostRepData[]

During workout (per-rep):
  ActiveSessionEngine completes rep → WorkoutCoordinator receives repCompleted event
      ↓ currentRepNumber, currentMcvMmS
  GhostRacingEngine.compare(currentRepNumber, ghostRepData) → GhostRaceState
      ↓ WorkoutCoordinator.ghostRaceState.value = newState
      ↓ MainViewModel.ghostRaceState StateFlow
      ↓ WorkoutHud.ExecutionPage → GhostRaceOverlay composable
```

### RPG Attribute Data Flow

```
Profile / Analytics screen load:
  combine(
    GamificationRepository.getGamificationStats(),       → GamificationStats
    PersonalRecordRepository.getAllPersonalRecords(),     → List<PersonalRecord>
    WorkoutRepository.getAllSessions()                    → List<WorkoutSession>
  )
      ↓
  RpgAttributeEngine.compute(stats, sessions, records) → RpgAttributes
      ↓
  RpgAttributeCard (gated: FeatureGate.isEnabled(RPG_ATTRIBUTES, tier))
```

### Readiness Briefing Data Flow

```
Pre-workout (exercise selected, before session start):
  SmartSuggestionsRepository.getSessionSummariesSince(nowMs - 28 * DAY_MS)
      ↓ List<SessionSummary> — same type SmartSuggestionsEngine uses
  ReadinessBriefingEngine.compute(sessions, targetMuscleGroup, nowMs)
      ↓ ReadinessBriefing (readinessLevel, advisoryText, daysSinceLastTraining)
      ↓ ReadinessBriefingCard shown (gated: READINESS_BRIEFING, ELITE tier)
  User dismisses → ActiveSessionEngine.start()
```

### HUD Customization Data Flow

```
Settings screen:
  HudCustomizationSheet
      ↓ toggle changed
  PreferencesManager.setHudPageConfig(newConfig)
      ↓ stored as JSON in Settings (SharedPrefs / NSUserDefaults)
      ↓ _preferencesFlow.value updated

Workout screen:
  MainViewModel collects preferencesFlow → exposes hudPageConfig StateFlow
      ↓ WorkoutHud(hudPageConfig = ...)
      ↓ visiblePages = buildList { EXECUTION always, INSTRUCTION if enabled, STATS if enabled }
      ↓ HorizontalPager(pageCount = { visiblePages.size })
```

---

## Schema Migration Assessment

| Feature | Schema Change Required | Migration | iOS DriverFactory Impact |
|---------|----------------------|-----------|--------------------------|
| Ghost Racing | Minimal — new SQL query only | New named query in `.sq` file (no table change) | None |
| RPG Attributes | None | None | None |
| HUD Customization | None (PreferencesManager) | None | None |
| Readiness Briefing | None | None | None |
| WCAG Accessibility | None (PreferencesManager) | None | None |
| CV Form Check persistence | Yes — form score/violations storage | New migration `16.sqm` → schema v17 | **CRITICAL: Increment `CURRENT_SCHEMA_VERSION = 17L`, add all new tables with `IF NOT EXISTS`, add new columns with `ALTER TABLE ADD COLUMN` and duplicate detection** |

**Five of six feature areas in this research require zero schema changes.** Only CV Form Check persistence (which has its own separate implementation plan) needs a migration.

**iOS DriverFactory sync rule (Daem0n warning #155):** When any `.sqm` migration file is added, `CURRENT_SCHEMA_VERSION` in `DriverFactory.ios.kt` must be incremented. The 4-layer defense purges outdated databases rather than migrating them — so the constant must match or iOS users will lose data on launch.

---

## Architectural Patterns to Follow

### Pattern 1: Stateless Domain Engine

**What:** Pure `object` with `fun compute(inputs): Output` signature. No mutable state, no DI, no DB.
**When to use:** All three new computation engines (Ghost, RPG, Readiness).
**Why this matters:** Enables pure unit tests with zero setup. Pass `nowMs` as explicit parameter for time-dependent functions — same discipline as `SmartSuggestionsEngine`.

```kotlin
// Correct pattern (stateless object)
object GhostRacingEngine {
    fun compare(currentRepNumber: Int, ghostReps: List<GhostRepData>): GhostRaceState { ... }
}

// Incorrect (stateful class with injected dependencies for pure computation)
class GhostRacingEngine(private val repository: RepMetricRepository) { ... }
```

### Pattern 2: Single Upstream Feature Gate

**What:** Gate at the data-fetching or engine invocation call site. Return null if tier insufficient. Null propagation handles all downstream UI naturally.
**When to use:** Every new premium feature.

```kotlin
// In ActiveSessionEngine or calling code:
val ghostState = if (FeatureGate.isEnabled(Feature.GHOST_RACING, tier))
    GhostRacingEngine.compare(repNum, ghostData)
else null
// Overlay composable: if (ghostState != null) GhostRaceOverlay(ghostState)
```

### Pattern 3: Preferences for User-Configurable Device State

**What:** `@Serializable` data class stored as JSON blob via `SettingsPreferencesManager`. One key per logical config group.
**When to use:** Any user preference that is local-only (not synced, not analytics).

```kotlin
// Consistent with existing SingleExerciseDefaults and JustLiftDefaults
private const val KEY_HUD_PAGE_CONFIG = "hud_page_config"

override suspend fun setHudPageConfig(config: HudPageConfig) {
    settings.putString(KEY_HUD_PAGE_CONFIG, json.encodeToString(config))
    updateAndEmit { copy(hudPageConfig = config) }
}
```

### Pattern 4: WorkoutCoordinator as State Conduit

**What:** New state that crosses manager boundaries lives as a `StateFlow` field on `WorkoutCoordinator`. Sub-managers write it; the HUD reads it via `MainViewModel`.
**When to use:** Ghost race state (written by `ActiveSessionEngine`, read by HUD overlay).

### Pattern 5: CompositionLocal for Cross-Cutting Theme Values

**What:** `compositionLocalOf { defaultValue }` injected at the theme root. Components consume locally without parameter threading.
**When to use:** Color-blind mode — it affects many components and would require threading through every call site if passed as a parameter.

---

## Anti-Patterns to Avoid

### Anti-Pattern 1: Adding Computation to ActiveSessionEngine

**What people do:** Embed ghost comparison, RPG calculation, or readiness heuristic inside the 2,600L `ActiveSessionEngine`.
**Why it's wrong:** The engine manages BLE, rep counting, weight control, and quality scoring — adding unrelated display computation increases size and regression risk. Ghost racing specifically is display-only.
**Do this instead:** New stateless engines in `domain/premium/`. State flows via `WorkoutCoordinator`.

### Anti-Pattern 2: Persisting Derived RPG Attributes

**What people do:** Create an `RpgAttributes` table to cache computed scores.
**Why it's wrong:** Attributes are fully derivable from existing data. Caching creates staleness — attributes would be wrong until the cache is invalidated after every new session.
**Do this instead:** Compute on demand in the analytics screen. The computation is O(sessions) — acceptable for an off-workout screen.

### Anti-Pattern 3: Parameter-Threading Color-Blind Flag

**What people do:** Add `colorBlindEnabled: Boolean` to `WorkoutHud` → `ExecutionPage` → `BalanceBar` → every leaf component.
**Why it's wrong:** 5+ call-site changes for a cross-cutting theme concern. Parameter bloat.
**Do this instead:** `LocalColorBlindMode` CompositionLocal. Leaf components read it directly.

### Anti-Pattern 4: New Koin Module for Stateless Engines

**What people do:** Register `GhostRacingEngine`, `RpgAttributeEngine`, `ReadinessBriefingEngine` in a new Koin module.
**Why it's wrong:** These are Kotlin `object` singletons — they do not need Koin registration. Koin is only needed for classes with injected dependencies.
**Do this instead:** Call them directly. Only update `DomainModule` if an engine requires a repository dependency.

### Anti-Pattern 5: Ghost Racing as a New Pager Page

**What people do:** Add ghost racing as a fourth page in the `HorizontalPager`.
**Why it's wrong:** Users cannot swipe to a different page while actively lifting. Ghost racing must be visible on the main `ExecutionPage` (page 0) during the rep.
**Do this instead:** Overlay composable on `ExecutionPage`, conditionally rendered when ghost state is non-null.

---

## Build Order Considering Dependencies

```
Independent:
  ┌─ Domain models (GhostRacingModels, RpgModels, ReadinessBriefingModels, HudPageConfig)
  ├─ FeatureGate additions (3 new Feature enum values)
  └─ AccessibilityColors.kt + LocalColorBlindMode

Foundation (no inter-feature dependencies):
  ├─ UserPreferences + PreferencesManager extensions (colorBlindMode, HudPageConfig)
  └─ SQL query: selectBestSessionForExercise (WorkoutRepository addition)

Engines (depend on models only):
  ├─ ReadinessBriefingEngine
  ├─ RpgAttributeEngine
  └─ GhostRacingEngine

UI components (depend on engines + preferences):
  ├─ ReadinessBriefingCard + integration (simplest, reuses SmartSuggestionsRepository)
  ├─ RpgAttributeCard + integration (reads from 3 existing repos)
  ├─ WCAG component updates (BalanceBar, WorkoutHud velocity colors)
  ├─ HUD customization (WorkoutHud.kt dynamic pager + settings sheet)
  └─ GhostRaceOverlay + WorkoutCoordinator wiring (most complex — last)
```

**Recommended phase sequence:**

1. **Foundation** — Domain models + FeatureGate + PreferencesManager extensions + `selectBestSessionForExercise` query. All testable, no UI.
2. **WCAG + HUD customization** — Board condition items. Preferences-based, no new engines. Lowest regression risk.
3. **Readiness briefing** — Stateless engine + single card composable. Reuses existing `SmartSuggestionsRepository`.
4. **RPG attributes** — Stateless engine + card. Reads from 3 existing repos via `combine()`.
5. **Ghost racing** — Most complex: `WorkoutCoordinator` change + pre-workout data loading + real-time HUD overlay. Last because it touches active workout lifecycle.

---

## Integration Points Summary

### New vs Modified Components

| Component | Status | Risk Level |
|-----------|--------|------------|
| `GhostRacingEngine.kt` | New | Low |
| `GhostRacingModels.kt` | New | Low |
| `GhostRaceOverlay.kt` | New | Low |
| `RpgAttributeEngine.kt` | New | Low |
| `RpgModels.kt` | New | Low |
| `RpgAttributeCard.kt` | New | Low |
| `ReadinessBriefingEngine.kt` | New | Low |
| `ReadinessBriefingModels.kt` | New | Low |
| `ReadinessBriefingCard.kt` | New | Low |
| `AccessibilityColors.kt` | New | Low |
| `HudPageConfig.kt` | New | Low |
| `HudCustomizationSheet.kt` | New | Low |
| `FeatureGate.kt` | Modified — add 3 Feature entries | Low |
| `UserPreferences.kt` | Modified — add 2 fields with defaults | Low (additive) |
| `PreferencesManager.kt` | Modified — add 4 methods | Low (additive) |
| `SettingsPreferencesManager.kt` | Modified — implement new methods | Low |
| `Theme.kt` | Modified — inject LocalColorBlindMode | Low |
| `BalanceBar.kt` | Modified — consume LocalColorBlindMode | Low |
| `WorkoutHud.kt` | Modified — dynamic page count + overlay slot | **Medium** |
| `WorkoutCoordinator.kt` | Modified — add ghostRaceState StateFlow | Medium |
| `MainViewModel.kt` | Modified — expose new StateFlows | Low (additive) |
| `WorkoutRepository.kt` | Modified — add best-session query | Low |
| `VitruvianDatabase.sq` | Modified — add selectBestSessionForExercise | Low |
| `DomainModule.kt` | No change needed (stateless objects) | None |
| `DataModule.kt` | No change needed | None |
| `DriverFactory.ios.kt` | Only if schema version changes | Critical if triggered |

---

## Sources

- Direct codebase inspection (all HIGH confidence):
  - `shared/.../sqldelight/.../VitruvianDatabase.sq` — complete schema v16 with all tables and queries
  - `shared/.../presentation/screen/WorkoutHud.kt` — HUD pager structure, page types, biomechanics overlay
  - `shared/.../domain/premium/SmartSuggestionsEngine.kt` — stateless engine pattern to follow
  - `shared/.../domain/premium/FeatureGate.kt` — tier gate system, Feature enum, phoenixFeatures set
  - `shared/.../domain/premium/SubscriptionTier.kt` — FREE/PHOENIX/ELITE structure
  - `shared/.../data/preferences/PreferencesManager.kt` — preferences storage pattern (JSON blob)
  - `shared/.../data/repository/SmartSuggestionsRepository.kt` — SessionSummary data access
  - `shared/.../data/repository/BiomechanicsRepository.kt` — GATE-04 pattern (no tier checks in repos)
  - `shared/.../data/repository/WorkoutRepository.kt` — session query capabilities
  - `shared/.../data/repository/GamificationRepository.kt` — stats singleton access
  - `shared/.../domain/model/Gamification.kt` — GamificationStats fields
  - `shared/.../domain/model/BiomechanicsModels.kt` — VBT/force/asymmetry result types
  - `shared/.../domain/model/UserPreferences.kt` — existing preference fields
  - `shared/.../ui/theme/DataColors.kt` — existing color-blind-safe data colors
  - `shared/.../iosMain/.../DriverFactory.ios.kt` — 4-layer defense, CURRENT_SCHEMA_VERSION = 16L
  - `shared/.../di/DomainModule.kt` and `DataModule.kt` — Koin wiring patterns
- `.planning/PROJECT.md` — v0.5.1 milestone scope, architecture summary, constraints

---
*Architecture research for: Project Phoenix MP v0.5.1 Board Polish & Premium UI*
*Researched: 2026-02-27*
