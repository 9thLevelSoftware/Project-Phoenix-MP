# Project Phoenix MP

## What This Is

Kotlin Multiplatform app for controlling Vitruvian Trainer workout machines (V-Form, Trainer+) via BLE. Community rescue project keeping machines functional after company bankruptcy. Supports Android (Compose) and iOS (SwiftUI) from a shared KMP codebase. Features premium biomechanics analysis: real-time velocity tracking, force curve visualization, bilateral asymmetry detection, LED biofeedback, rep quality scoring, smart training suggestions, VBT-based strength assessment, exercise auto-detection, and mobile session replay with per-rep force curves.

## Core Value

Users can connect to their Vitruvian trainer and execute workouts with accurate rep counting, weight control, and progress tracking — reliably, on both platforms.

## Requirements

### Validated

<!-- Shipped and confirmed valuable. -->

- ✓ BLE connection to V-Form (`Vee_*`) and Trainer+ (`VIT*`) devices — v0.1
- ✓ 6 workout modes (Old School, Eccentric, etc.) with real-time metric display — v0.1
- ✓ Rep counting via machine + position-based phase detection — v0.2
- ✓ Exercise library with muscle groups, equipment, video support — v0.2
- ✓ Routines with supersets, set/rep/weight tracking — v0.2
- ✓ Personal records with 1RM calculation (Brzycki, Epley) — v0.3
- ✓ Gamification: XP, badges, workout streaks — v0.3
- ✓ Training cycles with day rotation — v0.3
- ✓ Cloud sync infrastructure — v0.4
- ✓ MainViewModel decomposition into 5 managers — v0.4
- ✓ DefaultWorkoutSessionManager decomposed into 3 sub-managers — v0.4.1
- ✓ Circular dependency eliminated via bleErrorEvents SharedFlow — v0.4.1
- ✓ Koin DI split into 4 feature-scoped modules with verify() test — v0.4.1
- ✓ 38 characterization tests covering workout lifecycle and routine flow — v0.4.1
- ✓ Per-rep metrics stored in RepMetric table with force curve data — v0.4.5
- ✓ Subscription tier (FREE/PHOENIX/ELITE) with FeatureGate utility — v0.4.5
- ✓ LED biofeedback with velocity zones, PR celebration, rest period colors — v0.4.5
- ✓ Rep quality scoring (0-100) with 4-component algorithm and HUD indicator — v0.4.5
- ✓ Set summary with quality sparkline, radar chart, and trend indicator — v0.4.5
- ✓ Form Master badges (Bronze/Silver/Gold) for quality achievements — v0.4.5
- ✓ Smart Suggestions: volume tracking, balance analysis, plateau detection — v0.4.5
- ✓ Elite tier gating for Smart Insights tab — v0.4.5
- ✓ BiomechanicsEngine with VBT analysis (MCV, velocity zones, velocity loss, rep projection) — v0.4.6
- ✓ Force curve construction with 101-point ROM normalization, sticking point detection, strength profile — v0.4.6
- ✓ Per-rep cable asymmetry detection with dominant side identification — v0.4.6
- ✓ Real-time velocity HUD with zone color-coding and velocity loss display — v0.4.6
- ✓ L/R balance bar with severity colors and consecutive rep alert — v0.4.6
- ✓ Force curve mini-graph on HUD with tap-to-expand overlay — v0.4.6
- ✓ Set summary biomechanics cards (velocity, force curve, asymmetry) — v0.4.6
- ✓ Phoenix tier gating for all biomechanics features via single upstream gate — v0.4.6
- ✓ Dual-cable power calculation fix (loadA + loadB) — v0.4.7
- ✓ MetricSample sessionId index for query performance — v0.4.7
- ✓ ExerciseSignature table for movement pattern storage — v0.4.7
- ✓ AssessmentResult table for 1RM history — v0.4.7
- ✓ VBT-based strength assessment with OLS load-velocity regression — v0.4.7
- ✓ 6-step assessment wizard with video instructions and progressive weights — v0.4.7
- ✓ Exercise auto-detection via signature extraction from first 3-5 reps — v0.4.7
- ✓ Weighted similarity matching for exercise classification (ROM 40%, duration 20%, symmetry 25%, shape 15%) — v0.4.7
- ✓ EMA signature evolution (alpha=0.3) for learning user patterns — v0.4.7
- ✓ Non-blocking bottom sheet for exercise confirmation — v0.4.7
- ✓ Mobile replay cards with per-rep force sparklines — v0.4.7
- ✓ Valley-based rep boundary detection for accurate rep isolation — v0.4.7
- ✓ Biomechanics persistence: RepBiomechanics table, per-rep VBT/force/asymmetry saved to DB — v0.5.0
- ✓ Biomechanics history UI with lazy-loading and set-level summary — v0.5.0
- ✓ CV form rules engine for 5 exercises (squat, deadlift, OHP, curl, row) with form scoring 0-100 — v0.5.0
- ✓ MediaPipe pose estimation on Android (LIVE_STREAM, CPU delegate, 100ms throttle) — v0.5.0
- ✓ FormCheckOverlay composable with CameraX PiP + skeleton overlay — v0.5.0
- ✓ iOS no-op stub for FormCheckOverlay (CV deferred to v0.6.0+) — v0.5.0

