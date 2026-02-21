# Feature Research: v0.5.0 Premium Mobile Features

**Domain:** Premium mobile fitness features -- CV form checking, biomechanics persistence, ghost racing, RPG gamification, readiness briefing
**Researched:** 2026-02-20
**Confidence:** HIGH (five mature domains with strong competitor precedent and existing codebase infrastructure)

---

## Feature Landscape

### Table Stakes (Users Expect These)

Features that users of premium VBT/connected fitness apps expect once they're paying. Missing these means the premium tier feels incomplete relative to competitors like GymAware Cloud, Metric VBT, RepOne, and Vitruve.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| **Biomechanics data persistence** | Every VBT platform (GymAware, RepOne, Output Sports, Metric VBT) stores per-rep velocity/force data persistently. Currently Phoenix computes VBT metrics, force curves, and asymmetry in-memory but discards them at workout end. Users paying for PHOENIX/ELITE expect historical data. | MEDIUM | RepMetric table already exists with raw time-series per rep. Need new columns/table for computed biomechanics (MCV, velocity zone, force curve 101-point array, asymmetry %, sticking point). Schema version 16. |
| **Per-rep biomechanics in session history** | Post-workout review of VBT data per rep is standard in all VBT apps. Phoenix already has RepReplayCard with force sparklines, but no persisted MCV, velocity zone, or asymmetry per rep. | LOW | Straight extension of existing RepMetricRepository. Query RepMetric + new BiomechanicsRepMetric join. RepReplayCard already renders force sparklines from persisted FloatArrays. |
| **Set-level biomechanics summary persistence** | BiomechanicsSetSummary (avg MCV, total velocity loss, zone distribution, avg asymmetry, strength profile, averaged force curve) is computed live but lost. Session review needs this. | LOW | Single new table or columns on WorkoutSession. Data already computed in BiomechanicsEngine.getSetSummary(). |
| **Feature gate for new premium features** | Existing FeatureGate enum has 12 features. New features (CV_FORM_CHECK, GHOST_RACING, RPG_SKILL_TREES, PRE_WORKOUT_BRIEFING) need gates. Users expect clear tier boundaries. | LOW | Mechanical: add enum values and set membership. Pattern is well-established (see FeatureGate.kt). |

### Differentiators (Competitive Advantage)

Features that set Project Phoenix apart from other connected fitness apps. These are where the app competes -- no other Vitruvian community app (or most connected fitness platforms) offers these.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| **CV pose estimation with real-time form warnings** | On-device MediaPipe pose detection during active workouts with exercise-specific joint angle rule checking. Only FormCheck AI, CueForm, and Gymscore offer this, and none integrate with cable machine telemetry. The combination of BLE force data + CV form data is unique in the market. | HIGH | MediaPipe Pose Landmarker SDK + CameraX on Android. 33 3D landmarks at ~30fps. Platform-specific (androidMain for CameraX+MediaPipe, iosMain stub). JointAngleCalculator and FormRuleEngine in commonMain. Key risk: phone positioning (needs tripod/wall mount). |
| **CV form score (composite 0-100)** | Per-exercise form score computed from joint angle compliance, weighted by violation severity. Parallels the existing rep quality score (0-100) but for body form instead of cable metrics. Enables "form fatigue" tracking (form degrades as reps increase). | MEDIUM | Pure math in commonMain. Depends on CV pose data pipeline. Persisted locally in new FormAssessment table. Exercise-specific rules needed for each supported exercise. |
| **Ghost racing overlay** | Race against your personal best's cable telemetry in real-time during sets. Inspired by Zwift HoloReplay and Apple Watch Race Route. Two vertical progress bars: live position vs. historical position. Per-rep "Ahead/Behind" verdict. No competitor in the connected cable machine space offers this. | MEDIUM | PHOENIX tier uses rep_summaries (per-rep averages) for simplified velocity comparison. ELITE tier uses full 50Hz telemetry for real-time position overlay. Needs best-matching-session query (exercise + weight +/- 5%). GhostRaceOverlay Composable with synchronized animation. Stub data until portal sync ships (v0.5.5). |
| **RPG attribute card** | Physical profile as RPG character attributes (Strength, Power, Stamina, Consistency, Mastery) with auto-assigned character class. Gamification that reflects actual training data, not arbitrary badges. Inspired by Infitnite Fitness Fantasy RPG and Solo Leveling app patterns. | LOW | Mobile shows compact card with 5 attribute levels + class badge + "View on Portal" link. Full skill tree on Portal. RPG XP calculated server-side from existing VBT/quality/volume data. Mobile component is a read-only display composable. Stub data until portal ships. |
| **Pre-workout readiness briefing** | Before first set, show readiness score (Green/Yellow/Red) based on Bannister FFM + HRV/sleep biometrics. AI-suggested weight adjustments. Inspired by Fitbit Daily Readiness Score, Oura Readiness, and Elite HRV. User always has override. | MEDIUM | Readiness model runs server-side (portal Edge Function). Mobile calls RPC to get score + suggestions. PreWorkoutBriefing composable shown after machine connect. Stub data until portal integration ships (v0.5.5). The Bannister FFM has known statistical limitations (ill-conditioned, overfitting risk) but is the accepted starting point in sports science. |
| **CV exercise-specific form rules** | Joint angle thresholds tuned per exercise: squat depth/knee valgus, deadlift hip hinge/lumbar flexion, press shoulder elevation, curl elbow stability. Not generic pose -- exercise-aware rules that understand what "good form" means for each movement. | MEDIUM | ExerciseFormRules in commonMain as data class map. Initial rules for 5-6 core exercises. Extensible: users or community can suggest new rules later. Thresholds from exercise science literature (e.g., knee valgus >15deg, lumbar flexion >30deg under load). |

