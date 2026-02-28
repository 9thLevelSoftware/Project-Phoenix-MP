# Requirements: Project Phoenix MP

**Defined:** 2026-02-20
**Core Value:** Users can connect to their Vitruvian trainer and execute workouts with accurate rep counting, weight control, and progress tracking — reliably, on both platforms.

## v0.5.0 Requirements (SHIPPED)

All v0.5.0 requirements that shipped with Phases 13-15.

### Biomechanics Persistence

- [x] **PERSIST-01**: Per-rep VBT metrics (MCV, velocity zone, velocity loss, rep projection) are saved to database and available in session history
- [x] **PERSIST-02**: Per-rep force curve data (101-point normalized curve, sticking point info, strength profile) is saved to database
- [x] **PERSIST-03**: Per-rep asymmetry data (asymmetry %, dominant side) is saved to database
- [x] **PERSIST-04**: Set-level biomechanics summary (avg MCV, avg asymmetry, velocity loss trend) stored on WorkoutSession
- [x] **PERSIST-05**: Schema migration v16 applied safely on both Android and iOS (DriverFactory.ios.kt sync)

### CV Form Check (Infrastructure — shipped in v0.5.0)

- [x] **CV-02**: Camera preview appears as PiP overlay when Form Check is enabled
- [x] **CV-03**: Skeleton overlay renders tracked landmarks on camera feed
- [x] **CV-07**: Exercise-specific form rules defined for squat, deadlift/RDL, overhead press, curl, and row
- [x] **CV-08**: Warnings are advisory only — no automatic weight or machine adjustments
- [x] **CV-09**: Adaptive frame rate prevents CV processing from degrading BLE metric pipeline
- [x] **CV-11**: MediaPipe functions correctly in release builds (ProGuard/R8 keep rules validated)

## v0.5.1 Requirements

Requirements for v0.5.1 Board Polish & Premium UI. Each maps to roadmap phases.

### CV Form Check (UX & Persistence)

- [x] **CV-01**: User can enable "Form Check" toggle on Active Workout Screen (Phoenix+ tier)
- [x] **CV-04**: Real-time form warnings display for exercise-specific joint angle violations (audio + visual)
- [x] **CV-05**: Form score (0-100) calculated per exercise from joint angle compliance
- [x] **CV-06**: Form assessment data (score, violations, joint angles) persisted locally per exercise
- [x] **CV-10**: iOS displays "Form Check coming soon" message when Form Check toggle is tapped

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

- [x] **BRIEF-01**: Local ACWR-based readiness heuristic computes readiness score (0-100) with data sufficiency guard
- [x] **BRIEF-02**: Pre-workout briefing card shows readiness with Green/Yellow/Red status before first set (Elite tier)
- [x] **BRIEF-03**: Briefing is advisory only — user can always proceed with workout
- [x] **BRIEF-04**: "Connect to Portal for full readiness model" upsell displayed

### Board Conditions (Accessibility & Security)

- [x] **BOARD-01**: SmartSuggestions classifyTimeWindow() uses local time instead of UTC for training window classification
- [x] **BOARD-02**: All color-coded indicators (velocity zones, balance bar, readiness card) have secondary visual signals (icon, label, or pattern) for WCAG AA 1.4.1 compliance
- [x] **BOARD-03**: android:allowBackup exclusion rules prevent VitruvianDatabase and sensitive preferences from cloud/ADB backup (both fullBackupContent and dataExtractionRules XMLs)
- [x] **BOARD-04**: User can configure which HUD pages are visible during workouts via Settings (preset-based: Essential, Biomechanics, Full)
- [x] **BOARD-05**: Camera permission dialog shows custom rationale text explaining on-device-only CV processing guarantee
- [x] **BOARD-06**: iOS PHOENIX tier upgrade prompts do not mention Form Check as a feature until iOS CV parity ships
- [x] **BOARD-07**: versionName in androidApp/build.gradle.kts reflects actual app version (not hardcoded 0.4.0)
- [x] **BOARD-08**: PoseLandmarkerHelper gracefully handles missing pose_landmarker_lite.task asset with user-facing error instead of crash
- [x] **BOARD-09**: FeatureGate.Feature enum includes CV_FORM_CHECK, RPG_ATTRIBUTES, GHOST_RACING, and READINESS_BRIEFING entries with correct tier assignments

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
| Drag-and-drop HUD customization | Over-engineered for v0.5.1; preset pages (Essential/Biomechanics/Full) sufficient |
| Server-side subscription validation | RevenueCat disabled; client-side FeatureGate is accepted for pre-launch |
| SQLCipher database encryption | OS sandbox provides adequate protection for fitness data at current stage |
| ELITE 50Hz ghost telemetry overlay | May require v0.5.2 if mid-range device performance is insufficient |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| PERSIST-01 | Phase 13 | Complete |
| PERSIST-02 | Phase 13 | Complete |
| PERSIST-03 | Phase 13 | Complete |
| PERSIST-04 | Phase 13 | Complete |
| PERSIST-05 | Phase 13 | Complete |
| CV-02 | Phase 15 | Complete |
| CV-03 | Phase 15 | Complete |
| CV-07 | Phase 14 | Complete |
| CV-08 | Phase 14 | Complete |
| CV-09 | Phase 15 | Complete |
| CV-11 | Phase 15 | Complete |
| CV-01 | Phase 19 | Complete |
| CV-04 | Phase 19 | Complete |
| CV-05 | Phase 19 | Complete |
| CV-06 | Phase 19 | Complete |
| CV-10 | Phase 19 | Complete |
| GHOST-01 | Phase 22 | Pending |
| GHOST-02 | Phase 22 | Pending |
| GHOST-03 | Phase 22 | Pending |
| GHOST-04 | Phase 22 | Pending |
| RPG-01 | Phase 21 | Pending |
| RPG-02 | Phase 21 | Pending |
| RPG-03 | Phase 21 | Pending |
| RPG-04 | Phase 21 | Pending |
| BRIEF-01 | Phase 20 | Complete |
| BRIEF-02 | Phase 20 | Complete |
| BRIEF-03 | Phase 20 | Complete |
| BRIEF-04 | Phase 20 | Complete |
| BOARD-01 | Phase 16 | Complete |
| BOARD-02 | Phase 17 | Complete |
| BOARD-03 | Phase 16 | Complete |
| BOARD-04 | Phase 18 | Complete |
| BOARD-05 | Phase 16 | Complete |
| BOARD-06 | Phase 16 | Complete |
| BOARD-07 | Phase 16 | Complete |
| BOARD-08 | Phase 16 | Complete |
| BOARD-09 | Phase 16 | Complete |

**Coverage:**
- v0.5.1 requirements: 26 total
- Mapped to phases: 26/26
- Unmapped: 0

---
*Requirements defined: 2026-02-20*
*Last updated: 2026-02-27 after v0.5.1 roadmap creation*
