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

