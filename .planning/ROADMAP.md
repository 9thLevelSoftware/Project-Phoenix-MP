# Roadmap: Project Phoenix MP

## Milestones

- ✅ **v0.4.1 Architectural Cleanup** — Phases 1-4 (shipped 2026-02-13)
- ✅ **v0.4.5 Premium Features Phase 1** — Phases 1-5 (shipped 2026-02-14)
- ✅ **v0.4.6 Biomechanics MVP** — Phases 6-8 (shipped 2026-02-15)
- 🚧 **v0.4.7 Mobile Platform Features** — Phases 9-12 (in progress)

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

<details>
<summary>✅ v0.4.6 Biomechanics MVP (Phases 6-8) — SHIPPED 2026-02-15</summary>

- [x] Phase 6: Core Engine (4/4 plans) — BiomechanicsEngine with VBT, force curves, asymmetry
- [x] Phase 7: HUD Integration (3/3 plans) — Real-time velocity, balance bar, force curve mini-graph
- [x] Phase 8: Set Summary (3/3 plans) — Biomechanics cards with tier gating

See `.planning/milestones/v0.4.6-*` for archived phase details.

</details>

### 🚧 v0.4.7 Mobile Platform Features (In Progress)

**Milestone Goal:** Transform the app into an intelligent training platform with VBT-based strength assessment, exercise auto-detection, and mobile session replay.

#### Phase 9: Infrastructure ✓ COMPLETE (2026-02-15)
**Goal**: Data foundations and bug fixes that unblock all feature phases
**Depends on**: Nothing (first phase in milestone)
**Requirements**: INFRA-01, INFRA-02, INFRA-03, INFRA-04
**Success Criteria** (what must be TRUE):
  1. ✓ Power values displayed during dual-cable exercises reflect combined load (loadA + loadB)
  2. ✓ Session detail queries for sessions with 100+ metric samples return without noticeable delay
  3. ✓ ExerciseSignature table exists in database with columns for ROM, duration, symmetry, velocity profile, and cable usage
  4. ✓ AssessmentResult table exists in database with columns for exercise reference, estimated 1RM, load-velocity data points, and timestamp
**Plans**: 2 plans in 2 waves — **COMPLETE**

Plans:
- [x] 09-01-PLAN.md — Fix dual-cable power calculation and add MetricSample sessionId index (Wave 1)
- [x] 09-02-PLAN.md — Add ExerciseSignature and AssessmentResult table schemas (Wave 2)

#### Phase 10: Strength Assessment
**Goal**: Users can determine their 1RM for any exercise through a guided, velocity-based assessment flow
**Depends on**: Phase 9 (requires AssessmentResult table)
**Requirements**: ASSESS-01, ASSESS-02, ASSESS-03, ASSESS-04, ASSESS-05, ASSESS-06, ASSESS-07
**Success Criteria** (what must be TRUE):
  1. User can launch a strength assessment from their profile screen and select an exercise to test
  2. User sees a video demonstration and instructions before performing assessment reps
  3. User performs progressive-weight sets while seeing real-time velocity feedback, and the system identifies when velocity drops below threshold
  4. User is presented with an estimated 1RM derived from load-velocity regression and can accept it or enter a manual override
  5. Completed assessment is saved as a session (with `__ASSESSMENT__` marker) and the exercise's 1RM value is updated in the exercise record
**Plans**: 4 plans in 3 waves

Plans:
- [ ] 10-01-PLAN.md — Assessment domain engine: load-velocity regression, threshold detection, TDD (Wave 1)
- [ ] 10-02-PLAN.md — Assessment repository layer and navigation route setup (Wave 1)
- [ ] 10-03-PLAN.md — Assessment wizard ViewModel and multi-step UI screen (Wave 2)
- [ ] 10-04-PLAN.md — Navigation wiring, entry points, and human verification (Wave 3)

#### Phase 11: Exercise Auto-Detection
**Goal**: The app identifies what exercise the user is performing based on movement signature and learns from corrections
**Depends on**: Phase 9 (requires ExerciseSignature table)
**Requirements**: DETECT-01, DETECT-02, DETECT-03, DETECT-04, DETECT-05, DETECT-06
**Success Criteria** (what must be TRUE):
  1. After the first 3-5 reps of a set, the system suggests an exercise name with confidence percentage via a non-blocking bottom sheet
  2. User can confirm the suggestion or select a different exercise, and the interaction does not interrupt the workout
  3. Confirmed exercise signatures are stored and used to improve future suggestions for that user
  4. Repeat performances of the same exercise produce higher confidence scores over time as the signature history grows
**Plans**: TBD

Plans:
- [ ] 11-01: Signature extraction engine (ROM, duration, symmetry, velocity profile features)
- [ ] 11-02: Rule-based classifier and history matching with weighted similarity
- [ ] 11-03: Detection UI (bottom sheet, confirmation flow, signature evolution)

#### Phase 12: Mobile Replay Cards
**Goal**: Users can review any past session with per-rep detail including force curves and timing breakdowns
**Depends on**: Phase 9 (requires MetricSample index for query performance, correct power values)
**Requirements**: REPLAY-01, REPLAY-02, REPLAY-03, REPLAY-04
**Success Criteria** (what must be TRUE):
  1. Session detail screen displays a scrollable list of rep cards showing each rep in the set
  2. Each rep card renders a mini force curve sparkline drawn with Canvas
  3. Rep cards display peak force in kg plus concentric and eccentric durations in seconds
  4. Rep boundaries are detected from position data using valley detection with smoothing, producing accurate rep isolation
**Plans**: TBD

Plans:
- [ ] 12-01: Rep boundary detection from position valleys with smoothing
- [ ] 12-02: Replay card UI (scrollable rep list, force curve sparkline, metric display)

## Progress

| Milestone | Phases | Plans | Status | Shipped |
|-----------|--------|-------|--------|---------|
| v0.4.1 Architectural Cleanup | 1-4 | 10 | ✅ Complete | 2026-02-13 |
| v0.4.5 Premium Features Phase 1 | 1-5 | 11 | ✅ Complete | 2026-02-14 |
| v0.4.6 Biomechanics MVP | 6-8 | 10 | ✅ Complete | 2026-02-15 |
| v0.4.7 Mobile Platform Features | 9-12 | 6/14 | 🚧 In progress | - |

**Last phase number:** 12

---
*Last updated: 2026-02-15 after Phase 10 planning*