### Active

<!-- Current scope. Building toward these. -->

- [ ] CV Form Check UI: toggle, real-time warnings, form score display, tier gating
- [ ] CV Form Check persistence: form score + violations saved locally per exercise
- [ ] iOS "Form Check coming soon" stub UI
- [ ] Ghost racing overlay composable (race personal best session, local data)
- [ ] RPG attribute card composable (5 stats + character class, local computation)
- [ ] Pre-workout readiness briefing composable (volume heuristic, advisory only)
- [ ] Premium feature gates for new features (Form Check → Phoenix, RPG → Phoenix, Readiness → Elite)
- [ ] SmartSuggestions UTC time bug fix (classifyTimeWindow uses UTC not local)
- [ ] WCAG color-blind fallbacks (velocity zones, balance bar, readiness card)
- [ ] HUD page customization (user-configurable metric visibility)
- [ ] android:allowBackup exclusion rules for sensitive DB data
- [ ] Camera permission custom rationale text (on-device-only guarantee)
- [ ] iOS Form Check suppression in PHOENIX upgrade prompts
- [ ] versionName update (currently 0.4.0, needs to reflect actual version)
- [ ] pose_landmarker_lite.task asset verification (fallback on missing model)

### Out of Scope

<!-- Explicit boundaries. Includes reasoning to prevent re-adding. -->

- KableBleRepository decomposition — works reliably, refactoring risk outweighs benefit
- Biomechanics persistence to database — moved to Active for v0.5.0
- Load-Velocity Profile — requires cross-session persistence
- Auto-Regulation (Spec 03.3-4) — depends on historical velocity data
- Portal sync backend (Spec 05) — v0.6.0 infrastructure work
- Portal force curve integration — no backend sync infrastructure yet
- iOS-specific UI work — focus is shared module and Android Compose layer
- BLE protocol changes — no hardware interaction changes
- RevenueCat billing integration — blocked on auth migration
- Elite coaching suggestions — requires historical analysis infrastructure

## Context

- App is at v0.5.0, actively used by community
- Premium features shipped: biomechanics analysis, LED biofeedback, rep quality scoring, smart suggestions, strength assessment, exercise auto-detection, mobile replay, biomechanics persistence, CV form check infrastructure
- MainViewModel is a thin 420-line facade delegating to 5 specialized managers
- DefaultWorkoutSessionManager (449 lines) orchestrates 3 sub-managers:
  - WorkoutCoordinator (257L) — shared state bus, zero business logic
  - RoutineFlowManager (1,091L) — routine CRUD, navigation, supersets
  - ActiveSessionEngine (~2,600L) — workout lifecycle, BLE commands, auto-stop, quality scoring, biomechanics, detection
- Domain engines: BiomechanicsEngine, LedFeedbackController, RepQualityScorer, SmartSuggestionsEngine, AssessmentEngine, SignatureExtractor, ExerciseClassifier, RepBoundaryDetector
- New UI: BalanceBar, ForceCurveMiniGraph, VelocitySummaryCard, ForceCurveSummaryCard, AsymmetrySummaryCard, AssessmentWizardScreen, AutoDetectionSheet, RepReplayCard, ForceSparkline
- Koin DI: 4 feature-scoped modules (data, sync, domain, presentation) with verify() test
- Test infrastructure: DWSMTestHarness, WorkoutStateFixtures, fakes for all repositories
- ~30,500 lines of Kotlin in shared module
- Database schema version: 16 (RepBiomechanics table added in v0.5.0)
- Domain engines added: FormRulesEngine, PoseLandmarkerHelper, LandmarkAngleCalculator
- New UI: FormCheckOverlay (CameraX PiP + skeleton), BiomechanicsHistoryCard

## Current Milestone: v0.5.1 Board Polish & Premium UI

**Goal:** Complete carried-over premium UI features (CV Form Check UX, ghost racing, RPG attributes, readiness briefing) and address all Board of Directors conditions (accessibility, security, UX, versioning).

**Target features:**
- CV Form Check: toggle UI, real-time warnings, form score persistence, iOS stub, tier gating
- Ghost racing overlay (race personal best, dual progress bars, velocity delta)
- RPG attributes (5 stats + character class, local computation, Phoenix tier)
- Pre-workout readiness briefing (volume heuristic, advisory only, Elite tier)
- Board conditions: UTC fix, WCAG accessibility, allowBackup exclusion, HUD customization, camera rationale, iOS marketing suppression, versionName, asset verification

## Current State

**Version:** v0.5.0 (shipped 2026-02-27)
**Status:** Starting v0.5.1 Board Polish & Premium UI milestone

