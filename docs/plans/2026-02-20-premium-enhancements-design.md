# Premium Enhancement Design: Next-Generation Features

**Date:** 2026-02-20
**Branch:** `new_ideas`
**Status:** Approved
**Strategy:** Approach B — Maximum Wow (lead with visual impact, back-fill intelligence)

## Overview

Six major premium enhancement areas organized into three parallel development tracks. All features are software-only (no firmware changes required) and leverage existing BLE telemetry data. Every feature ties back to the Phoenix Portal web app as the premium hub.

**Source Research:** "Strategic Evaluation and Innovation Roadmap for the Project Phoenix Connected Fitness Ecosystem"

### What Already Exists (skip)

- VBT metrics (MCV, peak velocity, velocity zones, velocity loss %) — Phoenix tier
- Force curve analysis (101-point normalized curves, sticking point) — Phoenix tier
- Bilateral asymmetry detection (per-cable load comparison) — Elite tier
- Rep quality scoring (composite 0-100) — Phoenix tier
- Session replay (50Hz telemetry playback) — Elite tier
- Recovery score (ACWR-based) — Portal
- Wearable activity imports (Strava, Fitbit, Garmin, Hevy) — Elite tier
- Community challenges and shared routines/cycles — Portal

### What This Design Adds

| Feature | Tier | Track | Location |
|---------|------|-------|----------|
| Human Digital Twin (3D) | ELITE | Visual | Portal |
| RPG Skill Trees | PHOENIX | Visual | Portal + Mobile |
| Ghost Racing | PHOENIX/ELITE | Visual | Mobile + Portal |
| Force-Velocity Profile Dashboard | PHOENIX | Intelligence | Portal |
| Mechanical Impulse Quantification | PHOENIX | Intelligence | Portal |
| Predictive Fatigue Model + AI Auto-Regulation | ELITE | Intelligence | Portal + Mobile |
| Computer Vision Posture Correction | PHOENIX/ELITE | Safety | Mobile + Portal |

---

## Track 1: Visual Impact Features

### 1A. Human Digital Twin (3D) — ELITE

**Location:** Portal — new route `/twin`

An interactive Three.js 3D musculoskeletal model of the user. Rotation, zoom, click-to-inspect muscle groups.

#### Visual States per Muscle Group

| State | Color | Trigger |
|-------|-------|---------|
| Fatigued | Deep red, pulsing glow | Trained within last 6 hours |
| Recovering | Orange to Yellow gradient | 6-48 hours post-workout, scaled by volume |
| Ready | Green | Recovery model indicates readiness |
| Overtrained | Purple warning icon | 3+ sessions targeting same group in 7 days with declining velocity |
| Asymmetry Alert | Flashing side indicator | >15% persistent force imbalance |

#### Data Sources (all existing)

- `exercises.muscle_group` — which muscles were trained
- `sets.weight_kg` x `sets.actual_reps` — volume per muscle group
- `rep_summaries.asymmetry_pct` + `left_force_avg`/`right_force_avg` — asymmetry data
- `workout_sessions.started_at` — time since training
- Enhanced ACWR model (existing `recovery.ts`) extended per-muscle-group

#### Tech Stack

- **React Three Fiber (R3F)** — React renderer for Three.js
- **GLTF model** with named mesh groups per muscle (e.g., `left_quadriceps`, `right_bicep`)
- **Custom shader materials** for fatigue-state coloring (uniform colors, no texture swapping)
- **@react-three/drei** for OrbitControls, environment lighting, HTML overlays
- Touch-responsive for mobile browsers

#### New Database

- `muscle_recovery_state` — computed per-user, per-muscle recovery timeline (materialized from workout history)

#### Key Design Decisions

- No new mobile data required — everything needed already syncs to Supabase
- Recovery estimation is computed server-side (Edge Function or database function)
- GLTF model is a static asset served from CDN, not generated per-user
- Muscle group mapping uses existing `MuscleGroup` enum (12 groups)

---

### 1B. RPG Skill Trees — PHOENIX

**Location:** Portal (full interactive tree) + Mobile (stats summary card)

User's physical profile represented as RPG character attributes.

#### Core Attributes (5)

