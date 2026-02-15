# Requirements: Project Phoenix MP

**Defined:** 2026-02-14
**Core Value:** Users can connect to their Vitruvian trainer and execute workouts reliably on both platforms.

## v0.4.6 Requirements

Requirements for Biomechanics MVP milestone. Each maps to roadmap phases.

### VBT Engine

- [ ] **VBT-01**: App calculates Mean Concentric Velocity (MCV) for each rep from MetricSample velocity data
- [ ] **VBT-02**: App classifies each rep into velocity zone (Explosive/Fast/Moderate/Slow/Grind) based on MCV
- [ ] **VBT-03**: App tracks velocity loss percentage across reps within a set (first rep vs current)
- [ ] **VBT-04**: App projects estimated reps remaining before hitting velocity loss threshold
- [ ] **VBT-05**: App recommends stopping set when velocity loss exceeds user-configured threshold (default 20%)
- [ ] **VBT-06**: User can enable/disable velocity-based auto-stop recommendation in settings

### Force Curves

- [ ] **FORCE-01**: App constructs per-rep force-position curves from MetricSample load/position data
- [ ] **FORCE-02**: App normalizes force curves to 0-100% ROM for rep-to-rep comparison
- [ ] **FORCE-03**: App detects sticking point (minimum force position) in concentric phase
- [ ] **FORCE-04**: App classifies strength profile (Ascending/Descending/Bell-shaped/Flat) per set
- [ ] **FORCE-05**: User sees mini force curve visualization on HUD during workout
- [ ] **FORCE-06**: User sees full force curve with sticking point marker on set summary

### Asymmetry Detection

- [ ] **ASYM-01**: App calculates per-rep cable asymmetry percentage from loadA/loadB data
- [ ] **ASYM-02**: App identifies dominant cable side (A or B) for each rep
- [ ] **ASYM-03**: User sees real-time L/R balance bar on HUD during workout
- [ ] **ASYM-04**: Balance bar color indicates asymmetry severity (green <10%, yellow 10-15%, red >15%)
- [ ] **ASYM-05**: App triggers visual alert when asymmetry exceeds threshold for N consecutive reps
- [ ] **ASYM-06**: User sees asymmetry summary (avg, max, trend) on set completion

### HUD Integration

- [ ] **HUD-01**: Velocity number displays on WorkoutHud Metrics page with zone color
- [ ] **HUD-02**: Velocity loss percentage displays after rep 2
- [ ] **HUD-03**: L/R balance bar displays below existing cable position bars
- [ ] **HUD-04**: Force curve mini-graph displays at bottom of Metrics page (tap to expand)
- [ ] **HUD-05**: All biomechanics HUD elements respect Phoenix tier gating

### Set Summary

- [ ] **SUM-01**: Velocity card shows avg MCV, peak velocity, velocity loss %, zone distribution
- [ ] **SUM-02**: Force curve card shows averaged concentric curve with sticking point annotation
- [ ] **SUM-03**: Asymmetry card shows avg asymmetry %, dominant side, trend direction
- [ ] **SUM-04**: Strength profile badge displays below force curve (Ascending/Descending/Bell/Flat)
- [ ] **SUM-05**: All biomechanics summary cards respect Phoenix tier gating

### Data & Infrastructure

- [ ] **DATA-01**: Rep boundary timestamps are captured from RepEvent stream for MetricSample segmentation
- [ ] **DATA-02**: BiomechanicsEngine processes MetricSamples per-rep and exposes results via StateFlow
- [ ] **DATA-03**: Biomechanics computation runs on Dispatchers.Default to avoid UI thread blocking
- [ ] **DATA-04**: Raw data capture is unconditional; only UI display is tier-gated

## Future Requirements

Deferred to later milestones (per Spec 01 Phases 4-6).

### Persistence (v0.5.0+)

- **PERSIST-01**: Biomechanics summary columns added to WorkoutSession table
- **PERSIST-02**: Historical biomechanics viewable in workout history
- **PERSIST-03**: Load-Velocity Profile built from cross-session first-rep data

### Portal Integration (v0.6.0+)

- **PORTAL-01**: Force curve data included in sync API payload
- **PORTAL-02**: Portal force analysis tab shows real per-rep data
- **PORTAL-03**: Portal asymmetry visualization component

### Elite Coaching (v0.6.0+)

- **ELITE-01**: Velocity-based 1RM prediction from Load-Velocity Profile
- **ELITE-02**: Sticking point exercise suggestions
- **ELITE-03**: Asymmetry correction recommendations

## Out of Scope

| Feature | Reason |
|---------|--------|
| Portal integration | No backend sync infrastructure yet (v0.6.0) |
| Load-Velocity Profile | Requires cross-session persistence (v0.5.0+) |
| Elite coaching suggestions | Requires historical analysis (v0.6.0+) |
| Heuristic data reinterpretation | Separate task, not blocking for MVP |
| Auto-stop actually stopping set | Recommend only, user controls stopping |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| VBT-01 | Phase 6 | Pending |
| VBT-02 | Phase 6 | Pending |
| VBT-03 | Phase 6 | Pending |
| VBT-04 | Phase 6 | Pending |
| VBT-05 | Phase 6 | Pending |
| VBT-06 | Phase 6 | Pending |
| FORCE-01 | Phase 6 | Pending |
| FORCE-02 | Phase 6 | Pending |
| FORCE-03 | Phase 6 | Pending |
| FORCE-04 | Phase 6 | Pending |
| FORCE-05 | Phase 7 | Pending |
| FORCE-06 | Phase 8 | Pending |
| ASYM-01 | Phase 6 | Pending |
| ASYM-02 | Phase 6 | Pending |
| ASYM-03 | Phase 7 | Pending |
| ASYM-04 | Phase 7 | Pending |
| ASYM-05 | Phase 7 | Pending |
| ASYM-06 | Phase 8 | Pending |
| HUD-01 | Phase 7 | Pending |
| HUD-02 | Phase 7 | Pending |
| HUD-03 | Phase 7 | Pending |
| HUD-04 | Phase 7 | Pending |
| HUD-05 | Phase 7 | Pending |
| SUM-01 | Phase 8 | Pending |
| SUM-02 | Phase 8 | Pending |
| SUM-03 | Phase 8 | Pending |
| SUM-04 | Phase 8 | Pending |
| SUM-05 | Phase 8 | Pending |
| DATA-01 | Phase 6 | Pending |
| DATA-02 | Phase 6 | Pending |
| DATA-03 | Phase 6 | Pending |
| DATA-04 | Phase 6 | Pending |

**Coverage:**
- v0.4.6 requirements: 29 total
- Mapped to phases: 29
- Unmapped: 0 ✓

---
*Requirements defined: 2026-02-14*
*Last updated: 2026-02-14 after initial definition*
