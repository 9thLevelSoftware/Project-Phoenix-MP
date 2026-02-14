# Project Phoenix MP

## What This Is

Kotlin Multiplatform app for controlling Vitruvian Trainer workout machines (V-Form, Trainer+) via BLE. Community rescue project keeping machines functional after company bankruptcy. Supports Android (Compose) and iOS (SwiftUI) from a shared KMP codebase. Now includes premium features: LED biofeedback, rep quality scoring, and smart training suggestions.

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

### Active

<!-- Current scope. Building toward these. -->

(Next milestone requirements to be defined via `/gsd:new-milestone`)

### Out of Scope

<!-- Explicit boundaries. Includes reasoning to prevent re-adding. -->

- KableBleRepository decomposition — works reliably, refactoring risk outweighs benefit
- Biomechanics MVP (Spec 01) — v0.5.0 scope, depends on velocity pipeline
- Auto-Regulation (Spec 03.3-4) — depends on Spec 01 velocity pipeline
- Portal sync backend (Spec 05) — v0.6.0 infrastructure work
- Portal replay features — no backend exists yet
- iOS-specific UI work — focus is shared module and Android Compose layer
- BLE protocol changes — no hardware interaction changes
- RevenueCat billing integration — blocked on auth migration

## Context

- App is at v0.4.5, actively used by community
- Premium features shipped: LED biofeedback, rep quality scoring, smart suggestions
- MainViewModel is a thin 420-line facade delegating to 5 specialized managers
- DefaultWorkoutSessionManager (449 lines) orchestrates 3 sub-managers:
  - WorkoutCoordinator (257L) — shared state bus, zero business logic
  - RoutineFlowManager (1,091L) — routine CRUD, navigation, supersets
  - ActiveSessionEngine (2,200L) — workout lifecycle, BLE commands, auto-stop, quality scoring
- New engines: LedFeedbackController, RepQualityScorer, SmartSuggestionsEngine
- New UI: RepQualityIndicator, SmartInsightsTab, quality stats in SetSummaryCard
- Koin DI: 4 feature-scoped modules (data, sync, domain, presentation) with verify() test
- Test infrastructure: DWSMTestHarness, WorkoutStateFixtures, fakes for all repositories
- ~21,800 lines of Kotlin in shared module (+1,832 from v0.4.5)

## Current State

**Version:** v0.4.5 (shipped 2026-02-14)

Premium features foundation complete. Three subscription tiers operational with proper UI gating. Architecture remains clean with new engines following established patterns (injectable time providers, stateless pure functions, StateFlow exposure).

## Next Milestone Goals

- **v0.5.0** — Biomechanics MVP (Spec 01: VBT engine, velocity HUD, force curve visualization)
- **v0.5.5** — Mobile Platform Features (Spec 04: strength assessment, exercise auto-detection)
- **v0.6.0** — Auth Migration (Spec 05a: Supabase auth, user migration)

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

---
*Last updated: 2026-02-14 after v0.4.5 milestone*
