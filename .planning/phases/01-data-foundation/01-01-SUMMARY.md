---
phase: 01-data-foundation
plan: 01
subsystem: database
tags: [sqldelight, sqlite, migration, premium, subscription, feature-gating, kmp]

# Dependency graph
requires: []
provides:
  - RepMetric table schema and SQLDelight queries for per-rep force curve storage
  - SubscriptionTier enum (FREE/PHOENIX/ELITE) with DB string conversion
  - FeatureGate utility with isEnabled() and resolveEffectiveTier()
  - RepMetricData domain model for in-memory per-rep metrics
  - iOS DriverFactory synced to schema version 13
affects: [01-02, 02-led-biofeedback, 03-rep-quality, 04-smart-suggestions]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "FeatureGate object pattern for tier-based feature access (domain utility, no DI)"
    - "RepMetric JSON-array-in-TEXT-column pattern for force curve storage"

key-files:
  created:
    - shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/12.sqm
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/RepMetrics.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/SubscriptionTier.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/FeatureGate.kt
  modified:
    - shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq
    - shared/src/iosMain/kotlin/com/devil/phoenixproject/data/local/DriverFactory.ios.kt

key-decisions:
  - "SubscriptionTier is separate from existing SubscriptionStatus (tier = feature access, status = payment state)"
  - "RepMetricData uses FloatArray/LongArray for curve data (JSON serialization deferred to Plan 02 repository layer)"
  - "Custom equals/hashCode on RepMetricData for proper array deep comparison"

patterns-established:
  - "domain/premium/ package for subscription and feature gating utilities"
  - "FeatureGate.isEnabled(feature, tier) as the canonical feature access check"

# Metrics
duration: 12min
completed: 2026-02-14
---

# Phase 1 Plan 1: Schema, Models & Feature Gate Summary

**RepMetric table with 12.sqm migration, SubscriptionTier enum, FeatureGate utility with 30-day offline grace, iOS DriverFactory v13 sync**

## Performance

- **Duration:** 12 min
- **Started:** 2026-02-14T04:56:22Z
- **Completed:** 2026-02-14T05:08:00Z
- **Tasks:** 3
- **Files modified:** 6

## Accomplishments
- RepMetric table with 32 columns for per-rep force curve and summary data, FK to WorkoutSession with CASCADE delete
- 5 SQLDelight queries for CRUD and sync operations on RepMetric
- SubscriptionTier enum (FREE/PHOENIX/ELITE) with DB string round-trip conversion
- FeatureGate with 12 premium features gated across two tiers, plus 30-day offline grace period
- iOS DriverFactory bumped to schema version 13 with RepMetric table and indexes matching .sq exactly

## Task Commits

Each task was committed atomically:

1. **Task 1: Create RepMetric table schema and migration** - `579b68b4` (feat)
2. **Task 2: Create SubscriptionTier enum and FeatureGate utility** - `23fad99a` (feat)
3. **Task 3: Sync iOS DriverFactory to schema version 13** - `9cd87fca` (feat)

## Files Created/Modified
- `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/12.sqm` - Migration creating RepMetric table with indexes
- `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq` - RepMetric table definition and 5 queries added
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/RepMetrics.kt` - RepMetricData data class with concentric/eccentric arrays and computed summaries
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/SubscriptionTier.kt` - Three-tier subscription enum
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/FeatureGate.kt` - Tier-based feature gating with offline grace
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/data/local/DriverFactory.ios.kt` - Version bump to 13, RepMetric table and indexes added

## Decisions Made
- SubscriptionTier is a separate concept from existing SubscriptionStatus (tier = feature access level, status = payment state). Both coexist intentionally.
- RepMetricData uses FloatArray/LongArray (not List) for curve data - better performance for numerical computation. Custom equals/hashCode implemented for proper deep comparison.
- JSON serialization for DB storage deferred to Plan 02 (repository layer) per plan specification.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Schema foundation complete for per-rep metrics storage
- FeatureGate ready for use by LED biofeedback, rep quality, and smart suggestions features
- Plan 02 (RepSlicer + repository layer) can now build on RepMetric table and RepMetricData model
- iOS and Android schema versions are in sync at version 13

---
*Phase: 01-data-foundation*
*Completed: 2026-02-14*

## Self-Check: PASSED
