# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-13)

**Core value:** Users can connect to their Vitruvian trainer and execute workouts reliably on both platforms.
**Current focus:** Phase 4 - Smart Suggestions

## Current Position

Phase: 4 of 4 (Smart Suggestions) - COMPLETE
Plan: 3 of 3 in current phase
Status: Phase 4 Complete - v0.4.5 Milestone Complete
Last activity: 2026-02-14 — Completed 04-03 (Smart Suggestions UI)

Progress: [██████████] 100%

## Performance Metrics

**Velocity:**
- Total plans completed: 10 (from v0.4.1)
- Average duration: not tracked (pre-metrics)
- Total execution time: not tracked

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 1-4 (v0.4.1) | 10 | - | - |
| 01-01 (v0.4.5) | 1 | 12min | 12min |
| 01-02 (v0.4.5) | 1 | 6min | 6min |
| 02-01 (v0.4.5) | 1 | 6min | 6min |
| 02-02 (v0.4.5) | 1 | ~90min | ~90min |
| 03-01 (v0.4.5) | 1 | 5min | 5min |
| 03-02 (v0.4.5) | 1 | 11min | 11min |
| 03-03 (v0.4.5) | 1 | 14min | 14min |
| 04-01 (v0.4.5) | 1 | 6min | 6min |
| 04-02 (v0.4.5) | 1 | 6min | 6min |
| 04-03 (v0.4.5) | 1 | 11min | 11min |

*Updated after each plan completion*

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- [v0.4.5 init]: Data Foundation must ship before LED/Quality/Suggestions (dependency)
- [v0.4.5 init]: Data capture for all tiers, gating at UI/feature level only (GATE-04)
- [v0.4.1]: 38 characterization tests lock in existing workout behavior
- [01-01]: SubscriptionTier separate from SubscriptionStatus (tier = feature access, status = payment state)
- [01-01]: RepMetricData uses FloatArray/LongArray for performance; JSON serialization deferred to Plan 02
- [01-01]: domain/premium/ package established for subscription and gating utilities
- [01-02]: Manual JSON serialization (joinToString/split) for primitive arrays instead of kotlinx.serialization
- [01-02]: Serialization helpers marked internal - repository layer implementation detail
- [02-01]: Injectable timeProvider lambda for deterministic test control in LedFeedbackController
- [02-01]: Reused FakeBleRepository with colorSchemeCommands tracking rather than new test double
- [02-01]: Internal visibility on resolver methods for white-box testing of boundary conditions
- [02-02]: Simplified from 6 zones to 4 (OFF/Green/Blue/Red) for clearer visual feedback
- [02-02]: Velocity thresholds recalibrated from spec (~5x lower): 5/30/60 mm/s based on real hardware data
- [02-02]: Removed BLE throttle (500ms) for faster response; kept hysteresis (3 samples) for stability
- [03-01]: First rep gets perfect ROM/velocity scores (no baseline to penalize against)
- [03-01]: Running averages updated after scoring (score against prior reps only)
- [03-01]: Smoothness uses coefficient of variation with 2x multiplier for sensitivity
- [03-01]: Trend detection uses half-split +/-5 threshold
- [03-02]: Score gated by SubscriptionManager.hasProAccess at ActiveWorkoutScreen level
- [03-02]: Approximate metric data for HUD scoring (full accuracy in persisted RepMetricData)
- [03-02]: RepQualityIndicator at TopCenter with 80dp offset, 800ms auto-dismiss
- [03-03]: Quality summary captured before scorer reset in handleSetCompletion
- [03-03]: Quality streak is session-scoped, resets on new workout start
- [03-03]: QualityStreak badges bypass DB stats check (awarded directly by GamificationManager)
- [03-03]: Radar chart uses Compose Text labels around Canvas for KMP compatibility
- [03-03]: Tap-to-toggle between sparkline and radar chart (simpler than HorizontalPager)
- [04-01]: Stateless object SmartSuggestionsEngine (no DI, pure functions only)
- [04-01]: Injectable nowMs: Long parameter for time-dependent functions (follows 02-01 pattern)
- [04-01]: Volume formula: weightPerCableKg * 2 * workingReps (dual cable machine)
- [04-01]: Balance thresholds: <25% or >45% triggers imbalance (excluding core)
- [04-01]: Plateau: 4+ consecutive sessions within 0.5kg, minimum 5 total sessions
- [04-01]: Time-of-day optimal requires 3+ sessions for statistical relevance
- [04-02]: Used SubscriptionTier.fromDbString() instead of broken SubscriptionStatus for tier detection
- [04-02]: Added getActiveProfileTier() to UserProfileRepository for direct DB-to-tier mapping
- [04-03]: Created SmartInsightsTab.kt (new file) to avoid breaking existing InsightsTab in AnalyticsScreen
- [04-03]: Self-loading composable pattern for SmartInsightsTab (no ViewModel, internal Koin + data loading)
- [04-03]: Elite-tier UI gating via hasEliteAccess with LockedFeatureOverlay fallback

### Pending Todos

None yet.

### Blockers/Concerns

None yet.

## Session Continuity

Last session: 2026-02-14
Stopped at: Completed 04-03-PLAN.md (Smart Suggestions UI) - Phase 4 and v0.4.5 Milestone COMPLETE
Resume file: None