### Anti-Features (Commonly Requested, Often Problematic)

Features that seem good on the surface but create problems in this context.

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| **CV auto-deload/auto-spotter from form detection** | "If the camera sees bad form, automatically reduce the weight." Safety-first thinking. | CV pose estimation at 30fps on a phone camera has inherent noise, occlusion, and lighting variability. A false positive auto-deload mid-rep is dangerous -- sudden weight changes while under load cause injury. The hardware spotter (DELOAD_OCCURRED flag) already works independently and reliably. | Warnings only: audio cue, screen flash, haptic. Never auto-deload from CV. Design doc explicitly states this safety decision. |
| **Full 3D skeleton rendering on mobile** | "Show me a real-time 3D model of my body on the phone screen." Visually impressive. | Rendering 33-point 3D skeleton + camera feed + workout HUD + ghost overlay simultaneously will tank performance on mid-range phones. Battery drain. Screen real estate conflict with existing VBT HUD. | Small PiP camera preview in corner with 2D skeleton overlay. Joint angle warnings as text/audio, not visual skeleton annotations. Full 3D analysis on portal post-workout. |
| **Real-time video recording + upload for CV analytics** | "Record my workout and upload it for analysis on the portal." | Video files are huge (300MB+ for a 30-minute workout at 30fps). Upload on mobile data is expensive. Privacy concerns with storing user workout video. Storage costs on Supabase. | Sync only computed data: form scores, violation logs, joint angle summaries. No raw video, no raw landmarks. Privacy-preserving and bandwidth-efficient. |
| **RPG PvP battles using real workout data** | "Let me battle other users based on who has better stats." | Comparing users with different body weights, training ages, and goals is meaningless and discouraging. Competitive leaderboards in fitness apps have well-documented negative effects on beginner retention. | Opt-in leaderboards by class + overall level (existing `leaderboard_participation` flag). Focus on self-improvement narrative, not competition. |
| **Ghost racing against other users' data** | "Let me race against my friend's best set." | Requires syncing another user's raw telemetry data to your device. Privacy, consent, and data transfer complexity. Different body mechanics make cable position comparison between users meaningless. | Ghost racing against your own personal best only. Community competition via portal leaderboards where aggregated stats (not raw telemetry) are compared. |
| **Readiness score blocking workouts** | "If readiness is Red, prevent the user from starting a workout." | Users know their bodies. Recovery models have error margins. Preventing a paying user from using their equipment because of a prediction algorithm will drive uninstalls. | Advisory only. Show readiness score, suggest weight reductions, but always allow override. Log acceptance/override for model improvement. |
| **Full biomechanics persistence for FREE tier** | "Capture everything for everyone, just gate the display." | Per-rep biomechanics data (101-point force curves, velocity profiles per rep) generates significant SQLite storage. Free users doing 5 sets/day generate ~50KB/session of biomechanics data. Over a year that's 18MB per user just for biomechanics. | Follow existing GATE-04 pattern: capture raw MetricSample and RepMetric data for all tiers (already happening). Gate the computed biomechanics persistence and display. Free users get basic rep counts and weights. |

---

## Feature Dependencies

