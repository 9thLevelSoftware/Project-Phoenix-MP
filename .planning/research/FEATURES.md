# Feature Research: v0.5.1 Board Polish & Premium UI

**Domain:** Premium mobile fitness UI — ghost racing, RPG gamification, pre-workout readiness, accessibility, HUD customization
**Researched:** 2026-02-27
**Confidence:** HIGH (each domain has well-established competitor precedent and clear local-only implementation path)

---

## Context

v0.5.0 shipped with ghost racing, RPG, and readiness composables in stub mode. v0.5.1 completes the UX of those stubs and addresses 9 Board of Directors conditions. This document supersedes the v0.5.0 FEATURES.md for the five active research questions:

1. Ghost racing — how it works locally without a server
2. RPG attribute systems — stat derivation from workout data
3. Pre-workout readiness — volume-based heuristics with no wearable
4. WCAG AA accessibility — color-blind and high contrast in workout HUDs
5. HUD customization — configurable widgets in data-dense fitness displays

---

## Feature Landscape

### Table Stakes (Users Expect These)

Features users expect from a premium-tier connected fitness app in 2026. Missing any of these makes the PHOENIX/ELITE product feel unfinished.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| **Ghost racing shows vs. personal best** | Zwift (HoloReplay), Apple Watch (Race Route), Garmin (Segment Racing) all offer this. Any "performance improvement" feature that doesn't show progress vs. self is missing its point. | MEDIUM | Local-only via BiomechanicsHistory queries. Best match = same exercise + weight ±5%. Telemetry replayed time-indexed from set-start timestamp. |
| **RPG stats derived from actual workout data** | Gamification that doesn't connect to real data feels hollow. Fitbod's adaptive load, Freeletics' scoring, and every credible fitness RPG app (FitDM, INFITNITE) derive stats from logged workout metrics — not arbitrary point-clicks. | LOW | All inputs already exist: MCV from BiomechanicsEngine, rep quality from RepQualityScorer, GamificationStats for volume/streaks, AssessmentResult for 1RM. Computation is pure local math. |
| **Readiness score before workout** | Fitbit (Daily Readiness), Oura (Readiness Score), Garmin (Training Readiness), WHOOP (Recovery Score) all offer pre-session guidance. Users paying for ELITE expect some form of readiness advisory. | MEDIUM | Without wearable HRV/sleep data, volume-based ACWR heuristic (see research below) is the honest local-only approach. Must be clearly labeled "estimated from volume" not "HRV-based." |
| **Color-blind safe palette for status indicators** | WCAG 1.4.1 (Use of Color) is mandatory: color cannot be the sole differentiator. European Accessibility Act (EAA) enforcement since June 2025 applies to apps distributed in EU. Velocity zones and balance bar currently use color alone. | LOW | Add secondary differentiator (icon, pattern, label) alongside every color indicator. Compose MaterialTheme swap for high-contrast mode. No custom rendering required. |
| **User-configurable HUD metrics** | Garmin has offered customizable data screens (1–4 metrics per screen, multiple screens) since 2015. Peloton exposes configurable metrics. Any data-dense workout display that forces one layout on all users will frustrate advanced users who don't need every metric. | MEDIUM | Page-based HUD with user-selectable widget slots. SharedPreferences/DataStore for persistence. Drag-and-drop is out of scope for v0.5.1 — toggle on/off per metric is sufficient. |

### Differentiators (Competitive Advantage)

