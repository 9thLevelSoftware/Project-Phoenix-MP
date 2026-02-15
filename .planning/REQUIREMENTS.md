# Requirements: Project Phoenix MP v0.4.7

**Defined:** 2026-02-14
**Core Value:** Users can connect to their Vitruvian trainer and execute workouts reliably on both platforms.

## v0.4.7 Requirements

Requirements for v0.4.7 Mobile Platform Features milestone. Implements Spec 04 mobile portions.

### Strength Assessment (Phoenix tier)

- [ ] **ASSESS-01**: User can start guided strength assessment from profile/onboarding
- [ ] **ASSESS-02**: User sees video instruction for each exercise before testing
- [ ] **ASSESS-03**: User performs 3-5 reps at progressive weights with velocity feedback
- [ ] **ASSESS-04**: System estimates 1RM using load-velocity linear regression (min 2 data points)
- [ ] **ASSESS-05**: User can accept or manually override estimated 1RM
- [ ] **ASSESS-06**: Results saved to Exercise.one_rep_max_kg with AssessmentResult history
- [ ] **ASSESS-07**: Assessment stored as WorkoutSession with `routineName = "__ASSESSMENT__"` marker

### Exercise Auto-Detection (Elite tier)

- [ ] **DETECT-01**: System extracts ExerciseSignature from first 3-5 reps (ROM range, rep duration, load symmetry, velocity profile, cable usage)
- [ ] **DETECT-02**: Rule-based classifier suggests exercise category and specific exercise with confidence %
- [ ] **DETECT-03**: User confirms or selects different exercise via non-blocking bottom sheet
- [ ] **DETECT-04**: Confirmed signatures stored in ExerciseSignature table for future matching
- [ ] **DETECT-05**: History matching uses weighted similarity (ROM 40%, duration 20%, symmetry 25%, shape 15%)
- [ ] **DETECT-06**: Signature evolution uses exponential moving average (alpha=0.3) on confirmation

### Mobile Replay Cards (Phoenix tier)

- [ ] **REPLAY-01**: Session detail screen shows scrollable list of rep cards
- [ ] **REPLAY-02**: Each rep card displays mini force curve sparkline (Canvas-based)
- [ ] **REPLAY-03**: Rep cards show peak force (kg), concentric duration (s), eccentric duration (s)
- [ ] **REPLAY-04**: Rep boundary detection from position valleys with smoothing and minimum prominence

### Infrastructure

- [ ] **INFRA-01**: Fix power calculation to use loadA + loadB for dual-cable exercises
- [ ] **INFRA-02**: Add MetricSample index on sessionId for query performance
- [ ] **INFRA-03**: ExerciseSignature table schema (SQLDelight migration)
- [ ] **INFRA-04**: AssessmentResult table schema (SQLDelight migration)

## Future Requirements

Deferred to v0.5.0+ (requires backend sync infrastructure).

### Portal Replay (Spec 04.2)

- **PORTAL-01**: Animated timeline view with position/load dual-axis chart
- **PORTAL-02**: Rep isolation view with single-rep force curves
- **PORTAL-03**: Session heatmap showing intensity distribution
- **PORTAL-04**: Session comparison (side-by-side same exercise)
- **PORTAL-05**: CSV/JSON export of metric data
- **PORTAL-06**: MetricSampleBatchSyncDto for session metric upload

## Out of Scope

| Feature | Reason |
|---------|--------|
| Portal replay UI | Requires Spec 05 backend sync (no Supabase infrastructure) |
| Metric sync to cloud | Deferred to v0.5.5 auth migration |
| Auto-detection ML model | Spec explicitly calls for rule-based heuristics, not ML |
| Assessment re-assessment prompts | Nice-to-have, defer to polish pass |
| Assessment monthly nudge | Requires notification infrastructure |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| INFRA-01 | Phase 9 | Pending |
| INFRA-02 | Phase 9 | Pending |
| INFRA-03 | Phase 9 | Pending |
| INFRA-04 | Phase 9 | Pending |
| ASSESS-01 | Phase 10 | Pending |
| ASSESS-02 | Phase 10 | Pending |
| ASSESS-03 | Phase 10 | Pending |
| ASSESS-04 | Phase 10 | Pending |
| ASSESS-05 | Phase 10 | Pending |
| ASSESS-06 | Phase 10 | Pending |
| ASSESS-07 | Phase 10 | Pending |
| DETECT-01 | Phase 11 | Pending |
| DETECT-02 | Phase 11 | Pending |
| DETECT-03 | Phase 11 | Pending |
| DETECT-04 | Phase 11 | Pending |
| DETECT-05 | Phase 11 | Pending |
| DETECT-06 | Phase 11 | Pending |
| REPLAY-01 | Phase 12 | Pending |
| REPLAY-02 | Phase 12 | Pending |
| REPLAY-03 | Phase 12 | Pending |
| REPLAY-04 | Phase 12 | Pending |

**Coverage:**
- v0.4.7 requirements: 21 total
- Mapped to phases: 21
- Unmapped: 0

---
*Requirements defined: 2026-02-14*
*Last updated: 2026-02-15 after roadmap creation*