```
[Biomechanics Persistence]
    |-- foundation for all analytics features
    |-- required BEFORE ghost racing (needs historical biomechanics to compare against)
    |-- required BEFORE RPG attributes (XP formulas reference MCV, power, quality scores)
    |-- required BEFORE readiness briefing (fatigue model needs historical velocity data)
    +-- NOT required for CV form check (CV is independent data stream)

[CV Pose Estimation (MediaPipe)]
    +--requires--> [CV Form Rules Engine]
                       +--requires--> [CV Form Score Calculator]
                                          +--requires--> [CV Form Persistence]

[Ghost Racing Overlay]
    +--requires--> [Biomechanics Persistence] (needs persisted per-rep velocity/position)
    +--requires--> [Best Matching Session Query] (exercise + weight matching)
    +--stub-until--> [Portal Sync v0.5.5] (full telemetry from portal)

[RPG Attribute Card]
    +--reads-from--> [Biomechanics Persistence] (VBT metrics for Strength/Power XP)
    +--reads-from--> [Rep Quality Score] (already exists, for Mastery XP)
    +--reads-from--> [GamificationStats] (already exists, for Consistency XP)
    +--stub-until--> [Portal Edge Function] (server-side XP computation)

[Pre-Workout Briefing]
    +--requires--> [Biomechanics Persistence] (historical velocity for fatigue model input)
    +--requires--> [Portal Readiness API] (server-side FFM computation)
    +--stub-until--> [Wearable Integration v0.6+] (HRV/sleep biometric correction)

[Feature Gates for New Features]
    +--enhances--> all new features (tier gating)
    +--depends-on--> [Existing FeatureGate.kt] (add 4 new enum values)
```

### Dependency Notes

- **Biomechanics Persistence is the foundation**: It unblocks ghost racing, RPG XP, and readiness model input. Build first.
- **CV is independent**: CV pose estimation has zero dependency on biomechanics persistence. Can be built in parallel.
- **Three features need portal stubs**: Ghost racing, RPG attribute card, and pre-workout briefing all need data that will eventually come from the portal. For v0.5.0, they use local stub data or local-only computation.
- **Feature gates are trivial**: Adding 4 enum values to FeatureGate.kt is a 10-minute task. Do it alongside each feature, not as a separate phase.

---

## MVP Definition

### Launch With (v0.5.0)

Minimum viable for this milestone -- what justifies shipping v0.5.0.

- [x] **Biomechanics persistence** -- Per-rep VBT metrics (MCV, zone, velocity loss), force curve (101-point array), asymmetry (% and dominant side) persisted to SQLite. Set-level summary persisted to WorkoutSession or new table. This is the foundation everything else builds on.
- [x] **CV pose estimation + basic form warnings** -- MediaPipe integration on Android. Camera PiP overlay during workout. 3-4 joint angle calculations. 2-3 exercises with form rules (squat, deadlift/RDL, overhead press). Audio + visual warnings. No auto-deload.
- [x] **CV form score** -- Composite 0-100 per exercise, persisted locally. Displayed in set summary.
- [x] **Ghost racing overlay (stub mode)** -- GhostRaceOverlay composable rendering two vertical bars. Uses locally persisted biomechanics data from previous sessions on the same device. "Ahead/Behind" per-rep verdict. PHOENIX tier simplified (velocity comparison), ELITE tier full position overlay.
- [x] **RPG attribute card (stub mode)** -- AttributeCard composable showing 5 levels + class badge. Computed locally from existing GamificationStats + biomechanics data. No portal dependency.
- [x] **Pre-workout briefing (stub mode)** -- PreWorkoutBriefing composable showing readiness estimate. Uses local-only simplified model (volume-based, no HRV/sleep). Shows "Connect to Portal for full readiness analysis" upsell.
- [x] **Feature gates** -- CV_FORM_CHECK (PHOENIX), GHOST_RACING (PHOENIX basic / ELITE full), RPG_SKILL_TREES (PHOENIX), PRE_WORKOUT_BRIEFING (ELITE).

### Add After Portal Integration (v0.5.5+)

Features to add once Supabase auth and portal sync are live.

- [ ] **Ghost racing with portal data** -- Fetch best-matching-session from Supabase RPC. Full 50Hz telemetry ghost for ELITE.
- [ ] **RPG attribute card with portal XP** -- Server-side XP computation via `compute-attributes` Edge Function. Real-time level updates on workout sync.
- [ ] **Full readiness briefing** -- Portal-computed Bannister FFM with HRV/sleep biometric correction. AI-suggested weight adjustments.
- [ ] **CV form data sync to portal** -- Form scores, violation logs, joint angle summaries synced for ELITE users. Portal "Form Analysis" tab.

### Future Consideration (v0.7.0+)

Features to defer until the portal premium features ship.

