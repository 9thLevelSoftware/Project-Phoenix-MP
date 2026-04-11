---
phase: 13-biomechanics-persistence
plan: 02
subsystem: ui
tags: [compose, biomechanics, vbt, force-curve, asymmetry, history, tier-gating, premium]

# Dependency graph
requires:
  - phase: 13-biomechanics-persistence
    plan: 01
    provides: "BiomechanicsRepository, RepBiomechanics table, WorkoutSession.hasBiomechanicsData, session summary columns"
  - phase: 12-biomechanics-engine
    provides: "BiomechanicsRepResult, BiomechanicsVelocityZone, StrengthProfile domain models"
provides:
  - "BiomechanicsHistorySummary composable for set-level biomechanics at-a-glance in session history"
  - "RepBiomechanicsDetail composable with per-rep VBT metrics and expandable force curve sparklines"
  - "PremiumBiomechanicsUpsell card for FREE tier users"
  - "Tier-gated biomechanics section in HistoryTab (both WorkoutHistoryCard and GroupedRoutineCard)"
affects: [ghost-racing-ui, rpg-system-ui, premium-composables]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "SubscriptionManager koinInject() pattern for tier resolution in composables (matching ActiveWorkoutScreen)"
    - "Lazy-load per-rep data only on user expand (avoids deserializing 101-point force curves eagerly)"
    - "Tiered feature gating: hasProAccess for VBT/force, hasEliteAccess for asymmetry"

key-files:
  created:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/BiomechanicsHistoryCard.kt
  modified:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/HistoryTab.kt

key-decisions:
  - "Used SubscriptionManager.hasProAccess/hasEliteAccess flows instead of raw FeatureGate.isEnabled() for tier gating — matches established pattern in ActiveWorkoutScreen, SettingsTab, SmartInsightsTab"
  - "Lazy-load RepBiomechanics only when user expands per-rep section (not on card expand) to avoid deserializing 101-point force curves for every session"
  - "VelocityZone colors follow VBT convention: GRIND=Red, SLOW=Orange, MODERATE=Amber, FAST=Green, EXPLOSIVE=Cyan"

patterns-established:
  - "BiomechanicsSection composable pattern: guard with hasBiomechanicsData, gate with hasProAccess/hasEliteAccess, lazy-load per-rep on expand"
  - "VelocityZoneChip reusable composable for velocity zone color coding across the app"

requirements-completed: [PERSIST-01, PERSIST-02, PERSIST-03, PERSIST-04]

# Metrics
duration: 6min
completed: 2026-02-21
---

# Phase 13 Plan 02: Session History Biomechanics UI Summary

**BiomechanicsHistoryCard composable with set-level MCV/asymmetry/velocity-loss summary, per-rep drill-down with expandable force curve sparklines via ForceSparkline reuse, and 3-tier gating (FREE=upsell, Phoenix+=VBT+force, Elite=asymmetry) integrated into HistoryTab**

## Performance

- **Duration:** 6 min
- **Started:** 2026-02-21T03:31:09Z
- **Completed:** 2026-02-21T03:37:11Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments
- BiomechanicsHistorySummary composable showing avg MCV with velocity zone color indicator, strength profile, velocity loss trend, and asymmetry (Elite-only) at a glance
- RepBiomechanicsDetail composable with per-rep rows showing MCV, velocity zone chip, velocity loss %, asymmetry, and expandable force curve sparklines with sticking point / strength profile annotations
- PremiumBiomechanicsUpsell card for FREE tier following existing LockedFeatureOverlay visual pattern
- Full integration into both WorkoutHistoryCard and GroupedRoutineCard expanded sections in HistoryTab
- 3-tier gating: FREE sees upsell, Phoenix+ sees VBT metrics and force curves, Elite sees everything including asymmetry
- Graceful null handling: older sessions without biomechanics data show no biomechanics section at all

## Task Commits

Each task was committed atomically:

1. **Task 1: BiomechanicsHistoryCard composable with set-level summary and per-rep detail** - `e614308d` (feat)
2. **Task 2: Integrate biomechanics display into HistoryTab with lazy-loading and tier gating** - `c84b4cd6` (feat)

**Plan metadata:** (pending final commit)

## Files Created/Modified
- `shared/src/commonMain/kotlin/.../presentation/components/BiomechanicsHistoryCard.kt` - BiomechanicsHistorySummary, RepBiomechanicsDetail, PremiumBiomechanicsUpsell, VelocityZoneChip composables
- `shared/src/commonMain/kotlin/.../presentation/screen/HistoryTab.kt` - BiomechanicsSection private composable + integration into WorkoutHistoryCard and GroupedRoutineCard expanded views

## Decisions Made
- Used SubscriptionManager.hasProAccess/hasEliteAccess flows for tier gating rather than resolving raw SubscriptionTier + FeatureGate.isEnabled() -- this matches the established composable pattern already used in ActiveWorkoutScreen, SettingsTab, and SmartInsightsTab. Functionally equivalent (Phoenix = hasProAccess, Elite = hasEliteAccess).
- Lazy-load per-rep RepBiomechanics data only when user taps "View Per-Rep Details" and the AnimatedVisibility becomes visible. This prevents eager deserialization of 101-point force curve FloatArrays for every session in the history list.
- Velocity zone colors follow VBT training convention (GRIND=Red for near-max, EXPLOSIVE=Cyan for speed work) rather than traffic-light coding, to align with user expectations from VBT literature.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Phase 13 (Biomechanics Persistence) is fully complete: schema, repository, persistence wiring, and UI display
- Ghost racing, RPG system, and readiness model phases can now query historical biomechanics data via BiomechanicsRepository
- Premium composables phase (17) can reference BiomechanicsHistoryCard patterns for other premium UI gating
- All biomechanics data captured for ALL tiers (GATE-04), gating at UI layer only

## Self-Check: PASSED

All 2 files verified present on disk. Both task commits (e614308d, c84b4cd6) verified in git log.

---
*Phase: 13-biomechanics-persistence*
*Completed: 2026-02-21*