| Attribute | XP Source | Existing Data Used |
|-----------|-----------|-------------------|
| Strength | High-load, low-velocity reps (VBT zone <0.5 m/s) | `rep_summaries.mean_velocity_mps` + `sets.weight_kg` |
| Power | High-velocity, explosive reps (VBT zone >0.75 m/s) | `rep_summaries.mean_velocity_mps` + `rep_summaries.power_watts` |
| Stamina | High TUT, high-rep endurance, total session volume | `rep_summaries.tut_ms` + `sets.actual_reps` + `workout_sessions.total_volume` |
| Consistency | Streak length, workout frequency, weekly adherence | Existing streak + session frequency calculations |
| Mastery | Rep quality scores, ROM consistency, eccentric control | Existing rep quality score (0-100) |

#### Leveling System

- Each attribute: Level 1-99, exponential XP curve
- **Character Class** auto-assigned from attribute ratios:
  - *Powerlifter* — Strength dominant
  - *Athlete* — Power dominant
  - *Ironman* — Stamina dominant
  - *Monk* — Mastery dominant
  - *Phoenix* — balanced above threshold
- XP calculated server-side on workout sync

#### Portal Visualization

- Radial spider/radar chart showing all 5 attributes
- Animated level-up celebrations (Framer Motion)
- Class badge with visual identity
- Leaderboard by class + overall level (opt-in via existing `leaderboard_participation`)

#### Mobile (Compose)

- Compact attribute card on profile/gamification screen
- Level numbers + class badge
- "View full skill tree on Phoenix Portal" deep link

#### New Database

- `user_attributes` — per-user attribute levels + XP (strength_xp, strength_level, power_xp, power_level, etc.)
- `attribute_history` — XP gain log for trend tracking (user_id, attribute, xp_gained, source_session_id, timestamp)

---

### 1C. Ghost Racing — PHOENIX (basic) / ELITE (advanced)

**Location:** Mobile (during active workout) + Portal (post-workout replay comparison)

Race against your personal best's exact cable telemetry in real-time.

#### Mobile — Active Workout Overlay

1. Before starting a set, app queries Supabase for best matching session (same exercise + same weight +/-5%)
2. During the set, two vertical progress bars animate side-by-side:
   - **Left:** Current real-time cable position (live BLE)
   - **Right:** Ghost replay (historical telemetry, time-aligned)
3. Per-rep result: "Ahead" / "Behind" based on concentric velocity comparison
4. End-of-set verdict: total time comparison + velocity deltas
5. Optional haptic/audio pacing cues

#### Portal — Replay Comparison

- Two-session overlay on existing Session Replay page
- Dual force curves rendered on same visx chart
- Timeline scrubber syncs both traces
- Per-rep side-by-side stats table (velocity, force, TUT)

#### Tier Split

| Tier | Ghost Data Source | Experience |
|------|------------------|------------|
| PHOENIX | `rep_summaries` (per-rep averages) | "Beat your best rep velocity" — simplified comparison |
| ELITE | `rep_telemetry` (50Hz raw data) | Full real-time position overlay + post-workout dual-trace replay |

#### New Infrastructure

- Supabase function: `best_matching_session(exercise_name, weight_kg, tolerance_pct)` — returns session ID of PR matching criteria
- Mobile: `GhostRaceOverlay` composable with synchronized animation
- Portal: `DualSessionReplay` component extending existing replay page

---

## Track 2: Intelligence Features

### 2A. Force-Velocity Profile Dashboard — PHOENIX

**Location:** Portal — new tab on `/biomechanics` page

Automatically generates individualized Force-Velocity curves from longitudinal workout data.

#### How It Works

1. **Passive data collection:** Each set produces `mean_velocity_mps` and `weight_kg` data points (already in `rep_summaries` and `sets`)

2. **Profile generation (server-side):** Linear regression on (load, velocity) pairs per exercise:
   - `V = V0 - (V0/F0) x F` (linear F-v relationship)
   - Extrapolated intercepts:
     - **V0** — theoretical max velocity at zero load
     - **F0** — theoretical max force at zero velocity (estimated 1RM in Newtons)
     - **Pmax** = (F0 x V0) / 4 (theoretical peak power output)
   - **R-squared** coefficient for profile quality (need 3+ distinct loads)

3. **CNS Fatigue Detection:** If F-v slope shifts significantly downward over 14-day window (same loads produce lower velocities), flag "CNS Fatigue Warning"

#### Portal Components

- `ForceVelocityChart` — visx scatter plot + regression line + confidence band
- `FVProfileCard` — summary: V0, F0, Pmax, estimated daily 1RM, R-squared
- `CNSFatigueAlert` — warning banner on profile degradation
- Trend overlay: compare current profile vs. 30/60/90 days ago

