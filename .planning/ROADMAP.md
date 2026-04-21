# Roadmap: Project Phoenix MP

## Milestones

- v0.4.1 Architectural Cleanup — Phases 1-4 (shipped 2026-02-13)
- v0.4.5 Premium Features Phase 1 — Phases 1-5 (shipped 2026-02-14)
- v0.4.6 Biomechanics MVP — Phases 6-8 (shipped 2026-02-15)
- v0.4.7 Mobile Platform Features — Phases 9-12 (shipped 2026-02-15)
- v0.5.0 Premium Mobile — Phases 13-15 (shipped 2026-02-27)
- v0.5.1 Board Polish & Premium UI — Phases 16-22 (shipped 2026-02-28)
- v0.6.0 Portal Sync Compatibility — Phases 23-28 (shipped 2026-03-02)
- v0.7.0 MVP Cloud Sync — Phases 29-31 (shipped 2026-03-15)
- v0.8.0 Beta Readiness — Phases 32-36 (shipped 2026-03-24)
- **v0.9.0 Enhancement Sweep** — Phases 37-44 (active)

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (16.1, 16.2): Urgent insertions (marked with INSERTED)

<details>
<summary>v0.4.1 - v0.8.0 (Phases 1-36) — SHIPPED</summary>

See `.planning/milestones/` for archived phase details.

</details>

### v0.9.0 Enhancement Sweep (Phases 37-44) — ACTIVE

- [x] Phase 37: Foundation (#323 weight display)
- [x] Phase 38: Weight-Dependent Features (#266, #337)
- [x] Phase 39: Routine Cluster (#365, #307)
- [ ] Phase 40: Analytics (#229, #225)
- [ ] Phase 41: Quick Wins (#190, #228, #100)
- [ ] Phase 42: Platform (#363)
- [ ] Phase 43: Advanced VBT (#313)
- [ ] Phase 44: Integration Validation

## Phase Details

### Phase 37: Foundation
**Goal**: Fix weight display across all surfaces — per-cable → total weight
**Requirements**: FOUND-01 (#323)
**Recommended Agents**: Senior Developer, Frontend Developer, QA Verification Specialist
**Success Criteria**:
- [ ] All weight displays show total weight (per-cable × 2)
- [ ] Unit preference (kg/lbs) respected
- [ ] Single-cable exercises handled correctly
- [ ] No double-multiplication regressions
- [ ] Insights tab, history, set summary all updated
**Plans**: 2

### Phase 38: Weight-Dependent Features
**Goal**: Implement granular weight adjustments and bulk routine weight changes
**Requirements**: WEIGHT-01 (#266), WEIGHT-02 (#337)
**Recommended Agents**: Senior Developer, UX Architect, QA Verification Specialist
**Success Criteria**:
- [ ] Weight wheel supports 0.1lb/0.1kg increments
- [ ] User preference for min increment stored
- [ ] Bulk weight adjust by % or absolute in routine editor
- [ ] Both features respect new weight display layer
**Plans**: 3

### Phase 39: Routine Cluster
**Goal**: Enable superset reorder and routine parent grouping
**Requirements**: ROUTINE-01 (#365), ROUTINE-02 (#307)
**Recommended Agents**: Senior Developer, Frontend Developer, UX Architect
**Success Criteria**:
- [ ] Exercises inside supersets can be reordered
- [ ] Supersets can be dragged relative to standalone exercises
- [ ] Parent groups created/edited for daily routines
- [ ] Routines can be moved between groups
- [ ] Drag-and-drop edge cases handled
**Plans**: 3

### Phase 40: Analytics
**Goal**: Add bodyweight volume tracking and historical time estimates
**Requirements**: ANALYTICS-01 (#229), ANALYTICS-02 (#225)
**Recommended Agents**: Senior Developer, Data Analytics Engineer, UX Architect
**Success Criteria**:
- [ ] Body weight captured in settings or per-exercise prompt
- [ ] Push-up/pull-up volume calculated per formula
- [ ] Decline height options available
- [ ] Routine time estimates based on historical completion
- [ ] AMRAP handling for time estimates
**Plans**: 3

### Phase 41: Quick Wins
**Goal**: Ship independent UX improvements for immediate user value
**Requirements**: UX-01 (#190), UX-02 (#228), AUDIO-01 (#100)
**Recommended Agents**: Senior Developer, Frontend Developer, QA Verification Specialist
**Success Criteria**:
- [ ] Routine auto-starts when first exercise selected
- [ ] Timer pause/restart buttons available on timed exercises
- [ ] Countdown sounds improved (last 10 seconds)
- [ ] Final rep sound distinct
- [ ] Rep chirps more audible over music
**Plans**: 2

### Phase 42: Platform
**Goal**: Enable user-defined backup locations (NAS, cloud drives)
**Requirements**: PLATFORM-01 (#363)
**Recommended Agents**: Backend Architect, Mobile App Builder, QA Verification Specialist
**Success Criteria**:
- [ ] User can select backup destination (local, Google Drive, Dropbox, etc.)
- [ ] Android Storage Access Framework integration
- [ ] iOS Files app integration
- [ ] Backup scheduling respects selected location
- [ ] Restore from alternate locations
**Plans**: 3

### Phase 43: Advanced VBT
**Goal**: Implement power loss thresholds for velocity-based training
**Requirements**: ADVANCED-01 (#313)
**Recommended Agents**: Senior Developer, Backend Architect, Data Analytics Engineer
**Success Criteria**:
- [ ] User can set power loss threshold (e.g., 20%)
- [ ] Real-time power tracking during set
- [ ] Auto-end set when threshold reached
- [ ] Audio/visual alert at threshold
- [ ] Integration with existing biomechanics engine
**Plans**: 3

### Phase 44: Integration Validation
**Goal**: Cross-feature verification and regression testing
**Requirements**: All features from Phases 37-43
**Recommended Agents**: QA Verification Specialist, Test Results Analyzer, Evidence Collector
**Success Criteria**:
- [ ] All 12 enhancements verified working
- [ ] No regressions in existing features
- [ ] Both platforms tested (Android + iOS)
- [ ] Sync compatibility maintained
- [ ] Performance benchmarks passed
**Plans**: 2

## Progress

| Milestone | Phases | Plans | Status | Shipped |
|-----------|--------|-------|--------|---------|
| v0.4.1 Architectural Cleanup | 1-4 | 10 | Complete | 2026-02-13 |
| v0.4.5 Premium Features Phase 1 | 1-5 | 11 | Complete | 2026-02-14 |
| v0.4.6 Biomechanics MVP | 6-8 | 10 | Complete | 2026-02-15 |
| v0.4.7 Mobile Platform Features | 9-12 | 13 | Complete | 2026-02-15 |
| v0.5.0 Premium Mobile | 13-15 | 7 | Complete | 2026-02-27 |
| v0.5.1 Board Polish & Premium UI | 16-22 | 16 | Complete | 2026-02-28 |
| v0.6.0 Portal Sync Compatibility | 23-28 | 13 | Complete | 2026-03-02 |
| v0.7.0 MVP Cloud Sync | 29-31 | 8 | Complete | 2026-03-15 |
| v0.8.0 Beta Readiness | 32-36 | 15 | Complete | 2026-03-24 |
| **v0.9.0 Enhancement Sweep** | 37-44 | 21 | Active | — |

**Last phase number:** 44

---
*Last updated: 2026-04-21 — v0.9.0 Enhancement Sweep initialized*
