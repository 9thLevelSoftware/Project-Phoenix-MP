# Roadmap: Project Phoenix MP

## Milestones

- ✅ **v0.4.1 Architectural Cleanup** — Phases 1-4 (shipped 2026-02-13)
- ✅ **v0.4.5 Premium Features Phase 1** — Phases 1-5 (shipped 2026-02-14)
- ✅ **v0.4.6 Biomechanics MVP** — Phases 6-8 (shipped 2026-02-15)
- ✅ **v0.4.7 Mobile Platform Features** — Phases 9-12 (shipped 2026-02-15)
- 🚧 **v0.5.0 Premium Mobile** — Phases 13-17 (in progress)

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (13.1, 13.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

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

<details>
<summary>✅ v0.4.7 Mobile Platform Features (Phases 9-12) — SHIPPED 2026-02-15</summary>

- [x] Phase 9: Infrastructure (2/2 plans) — Power fix, MetricSample index, ExerciseSignature + AssessmentResult tables
- [x] Phase 10: Strength Assessment (4/4 plans) — OLS 1RM estimation, wizard UI, navigation wiring
- [x] Phase 11: Exercise Auto-Detection (4/4 plans) — Signature extraction, classifier, bottom sheet UI, ActiveWorkoutScreen wiring
- [x] Phase 12: Mobile Replay Cards (3/3 plans) — Rep boundary detection, ForceSparkline, RepReplayCard

See `.planning/milestones/v0.4.7-*` for archived phase details.

</details>

### 🚧 v0.5.0 Premium Mobile (In Progress)

**Milestone Goal:** Add on-device CV form checking, persist biomechanics data to database, and build mobile UI components for premium features (ghost racing, RPG attributes, readiness briefing).

- [x] **Phase 13: Biomechanics Persistence** — Persist per-rep VBT, force curves, and asymmetry data to database with schema v16 (completed 2026-02-21)
- [ ] **Phase 14: CV Form Check — Domain Logic** — Exercise-specific form rules engine and joint angle models in commonMain
- [ ] **Phase 15: CV Form Check — Android Integration** — MediaPipe pose estimation, CameraX pipeline, skeleton overlay, thermal management
- [ ] **Phase 16: CV Form Check — UI, Persistence, and Feature Gating** — Camera PiP, form warnings, form score persistence, iOS stub, tier gates
- [ ] **Phase 17: Premium UI Composables** — Ghost racing overlay, RPG attribute card, pre-workout briefing with local data

## Phase Details

### Phase 13: Biomechanics Persistence
**Goal**: Users' per-rep biomechanics data survives workout completion and is available in session history
**Depends on**: Phase 12 (continues from v0.4.7 — existing BiomechanicsEngine output now gets stored)
**Requirements**: PERSIST-01, PERSIST-02, PERSIST-03, PERSIST-04, PERSIST-05
**Success Criteria** (what must be TRUE):
  1. User completes a workout and can view per-rep VBT metrics (MCV, velocity zone, velocity loss) in session history
  2. User can view per-rep force curve data (normalized curve, sticking point, strength profile) in session history
  3. User can view per-rep asymmetry data (asymmetry %, dominant side) in session history
  4. User can see set-level biomechanics summary (avg MCV, avg asymmetry, velocity loss trend) on the workout session record
  5. App launches successfully on both Android and iOS after schema migration v16 with no data loss
**Plans**: 2 plans

Plans:
- [x] 13-01-PLAN.md — Schema migration v16 + BiomechanicsRepository + persistence wiring
- [ ] 13-02-PLAN.md — Biomechanics history UI with set-level summary and per-rep drill-down

### Phase 14: CV Form Check — Domain Logic
**Goal**: Exercise-specific form rules can evaluate joint angles and produce form violations — entirely in cross-platform code with full test coverage
**Depends on**: Nothing (independent of Phase 13; can proceed in parallel after Phase 13 starts)
**Requirements**: CV-07, CV-08
**Success Criteria** (what must be TRUE):
  1. Given a set of joint angles for a squat, deadlift/RDL, overhead press, curl, or row, the form rules engine returns correct violations with severity and corrective cues
  2. Form warnings are advisory only — no code path exists that adjusts weight, stops the machine, or modifies workout parameters based on form data
**Plans**: TBD

Plans:
- [ ] 14-01: TBD

### Phase 15: CV Form Check — Android Integration
**Goal**: MediaPipe pose estimation runs on-device during an active workout without degrading BLE metric processing
**Depends on**: Phase 14 (implements against domain interfaces defined there)
**Requirements**: CV-02, CV-03, CV-09, CV-11
**Success Criteria** (what must be TRUE):
  1. Camera preview appears as a picture-in-picture overlay on the Active Workout Screen when Form Check is enabled
  2. Skeleton overlay renders tracked body landmarks on the camera feed in real-time
  3. BLE metric processing (rep counting, velocity tracking) shows no degradation when CV processing is active — no missed reps, no metric delays >50ms
  4. App does not crash in release builds with minifyEnabled=true (ProGuard/R8 keep rules validated)
**Plans**: TBD

Plans:
- [ ] 15-01: TBD
- [ ] 15-02: TBD

### Phase 16: CV Form Check — UI, Persistence, and Feature Gating
**Goal**: Users can enable form checking during workouts, receive real-time form warnings, and review form scores after sessions
**Depends on**: Phase 15 (requires working camera pipeline and pose estimation)
**Requirements**: CV-01, CV-04, CV-05, CV-06, CV-10
**Success Criteria** (what must be TRUE):
  1. User can tap "Form Check" toggle on Active Workout Screen to enable/disable CV form checking (Phoenix+ tier only)
  2. User sees real-time form warnings (visual + audio) when exercise-specific joint angle thresholds are violated during a set
  3. User can view a form score (0-100) after completing an exercise, and that score is persisted locally for future review
  4. iOS user sees "Form Check coming soon" message when tapping the Form Check toggle
  5. FREE tier user cannot access the Form Check toggle (gated behind Phoenix tier)
**Plans**: TBD

Plans:
- [ ] 16-01: TBD
- [ ] 16-02: TBD

### Phase 17: Premium UI Composables
**Goal**: Users can race their ghost, view RPG attributes, and see readiness briefings — all from locally-computed data with portal upsell hooks
**Depends on**: Phase 13 (ghost racing and RPG need persisted biomechanics data)
**Requirements**: GHOST-01, GHOST-02, GHOST-03, GHOST-04, RPG-01, RPG-02, RPG-03, RPG-04, BRIEF-01, BRIEF-02, BRIEF-03, BRIEF-04
**Success Criteria** (what must be TRUE):
  1. User can race against their best matching local session during a set, with dual vertical progress bars showing current vs. ghost cable position and per-rep AHEAD/BEHIND verdict
  2. User can view an end-of-set summary showing total velocity delta vs. the ghost session
  3. User can view five RPG attributes (Strength, Power, Stamina, Consistency, Mastery) and their auto-assigned character class on the gamification screen (Phoenix+ tier)
  4. User sees a pre-workout readiness briefing card with Green/Yellow/Red status before their first set (Elite tier), with advisory-only guidance they can dismiss
  5. Each feature displays a "Connect to Portal" or "View on Portal" deep link for full functionality upsell
**Plans**: TBD

Plans:
- [ ] 17-01: TBD
- [ ] 17-02: TBD
- [ ] 17-03: TBD

## Progress

**Execution Order:**
Phases execute in numeric order: 13 -> 14 -> 15 -> 16 -> 17
Note: Phase 14 can start in parallel with Phase 13 (no dependency). Phase 17 can start after Phase 13 completes (parallel with 14-16).

| Milestone | Phases | Plans | Status | Shipped |
|-----------|--------|-------|--------|---------|
| v0.4.1 Architectural Cleanup | 1-4 | 10 | ✅ Complete | 2026-02-13 |
| v0.4.5 Premium Features Phase 1 | 1-5 | 11 | ✅ Complete | 2026-02-14 |
| v0.4.6 Biomechanics MVP | 6-8 | 10 | ✅ Complete | 2026-02-15 |
| v0.4.7 Mobile Platform Features | 9-12 | 13 | 2/2 | Complete   | 2026-02-21 | v0.5.0 Premium Mobile | 13-17 | TBD | 🚧 In progress | - |

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 13. Biomechanics Persistence | 1/2 | In progress | - |
| 14. CV Domain Logic | 0/TBD | Not started | - |
| 15. CV Android Integration | 0/TBD | Not started | - |
| 16. CV UI + Persistence + Gating | 0/TBD | Not started | - |
| 17. Premium UI Composables | 0/TBD | Not started | - |

**Last phase number:** 17

---
*Last updated: 2026-02-20 after v0.5.0 roadmap creation*