#### New Database

- `fv_profiles` — per-user, per-exercise: v0, f0, pmax, r2, data_points (JSON), computed_at
- `fv_profile_history` — time-series snapshots for trend comparison

---

### 2B. Mechanical Impulse Quantification — PHOENIX

**Location:** Portal — integrated into session detail + analytics + biomechanics pages

Volume (sets x reps x weight) is crude. Mechanical Impulse captures true mechanical work by integrating force over time.

#### Calculation

```
Impulse = SUM(Force_i x Delta_t_i) for all active time points

- Force_i = load in Newtons at each sample
- Delta_t_i = time interval between samples (20ms at 50Hz)
- Separated into: Concentric Impulse, Eccentric Impulse, Total Impulse
```

#### Where It Appears

- **Session Detail:** Impulse per exercise, per set, with concentric/eccentric split
- **Analytics Trend:** Weekly/monthly total impulse trend line (supplements volume trend)
- **Biomechanics:** Impulse-vs-volume comparison showing "training quality"
- **RPG Integration:** Stamina XP weighted by impulse, not just volume

#### Tier Implementation

| Tier | Data Source | Precision |
|------|-----------|-----------|
| PHOENIX | `rep_summaries.mean_force_n x tut_ms` | Approximate but valuable |
| ELITE | Raw `rep_telemetry` (50Hz) | Precise integration |

#### Database Changes

- Add `impulse_ns` column to `rep_summaries`
- Add `total_impulse_ns` to `exercise_progress` aggregation

---

### 2C. Predictive Fatigue Model + AI Auto-Regulation — ELITE

**Location:** Portal (model + dashboard) + Mobile (pre-workout briefing)

Fuses machine telemetry with wearable biometrics to predict readiness and auto-adjust workouts.

#### Architecture

```
Wearable APIs (Garmin/Fitbit/Oura)
    |
    | HRV, Sleep, RHR
    v
Workout Telemetry -----> Predictive Fatigue Model (FFM) -----> Readiness Score 0-100
(Impulse, VBT, Volume)   Bannister variant                     per muscle group + CNS
                                                                      |
                                    +--------------------+------------+
                                    v                    v            v
                              Digital Twin        AI Coach      Mobile Pre-WO
                              Coloring            Dashboard     Briefing
```

#### Step 1: Enhanced Wearable Ingestion

Extend existing Garmin/Fitbit OAuth integrations to pull:
- **HRV** (rMSSD) — daily morning reading
- **Sleep stages** — deep/REM/light duration + sleep score
- **Resting Heart Rate** — daily trend

New integration: **Oura Ring** (OAuth2, REST API)

New table: `biometric_readings`
```
user_id, date, provider, hrv_rmssd, resting_hr, sleep_score,
deep_sleep_minutes, rem_sleep_minutes, total_sleep_minutes
```

#### Step 2: Bannister Fitness-Fatigue Model (FFM)

```
Performance(t) = p0 + k1 * Fitness(t) - k2 * Fatigue(t)

Fitness(t) = SUM w(i) * e^(-(t-i)/tau1)    tau1 ~ 42 days (long-term)
Fatigue(t) = SUM w(i) * e^(-(t-i)/tau2)    tau2 ~ 7 days (short-term)
w(i) = mechanical impulse of session i
```

- Initial constants from sports science literature; personalized over time via observed velocity trends
- **Biometric correction:** HRV suppressed >1 SD below 14-day baseline increases fatigue decay rate. Low sleep score same adjustment.
- Output: Readiness Score (0-100) per muscle group + overall CNS readiness

#### Step 3: AI Coach — Pre-Workout Briefing

When user opens mobile app and connects to machine:

1. App fetches readiness score from portal API
2. If scheduled workout exists (from training cycle):
   - **Green (>70):** "Fully recovered. Execute as planned."
   - **Yellow (50-70):** "Recovery moderate. Reducing loads 10-15%. Focus on velocity."
   - **Red (<50):** "Significant fatigue. Recommending light recovery session: 50% loads, tempo work."
3. Adjusted weights shown as "AI Suggested Weight" alongside prescribed weight
4. User can accept or override (always optional)

#### Portal Dashboard

- Readiness timeline (daily scores, 30/90 day view)
- Muscle group recovery heatmap (feeds Digital Twin coloring)
- Training load vs. readiness correlation chart
- "Overreaching Risk" alert when cumulative impulse exceeds threshold