Features that Project Phoenix offers that no other connected cable machine app provides. This is where the app competes.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| **Ghost racing with cable telemetry position** | Velocity/position ghost built from Vitruvian BLE data. No other strength app (GymAware, RepOne, Metric VBT, Vitruve) offers rep-by-rep real-time ghost comparison on the same display as live cable data. Zwift and Apple Watch do this for endurance only. | MEDIUM | PHOENIX tier: rep-level velocity deltas only (simpler). ELITE tier: sample-level position replay at 50Hz. Ghost data comes from RepBiomechanics + RepMetric tables. |
| **RPG class auto-assigned from training style** | Character class determined by training profile, not user choice. Powerlifter class = heavy load dominance. Athlete class = velocity + consistency. Monk class = form quality dominance. This is unique — FitDM requires user to pick class. Only INFITNITE auto-assigns via workout history. | LOW | Class assignment formula: compute ratio of strength/power/consistency/mastery/stamina scores, take dominant profile. 5 classes × 5 dominant attributes = deterministic mapping. |
| **Readiness driven by Vitruvian-specific load** | Readiness input includes cable machine load data (session_volume = sets × reps × kg), not just step counts or generic "activity." Acute workload is computed from actual resistance training volume, which is more meaningful for strength athletes than WHOOP's strain (HR-based). | MEDIUM | ACWR using weighted volume units. Acute = 7-day rolling sum of volume. Chronic = 28-day rolling average. Ratio drives Green/Yellow/Red. No HRV needed at v0.5.1 — volume only. |
| **Accessibility modes that don't break the workout UI** | Most fitness apps treat accessibility as an afterthought. Offering a clearly labeled "Color Blind Mode" toggle in Settings (not buried in OS accessibility) with pre-tested alternate palettes shows intentional design. For the Vitruvian community, this is differentiating — competitors don't offer it. | LOW | Two alternate themes: deuteranopia-safe (replace green/red with blue/orange) and high-contrast (increase brightness differential). Stored in user preferences. Applied at MaterialTheme level — no per-composable overrides needed. |
| **HUD page presets with one-tap switching** | Instead of drag-and-drop widget editing (complex to implement correctly), offer 3 preset layouts: Compact (4 core metrics), Standard (current layout), Expert (all metrics including biomechanics). User can also toggle individual metrics in Settings. Garmin's 5-screen model is overkill for a focused machine UI. | MEDIUM | PageIndicator + swipe gesture already implied by Compose HUD structure. Preset JSON stored in DataStore. Preset selector exposed in Settings screen. |

### Anti-Features (Commonly Requested, Often Problematic)

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| **Ghost racing against other users' sessions** | "Let me race my training partner's PR." | Requires syncing another user's raw BLE telemetry to your device. Cable position data reflects individual limb length and machine calibration — comparing positions across users is physically meaningless. Privacy and consent complexity is high. | Ghost vs. own PR only. Future: opt-in leaderboard score comparison (aggregated, not raw telemetry). |
| **Readiness score blocking workouts** | "Red means don't train." | ACWR models carry a ±20% error margin. A false Red on the day a user's schedule allows training will cause a rage-quit uninstall. Sports science consensus (2024 Frontiers review) finds no injury reduction from ACWR-based workout blocking. | Advisory only. Always show override. Log whether user accepted or overrode. |
| **Ghost racing with animated avatar/avatar character** | "Show an avatar running alongside me." | Rendering an animated character on top of the existing camera PiP, balance bar, velocity HUD, and force curve mini-graph creates a density problem. Mid-range Android devices will drop frames. | Two clean vertical progress bars. Color (green/red) + numeric delta. No avatar. |
| **RPG stat manual override** | "I want to set my own Strength level." | Self-reported stats disconnect RPG from reality. The value is that class and levels reflect actual training data — arbitrary self-assignment destroys that. Habitica allows this and it leads to users quitting because their stats don't mean anything. | Display "based on your last N sessions" with source transparency. Show what data drives each stat. |
| **Drag-and-drop HUD layout editor** | "I want to arrange metrics exactly how I want." | Drag-and-drop on a dense workout screen requires significant engineering (hit-testing, reorder animation, persist state) and creates support burden (users lock themselves into unusable layouts). Not one competing app (Garmin Connect app, TrainingPeaks, WahooFitness) has shipped a fully free-form drag-and-drop for live workout HUD. | Preset layouts (3 options) + per-metric toggle in Settings. Handles 95% of customization need with 10% of implementation cost. |
| **Full WCAG AAA compliance** | "Why not go above AA?" | AAA requires 7:1 contrast ratio for normal text. On a dark workout HUD with colored status indicators (velocity zones, balance bar), achieving 7:1 across all states is impossible without abandoning the existing visual design entirely. The Board condition specifies WCAG AA. | Target WCAG AA (4.5:1 text, 3:1 UI components). AAA is a future aspiration, not a v0.5.1 requirement. |
| **HUD customization remembered per-exercise** | "I use different metrics for bench vs. squat." | Per-exercise layout state means 15+ different preference sets to manage. When auto-detection identifies exercise, UI has to switch layouts mid-workout. The engineering cost (detection → layout mapping, edge cases on mis-detection) far exceeds value for a first customization feature. | One global layout preference. If users want per-exercise differentiation, that's a v0.7+ feature request to track. |