- [ ] **iOS CV form checking** -- MediaPipe iOS SDK + AVCapture integration. Deferred because iOS user base is smaller and MediaPipe iOS integration is less mature.
- [ ] **Additional exercise form rules** -- Beyond the initial 5-6 exercises. Community-contributed rules.
- [ ] **Form-fatigue correlation analytics** -- Scatter plot of rep number vs. form score showing fatigue-induced breakdown. Portal-only visualization.
- [ ] **Ghost racing against community PRs** -- Leaderboard integration for ghost racing. Requires consent framework and privacy controls.

---

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority | Tier |
|---------|------------|---------------------|----------|------|
| Biomechanics persistence | HIGH | MEDIUM | P1 | PHOENIX |
| CV pose estimation + form warnings | HIGH | HIGH | P1 | PHOENIX |
| CV form score (0-100) | HIGH | MEDIUM | P1 | PHOENIX |
| CV exercise-specific rules (squat, deadlift, press) | HIGH | MEDIUM | P1 | PHOENIX |
| Feature gates for new features | HIGH | LOW | P1 | All |
| Ghost racing overlay (stub) | MEDIUM | MEDIUM | P2 | PHOENIX/ELITE |
| RPG attribute card (stub) | MEDIUM | LOW | P2 | PHOENIX |
| Pre-workout briefing (stub) | MEDIUM | MEDIUM | P2 | ELITE |
| CV form persistence to local DB | MEDIUM | LOW | P2 | PHOENIX |
| Ghost racing with portal data | MEDIUM | MEDIUM | P3 | PHOENIX/ELITE |
| RPG with portal XP | MEDIUM | LOW | P3 | PHOENIX |
| Full readiness with HRV/sleep | MEDIUM | HIGH | P3 | ELITE |

**Priority key:**
- P1: Must have for v0.5.0 launch
- P2: Should have for v0.5.0, adds differentiation
- P3: Deferred to v0.5.5+ (portal dependency)

---

## Competitor Feature Analysis

| Feature | GymAware / RepOne | Metric VBT / Vitruve | FormCheck AI / CueForm | Fitbit / Oura | Phoenix (Our Approach) |
|---------|-------------------|----------------------|------------------------|---------------|----------------------|
| VBT persistence | Per-rep velocity stored in cloud | Per-rep stored locally + cloud | N/A | N/A | Per-rep MCV, zone, velocity loss + 101-point force curve + asymmetry persisted to SQLite |
| Force curves | Yes (post-workout cloud) | Basic (bar path) | N/A | N/A | 101-point normalized, sticking point detection, strength profile, persisted per-rep |
| CV form check | No | No | Yes (standalone, no machine data) | No | Yes, integrated with cable machine telemetry -- unique combination |
| Ghost racing | No | No | No | Race Route (running only) | Yes, against own PR cable telemetry -- novel for strength training |
| RPG gamification | No | No | No | No | Yes, 5 attributes from actual training metrics (not arbitrary) |
| Readiness score | No | No | No | Yes (HRV/sleep-based) | Yes, combining FFM + machine velocity data + biometrics |
| Form scoring | No | No | Yes (AI-based, 0-100) | No | Yes, rules-based 0-100 with exercise-specific thresholds |
| Machine + body data fusion | No | No | No | No | Yes -- only platform combining cable telemetry with CV pose data |

**Key competitive insight:** No competitor in the connected strength training space combines cable machine telemetry with computer vision pose estimation. This fusion (force data + body position data) enables unique analytics like load-adjusted form thresholds (e.g., warn about lumbar flexion only when load exceeds 75% estimated 1RM).

---

## Expected User Behavior and UX Patterns

### CV Form Checking -- Expected User Flow

1. User places phone on tripod/against wall, camera facing them (full body visible)
2. Taps "Enable Form Check" toggle on Active Workout Screen (PHOENIX+ only)
3. Small camera PiP appears in corner (150x200dp) with 2D skeleton overlay
4. During reps, joint angles are calculated per frame (~30fps)
5. **WARNING triggers:** Audio cue ("push knees out") + screen border flash + haptic. Text overlay on PiP shows the specific violation.
6. **No auto-deload.** The existing hardware spotter continues to work independently.
7. At set completion, form score (0-100) appears in set summary alongside rep quality score
8. Form violations are listed with severity and rep number

### Ghost Racing -- Expected User Flow

1. Before starting a set, app queries local DB (or portal) for best matching session (same exercise, weight +/- 5%)
2. If match found, "Race Ghost?" prompt with PR details (date, velocity, reps)
3. During set: two vertical bars animate side-by-side
   - Left bar: current real-time cable position (live BLE data)
   - Right bar: ghost replay (historical data, time-aligned from set start)