#### New Infrastructure

- `biometric_readings` table
- `readiness_scores` table (daily per-user, per-muscle-group)
- `readiness_adjustments` table (AI suggestion log + user acceptance tracking)
- Edge Function: `compute-readiness` (runs nightly or on-sync)
- Edge Function: `oura-oauth` + `oura-sync`
- Mobile RPC: `pre_workout_briefing` — returns readiness + adjusted weights

---

## Track 3: Computer Vision Posture Correction

### 3A. Real-Time Pose Estimation — PHOENIX (basic) / ELITE (full analytics)

**Location:** Mobile (primary) + Portal (post-workout analytics)

On-device ML pose estimation using MediaPipe to track body form during exercises.

**IMPORTANT SAFETY DECISION:** CV provides **warnings only** (audio, visual, haptic). No automatic spotter/deload trigger from CV. The existing hardware-side spotter (DELOAD_OCCURRED flag) continues to work independently.

#### Mobile Architecture

**ML Framework:** Google MediaPipe Pose Landmarker
- 33 3D landmarks, ~30fps on mid-range phones
- Android: MediaPipe Tasks SDK (Kotlin, GPU delegate)
- iOS: MediaPipe Tasks SDK (Swift)
- Purely on-device, no cloud ML

#### User Flow

1. User props phone on tripod/against wall, camera facing them (full body visible)
2. Taps "Enable Form Check" toggle on Active Workout Screen
3. Camera preview appears as small PiP overlay in corner
4. Skeleton overlay drawn on camera feed
5. Real-time joint angle calculations on each frame

#### Joint Angles Calculated

| Joint | Landmarks | Key Exercises |
|-------|-----------|---------------|
| Knee flexion | Hip, Knee, Ankle | Squat, Lunge, Leg Press |
| Hip hinge | Shoulder, Hip, Knee | Deadlift, RDL, Good Morning |
| Lumbar flexion | Mid-spine, Lower-spine, Hip | All hinge/pull movements |
| Shoulder elevation | Elbow, Shoulder, Hip | Overhead Press, Lateral Raise |
| Elbow flexion | Shoulder, Elbow, Wrist | Curl, Tricep Extension |
| Knee valgus | Hip, Knee, Ankle (frontal) | Squat, Lunge |

#### Exercise-Specific Rules (example: Squat)

```
IF lumbar_flexion > 30deg AND cable_load > 75% estimated_1rm:
    WARNING: "Round back detected under heavy load"
    Action: Audio warning + screen flash (NO auto-deload)

IF knee_valgus > 15deg:
    WARNING: "Knees caving inward"
    Action: Audio cue "Push knees out"

IF hip_hinge_depth < 90deg at rep bottom:
    INFO: "Partial ROM detected"
    Action: Subtle visual indicator
```

#### Tier Split

| Tier | Features |
|------|----------|
| PHOENIX | Real-time skeleton overlay + basic form warnings (audio/visual). Post-workout form score. |
| ELITE | Full postural analytics synced to portal. Joint angle trends, fatigue-form correlation charts. |

#### Portal — Postural Analytics Dashboard

New tab on `/biomechanics` — "Form Analysis"

**Synced data (no raw video, no raw landmarks — privacy + bandwidth):**
- **Form Score** per exercise (0-100, composite of joint angle compliance)
- **Violation Log** — timestamped deviations with severity
- **Joint Angle Summary** — min/max/avg angles per joint per exercise
- **Rep-by-Rep Posture Heatmap** — which reps had worst form (fatigue-induced breakdown)

**Visualizations:**
- Form Score Trend — per-exercise quality over time (Recharts line)
- Joint Angle Radar — spider chart of compliance across tracked joints
- Fatigue-Form Correlation — scatter of rep number vs. form score
- Postural Heatmap — body silhouette with color-coded joint compliance

#### KMP Code Split

- `commonMain`: `FormRuleEngine`, `JointAngleCalculator`, `ExerciseFormRules` (pure math)
- `androidMain`: CameraX + MediaPipe Android SDK
- `iosMain`: AVCapture + MediaPipe iOS SDK

#### New Database

- `form_assessments` — per-session, per-exercise: form_score, violation_count, critical_violations, joint_angles_summary (JSON)
- `form_violations` — individual violations: exercise_id, rep_number, joint, angle_degrees, threshold_degrees, severity, timestamp_ms