---

## Ghost Racing: How It Works Locally

### The Zwift HoloReplay Model (Reference Implementation)

Zwift HoloReplay (launched 2023) is the highest-fidelity ghost racing implementation in fitness:

1. During every ride, Zwift records telemetry as a time-indexed sequence (position, speed, power at each second).
2. When user starts a timed segment, Zwift retrieves their best prior effort's telemetry for that segment.
3. A "ghost" avatar is rendered at the position corresponding to `elapsed_time` in the historical telemetry.
4. Ghost is visible only to the user, not draftable.
5. Key design decision: ghost replays moment-for-moment (if user blew up at minute 3, ghost does the same). This is more honest and useful than a smooth-speed ghost.

**Adaptation for Project Phoenix (local, strength training):**

The strength training context differs from cycling in one key way: there is no continuous spatial track. Instead of position-on-route, the meaningful comparison is cable position (mm) over time within a set.

Ghost matching strategy (local, no server):
- Query `RepBiomechanics` + `WorkoutSession` for sessions with matching exercise ID and weight within ±5% of current set weight.
- Among matches, select the session with highest average MCV (personal best by velocity).
- Ghost replay: for PHOENIX tier, replay rep-by-rep average MCV from `RepBiomechanics.avgMcv`. For ELITE tier, replay the raw `MetricSample` position stream (50Hz), time-indexed from set start.

**Key UX principle (from all ghost racing apps):** Ghost must always be the user's own data. Comparing to another person's cable position is physically meaningless (different limb lengths, different machine calibration baselines). Ghost is a tool for self-improvement, not competition.

### Local Session Matching Algorithm

```
bestGhostSession(exerciseId, currentWeightKg):
  candidates = RepBiomechanics
    JOIN WorkoutSession ON sessionId
    WHERE exerciseId = exerciseId
      AND workoutSession.avgWeightKg BETWEEN currentWeightKg * 0.95 AND currentWeightKg * 1.05
    ORDER BY avgMcv DESC
    LIMIT 1
```

This query already has all required tables in schema v16 (RepBiomechanics added in v0.5.0). No schema changes required for v0.5.1.

### Real-Time Overlay Mechanics

For PHOENIX tier (rep-level comparison):
- Ghost bar advances one step per ghost rep completed (based on rep duration from historical data).
- Current bar advances one step per live rep detected.
- Delta displayed: "Ahead by 2 reps" or "0.12 m/s faster."

For ELITE tier (sample-level comparison):
- Ghost position = `MetricSample.positionA` at `ghost_session_start + elapsed_time`.
- Live position = current BLE `positionA` value.
- Delta bar updates at BLE sample rate.

**Confidence:** HIGH — Zwift HoloReplay is the gold standard reference, well-documented. Local query approach is straightforward given existing schema.

---

## RPG Attribute System: Stat Derivation from Workout Data

### Competitor Approaches

| App | Stat Source | Class Assignment | Transparent? |
|-----|-------------|------------------|--------------|
| FitDM | Workout completion (binary) | User-selected | No computation shown |
| INFITNITE | Workout type (strength/cardio/mental) logged | Auto-assigned from activity mix | Partially |
| RPGFitness | XP per workout completion | Warrior/Mage/Rogue by training style | Not disclosed |
| Habitica | Task completion (habits) | User-selected | Not fitness-derived |

**Key finding:** No competitor in strength training derives RPG stats from biomechanical data (MCV, velocity loss, force curve shape). This is the differentiator. All competitors use coarse proxies (workout count, XP points).

### Recommended 5-Attribute System

Map existing computed data to intuitive RPG attributes:

| Attribute | Source Data | Computation | Confidence |
|-----------|-------------|-------------|------------|
| **Strength** | AssessmentResult.estimatedOneRepMax (most recent per exercise) | Normalized 1RM as % of bodyweight equivalent. Scale: 0–100. | HIGH |
| **Power** | RepBiomechanics.avgMcv (all reps, last 30 days) | Average of top-quartile MCV values. Normalized to VBT zone thresholds (250–1000 mm/s scale). | HIGH |
| **Stamina** | WorkoutSession count + set volume (last 30 days) | Total volume-weeks (session_count × avg_sets × avg_reps × avg_kg). Normalized against personal history percentile. | HIGH |
| **Consistency** | GamificationStats.currentStreak + longest_streak (already exists) | Streak-weighted: current_streak × 2 + longest_streak / 2, normalized 0–100. | HIGH |
| **Mastery** | RepQualityScore per rep (last 30 days) | Mean quality score weighted by session recency (recent sessions count more). Same 0–100 scale. | HIGH |

All five attribute inputs are already persisted in the database. No new data capture is required. The computation is pure KMP commonMain math — no server dependency.

### Character Class Assignment

Auto-assign from dominant attribute profile:

| Class | Dominant Attribute | Secondary | Flavor |
|-------|--------------------|-----------|--------|
| **Powerlifter** | Strength | Stamina | Heavy, methodical, maximum load |
| **Athlete** | Power | Stamina | Explosive, sport-performance |
| **Phoenix** | Mastery | Consistency | Form-perfect, never misses |
| **Ironman** | Stamina | Consistency | Volume king, never stops |
| **Monk** | Consistency | Mastery | Disciplined, streak-focused |

Assignment rule:
```
class = argmax(strength, power, stamina, consistency, mastery)
```
Ties broken by secondary attribute. Class updates after each workout sync.

**Anti-pattern to avoid:** Exposing the raw numbers that feed into attributes (e.g., "Your 1RM is 87.5kg, normalized to 62/100 Strength"). Users don't care about normalization math. Show the attribute level number and a brief "based on your estimated 1RM" tooltip.

**Confidence:** HIGH — attribute mapping is well-reasoned from available data. Normalization formulas need calibration against real user data post-v0.5.1 but the formula shapes are sound.

---

## Pre-Workout Readiness: Volume-Based Heuristic

### Why Not HRV/Sleep for v0.5.1

WHOOP, Garmin, Oura, and Fitbit all use HRV + resting heart rate + sleep as the three pillars of readiness. HRV requires either a wearable or a dedicated HRV measurement (5-min camera-based PPG). Neither is available in v0.5.1.

The academically sound alternative without biometrics: **Acute:Chronic Workload Ratio (ACWR)**.

### ACWR Implementation

Sports science consensus (Foster 1998, Gabbett 2016, TritonWear commercial implementation):

```
acute_load  = sum(session_volume) for last 7 days
chronic_load = average(weekly_volume) for last 4 weeks  // = sum(28-day volume) / 4
acwr = acute_load / chronic_load
```

Where `session_volume` = sum(sets × reps × weight_kg) per session.

Traffic light thresholds (from Garmin Load Ratio + TritonWear Readiness):
- **Green (Train Hard):** ACWR 0.8–1.3 — optimal zone, injury risk minimized
- **Yellow (Train Moderate):** ACWR 0.5–0.8 (under-trained) or 1.3–1.5 (slightly spiked)
- **Red (Rest or Light):** ACWR < 0.5 (detraining risk) or > 1.5 (injury spike zone)

**Critical caveat to surface in UI:** ACWR has known limitations (Impellizzeri 2020, Verhagen 2017 — available in PubMed). It is a heuristic, not a diagnostic. The v0.5.1 readiness card MUST:
1. Label the score as "Estimated from training volume"
2. Not claim HRV or sleep is factored in
3. Always show "Override and train anyway" with one tap
4. Log the user's choice (accepted/overrode) for future model calibration

**Bootstrap problem:** ACWR requires 28 days of data to be reliable. For new users, the 28-day window is sparse. Resolution:
- 0–7 days of history: Show "Insufficient data — train as planned"
- 8–14 days: ACWR from available data, labeled "Early estimate (limited history)"
- 15–28 days: ACWR functional, labeled "Estimated from X weeks of data"
- 28+ days: Full ACWR, labeled "Based on 4-week training history"

**Confidence:** HIGH for the algorithm. MEDIUM for the normalization/threshold values — they come from group-level sports science studies and may need per-user calibration over time.

---

## WCAG AA Accessibility in Workout HUDs

### What WCAG AA Requires (2025 Status)