CV form checking infrastructure complete: MediaPipe running on-device with skeleton overlay, form rules engine for 5 exercises, biomechanics data persisted. Board of Directors review (2026-02-27) approved 5-0 with 9 conditions targeting accessibility (WCAG color-blind), security (allowBackup), UX (HUD density), and operational polish (versionName, UTC bug). Premium UI composables (ghost racing, RPG, readiness) ready for implementation with all domain logic in place.

## Future Milestones

- **v0.6.0** — Auth Migration + Portal Integration (Supabase auth, force curve sync, iOS CV)
- **v0.7.0** — Portal Replay + Community Features (Spec 04 portal, Spec 05c-d)
- **v0.8.0** — Premium Portal Features (RPG trees, ghost replay, digital twin, F-v dashboard, AI auto-regulation, CV analytics)

## Constraints

- **Platform**: KMP shared module — all business logic must remain in commonMain
- **Compatibility**: No breaking changes to existing workout behavior — characterization tests first
- **BLE stability**: Do not touch KableBleRepository or BLE protocol code
- **Incremental**: Each phase must leave the app in a buildable, working state
- **Tier gating**: Data capture for all tiers, gating at UI/feature level only (GATE-04)

## Key Decisions

<!-- Decisions that constrain future work. Add throughout project lifecycle. -->

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Manager extraction pattern (scope injection, interface-based) | Enables testability, preserves ViewModel lifecycle | ✓ Good |
| MainViewModel as thin facade during transition | Preserves UI API while extracting logic incrementally | ✓ Good |
| Leave KableBleRepository alone | Works reliably, high risk/low reward to refactor | ✓ Good |
| Characterize before refactoring | Tests lock in behavior, catch regressions | ✓ Good — 38 tests |
| WorkoutCoordinator as zero-method state bus | Sub-managers share state without circular refs | ✓ Good — v0.4.1 |
| bleErrorEvents SharedFlow for BLE→DWSM | Eliminates lateinit var circular dependency | ✓ Good — v0.4.1 |
| Feature-scoped Koin modules with verify() | Catches DI wiring issues at test time | ✓ Good — v0.4.1 |
| SubscriptionTier separate from SubscriptionStatus | Tier = feature access, Status = payment state | ✓ Good — v0.4.5 |
| Manual JSON serialization for primitive arrays | Avoid kotlinx.serialization complexity for simple cases | ✓ Good — v0.4.5 |
| 4-zone LED scheme (OFF/Green/Blue/Red) | Clearer visual feedback than 6-zone spec | ✓ Good — v0.4.5 |
| Velocity thresholds 5/30/60 mm/s | Hardware calibrated (5x lower than spec) | ✓ Good — v0.4.5 |
| First rep gets perfect ROM/velocity scores | No baseline to penalize against | ✓ Good — v0.4.5 |
| Stateless SmartSuggestionsEngine | Pure functions, injectable time, easy testing | ✓ Good — v0.4.5 |
| SmartInsightsTab as separate file | Avoid breaking existing InsightsTab in AnalyticsScreen | ✓ Good — v0.4.5 |
| BiomechanicsVelocityZone thresholds 250/500/750/1000 mm/s | Standard VBT thresholds for MCV classification | ✓ Good — v0.4.6 |
| MCV = avg(max(abs(velocityA), abs(velocityB))) | Handles dual-cable Vitruvian machines | ✓ Good — v0.4.6 |
| 101-point ROM normalization for force curves | Standard VBT approach, enables rep-to-rep comparison | ✓ Good — v0.4.6 |
| Sticking point excludes first/last 5% ROM | Filters transition noise at ROM boundaries | ✓ Good — v0.4.6 |
| 2% threshold for BALANCED asymmetry | Measurement noise tolerance | ✓ Good — v0.4.6 |
| Single upstream gate pattern for tier gating | Gate at data collection, downstream UI checks null naturally | ✓ Good — v0.4.6 |
| InfiniteTransition created unconditionally | Satisfies Compose call-site stability requirements | ✓ Good — v0.4.6 |
| Element-wise averaging of 101-point force curves | Set-level averaged curve for summary display | ✓ Good — v0.4.6 |
| Double precision for OLS regression internals | Avoid Float accumulation errors in regression | ✓ Good — v0.4.7 |
| __ASSESSMENT__ marker in routineName | Identifies assessment WorkoutSessions | ✓ Good — v0.4.7 |
| Valley-based rep detection (5-sample smoothing, 10mm threshold) | Consistent algorithm across Phase 11 and 12 | ✓ Good — v0.4.7 |
| History matching threshold 0.85 before rule-based fallback | Balance between learning and accuracy | ✓ Good — v0.4.7 |
| EMA alpha=0.3 for signature evolution | Gradual learning, resistant to outliers | ✓ Good — v0.4.7 |
| ForceSparkline 40dp height with peak marker | Compact card embedding with visual clarity | ✓ Good — v0.4.7 |

---
*Last updated: 2026-02-27 after v0.5.1 milestone start*