4. Color coding: green = ahead, red = behind (inspired by Zwift HoloReplay and Apple Watch Race Route)
5. Per-rep verdict: "Ahead by 0.3s" or "Behind by 15mm/s"
6. End-of-set summary: total comparison, velocity deltas, overall verdict

### RPG Attribute Card -- Expected User Flow

1. Compact card on Profile/Gamification screen
2. Shows 5 attributes with level numbers (Strength Lv.12, Power Lv.8, etc.)
3. Character class badge (Powerlifter, Athlete, Ironman, Monk, Phoenix)
4. XP progress bar for each attribute
5. "View Full Skill Tree on Phoenix Portal" deep link
6. Updates after each workout sync (or locally computed in stub mode)

### Pre-Workout Briefing -- Expected User Flow

1. After connecting to Vitruvian machine, before first set
2. Briefing card slides up: "Ready to Train" / "Recovery Moderate" / "Rest Recommended"
3. Readiness score (0-100) with color coding (Green >70 / Yellow 50-70 / Red <50)
4. For scheduled routine: "AI Suggested Weight" alongside prescribed weight per exercise
5. User taps "Accept Suggestions" or "Train as Planned" (always optional)
6. If no readiness data available (no portal connection), shows "Connect to Portal for personalized readiness" upsell
7. Pattern inspired by Fitbit Morning Brief and Oura Readiness Score

---

## Sources

### CV Pose Estimation
- [Google MediaPipe BlazePose Research](https://research.google/blog/on-device-real-time-body-pose-tracking-with-mediapipe-blazepose/)
- [MediaPipe Pose Landmarker Android Guide](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker/android)
- [RepDetect - MediaPipe Android Exercise Form App](https://github.com/giaongo/RepDetect)
- [MediaPipe Pose Estimation for Fitness Applications](https://medium.com/@nsidana123/real-time-pose-tracking-with-mediapipe-a-comprehensive-guide-for-fitness-applications-series-2-731b1b0b8f4d)
- [FormCheck AI](https://apps.apple.com/us/app/formcheck-ai/id6741048432)
- [CueForm AI](https://cueform.ai/)
- [Gymscore](https://www.gymscore.ai/)

### Ghost Racing
- [Zwift HoloReplay Feature](https://zwiftinsider.com/holoreplay/)
- [Apple Watch Race Route](https://www.tomsguide.com/how-to/i-used-this-new-apple-watch-fitness-feature-to-gamify-my-weekly-run)
- [Ghostracer App](https://play.google.com/store/apps/details?id=com.bravetheskies.ghostracer)
- [Forrest Ghost Racer](https://connectthewatts.com/2021/05/24/forrest-ghost-racer-fitness-app-apple-watch/)
- [Ghost Pacer AR](https://ghostpacer.com/)

### RPG Gamification
- [Infitnite Fitness Fantasy RPG](https://fitnessfantasyrpg.com/)
- [RPG Fitness Skill System Benefits](https://fitnessfantasyrpg.com/blog/gamification/10-rpg-skill-system-benefits)
- [RPGFitness Gamified Workout App](https://www.rpgfitness.fr/en)
- [Skill Trees in Gamification](https://www.gamified.uk/2015/01/29/skill-trees-gamification/)
- [Habitica](https://play.google.com/store/apps/details?id=com.habitrpg.android.habitica)

### Readiness and Recovery
- [Fitbit Daily Readiness Score](https://store.google.com/us/magazine/fitbit_daily_readiness_score)
- [Fitbit Morning Brief](https://support.google.com/fitbit/answer/15344549)
- [Oura Readiness Score](https://support.ouraring.com/hc/en-us/articles/360025589793-Readiness-Score)
- [HRV and Readiness - Marco Altini](https://medium.com/@altini_marco/on-heart-rate-variability-hrv-and-readiness-394a499ed05b)
- [Bannister FFM Limitations](https://pmc.ncbi.nlm.nih.gov/articles/PMC1974899/)

### VBT Data Persistence
- [GymAware Cloud](https://gymaware.com/)
- [Metric VBT](https://www.metric.coach/)
- [RepOne Strength](https://www.reponestrength.com/)
- [Output Sports VBT](https://www.outputsports.com/performance/velocity-based-training)
- [Vitruve Load-Velocity Profile](https://vitruve.fit/blog/vbt-guide-creating-a-load-velocity-profile/)

---
*Feature research for: v0.5.0 Premium Mobile Features*
*Researched: 2026-02-20*