WCAG 2.1 Level AA (the EAA-mandated standard as of June 2025):
- **1.4.3:** Text contrast ≥ 4.5:1 (normal) or 3:1 (large, ≥18pt)
- **1.4.11:** UI component contrast ≥ 3:1 (borders, focus indicators, graphs)
- **1.4.1:** Color cannot be the sole differentiator (velocity zones, balance bar severity — both currently violate this)

### Current Violations in Project Phoenix HUD

| Element | Current State | Violation |
|---------|--------------|-----------|
| Velocity zone indicator | Green/Blue/Red/Grey by color only | 1.4.1 — no secondary differentiator |
| Balance bar severity | Green/Orange/Red colors | 1.4.1 — no secondary differentiator |
| Rep quality score | Color gradient (green to red) | 1.4.1 — no secondary differentiator |
| Readiness card (v0.5.1) | Traffic light Green/Yellow/Red | 1.4.1 — will violate without mitigation |
| Force curve mini-graph | Color fill with no labels | 1.4.11 — graph contrast against dark background needs verification |

### Recommended Fixes (Minimum Viable WCAG AA)

**Approach: secondary differentiator, not palette replacement.**

For each color-coded element, add a text label or icon alongside color — do not rely on palette alone:

| Element | Fix | Implementation |
|---------|-----|----------------|
| Velocity zone indicator | Add zone name text label ("SPEED", "STRENGTH", etc.) alongside color badge | Add `Text(zone.label)` next to colored indicator dot |
| Balance bar | Add severity icon (L/R arrow weight + numeric %) alongside bar color | Already has numeric %, add icon for directionality |
| Rep quality score chip | Add letter grade suffix ("A", "B", "C", "D") alongside color | Thresholds: A=80+, B=60+, C=40+, D<40 |
| Readiness card | Add emoji-free text label ("TRAIN HARD", "MODERATE", "REST") alongside traffic light dot | Text label is primary, color is secondary |

**Color-blind palette option (user-selectable, not system-default):**

The deuteranopia (red-green) palette replacement matters most because:
- 8% of men have red-green color blindness (deuteranopia or deuteranomaly)
- The current velocity zone palette uses green (slow) through blue (moderate) to red (fast) — the green/red endpoints are indistinguishable to deuteranopes

