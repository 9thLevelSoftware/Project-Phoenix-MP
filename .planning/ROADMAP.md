# Roadmap: Project Phoenix MP

## Milestones

- v0.4.1 Architectural Cleanup (shipped 2026-02-13) — Phases 1-4
- v0.4.5 Premium Features Phase 1 (in progress) — Phases 1-4

<details>
<summary>v0.4.1 Architectural Cleanup - SHIPPED 2026-02-13</summary>

See `.planning/milestones/v0.4.1-*` for archived phase details.

</details>

### v0.4.5 Premium Features Phase 1 (In Progress)

**Milestone Goal:** Ship the first premium features (LED biofeedback, rep quality scoring, smart suggestions) with proper data foundation and subscription gating.

## Phases

- [x] **Phase 1: Data Foundation** - RepMetric table, subscription tier, FeatureGate, migration v13 ✓ 2026-02-14
- [x] **Phase 2: LED Biofeedback** - Velocity-zone LED control with mode-specific feedback ✓ 2026-02-14
- [x] **Phase 3: Rep Quality Scoring** - Per-rep quality scores with HUD and set summaries ✓ 2026-02-14
- [ ] **Phase 4: Smart Suggestions** - Volume tracking, balance analysis, plateau detection

## Phase Details

### Phase 1: Data Foundation
**Goal**: App has the storage, schema, and gating infrastructure that all premium features depend on
**Depends on**: v0.4.1 (architectural cleanup complete)
**Requirements**: DATA-01, DATA-02, DATA-03, DATA-04, DATA-05, GATE-04
**Success Criteria** (what must be TRUE):
  1. Per-rep metric data (position, velocity, load curves) persists to database during a workout
  2. User's subscription tier is stored and can be queried from any feature module
  3. FeatureGate correctly returns enabled/disabled for features based on subscription tier
  4. Database migrates cleanly from v12 to v13 on both Android and iOS without data loss
  5. Raw metric data is captured for all users regardless of tier (gating happens at feature UI only)
**Plans**: 2 plans

Plans:
- [x] 01-01-PLAN.md -- Schema, migration, domain models, FeatureGate, iOS DriverFactory sync ✓
- [x] 01-02-PLAN.md -- RepMetricRepository wiring, DI registration, FeatureGate + repository tests ✓

### Phase 2: LED Biofeedback
**Goal**: Users see real-time LED color feedback on the machine during workouts based on their performance
**Depends on**: Phase 1 (FeatureGate and subscription tier required)
**Requirements**: LED-01, LED-02, LED-03, LED-04, LED-05, LED-06, LED-07, GATE-01
**Success Criteria** (what must be TRUE):
  1. Machine LEDs change color based on velocity zone (e.g., green for optimal, red for too slow) during a set
  2. LED color transitions are smooth without visible flicker even during rapid velocity changes
  3. TUT/Echo modes show mode-specific feedback (tempo guide and load matching respectively)
  4. User can toggle LED biofeedback on/off in settings, and the toggle is hidden for Free tier users
  5. LEDs show blue during rest periods and fire a celebration flash on PR achievement
**Plans**: 2 plans

Plans:
- [x] 02-01-PLAN.md -- VelocityZone enum, LedFeedbackController engine, RunningAverage utility, unit tests ✓
- [x] 02-02-PLAN.md -- Preferences, Settings UI with tier gating, ActiveSessionEngine integration, hardware verification ✓

### Phase 3: Rep Quality Scoring
**Goal**: Users receive meaningful per-rep quality feedback during workouts and set summaries
**Depends on**: Phase 1 (RepMetric data capture required for scoring inputs)
**Requirements**: QUAL-01, QUAL-02, QUAL-03, QUAL-04, QUAL-05, QUAL-06, QUAL-07, QUAL-08, QUAL-09, GATE-02
**Success Criteria** (what must be TRUE):
  1. Each rep displays a quality score (0-100) on the workout HUD during the set
  2. Set summary shows average, best, and worst rep quality with visible quality trend indicator
  3. Quality score reflects four distinct components (ROM, velocity, eccentric control, smoothness)
  4. Form Master badges are awarded for quality achievements, visible only to Phoenix+ tier users
  5. Free tier users do not see quality scores or badges (feature gated to Phoenix+)
**Plans**: 3 plans

Plans:
- [x] 03-01-PLAN.md -- RepQualityScorer TDD engine: 4-component scoring algorithm with full test coverage ✓
- [x] 03-02-PLAN.md -- HUD integration: wire scorer into ActiveSessionEngine, per-rep quality indicator on WorkoutTab ✓
- [x] 03-03-PLAN.md -- Set summary quality section (sparkline, radar chart, trend), Form Master badges ✓

### Phase 4: Smart Suggestions
**Goal**: Users receive actionable training insights that help them train more effectively
**Depends on**: Phase 1 (volume tracking depends on data foundation)
**Requirements**: SUGG-01, SUGG-02, SUGG-03, SUGG-04, SUGG-05, SUGG-06, GATE-03
**Success Criteria** (what must be TRUE):
  1. App shows weekly volume breakdown per muscle group (sets, reps, total kg)
  2. Push/pull/legs balance analysis surfaces imbalances with corrective suggestions
  3. User receives prompts for neglected exercises (>14 days) and stalled exercises (plateau detection)
  4. Time-of-day analysis is available for Elite tier users showing optimal training windows
  5. All smart suggestion features are hidden from non-Elite users
**Plans**: TBD

Plans:
- [ ] 04-01: TBD
- [ ] 04-02: TBD

## Progress

**Execution Order:** 1 -> 2 -> 3 -> 4
(Phases 2 and 3 both depend on Phase 1 but LED ships first per priority order; Phase 4 last.)

| Phase | Milestone | Plans Complete | Status | Completed |
|-------|-----------|----------------|--------|-----------|
| 1. Data Foundation | v0.4.5 | 2/2 | ✓ Complete | 2026-02-14 |
| 2. LED Biofeedback | v0.4.5 | 2/2 | ✓ Complete | 2026-02-14 |
| 3. Rep Quality Scoring | v0.4.5 | 3/3 | ✓ Complete | 2026-02-14 |
| 4. Smart Suggestions | v0.4.5 | 0/TBD | Not started | - |
