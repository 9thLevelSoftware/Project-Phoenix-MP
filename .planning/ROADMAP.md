# Roadmap: Project Phoenix MP

## Milestones

- ✅ **v0.4.1 Architectural Cleanup** — Phases 1-4 (shipped 2026-02-13)
- ✅ **v0.4.5 Premium Features Phase 1** — Phases 1-5 (shipped 2026-02-14)
- ✅ **v0.4.6 Biomechanics MVP** — Phases 6-8 (shipped 2026-02-15)
- ✅ **v0.4.7 Mobile Platform Features** — Phases 9-12 (shipped 2026-02-15)
- ✅ **v0.5.0 Premium Mobile** — Phases 13-15 (shipped 2026-02-27)
- 🚧 **v0.5.1 Board Polish & Premium UI** — Phases 16-22 (in progress)

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (16.1, 16.2): Urgent insertions (marked with INSERTED)

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

<details>
<summary>✅ v0.5.0 Premium Mobile (Phases 13-15) — SHIPPED 2026-02-27</summary>

- [x] Phase 13: Biomechanics Persistence (2/2 plans) — Schema v16, BiomechanicsRepository, history UI
- [x] Phase 14: CV Domain Logic (2/2 plans) — FormRulesEngine, 5 exercise rule sets, form scoring
- [x] Phase 15: CV Android Integration (3/3 plans) — MediaPipe, CameraX, FormCheckOverlay, skeleton overlay

See `.planning/milestones/v0.5.0-*` for archived phase details.

</details>

### 🚧 v0.5.1 Board Polish & Premium UI (In Progress)

**Milestone Goal:** Complete carried-over premium UI features (CV Form Check UX, ghost racing, RPG attributes, readiness briefing) and address all Board of Directors conditions (accessibility, security, UX, versioning).

- [x] **Phase 16: Foundation & Board Conditions** — FeatureGate entries, versionName, UTC fix, backup exclusion, camera rationale, iOS suppression, asset fallback (completed 2026-02-27)
- [x] **Phase 17: WCAG Accessibility** — Color-blind mode toggle, secondary visual signals on all color-coded indicators (completed 2026-02-28)
- [x] **Phase 18: HUD Customization** — Preset-based HUD page visibility with string-key persistence (completed 2026-02-28)
- [x] **Phase 19: CV Form Check UX & Persistence** — Toggle UI, real-time warnings, form score persistence, iOS stub, tier gating (completed 2026-02-28)
- [x] **Phase 20: Readiness Briefing** — ACWR engine, readiness card, InsufficientData guard, Elite tier gate (completed 2026-02-28)
- [ ] **Phase 21: RPG Attributes** — Attribute engine, character class, attribute card, schema migration v17, Phoenix tier gate
- [ ] **Phase 22: Ghost Racing** — Ghost engine, dual progress bars, rep-index sync, workout lifecycle integration

## Phase Details

### Phase 16: Foundation & Board Conditions
**Goal**: App meets all Board of Directors operational conditions and new FeatureGate entries exist for every v0.5.1 premium feature
**Depends on**: Phase 15 (continues from v0.5.0)
**Requirements**: BOARD-01, BOARD-03, BOARD-05, BOARD-06, BOARD-07, BOARD-08, BOARD-09
**Success Criteria** (what must be TRUE):
  1. versionName in androidApp/build.gradle.kts reads "0.5.1" and appears correctly in the app's About/Settings screen
  2. SmartSuggestions classifyTimeWindow() returns correct training window for a user in UTC+10 timezone (no longer misclassifies evening sessions as morning)
  3. VitruvianDatabase and sensitive preferences are excluded from Android auto-backup on both API 30 and API 31+ devices
  4. Camera permission dialog displays custom rationale text explaining that CV processing happens entirely on-device
  5. iOS PHOENIX tier upgrade prompts do not mention Form Check as a feature
  6. PoseLandmarkerHelper displays a user-facing error message instead of crashing when pose_landmarker_lite.task asset is missing
  7. FeatureGate correctly gates CV_FORM_CHECK, RPG_ATTRIBUTES, GHOST_RACING, and READINESS_BRIEFING at their assigned tiers (FREE users cannot access any of these)
**Plans**: 2 plans

Plans:
- [ ] 16-01-PLAN.md — FeatureGate v0.5.1 entries, version bump to 0.5.1, UTC timezone fix
- [ ] 16-02-PLAN.md — Backup exclusion, camera rationale, PoseLandmarker error handling, iOS platform detection

