# Project Research Summary

**Project:** Project Phoenix MP — v0.5.0 Premium Mobile Features
**Domain:** KMP BLE fitness app — CV pose estimation, VBT persistence, gamified premium UI
**Researched:** 2026-02-20
**Confidence:** HIGH

## Executive Summary

Project Phoenix v0.5.0 adds five premium feature sets to an already-functional KMP BLE fitness app: biomechanics data persistence, on-device CV pose estimation for form checking, a ghost racing overlay, an RPG attribute card, and a pre-workout readiness briefing. The architectural foundations are strong — the app already computes all the VBT and biomechanics data it needs; the core problem is that none of it is persisted. This makes biomechanics persistence the mandatory first phase, since ghost racing, RPG XP, and the readiness model all depend on locally-stored historical data. CV pose estimation is independent and can be developed in parallel once the domain boundary is set correctly.

The recommended approach follows the existing architectural patterns strictly: new managers (CvFormCheckManager, GhostRaceManager, ReadinessBriefingProvider) follow the nullable-injection delegate pattern established by LedFeedbackController and ExerciseDetectionManager; MediaPipe stays entirely in androidMain with the abstraction boundary drawn at the joint-angles level; and all new UI composables are built stub-first with comprehensive error/loading/empty states to avoid contract drift when the portal backend ships. Three features (ghost racing data, RPG XP, readiness model) will use local or synthetic data for v0.5.0 and receive portal integration in v0.5.5+. This is an intentional design decision, not technical debt.

The top risk is the combination of MediaPipe inference and BLE metric processing competing for CPU resources during an active workout — thermal throttling degrades both the BLE rep counting pipeline and CV pose quality simultaneously. The second-highest risk is the iOS database migration system: every schema change must be manually replicated in DriverFactory.ios.kt or iOS users lose all workout history on update. Both risks are well-understood with documented prevention strategies, but they must be addressed at the start of their respective phases, not retrofitted later.

## Key Findings

### Recommended Stack

The existing stack (Kotlin 2.3.0, Compose Multiplatform 1.10.0, SQLDelight 2.2.1, Koin 4.1.1) requires only three new library additions. MediaPipe Tasks Vision 0.10.32 provides 33 3D world-coordinate landmarks on-device at 30fps via LIVE_STREAM mode and is Google's actively maintained replacement for ML Kit Pose. CameraX 1.5.3 (stable, January 2026) provides the camera-compose artifact with a CameraXViewfinder composable that requires no AndroidView wrapper. Accompanist Permissions 0.37.3 handles Compose-idiomatic camera permission flow. Biomechanics persistence, ghost racing, RPG, and readiness briefing require zero new dependencies — all use the existing Compose and SQLDelight stack.

