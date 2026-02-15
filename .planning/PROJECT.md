# Project Phoenix MP

## What This Is

Kotlin Multiplatform app for controlling Vitruvian Trainer workout machines (V-Form, Trainer+) via BLE. Community rescue project keeping machines functional after company bankruptcy. Supports Android (Compose) and iOS (SwiftUI) from a shared KMP codebase. Features premium biomechanics analysis: real-time velocity tracking, force curve visualization, bilateral asymmetry detection, LED biofeedback, rep quality scoring, and smart training suggestions.

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

### Active

<!-- Current scope. Building toward these. -->

**v0.5.0 Mobile Platform Features** — Strength assessment, exercise auto-detection (Spec 04)

### Out of Scope

<!-- Explicit boundaries. Includes reasoning to prevent re-adding. -->

- KableBleRepository decomposition — works reliably, refactoring risk outweighs benefit
- Biomechanics persistence to database — v0.5.0+ scope (PERSIST-01 through PERSIST-03)
- Load-Velocity Profile — requires cross-session persistence
- Auto-Regulation (Spec 03.3-4) — depends on historical velocity data
- Portal sync backend (Spec 05) — v0.6.0 infrastructure work
- Portal force curve integration — no backend sync infrastructure yet
- iOS-specific UI work — focus is shared module and Android Compose layer
- BLE protocol changes — no hardware interaction changes
- RevenueCat billing integration — blocked on auth migration
- Elite coaching suggestions — requires historical analysis infrastructure

## Context

- App is at v0.4.6, actively used by community
- Premium features shipped: biomechanics analysis, LED biofeedback, rep quality scoring, smart suggestions
- MainViewModel is a thin 420-line facade delegating to 5 specialized managers
- DefaultWorkoutSessionManager (449 lines) orchestrates 3 sub-managers:
  - WorkoutCoordinator (257L) — shared state bus, zero business logic
  - RoutineFlowManager (1,091L) — routine CRUD, navigation, supersets
  - ActiveSessionEngine (~2,400L) — workout lifecycle, BLE commands, auto-stop, quality scoring, biomechanics
- Domain engines: BiomechanicsEngine, LedFeedbackController, RepQualityScorer, SmartSuggestionsEngine
- New UI: BalanceBar, ForceCurveMiniGraph, VelocitySummaryCard, ForceCurveSummaryCard, AsymmetrySummaryCard
- Koin DI: 4 feature-scoped modules (data, sync, domain, presentation) with verify() test
- Test infrastructure: DWSMTestHarness, WorkoutStateFixtures, fakes for all repositories
- ~28,700 lines of Kotlin in shared module (+6,917 from v0.4.6)

## Current State

**Version:** v0.4.6 (shipped 2026-02-15)
**Next:** v0.5.0 Mobile Platform Features (planning)

Biomechanics MVP complete. Real-time velocity-based training analysis with VBT engine (MCV, velocity zones, velocity loss, rep projection), force curve visualization (101-point ROM normalization, sticking point, strength profile), and bilateral asymmetry detection. Three subscription tiers operational with single upstream gate pattern for Phoenix tier features. Architecture remains clean with all engines following established patterns (injectable time providers, stateless pure functions, StateFlow exposure).

## Future Milestones

- **v0.5.0** — Mobile Platform Features (Spec 04: strength assessment, exercise auto-detection)
- **v0.5.5** — Biomechanics Persistence (PERSIST-01 through PERSIST-03: database storage, historical views)
- **v0.6.0** — Auth Migration + Portal Integration (Spec 05: Supabase auth, force curve sync)

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

---
*Last updated: 2026-02-15 after v0.4.6 milestone*