### Phase 17: WCAG Accessibility
**Goal**: All color-coded UI indicators are usable by color-blind users through secondary visual signals and an optional color-blind palette
**Depends on**: Phase 16 (FeatureGate entries must exist; foundation must be stable)
**Requirements**: BOARD-02
**Success Criteria** (what must be TRUE):
  1. User can enable a color-blind mode toggle in Settings that switches the app to a deuteranopia-safe palette (blue/orange/teal replacing green/red)
  2. Velocity zone indicators display text labels (e.g., "Explosive", "Grind") alongside color coding, visible regardless of color-blind mode
  3. Balance bar shows numeric asymmetry percentage alongside the colored bar
  4. All new composables built in Phases 18-22 consume LocalColorBlindMode and AccessibilityColors from the theme root
**Plans**: 2 plans

Plans:
- [ ] 17-01-PLAN.md — AccessibilityColors infrastructure, CompositionLocal wiring, Settings toggle
- [ ] 17-02-PLAN.md — Retrofit all composables to use AccessibilityColors, add velocity zone text labels, relocate BalanceBar percentage

### Phase 18: HUD Customization
**Goal**: Users can control which HUD pages are visible during workouts without being overwhelmed by data density
**Depends on**: Phase 17 (WCAG infrastructure must exist so HUD customization UI is accessible from the start)
**Requirements**: BOARD-04
**Success Criteria** (what must be TRUE):
  1. User can select a HUD preset (Essential, Biomechanics, Full) in Settings that controls which pages appear during workouts
  2. WorkoutHud pager only shows pages included in the selected preset (page count changes dynamically)
  3. HUD preference persists across app restarts (stored as stable string keys, not integer indices)
  4. Execution page is always visible regardless of preset selection (cannot be hidden)
**Plans**: 2 plans

Plans:
- [ ] 18-01-PLAN.md — HudPage/HudPreset domain models, preference pipeline (UserPreferences -> PreferencesManager -> SettingsManager -> MainViewModel), FakePreferencesManager fixes, SettingsManager test
- [ ] 18-02-PLAN.md — WorkoutHud dynamic pager filtering, ActiveWorkoutScreen/WorkoutUiState/WorkoutTab threading, SettingsTab preset selector UI, NavGraph wiring

### Phase 19: CV Form Check UX & Persistence
**Goal**: Users can enable form checking during workouts, see real-time form warnings, and review persisted form scores after sessions
**Depends on**: Phase 16 (FeatureGate.CV_FORM_CHECK must exist), Phase 15 (camera pipeline from v0.5.0)
**Requirements**: CV-01, CV-04, CV-05, CV-06, CV-10
**Success Criteria** (what must be TRUE):
  1. User can tap a "Form Check" toggle on the Active Workout Screen to enable/disable CV form checking (Phoenix+ tier only; FREE users see no toggle)
  2. User sees real-time visual warnings and hears audio cues when exercise-specific joint angle thresholds are violated during a set
  3. User can view a form score (0-100) after completing an exercise, and that score persists locally across app restarts
  4. iOS user sees a "Form Check coming soon" message when tapping the Form Check toggle instead of activating the camera
**Plans**: 2 plans

Plans:
- [ ] 19-01-PLAN.md — Schema migration (formScore column), HapticEvent.FORM_WARNING, assessment accumulation in ActiveSessionEngine, form score computation at set end
- [ ] 19-02-PLAN.md — Form check toggle in WorkoutHud with tier gating, FormWarningBanner, FormCheckOverlay wiring, form score in SetSummaryCard, iOS "coming soon" dialog

### Phase 20: Readiness Briefing
**Goal**: Users receive an evidence-based pre-workout readiness advisory that helps them calibrate training intensity without blocking their workout
**Depends on**: Phase 17 (WCAG color-blind signals for Green/Yellow/Red readiness card)
**Requirements**: BRIEF-01, BRIEF-02, BRIEF-03, BRIEF-04
**Success Criteria** (what must be TRUE):
  1. User with 28+ days of training history sees a readiness card with Green/Yellow/Red status and a score (0-100) before their first set
  2. User with fewer than 3 sessions in the past 14 days sees an "Insufficient Data" message instead of a misleading readiness score
  3. User can always dismiss the readiness briefing and proceed with their workout (advisory only, never blocks)
  4. Readiness briefing card displays a "Connect to Portal for full readiness model" upsell link
  5. Readiness briefing is gated to Elite tier (FREE and PHOENIX users do not see it)
