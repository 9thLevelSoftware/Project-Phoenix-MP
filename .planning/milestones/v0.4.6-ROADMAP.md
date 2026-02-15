# Roadmap: Project Phoenix MP

## Milestones

- ✅ **v0.4.1 Architectural Cleanup** — Phases 1-4 (shipped 2026-02-13)
- ✅ **v0.4.5 Premium Features Phase 1** — Phases 1-5 (shipped 2026-02-14)
- ✅ **v0.4.6 Biomechanics MVP** — Phases 6-8 (shipped 2026-02-15)

## Phases

<details>
<summary>✅ v0.4.1 Architectural Cleanup (Phases 1-4) — SHIPPED 2026-02-13</summary>

See `.planning/milestones/v0.4.1-*` for archived phase details.

</details>

<details>
<summary>✅ v0.4.5 Premium Features Phase 1 (Phases 1-5) — SHIPPED 2026-02-14</summary>

- [x] Phase 1: Data Foundation (2/2 plans) — RepMetric schema, SubscriptionTier, FeatureGate
- [x] Phase 2: LED Biofeedback (2/2 plans) — Velocity-zone LEDs, PR celebration, settings toggle
- [x] Phase 3: Rep Quality Scoring (3/3 plans) — 4-component algorithm, HUD indicator, sparkline
- [x] Phase 4: Smart Suggestions (3/3 plans) — Volume, balance, neglect, plateau, time-of-day
- [x] Phase 5: RepMetric Persistence (1/1 plan) — Gap closure, per-rep force curve storage

See `.planning/milestones/v0.4.5-*` for archived phase details.

</details>

### ✅ v0.4.6 Biomechanics MVP — SHIPPED 2026-02-15

**Milestone Goal:** Transform raw BLE telemetry into actionable training insights with real-time velocity tracking, force curve analysis, and bilateral asymmetry detection.

- [x] **Phase 6: Core Engine** — VBT algorithms, force curve construction, asymmetry calculation, data infrastructure (2026-02-15)
- [x] **Phase 7: HUD Integration** — Real-time biomechanics display during workouts (2026-02-15)
- [x] **Phase 8: Set Summary** — Post-set biomechanics visualization cards (2026-02-15)

## Phase Details

### Phase 6: Core Engine
**Goal**: BiomechanicsEngine processes per-rep MetricSamples and produces velocity metrics, force curves, and asymmetry data accessible via StateFlow
**Depends on**: v0.4.5 (RepMetric persistence, MetricSample infrastructure)
**Requirements**: VBT-01, VBT-02, VBT-03, VBT-04, VBT-05, VBT-06, FORCE-01, FORCE-02, FORCE-03, FORCE-04, ASYM-01, ASYM-02, DATA-01, DATA-02, DATA-03, DATA-04
**Success Criteria** (what must be TRUE):
  1. After completing a rep, the app exposes that rep's Mean Concentric Velocity and velocity zone classification (Explosive/Fast/Moderate/Slow/Grind) via StateFlow
  2. After rep 2+, the app calculates velocity loss percentage relative to first rep, projects remaining reps, and recommends stopping when loss exceeds threshold
  3. After completing a rep, the app exposes a normalized force-position curve (0-100% ROM) with detected sticking point and strength profile classification
  4. After completing a rep, the app exposes cable asymmetry percentage and dominant side derived from loadA/loadB data
  5. All biomechanics computation runs on Dispatchers.Default (not main thread) and raw data capture is unconditional regardless of subscription tier
**Plans**: 4 plans in 2 waves

Plans:
- [x] 06-01-PLAN.md — BiomechanicsEngine infrastructure, domain models, rep boundary capture, StateFlow exposure (Wave 1)
- [x] 06-02-PLAN.md — VBT engine: MCV, velocity zones, velocity loss, rep projection, auto-stop (Wave 2, TDD)
- [x] 06-03-PLAN.md — Force curve engine: construction, normalization, sticking point, strength profile (Wave 2, TDD)
- [x] 06-04-PLAN.md — Asymmetry engine: per-rep asymmetry percentage, dominant side (Wave 2, TDD)

### Phase 7: HUD Integration
**Goal**: Users see real-time biomechanics feedback on the workout HUD during exercise execution
**Depends on**: Phase 6
**Requirements**: HUD-01, HUD-02, HUD-03, HUD-04, HUD-05, FORCE-05, ASYM-03, ASYM-04, ASYM-05
**Success Criteria** (what must be TRUE):
  1. User sees current rep velocity number on the Metrics page, color-coded by velocity zone
  2. After rep 2, user sees velocity loss percentage displayed on the Metrics page
  3. User sees L/R balance bar below cable position bars, color-coded by severity (green <10%, yellow 10-15%, red >15%), with visual alert on consecutive high-asymmetry reps
  4. User sees mini force curve graph at bottom of Metrics page with tap-to-expand interaction
  5. All biomechanics HUD elements are hidden for users below Phoenix subscription tier
**Plans**: 3 plans in 3 waves

Plans:
- [x] 07-01-PLAN.md — Data plumbing + velocity HUD on StatsPage: MCV display, zone color, velocity loss (Wave 1)
- [x] 07-02-PLAN.md — Balance bar and asymmetry alerts: L/R bar, severity colors, consecutive rep alert (Wave 2)
- [x] 07-03-PLAN.md — Force curve mini-graph with tap-to-expand and Phoenix tier gating for all biomechanics (Wave 3)

### Phase 8: Set Summary
**Goal**: Users see comprehensive biomechanics analysis cards after completing a set
**Depends on**: Phase 6 (engine data), Phase 7 (HUD patterns and tier gating approach)
**Requirements**: SUM-01, SUM-02, SUM-03, SUM-04, SUM-05, FORCE-06, ASYM-06
**Success Criteria** (what must be TRUE):
  1. After completing a set, user sees velocity card showing avg MCV, peak velocity, velocity loss %, and zone distribution
  2. After completing a set, user sees force curve card with averaged concentric curve, sticking point annotation, and strength profile badge (Ascending/Descending/Bell/Flat)
  3. After completing a set, user sees asymmetry card showing avg asymmetry %, dominant side, and trend direction
  4. All biomechanics summary cards are hidden for users below Phoenix subscription tier
**Plans**: 3 plans in 2 waves

Plans:
- [x] 08-01-PLAN.md — Data plumbing + velocity summary card: BiomechanicsSetSummary wiring, averaged force curve, velocity card (Wave 1)
- [x] 08-02-PLAN.md — Force curve summary card: averaged curve with sticking point, strength profile badge (Wave 2)
- [x] 08-03-PLAN.md — Asymmetry summary card and Phoenix tier gating for all biomechanics cards (Wave 2)

## Progress

**Execution Order:** Phase 6 -> Phase 7 -> Phase 8

| Phase | Milestone | Plans Complete | Status | Completed |
|-------|-----------|----------------|--------|-----------|
| 1-4 | v0.4.1 | 10/10 | Complete | 2026-02-13 |
| 1-5 | v0.4.5 | 11/11 | Complete | 2026-02-14 |
| 6. Core Engine | v0.4.6 | 4/4 | Complete | 2026-02-15 |
| 7. HUD Integration | v0.4.6 | 3/3 | Complete | 2026-02-15 |
| 8. Set Summary | v0.4.6 | 3/3 | Complete | 2026-02-15 |

---
*Last updated: 2026-02-15 after Phase 8 execution complete — v0.4.6 SHIPPED*
