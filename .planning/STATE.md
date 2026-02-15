# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-14)

**Core value:** Users can connect to their Vitruvian trainer and execute workouts reliably on both platforms.
**Current focus:** Phase 8 — Set Summary (v0.4.6 Biomechanics MVP)

## Current Position

Phase: 8 of 8 (Set Summary)
Plan: 3 of 3 in current phase (3 complete)
Status: Phase Complete
Last activity: 2026-02-15 — Plan 08-03 (Asymmetry Card and Tier Gating) complete

Progress: [██████████] 100% (10/10 plans)

## Performance Metrics

**Velocity:**
- Total plans completed: 21 (10 from v0.4.1 + 11 from v0.4.5)
- v0.4.5 execution time: ~3 hours total (11 plans)
- Average plan duration: ~16 minutes

**By Milestone:**

| Milestone | Phases | Plans | Duration | Notes |
|-----------|--------|-------|----------|-------|
| v0.4.1 | 4 | 10 | - | Pre-metrics |
| v0.4.5 | 5 | 11 | ~3h | 59 commits, +1,832 LOC |
| v0.4.6 | 3 | 10 | - | Complete (10/10) |

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Key decisions affecting v0.4.6:

- Velocity thresholds 5/30/60 mm/s (hardware calibrated, 5x lower than spec)
- Data capture for all tiers, gating at UI/feature level only (GATE-04)
- Stateless engine pattern (pure functions, injectable time, StateFlow exposure)
- BiomechanicsVelocityZone thresholds 250/500/750/1000 mm/s for MCV classification (06-01)
- Concentric phase detection by positive velocity with first-half fallback (06-01)
- MCV = average(max(abs(velocityA), abs(velocityB))) per sample (06-02)
- Velocity loss clamped 0-100%, rep projection capped at 99 (06-02)
- 101-point ROM normalization for force curves (06-03)
- Sticking point excludes first/last 5% ROM (transition noise) (06-03)
- 15% threshold for strength profile classification (06-03)
- Asymmetry formula: abs(avgLoadA - avgLoadB) / max(avgLoadA, avgLoadB) * 100 (06-04)
- Asymmetry below 2% classified as BALANCED (measurement noise threshold) (06-04)
- Velocity card above LOAD card on StatsPage for prominence (07-01)
- Integer arithmetic for formatMcv() in KMP commonMain (no String.format) (07-01)
- No tier gating on biomechanics data in plumbing layer (Plan 03 handles gating) (07-01)
- InfiniteTransition created unconditionally for Compose call-site stability (07-02)
- Balance bar 70% width, bottom-aligned 24dp above pager dots (07-02)
- Single upstream gate (gatedBiomechanicsResult) nulls all biomechanics for free tier (07-03)
- AlertDialog for expanded force curve overlay (consistent Material3 pattern) (07-03)
- Element-wise averaging of 101-point force curves for set-level avgForceCurve (08-01)
- repNumber=0 convention for set-level averaged force curve (08-01)
- Velocity loss color coding: green <10%, orange 10-20%, red >=20% (08-01)
- Interior min force excludes first/last 5% ROM for stats display (08-02)
- Strength profile badge uses secondaryContainer color for visual distinction (08-02)
- Asymmetry trend: 2% threshold between first-half and second-half rep averages (08-03)
- Single upstream gate on SetSummary.biomechanicsSummary hides all biomechanics cards for free tier (08-03)

### Pending Todos

None.

### Blockers/Concerns

None.

## Session Continuity

Last session: 2026-02-15
Stopped at: Completed 08-03-PLAN.md (Asymmetry Card and Tier Gating) -- Phase 8 COMPLETE
Resume file: None
Next action: v0.4.6 milestone complete -- all 10 plans across 3 phases executed