**Core technologies (new additions only):**
- **MediaPipe Tasks Vision 0.10.32**: On-device 33-landmark pose estimation — only library offering 3D world coordinates without camera calibration; ML Kit Pose is a wrapper around this
- **CameraX 1.5.3 (core, camera2, lifecycle, compose)**: Camera pipeline with lifecycle management — stable Compose-native API; no Camera2 boilerplate or AndroidView wrappers
- **Accompanist Permissions 0.37.3**: Runtime camera permission — Compose-idiomatic; consistent with rest of UI layer
- **pose_landmarker_lite.task (~5MB model asset)**: Use CPU delegate only; GPU delegate has documented orientation-crash bugs (GitHub #5835, #6223)
- **ABI splits (arm64-v8a + armeabi-v7a)**: Mandatory — MediaPipe native .so files add ~5-8MB per ABI; total APK size increase ~10-13MB with splits

### Expected Features

The feature research reveals a clear dependency hierarchy and a clean split between what ships in v0.5.0 with stub/local data and what requires portal infrastructure in v0.5.5+.

**Must have for v0.5.0 (table stakes):**
- **Biomechanics persistence** — every competing VBT platform (GymAware, RepOne, Metric VBT) persists per-rep velocity and force data; Phoenix currently discards it at workout end
- **Feature gates for new features** — CV_FORM_CHECK (PHOENIX), GHOST_RACING_BASIC/FULL (PHOENIX/ELITE), RPG_ATTRIBUTES (PHOENIX), PRE_WORKOUT_BRIEFING (ELITE)
- **CV pose estimation + exercise-specific form warnings** — no competitor combines cable machine telemetry with CV pose; this is the unique market differentiator

**Should have for v0.5.0 (differentiators):**
- **CV form score (0-100)** — mirrors existing rep quality score; enables form-fatigue tracking across sessions
- **Ghost racing overlay (stub mode)** — races against locally-persisted session data from same device; two vertical progress bars with per-rep AHEAD/BEHIND verdict
- **RPG attribute card (stub mode)** — 5 attributes computed locally from existing GamificationStats + biomechanics; reads real data, no portal required
- **Pre-workout briefing (stub mode)** — local volume-based readiness heuristic; shows "Connect to Portal" upsell for full model

**Defer to v0.5.5+ (portal dependency):**
- Ghost racing against portal-matched sessions (full 50Hz telemetry for ELITE)
- RPG XP computed server-side via compute-attributes Edge Function
- Readiness briefing with Bannister FFM + HRV/sleep biometric correction
- CV form data sync to portal for post-workout analysis

**Confirmed anti-features (do not build):**
- CV auto-deload on form detection (safety hazard — false positives under load cause injury)
- Full 3D skeleton rendering during workout (performance conflict with BLE HUD)
- Ghost racing against other users' data (privacy, consent, different body mechanics)
- Readiness score blocking workouts (advisory only, always overridable)
- Full biomechanics persistence for FREE tier (storage bloat; follow GATE-04 pattern)

### Architecture Approach

All new features slot into the existing delegate-manager architecture without modifying the BLE data hot path. The pattern is: create a new manager class (CvFormCheckManager, GhostRaceManager, ReadinessBriefingProvider), inject it as a nullable parameter into WorkoutCoordinator, expose results as StateFlow, and consume in Compose. The same pattern already governs LedFeedbackController and ExerciseDetectionManager. The critical architectural boundary for CV: MediaPipe and CameraX live entirely in androidMain; commonMain sees only domain types (PoseLandmarks, JointAngle, FormViolation). The schema requires one migration (v15 to v16) adding columns to RepMetric and WorkoutSession, and two new tables (FormAssessment, FormViolation).

**Major components:**
1. **BiomechanicsPersistence** — Extend RepMetricData + RepMetricRepository; populate from existing BiomechanicsEngine output in ActiveSessionEngine at set completion; schema migration v16
2. **CvFormCheckManager (commonMain) + AndroidPoseEstimator (androidMain)** — Manager receives JointAngles from platform layer, runs FormRuleEngine, emits FormCheckState StateFlow; MediaPipe/CameraX remain entirely platform-specific
3. **GhostRaceManager** — Loads best-matching session from local DB (same exercise, weight +/- 5%); emits synchronized rep comparison data; stub-first with repository interface
4. **RpgCalculator** — Local computation of 5 attributes from existing GamificationStats + biomechanics data; designed for easy swap to portal XP computation in v0.5.5+
5. **ReadinessBriefingProvider** — Local volume/streak heuristic in v0.5.0; same interface consumed by portal-backed implementation in v0.5.5+
6. **Premium UI composables** — GhostRaceOverlay, RpgAttributeCard, PreWorkoutBriefingCard; all in commonMain/presentation/components/premium/; all handle Loading/Error/Empty/Content states from day one

### Critical Pitfalls

1. **MediaPipe + BLE concurrent processing causes thermal throttling** — MediaPipe at 15-30fps and the existing BLE metric pipeline (BiomechanicsEngine, RepQualityScorer running per-metric-sample) compete for CPU and cause thermal throttling that degrades both systems simultaneously. Prevention: separate Dispatchers for BLE and CV, STRATEGY_KEEP_ONLY_LATEST backpressure, adaptive frame rate via Android ThermalStatusListener, CPU-only delegate (no GPU), and a thermal budget monitor that disables CV gracefully. Missing this causes the BLE rep-counting pipeline to miss rep boundaries.

2. **iOS schema migration desync causes total data loss** — The iOS DriverFactory.ios.kt bypasses SQLDelight migrations with a manual 4-layer defense system. Every schema change (v16) must be applied in three places: VitruvianDatabase.sq, the .sqm migration file (Android), AND DriverFactory.ios.kt (createAllTables + ensureAllColumnsExist + createAllIndexes + CURRENT_SCHEMA_VERSION). Missing any step causes iOS to purge the entire database on launch, deleting all workout history. Daem0n warning #155 documents this exactly.

3. **ProGuard/R8 strips MediaPipe classes in release builds** — isMinifyEnabled = true in the project's release config will crash the app on MediaPipe initialization. This is a documented upstream issue (GitHub #4806, #3509, #6138). Add comprehensive keep rules on the same day MediaPipe is added as a dependency; test release builds immediately; do not defer.

4. **Stub UI components assume non-existent portal contract shapes** — Ghost racing, RPG, and briefing components built with stub data will diverge from the actual portal API shapes when v0.5.5 lands. Prevention: define domain models in commonMain now as the contract; use a repository interface with comprehensive stubs that simulate delay (500ms), random failures (10%), empty states, and large data cases.

5. **ActiveSessionEngine God object recurrence** — Already at ~2,600 lines; adding CV callbacks, form persistence, and new state flows here pushes past 3,000+ lines. CvFormCheckManager must be a separate peer manager (not nested), following the ExerciseDetectionManager delegate pattern.

## Implications for Roadmap

Research establishes a clear five-phase build order based on hard dependencies and risk sequencing. Biomechanics persistence is the prerequisite that unblocks three other features. CV is independent but has the most complex integration risk and should be subdivided. Premium UI composables are self-contained and can be built after the foundation is in place.

### Phase 1: Biomechanics Persistence (Schema Foundation)
**Rationale:** Everything else depends on this. Ghost racing needs historical per-rep velocity data. RPG XP uses MCV and power from persisted biomechanics. The readiness model needs historical velocity trends. Zero new libraries means zero new risk. Data is already computed — this phase just stores it.
**Delivers:** Per-rep VBT metrics (MCV, zone, velocity loss, sticking point, strength profile, asymmetry) persisted to SQLite; set-level summary columns on WorkoutSession; schema v16 migration with full iOS DriverFactory sync.
**Addresses:** Biomechanics persistence (P1 table stake), per-rep biomechanics in session history (P1 table stake)
**Avoids:** iOS migration desync (Pitfall 2) — a schema change checklist covering all three sync locations must be produced before any code is written

### Phase 2: CV Form Check — Domain Logic (Platform-Agnostic)
**Rationale:** Establish the commonMain contract before touching any Android-specific code. JointAngleCalculator and FormRuleEngine are pure math — heavily testable without camera hardware. Defining these interfaces locks in what androidMain must deliver, preventing leaky abstractions later.
**Delivers:** CvModels.kt, JointAngleCalculator, FormRuleEngine, ExerciseFormRules (squat/deadlift/press initial rules), CvFormCheckManager; full unit test coverage; no platform dependencies.
**Addresses:** CV pose estimation domain (P1 differentiator)
**Avoids:** KMP abstraction boundary violation (Pitfall 7) — no MediaPipe types in commonMain; ActiveSessionEngine God object (Pitfall 10) — CvFormCheckManager is a separate peer manager from the start

### Phase 3: CV Form Check — Android Integration (High-Risk Platform Work)
**Rationale:** Highest-risk phase in the milestone. MediaPipe + CameraX lifecycle management, CPU/thermal contention with BLE, ProGuard crash in release builds, and coordinate transformation for skeleton overlay are all addressed here. Sequenced after Phase 2 so platform work implements against proven interfaces.
**Delivers:** PoseAnalyzerHelper (MediaPipe wrapper), CameraXProvider (lifecycle), AndroidPoseEstimator (connects CameraX to domain), PoseOverlayView (skeleton canvas); ProGuard rules; release build validation; thermal budget monitor.
**Addresses:** CV form warnings (P1 differentiator)
**Avoids:** Thermal throttling killing BLE pipeline (Pitfall 1) — separate dispatchers and adaptive frame rate; MediaPipe lifecycle leaks (Pitfall 2) — explicit start/stop PoseEstimationManager; ProGuard crash (Pitfall 3) — keep rules and release build test on day one; coordinate mismatch (Pitfall 6)

### Phase 4: CV Form Check — UI, Persistence, and Feature Gating
**Rationale:** Final CV layer — camera PiP overlay, form warning HUD, form score persistence, and feature gates. Depends on Phases 2 and 3 being complete. Smaller scope, mostly Compose composables and repository wiring.
**Delivers:** Camera PiP composable, form warning HUD overlay, FormAssessment + FormViolation tables and repositories, "Enable Form Check" toggle on Active Workout Screen, Feature.CV_FORM_CHECK and Feature.CV_ANALYTICS gates, FeatureGate unit test extension.
**Addresses:** CV form score (P1), CV persistence (P2), Feature gates (P1)
**Avoids:** Camera permission mid-workout disruption (Pitfall 9) — permission requested before workout starts; Compose recomposition storm (Pitfall 14) — Canvas drawWithCache, derivedStateOf; FeatureGate silent gating (Pitfall 12) — unit test verifies every enum value in at least one tier set

### Phase 5: Premium UI Composables (Parallel-Eligible After Phase 1)
**Rationale:** Ghost racing, RPG, and readiness briefing are self-contained UI features with no dependencies on each other or on the CV phases. They can be started after Phase 1 completes (they need persisted biomechanics data to show real content). Building stub-first with repository interfaces protects against portal API contract drift.
**Delivers:** GhostRaceOverlay + GhostRaceManager (with repository interface and comprehensive stub); RpgAttributeCard + RpgCalculator (local attribute computation from existing data); PreWorkoutBriefingCard + ReadinessBriefingProvider (local heuristic); all with Loading/Error/Empty/Content states; Feature.GHOST_RACING_BASIC, Feature.GHOST_RACING_FULL, Feature.RPG_ATTRIBUTES, Feature.PRE_WORKOUT_BRIEFING gates.
**Addresses:** Ghost racing (P2), RPG attribute card (P2), Pre-workout briefing (P2)
**Avoids:** Stub contract drift (Pitfall 5) — repository interface in commonMain with comprehensive stubs simulating delay, failure, empty, and large-data cases; Ghost race timestamp sync (Pitfall 11) — use position/rep-based alignment rather than wall-clock timestamps

### Phase Ordering Rationale

- **Phase 1 must come first**: Three of five feature sets cannot function without persisted biomechanics data; it is also the foundation for portal sync (v0.5.5+)
- **Phase 2 before Phase 3**: commonMain interfaces must be defined and tested before androidMain implements them; prevents leaky abstraction from forming
- **Phase 3 before Phase 4**: Cannot build CV overlay UI without the platform camera pipeline working
- **Phase 5 is parallel-eligible after Phase 1**: Premium composables need persisted data to show real content; CV not required; can proceed while Phases 2-4 are in progress
- **iOS migration risk demands Phase 1 schema is a specification artifact**: The schema change checklist is not boilerplate — it is the work product of Phase 1 planning
- **ProGuard risk demands release build validation is a Phase 3 completion criterion**: Not a pre-release task; must be validated before Phase 4 UI work begins

### Research Flags

**Phases needing closer attention during planning:**
- **Phase 1 (Schema):** iOS DriverFactory.ios.kt sync is well-documented but manually error-prone; the planning task should produce a concrete schema change checklist as a deliverable, not just code
- **Phase 3 (Android CV Integration):** Highest implementation uncertainty; thermal behavior is device-dependent; plan should include explicit performance test criteria (no BLE metric processing delays >50ms when CV is active) and identify two test devices (high-end and low-end, min SDK 26)
- **Phase 5 (Ghost Racing):** Portal API shape is unknown; the repository interface design is the critical planning deliverable; stub must be validated against multiple edge cases before portal integration begins

**Phases with standard, low-uncertainty patterns (lighter planning needed):**
- **Phase 2 (CV Domain Logic):** Pure Kotlin math and rule evaluation; well-understood patterns; standard unit testing applies
- **Phase 4 (CV UI):** Standard Compose composables and SQLDelight repository wiring; all patterns are established throughout the existing codebase

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | All recommendations from official Google docs and stable release notes; MediaPipe 0.10.32 and CameraX 1.5.3 are current stable releases; no alpha or beta dependencies required |
| Features | HIGH | Strong competitor precedent for VBT persistence and form scoring; ghost racing and RPG are novel but well-scoped with identified anti-features; dependency graph is fully mapped |
| Architecture | HIGH | Based on direct codebase analysis of WorkoutCoordinator, ActiveSessionEngine, DriverFactory.ios.kt; all new patterns follow established codebase conventions; no architectural invention required |
| Pitfalls | HIGH | Top pitfalls are backed by specific GitHub issue numbers and direct codebase analysis; MediaPipe threading and ProGuard issues are documented community problems with known mitigations, not speculation |

**Overall confidence:** HIGH

### Gaps to Address

- **Portal API shape for ghost racing**: The GhostRaceRepository interface must be designed to accommodate both local SQLite queries and future Supabase RPC calls. The exact response shape from the portal's best-matching-session RPC is unknown. Mitigation: design the interface around domain objects defined in commonMain now; validate stub contract with portal team when v0.5.5 planning begins.
- **Per-device thermal behavior**: The adaptive frame rate strategy uses Android ThermalStatusListener, but actual throttling thresholds vary significantly by device. The performance acceptance criterion (no BLE metric delays >50ms with CV active) needs validation on low-end target devices (min SDK 26). Mitigation: identify two test devices and run thermal stress tests during Phase 3.
- **Exercise form rule thresholds**: Initial rules for squat, deadlift, and overhead press use literature-sourced joint angle thresholds. These will need calibration against real users on real Vitruvian machine postures (cable machine exercises have different body positioning than free weights). Mitigation: implement rules as configurable data (ExerciseFormRules as data class map, not hardcoded), gather feedback post-launch.
- **APK size impact of MediaPipe model**: The pose_landmarker_lite.task (~5MB) is bundled in assets for v0.5.0. With ABI splits, estimated APK size increase is 10-13MB. Evaluate whether on-demand App Bundle delivery is required based on community distribution concerns.

## Sources

### Primary (HIGH confidence)
- [MediaPipe Pose Landmarker Android Guide](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker/android) — setup, API, threading model, landmark coordinates
- [CameraX Release Notes v1.5.3](https://developer.android.com/jetpack/androidx/releases/camera) — stable release confirmation, camera-compose artifact
- [SQLDelight 2.0.2 Migrations Guide](https://sqldelight.github.io/sqldelight/2.0.2/multiplatform_sqlite/migrations/) — migration file format, no-transaction rule
- [Accompanist Permissions v0.37.3](https://google.github.io/accompanist/permissions/) — Compose permission handling
- Direct codebase analysis: ActiveSessionEngine.kt (~2,600L), WorkoutCoordinator.kt (306L), DriverFactory.ios.kt (1,073L, 4-layer defense), VitruvianDatabase.sq (schema v15), FeatureGate.kt, BiomechanicsEngine.kt, androidApp/build.gradle.kts (minifyEnabled=true)
- docs/plans/2026-02-20-premium-enhancements-design.md — internal design doc

### Secondary (MEDIUM confidence)
- [AI Vision on Android: CameraX + MediaPipe + Compose](https://www.droidcon.com/2025/01/24/ai-vision-on-android-camerax-imageanalysis-mediapipe-compose/) — CameraX + MediaPipe integration pattern
- [MediaPiper KMP project](https://github.com/2BAB/mediapiper) — expect/actual boundary pattern for ML in KMP
- [RepDetect exercise form app](https://github.com/giaongo/RepDetect) — MediaPipe Pose for fitness rep counting validation
- [CameraX Compose-Native Guide](https://proandroiddev.com/goodbye-androidview-camerax-goes-full-compose-4d21ca234c4e) — CameraXViewfinder patterns, CoordinateTransformer
- [Zwift HoloReplay](https://zwiftinsider.com/holoreplay/), [Apple Watch Race Route](https://www.tomsguide.com/how-to/i-used-this-new-apple-watch-fitness-feature-to-gamify-my-weekly-run) — ghost racing UX precedent
- [Oura Readiness Score](https://support.ouraring.com/hc/en-us/articles/360025589793-Readiness-Score), [Fitbit Daily Readiness](https://store.google.com/us/magazine/fitbit_daily_readiness_score) — readiness briefing UX patterns
- [GymAware Cloud](https://gymaware.com/), [RepOne Strength](https://www.reponestrength.com/), [Metric VBT](https://www.metric.coach/) — competitor VBT persistence benchmarks

### Tertiary (documented issue reports, HIGH reliability as documented bugs)
- MediaPipe GitHub issues #5835, #6223 — GPU delegate orientation crash, memory swapping under load
- MediaPipe GitHub issues #4806, #3509, #6138 — ProGuard/R8 crash with minifyEnabled=true
- MediaPipe GitHub issue #2098 — ByteBuffer.allocateDirect memory leak pattern (long workouts)
- MediaPipe GitHub issue #3564 — 2-3 FPS on lower-end Android devices without optimization
- Daem0n memory warning #155 — iOS DriverFactory.ios.kt sync requirement for ALL schema changes

---
*Research completed: 2026-02-20*
*Ready for roadmap: yes*