**Plans**: 2 plans

Plans:
- [ ] 20-01-PLAN.md — ReadinessEngine ACWR computation (TDD), ReadinessModels domain types, data sufficiency guards, score mapping
- [ ] 20-02-PLAN.md — ReadinessBriefingCard composable, ActiveWorkoutScreen wiring with Elite tier gate, dismissible state, Portal upsell

### Phase 21: RPG Attributes
**Goal**: Users can see five computed RPG attributes and an auto-assigned character class that gamifies their training profile
**Depends on**: Phase 16 (FeatureGate.RPG_ATTRIBUTES must exist), Phase 17 (WCAG for attribute visualization)
**Requirements**: RPG-01, RPG-02, RPG-03, RPG-04
**Success Criteria** (what must be TRUE):
  1. User can view five attributes (Strength, Power, Stamina, Consistency, Mastery) on the gamification screen, each computed from their actual workout data
  2. User sees an auto-assigned character class (Powerlifter, Athlete, Ironman, Monk, or Phoenix) derived from their dominant attribute ratio
  3. Attribute card displays a "View full skill tree on Phoenix Portal" deep link
  4. RPG attributes are stored in a dedicated table (not the GamificationStats singleton) and survive app restarts
  5. RPG attribute card is gated to Phoenix+ tier (FREE users do not see it)
**Plans**: TBD

Plans:
- [ ] 21-01: TBD
- [ ] 21-02: TBD

### Phase 22: Ghost Racing
**Goal**: Users can race against their personal best during a set, with real-time visual comparison and post-set velocity delta summary
**Depends on**: Phase 18 (WorkoutHud pager must be stable), Phase 16 (FeatureGate.GHOST_RACING must exist), Phase 17 (WCAG for overlay accessibility)
**Requirements**: GHOST-01, GHOST-02, GHOST-03, GHOST-04
**Success Criteria** (what must be TRUE):
  1. User can race against their best matching local session during a set, with the ghost session pre-loaded into memory at workout start (no DB reads during active set)
  2. Two vertical progress bars show current vs. ghost cable position in real-time, synchronized by rep index (not wall-clock time)
  3. User sees a per-rep AHEAD/BEHIND verdict based on velocity comparison after each rep completes
  4. User sees an end-of-set summary showing total velocity delta vs. the ghost session
  5. Ghost racing overlay renders on the ExecutionPage as a conditional element (not a separate pager page)
**Plans**: TBD

Plans:
- [ ] 22-01: TBD
- [ ] 22-02: TBD
- [ ] 22-03: TBD

## Progress

**Execution Order:**
Phases execute in numeric order: 16 -> 17 -> 18 -> 19 -> 20 -> 21 -> 22
Note: Phase 19 (CV UX) can proceed after Phase 16 completes (independent of Phases 17-18). Phases 20 and 21 are independent of each other and can be parallelized.

| Milestone | Phases | Plans | Status | Shipped |
|-----------|--------|-------|--------|---------|
| v0.4.1 Architectural Cleanup | 1-4 | 10 | Complete | 2026-02-13 |
| v0.4.5 Premium Features Phase 1 | 1-5 | 11 | Complete | 2026-02-14 |
| v0.4.6 Biomechanics MVP | 6-8 | 10 | Complete | 2026-02-15 |
| v0.4.7 Mobile Platform Features | 9-12 | 13 | Complete | 2026-02-15 |
| v0.5.0 Premium Mobile | 13-15 | 7 | Complete | 2026-02-27 |
| v0.5.1 Board Polish & Premium UI | 16-22 | TBD | In progress | - |

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 16. Foundation & Board Conditions | 2/2 | Complete    | 2026-02-27 |
| 17. WCAG Accessibility | 2/2 | Complete    | 2026-02-28 |
| 18. HUD Customization | 2/2 | Complete    | 2026-02-28 |
| 19. CV Form Check UX & Persistence | 3/3 | Complete   | 2026-02-28 |
| 20. Readiness Briefing | 2/2 | Complete    | 2026-02-28 |
| 21. RPG Attributes | 0/TBD | Not started | - |
| 22. Ghost Racing | 0/TBD | Not started | - |

**Last phase number:** 22

---
*Last updated: 2026-02-28 after Phase 20 planning*