Recommended deuteranopia-safe substitution:
- Replace velocity-zone green → **blue** (#1565C0)
- Replace velocity-zone red → **orange** (#E65100)
- Keep blue for moderate zone → shift to **teal** (#00695C)

This is a MaterialTheme `ColorScheme` swap — one theme object, applied at `MaterialTheme { }` wrapper. No per-composable changes.

**High-contrast mode:**
- Background: `#000000`
- Text and UI: `#FFFFFF`
- Status indicators: brightness-differential only (not hue-dependent)
- Implementation: second `ColorScheme` in theme.kt, toggled by `AppPreferences.highContrastEnabled`

**Where to add the toggle:**
Settings screen, under "Display" section. System accessibility setting (`Configuration.uiMode`) should auto-detect high-contrast if the OS signals it (Android 13+ supports `UiModeManager.setApplicationNightMode`). Fall back to in-app toggle.

**Confidence:** HIGH — WCAG 1.4.1 requirement is unambiguous. Secondary differentiator pattern (G111 in WCAG 2.0 techniques) is the canonical fix. MaterialTheme swap is the correct Compose pattern.

---

## HUD Customization: Configurable Widgets

### Competitor Models

| Platform | Model | Granularity |
|----------|-------|-------------|
| Garmin Fenix | Multiple data screens, each with N configurable fields | Per-field (up to 8 fields/screen, 3 screens) |
| Garmin Connect IQ | Custom data fields via SDK | Developer-configurable |
| Peloton | Fixed layout with optional metric toggles | Toggle on/off only |
| WahooFitness ELEMNT | Page-based with configurable tile layout | Drag-and-drop tiles |
| Apple Watch Workout | Fixed layout per activity type | Not configurable |
| GymAware Cloud | Fixed dashboard layout | Not configurable |

**Key insight:** Garmin's free-form per-field configuration is the gold standard but requires significant engineering. Peloton's toggle-on/off approach is the minimum viable. Wahoo's page-based preset model is the sweet spot.

### Recommended Approach for v0.5.1: Pages + Metric Toggles

**Three preset HUD pages (swipe between):**

1. **Essential page (default):** Rep counter, weight, velocity (current), rep quality score. 4 metrics. Works for all users including FREE tier.
2. **Biomechanics page (PHOENIX):** Velocity zone indicator, balance bar, MCV, velocity loss %. Force curve mini-graph (tap to expand). 5 metrics.
3. **Full page (ELITE):** All of the above + ghost racing overlay + readiness chip + form score (if CV enabled). Dense, for power users.

Pages swiped with horizontal gesture, indicated by bottom dot indicators. Current page persisted in DataStore.

**Per-metric toggles (in Settings → HUD Preferences):**
- Each metric has an on/off toggle.
- Toggling off a metric removes it from all pages.
- Reordering is not supported in v0.5.1.

**UX principle from Garmin and Peloton research:** Users want density control, not layout control. The most common request is "hide the metrics I don't use" — not "move this metric to the upper-right corner." Toggle-based control satisfies the stated need without drag-and-drop complexity.

**Persistence:** `DataStore<Preferences>` (already in the KMP stack via androidMain). Keys: `pref_hud_page`, `pref_hud_show_velocity`, `pref_hud_show_balance_bar`, etc. One boolean per toggleable metric.

**iOS:** Same preference keys, different DataStore implementation in iosMain. HUD page layout is already platform-specific — iOS will use the same page structure but rendered in SwiftUI.

**Confidence:** MEDIUM — the three-page model is well-reasoned from competitor analysis, but actual user preference distribution (do users want 3 pages or 5?) requires user feedback post-launch to calibrate.

---

## Feature Dependencies (v0.5.1)

```
[Ghost Racing Overlay]
    +--reads--> [RepBiomechanics table] (already in schema v16)
    +--reads--> [MetricSample table] (ELITE tier, already in schema)
    +--uses--> [BiomechanicsHistory queries] (new query: best session by exercise+weight)
    +--renders-in--> [ActiveSessionScreen] (new GhostRaceOverlay composable)

[RPG Attribute Card]
    +--reads--> [AssessmentResult table] (Strength attribute)
    +--reads--> [RepBiomechanics table] (Power attribute)
    +--reads--> [WorkoutSession table] (Stamina attribute)
    +--reads--> [GamificationStats table] (Consistency attribute — already persisted)
    +--reads--> [RepMetric quality_score] (Mastery attribute — already persisted)
    +--renders-on--> [Profile/Gamification screen]

[Pre-Workout Readiness]
    +--reads--> [WorkoutSession table] (session_volume = sets × reps × kg)
    +--computes--> [ACWR: 7-day / 28-day rolling volume ratio]
    +--renders-on--> [Pre-workout briefing composable] (triggered on BLE connect)
    +--requires--> [28 days of history for reliability]

[WCAG Accessibility]
    +--modifies--> [MaterialTheme ColorScheme] (deuteranopia + high-contrast variants)
    +--adds-to--> [all color-coded HUD composables] (secondary text/icon differentiator)
    +--persisted-in--> [DataStore AppPreferences]

[HUD Customization]
    +--wraps--> [ActiveSessionScreen HUD] (page indicator + swipe gesture)
    +--persisted-in--> [DataStore HudPreferences]
    +--does-NOT-require--> schema changes or new domain logic
```

### Dependency Notes

- **Ghost racing has no new schema dependencies.** RepBiomechanics and MetricSample are already in schema v16. The only new work is a repository query method and a composable.
- **RPG attributes have no new schema dependencies.** All five attribute inputs are already persisted. The work is pure computation + a new composable card.
- **Readiness has no new schema dependencies.** WorkoutSession already stores set/rep/weight data. New work: volume aggregation query + ACWR computation class + composable.
- **WCAG and HUD customization are UI-layer only.** Zero domain logic changes, zero schema changes. Risk is low.
- **Feature gates for all four features** (CV_FORM_CHECK already done, GHOST_RACING, RPG_SKILL_TREES, PRE_WORKOUT_BRIEFING) — mechanical FeatureGate enum additions.

---

## MVP Definition for v0.5.1

### Ship in v0.5.1

- [ ] **Ghost racing overlay (local data)** — GhostRaceOverlay composable, session matching query, rep-level delta display (PHOENIX), sample-level position overlay (ELITE). No portal dependency.
- [ ] **RPG attribute card (local computation)** — AttributeCard composable, 5-attribute formula, 5-class auto-assignment. Reads from existing persisted tables.
- [ ] **Pre-workout readiness briefing (ACWR)** — Volume-based ACWR score, Green/Yellow/Red classification, advisory-only (override always available), bootstrap states (< 7 days = "insufficient data").
- [ ] **WCAG AA secondary differentiators** — Text label/icon alongside all color-coded indicators (velocity zone, balance bar, quality chip, readiness card). Not palette-only.
- [ ] **Color-blind mode toggle** — Deuteranopia-safe MaterialTheme ColorScheme, user-selectable in Settings.
- [ ] **HUD page presets** — Three preset pages (Essential, Biomechanics, Full), horizontal swipe, page persisted in DataStore.
- [ ] **Per-metric HUD toggles** — Toggle on/off for each non-essential metric in Settings → HUD Preferences.
- [ ] **Board conditions** — UTC fix (SmartSuggestions), allowBackup exclusion rules, camera permission rationale text, iOS upgrade prompt suppression for Form Check, versionName bump, asset verification fallback.
- [ ] **CV Form Check UX** — Toggle UI, real-time warning display, form score persistence, iOS stub UI.

### Defer to v0.6.0 (Portal Dependency)

- [ ] **Ghost racing with portal session data** — Fetch best session from Supabase RPC. Full-telemetry ELITE ghost from portal.
- [ ] **RPG attributes with server-side XP** — Edge Function `compute-attributes` with server-validated formulas.
- [ ] **Full readiness with HRV/sleep** — Portal integration with wearable data (WHOOP, Oura, Garmin API).
- [ ] **Form data sync to portal** — CV form scores, violation logs.

---

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority | Tier Gate |
|---------|------------|---------------------|----------|-----------|
| WCAG secondary differentiators | HIGH (compliance + usability) | LOW | P1 | All |
| Ghost racing overlay (local) | HIGH | MEDIUM | P1 | PHOENIX/ELITE |
| CV Form Check UX (toggle + warnings) | HIGH | MEDIUM | P1 | PHOENIX |
| Color-blind mode toggle | MEDIUM | LOW | P1 | All |
| RPG attribute card | MEDIUM | LOW | P1 | PHOENIX |
| Pre-workout readiness (ACWR) | MEDIUM | MEDIUM | P1 | ELITE |
| HUD page presets (3 presets) | MEDIUM | MEDIUM | P2 | All |
| Per-metric HUD toggles | LOW | LOW | P2 | All |
| Board conditions (UTC, allowBackup, etc.) | HIGH (compliance) | LOW | P1 | — |

**Priority key:**
- P1: Required for v0.5.1 Board approval
- P2: Should have for v0.5.1, adds differentiation

---

## Competitor Feature Analysis

| Feature | WHOOP | Garmin | Peloton | Zwift | Freeletics | Phoenix (v0.5.1) |
|---------|-------|--------|---------|-------|------------|-----------------|
| Ghost racing | No | Segment racing (GPS) | Lanebreak (gamified cadence, not ghost) | HoloReplay (personal best replay) | No | Own PR cable telemetry replay — first in strength training |
| RPG gamification | No | No | No | No | Fitness score only | 5 attributes auto-derived from VBT + quality + volume data |
| Readiness score | Recovery % (HRV+sleep) | Training Readiness (HRV+sleep+load) | No | No | No | ACWR from machine volume — no wearable required |
| Color-blind support | Not documented | Not in-app (OS-level) | Not documented | Not documented | Not documented | In-app toggle with pre-tested palette |
| HUD customization | Fixed layout | Multi-screen configurable data fields | Fixed with some toggles | Custom widgets via companion app | Fixed | 3 preset pages + per-metric toggle |

---

## Sources

### Ghost Racing
- [Zwift HoloReplay Feature Overview](https://zwiftinsider.com/holoreplay/) — Architecture reference for time-indexed ghost replay
- [Zwift HoloReplay FAQ](https://support.zwift.com/holoreplay-faq-BJq2ez4yj) — User-facing behavior details
- [Ghostracer App (Google Play)](https://play.google.com/store/apps/details?id=com.bravetheskies.ghostracer) — Running ghost racing reference
- [Forrest Ghost Racer](https://connectthewatts.com/2021/05/24/forrest-ghost-racer-fitness-app-apple-watch/) — Wrist-based ghost racing UX pattern
- [AI Ghost Run App](https://apps.apple.com/us/app/ai-ghost-run/id6755091445) — Personal best audio/haptic ghost pattern
- [Zwift Ghost Race Feature Requests Forum](https://forums.zwift.com/t/ghost-race-mode/607471) — User behavior expectations

### RPG Attribute Systems
- [FitDM Character Classes](https://fitdm.io/classes) — Reference for fitness RPG class taxonomy
- [INFITNITE Fitness Fantasy RPG](https://fitnessfantasyrpg.com/) — Stat system design (strength/endurance/agility/recovery)
- [Innovative Gamification in Fitness 2025 — Top 10 Apps](https://yukaichou.com/gamification-analysis/top-10-gamification-in-fitness/) — Market overview
- [RPGFitness App](https://www.rpgfitness.fr/en) — XP-based progression reference
- [GURPS Derived Stats](https://en.wikipedia.org/wiki/Attribute_(role-playing-games)) — RPG attribute derivation patterns

### Pre-Workout Readiness
- [WHOOP Recovery Score — How It Works](https://www.whoop.com/us/en/thelocker/how-does-whoop-recovery-work-101/) — HRV+RHR+sleep model reference
- [Garmin Training Readiness](https://www.garmin.com/en-US/garmin-technology/running-science/physiological-measurements/training-readiness/) — Multi-factor local readiness computation
- [Garmin Acute:Chronic Load Ratio](https://support.garmin.com/en-US/?faq=C6iHdy0SS05RkoSVbFz066) — ACWR thresholds (Green 0.8–1.3)
- [TritonWear Readiness Score Methodology](https://support.tritonwear.com/how-readiness-score-is-calculated) — Volume-based ACWR without wearable, bootstrap states documented
- [Readiness/Recovery Scores in Consumer Wearables — 2025 Evaluation](https://www.degruyterbrill.com/document/doi/10.1515/teb-2025-0001/html) — Academic review of composite health score accuracy
- [5 Ways to Gauge Training Readiness (CTS)](https://trainright.com/training-readiness/) — Volume-only readiness approaches

### WCAG Accessibility
- [WCAG 1.4.1: Use of Color — Understanding SC](https://www.w3.org/WAI/WCAG21/Understanding/use-of-color.html) — Primary standard, color cannot be sole differentiator
- [WCAG G111: Using Color and Pattern](https://www.w3.org/TR/WCAG20-TECHS/G111.html) — Canonical technique for adding secondary differentiator
- [Material Design 3 in Compose — Color System](https://developer.android.com/develop/ui/compose/designsystems/material3) — ColorScheme theming approach for Compose
- [European Accessibility Act 2025 Impact](https://medium.com/design-bootcamp/2025-accessibility-regulations-for-designers-how-wcag-eaa-and-ada-impact-ux-ui-eb785daf4436) — EAA compliance deadline (June 2025)
- [Section508.gov — Making Color Usage Accessible](https://www.section508.gov/create/making-color-usage-accessible/) — Pattern/icon alternatives to color

### HUD Customization
- [Garmin Forerunner — Customizing Activities and Apps](https://www8.garmin.com/manuals/webhelp/GUID-F41EAFB3-6CC9-42DE-9C6C-9E358DBB0671/EN-US/GUID-25FA2988-33F2-4FC9-92FA-E457CBDB9E72.html) — Multi-screen configurable data field model
- [How to Customize Garmin Data Screens (Wareable)](https://www.wareable.com/garmin/how-to-edit-customize-garmin-watch-data-fields-screens) — User behavior expectations for data field customization
- [Best UX/UI Practices for Fitness Apps 2025](https://dataconomy.com/2025/11/11/best-ux-ui-practices-for-fitness-apps-retaining-and-re-engaging-users/) — Density control vs. layout control user preference
- [Fitness App UI Design Principles (Eastern Peak)](https://easternpeak.com/blog/fitness-app-design-best-practices/) — Screen real estate management in workout displays

---

*Feature research for: v0.5.1 Board Polish & Premium UI*
*Researched: 2026-02-27*