---

## New Database Tables Summary (All in Supabase)

| Table | Track | Purpose |
|-------|-------|---------|
| `muscle_recovery_state` | Visual | Per-user, per-muscle recovery timeline |
| `user_attributes` | Visual | RPG attribute levels + XP |
| `attribute_history` | Visual | XP gain log per workout |
| `fv_profiles` | Intelligence | Per-exercise F-v profile parameters |
| `fv_profile_history` | Intelligence | F-v profile snapshots over time |
| `biometric_readings` | Intelligence | Daily HRV, sleep, RHR from wearables |
| `readiness_scores` | Intelligence | Daily computed readiness per muscle group |
| `readiness_adjustments` | Intelligence | AI suggestion log + acceptance tracking |
| `form_assessments` | Safety | Per-exercise form scores per session |
| `form_violations` | Safety | Individual form violation records |

## New Supabase Edge Functions

| Function | Track | Purpose |
|----------|-------|---------|
| `compute-readiness` | Intelligence | Nightly FFM computation |
| `compute-fv-profile` | Intelligence | F-v regression on workout sync |
| `compute-attributes` | Visual | RPG XP calculation on workout sync |
| `compute-recovery-state` | Visual | Per-muscle recovery estimation |
| `oura-oauth` | Intelligence | Oura Ring OAuth callback |
| `oura-sync` | Intelligence | Oura Ring data fetch |
| `best-matching-session` | Visual | Ghost racing session matcher |

## New Mobile Components (KMP)

| Component | Track | Location |
|-----------|-------|----------|
| `GhostRaceOverlay` | Visual | androidMain + iosMain (Compose/SwiftUI) |
| `AttributeCard` | Visual | commonMain model + platform UI |
| `CameraPreview` | Safety | androidMain (CameraX) + iosMain (AVCapture) |
| `PoseAnalyzer` | Safety | androidMain + iosMain (MediaPipe SDK) |
| `FormRuleEngine` | Safety | commonMain (pure Kotlin) |
| `JointAngleCalculator` | Safety | commonMain (pure math) |
| `PreWorkoutBriefing` | Intelligence | commonMain model + platform UI |

## Updated Tier Matrix

| Feature | FREE | PHOENIX | ELITE |
|---------|------|---------|-------|
| Basic workout tracking | Yes | Yes | Yes |
| VBT metrics, force curves | No | Yes | Yes |
| Rep quality scoring | No | Yes | Yes |
| **F-v Profile Dashboard** | No | **Yes** | Yes |
| **Mechanical Impulse** | No | **Yes (approx)** | **Yes (precise)** |
| **RPG Skill Trees** | No | **Yes** | Yes |
| **Ghost Racing (summary)** | No | **Yes** | Yes |
| **CV Form Check (basic)** | No | **Yes** | Yes |
| Asymmetry analysis | No | No | Yes |
| Session replay (50Hz) | No | No | Yes |
| **Ghost Racing (50Hz)** | No | No | **Yes** |
| **CV Postural Analytics** | No | No | **Yes** |
| **Human Digital Twin 3D** | No | No | **Yes** |
| **Predictive Fatigue Model** | No | No | **Yes** |
| **AI Auto-Regulation** | No | No | **Yes** |
| Wearable biometric fusion | No | No | Yes |

## Build Order (Approach B: Maximum Wow)

### Phase 1: Track 1 — Visual Impact (parallel)
1. **RPG Skill Trees** — lowest complexity in Track 1, immediate engagement value
2. **Ghost Racing** — uses existing data, high engagement during workouts
3. **Human Digital Twin 3D** — most complex, highest wow-factor

### Phase 2: Track 2 — Intelligence (sequential, data-dependency)
4. **Force-Velocity Profile Dashboard** — foundational analytics
5. **Mechanical Impulse Quantification** — feeds into fatigue model
6. **Enhanced Wearable Ingestion** (HRV/Sleep) — data pipeline for FFM
7. **Predictive Fatigue Model + AI Auto-Regulation** — depends on 4, 5, 6

### Phase 3: Track 3 — Safety (parallel to Track 2)
8. **CV Pose Estimation** — mobile MediaPipe integration
9. **CV Form Rules Engine** — exercise-specific angle rules
10. **CV Portal Analytics** — form trends + postural heatmaps

---

*This design document was collaboratively developed through structured brainstorming with trade-off analysis across three strategic approaches.*
