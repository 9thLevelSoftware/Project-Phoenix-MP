# Milestones

## v0.4.0 — Foundation (Pre-GSD)

**Shipped:** BLE control, workout execution, exercise library, routines, supersets, training cycles, personal records, gamification, cloud sync, initial MainViewModel extraction.

**Phases completed:** N/A (pre-GSD — work tracked via git history and OpenSpec)

**Last phase number:** 0

---

## v0.4.1 — Architectural Cleanup (Shipped: 2026-02-13)

**Delivered:** Complete architectural decomposition of remaining monoliths with testing foundation.

**Phases completed:** 4 phases, 10 plans

**Key accomplishments:**
- Created 38 characterization tests with DWSMTestHarness and WorkoutStateFixtures
- Decomposed 4,024-line DefaultWorkoutSessionManager into 4 focused components (449L orchestration layer)
- Extracted WorkoutCoordinator (257L) as zero-method shared state bus
- Extracted RoutineFlowManager (1,091L) for routine CRUD and navigation
- Extracted ActiveSessionEngine (2,174L) for workout lifecycle and BLE commands
- Eliminated circular dependency via bleErrorEvents SharedFlow pattern
- Split 2,750-line HistoryAndSettingsTabs.kt into HistoryTab.kt + SettingsTab.kt
- Extracted SetSummaryCard, WorkoutSetupDialog, ModeSubSelectorDialog from WorkoutTab.kt
- Split 30+ binding commonModule into 4 feature-scoped Koin modules with verify() test

**Last phase number:** 4

**Archive:** `.planning/milestones/v0.4.1-*`

---


## v0.4.5 Premium Features Phase 1 (Shipped: 2026-02-14)

**Delivered:** First premium features with subscription tier gating — LED biofeedback, rep quality scoring, smart training suggestions, and persistent per-rep metrics.

**Phases completed:** 5 phases, 11 plans

**Key accomplishments:**
- Data Foundation: RepMetric table schema, SubscriptionTier enum (FREE/PHOENIX/ELITE), FeatureGate utility, migration v13
- LED Biofeedback: Real-time velocity-zone LED colors (4-zone scheme), PR celebration flash, rest period blue, settings toggle
- Rep Quality Scoring: 4-component algorithm (ROM/velocity/eccentric/smoothness), HUD indicator with animation, sparkline + radar charts
- Form Master Badges: Bronze/Silver/Gold badges with quality streak requirements via GamificationManager
- Smart Suggestions: 5 Elite-tier insights (volume tracking, balance analysis, neglect alerts, plateau detection, time-of-day)
- RepMetric Persistence: Per-rep force curve data persisted to database during workouts (gap closure)

**Stats:** 59 commits, 131 files changed, +11,573/-9,741 lines

**Last phase number:** 5

**Archive:** `.planning/milestones/v0.4.5-*`

---


## v0.4.6 Biomechanics MVP (Shipped: 2026-02-15)

**Delivered:** Real-time velocity-based training analysis with force curve visualization and bilateral asymmetry detection — transforming raw BLE telemetry into actionable training insights.

**Phases completed:** 3 phases (6-8), 10 plans

**Key accomplishments:**
- BiomechanicsEngine with VBT analysis (MCV, velocity zones, velocity loss, rep projection), force curve construction (101-point ROM normalization, sticking point, strength profile), and asymmetry detection
- Real-time HUD velocity display with zone color-coding (Explosive→Grind), velocity loss % after rep 2, and estimated reps remaining
- L/R balance bar with green/yellow/red severity thresholds and pulsing alert after 3 consecutive high-asymmetry reps
- Force curve visualization: mini-graph on HUD with tap-to-expand AlertDialog, sticking point annotation, strength profile badge
- Set summary biomechanics cards: velocity (avg MCV, peak, loss %, zone distribution), force curve (averaged curve, sticking point), asymmetry (avg %, dominant side, trend sparkline)
- Phoenix tier gating via single upstream gate pattern — data capture for all tiers, UI gating at collection point

**Stats:** 45 files changed, +6,917 lines, 69 new tests (VBT: 34, Force: 19, Asymmetry: 16)

**Last phase number:** 8

**Archive:** `.planning/milestones/v0.4.6-*`

---


## v0.4.7 Mobile Platform Features (Shipped: 2026-02-15)

**Delivered:** Intelligent training platform with VBT-based strength assessment, exercise auto-detection via movement signatures, and mobile session replay with per-rep force curves.

**Phases completed:** 4 phases (9-12), 13 plans

**Key accomplishments:**
- Infrastructure: Fixed dual-cable power calculation (loadA + loadB), added MetricSample sessionId index, added ExerciseSignature + AssessmentResult tables (schema v15)
- Strength Assessment: OLS load-velocity regression for 1RM estimation, 6-step wizard UI with video instructions, progressive weights with velocity feedback, manual override support, 21 unit tests
- Exercise Auto-Detection: Signature extraction from 3-5 reps (ROM, duration, symmetry, velocity profile), weighted similarity matching (ROM 40%, duration 20%, symmetry 25%, shape 15%), EMA evolution (alpha=0.3), non-blocking bottom sheet UI, 27 tests
- Mobile Replay Cards: Valley-based rep boundary detection with 5-sample smoothing, ForceSparkline Canvas component, RepReplayCard with peak force + durations, HistoryTab integration, 13 tests

**Stats:** 25 feature commits, 61 new tests, 21 requirements satisfied

**Last phase number:** 12

**Archive:** `.planning/milestones/v0.4.7-*`

---


## v0.5.0 Premium Mobile (Shipped: 2026-02-27)

**Delivered:** On-device computer vision form checking infrastructure, biomechanics persistence, and CV domain logic — establishing the foundation for real-time form analysis during workouts.

**Phases completed:** 3 phases (13-15), 7 plans. Phases 16-17 rolled forward to v0.5.1.

**Key accomplishments:**
- Biomechanics Persistence: Schema migration v16 with RepBiomechanics table, BiomechanicsRepository, per-rep VBT/force/asymmetry data saved to DB, biomechanics history UI with lazy-loading
- CV Form Check Domain Logic: FormCheckModels with JointAngle, FormViolation, FormAssessment types, FormRulesEngine with exercise-specific rules for 5 exercises (squat, deadlift/RDL, OHP, curl, row), form scoring 0-100, 25 unit tests
- CV Android Integration: MediaPipe pose_landmarker_lite model bundled (5.78MB), PoseLandmarkerHelper with LIVE_STREAM async + 100ms throttle, LandmarkAngleCalculator for joint angle extraction, FormCheckOverlay composable with CameraX PiP + skeleton overlay, iOS no-op stub, ProGuard keep rules

**Rolled forward to v0.5.1:** Phase 16 (CV UI/Persistence/Gating), Phase 17 (Ghost Racing, RPG Attributes, Readiness Briefing)

**Last phase number:** 15

---

