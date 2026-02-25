# Requirements: Project Phoenix MP

**Defined:** 2026-02-20
**Core Value:** Users can connect to their Vitruvian trainer and execute workouts with accurate rep counting, weight control, and progress tracking — reliably, on both platforms.

## v0.5.0 Requirements

Requirements for v0.5.0 Premium Mobile milestone. Each maps to roadmap phases.

### Biomechanics Persistence

- [x] **PERSIST-01**: Per-rep VBT metrics (MCV, velocity zone, velocity loss, rep projection) are saved to database and available in session history
- [x] **PERSIST-02**: Per-rep force curve data (101-point normalized curve, sticking point info, strength profile) is saved to database
- [x] **PERSIST-03**: Per-rep asymmetry data (asymmetry %, dominant side) is saved to database
- [x] **PERSIST-04**: Set-level biomechanics summary (avg MCV, avg asymmetry, velocity loss trend) stored on WorkoutSession
- [x] **PERSIST-05**: Schema migration v16 applied safely on both Android and iOS (DriverFactory.ios.kt sync)

### CV Form Check

- [ ] **CV-01**: User can enable "Form Check" toggle on Active Workout Screen (Phoenix+ tier)
- [ ] **CV-02**: Camera preview appears as PiP overlay when Form Check is enabled
- [ ] **CV-03**: Skeleton overlay renders tracked landmarks on camera feed
- [ ] **CV-04**: Real-time form warnings display for exercise-specific joint angle violations (audio + visual)
- [ ] **CV-05**: Form score (0-100) calculated per exercise from joint angle compliance
- [ ] **CV-06**: Form assessment data (score, violations, joint angles) persisted locally per exercise
- [x] **CV-07**: Exercise-specific form rules defined for squat, deadlift/RDL, overhead press, curl, and row
- [x] **CV-08**: Warnings are advisory only — no automatic weight or machine adjustments
- [ ] **CV-09**: Adaptive frame rate prevents CV processing from degrading BLE metric pipeline
- [ ] **CV-10**: iOS displays "Form Check coming soon" stub when toggle is tapped
- [x] **CV-11**: MediaPipe functions correctly in release builds (ProGuard/R8 keep rules validated)

### Ghost Racing

- [ ] **GHOST-01**: User can race against their best matching local session during a set (Phoenix+ tier)
- [ ] **GHOST-02**: Two vertical progress bars show current vs. ghost cable position in real-time
- [ ] **GHOST-03**: Per-rep AHEAD/BEHIND verdict displayed based on velocity comparison
- [ ] **GHOST-04**: End-of-set summary shows total velocity delta vs. ghost

### RPG Attributes

- [ ] **RPG-01**: Five attributes (Strength, Power, Stamina, Consistency, Mastery) computed locally from workout data
- [ ] **RPG-02**: Character class auto-assigned from attribute ratios (Powerlifter, Athlete, Ironman, Monk, Phoenix)
- [ ] **RPG-03**: Compact attribute card displayed on gamification screen (Phoenix+ tier)
- [ ] **RPG-04**: "View full skill tree on Phoenix Portal" deep link on attribute card

### Readiness Briefing

- [ ] **BRIEF-01**: Local volume-based readiness heuristic computes readiness score (0-100)
- [ ] **BRIEF-02**: Pre-workout briefing card shows readiness with Green/Yellow/Red status before first set (Elite tier)
- [ ] **BRIEF-03**: Briefing is advisory only — user can always proceed with workout
- [ ] **BRIEF-04**: "Connect to Portal for full readiness model" upsell displayed

## v0.6.0+ Requirements

Deferred to future release. Tracked but not in current roadmap.

### Portal Integration

- **PORTAL-01**: Ghost racing against portal-matched sessions (best session across all devices)
- **PORTAL-02**: RPG XP computed server-side via compute-attributes Edge Function
- **PORTAL-03**: Readiness briefing powered by Bannister FFM + HRV/sleep biometric data
- **PORTAL-04**: CV form data synced to portal for post-workout postural analytics dashboard
- **PORTAL-05**: Ghost racing with full 50Hz telemetry overlay (Elite tier)

### iOS CV

- **IOS-CV-01**: iOS pose estimation via Apple Vision framework or MediaPipe iOS SDK
- **IOS-CV-02**: iOS camera preview with skeleton overlay

## Out of Scope

Explicitly excluded. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| CV auto-deload on form detection | Safety hazard — false positives under heavy load cause injury |
| Full 3D skeleton rendering during workout | Performance conflict with BLE HUD; saves battery/CPU for BLE pipeline |
| Ghost racing against other users' data | Privacy, consent, different body mechanics |
| Readiness score blocking workouts | Advisory only — users rightfully dislike being told they can't train |
| Raw video upload for CV analysis | Bandwidth/privacy nightmare; joint angle summaries sufficient |
| Full biomechanics persistence for FREE tier | Storage bloat; follow GATE-04 data-capture-for-all, UI-gating pattern |
| Portal-side features (RPG page, twin, F-v dashboard) | Tracked in phoenix-portal repo, not this KMP repo |
| iOS CV implementation (beyond stub) | Deferred to v0.6.0+; Android-first with iOS placeholder |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| PERSIST-01 | Phase 13 | Complete |
| PERSIST-02 | Phase 13 | Complete |
| PERSIST-03 | Phase 13 | Complete |
| PERSIST-04 | Phase 13 | Complete |
| PERSIST-05 | Phase 13 | Complete |
| CV-01 | Phase 16 | Pending |
| CV-02 | Phase 15 | Pending |
| CV-03 | Phase 15 | Pending |
| CV-04 | Phase 16 | Pending |
| CV-05 | Phase 16 | Pending |
| CV-06 | Phase 16 | Pending |
| CV-07 | Phase 14 | Complete |
| CV-08 | Phase 14 | Complete |
| CV-09 | Phase 15 | Pending |
| CV-10 | Phase 16 | Pending |
| CV-11 | Phase 15 | Complete |
| GHOST-01 | Phase 17 | Pending |
| GHOST-02 | Phase 17 | Pending |
| GHOST-03 | Phase 17 | Pending |
| GHOST-04 | Phase 17 | Pending |
| RPG-01 | Phase 17 | Pending |
| RPG-02 | Phase 17 | Pending |
| RPG-03 | Phase 17 | Pending |
| RPG-04 | Phase 17 | Pending |
| BRIEF-01 | Phase 17 | Pending |
| BRIEF-02 | Phase 17 | Pending |
| BRIEF-03 | Phase 17 | Pending |
| BRIEF-04 | Phase 17 | Pending |

**Coverage:**
- v0.5.0 requirements: 28 total
- Mapped to phases: 28
- Unmapped: 0

---
*Requirements defined: 2026-02-20*
*Last updated: 2026-02-20 after roadmap creation (traceability complete)*
