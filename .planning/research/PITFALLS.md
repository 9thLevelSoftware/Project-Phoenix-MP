# Domain Pitfalls: v0.5.0 Premium Mobile Features

**Domain:** Adding CV pose estimation, biomechanics persistence, ghost racing overlay, RPG attribute card, and pre-workout briefing to existing KMP BLE fitness app
**Researched:** 2026-02-20
**Confidence:** HIGH (codebase analysis + verified external sources)

## Critical Pitfalls

Mistakes that cause crashes, data loss, performance collapse, or require rewrites.

### Pitfall 1: MediaPipe + BLE Concurrent Processing Causes Thermal Throttling and Frame Starvation

**What goes wrong:** MediaPipe Pose Landmarker running at 15-30 FPS consumes significant CPU/GPU resources. The app already processes BLE telemetry at 10-20 Hz through `handleMonitorMetric()`, which feeds rep counting, auto-stop detection, velocity tracking, force curve construction, exercise signature extraction, and biomechanics analysis. Adding real-time camera frame processing on top of this saturates the device, causing thermal throttling that degrades BOTH the CV inference AND the BLE data processing.

**Why it happens:** MediaPipe pose estimation achieves 2-10 FPS on lower-end devices even without other workloads (GitHub issue #3564). The existing BLE hot path in ActiveSessionEngine processes every metric sample through BiomechanicsEngine (MCV calculation, velocity zone classification, force curve normalization to 101 points, sticking point detection, asymmetry analysis) and RepQualityScorer (4-component scoring). These are CPU-intensive floating-point operations running on the same cores that MediaPipe needs. Thermal throttling causes both systems to degrade simultaneously, and neither has backpressure awareness of the other.

**Consequences:**
- BLE metric processing delays cause missed rep boundaries (valley detection uses 5-sample smoothing with 10mm threshold -- timing matters)
- Auto-stop velocity stall detection (5-second timer at 2.5 mm/s threshold) fires incorrectly when metric samples arrive late
- MediaPipe drops to 2-3 FPS, making pose feedback useless for form checking during fast movements
- Device overheats during workout, OS kills app or user stops using camera feature
- Battery drain makes the feature impractical for a full workout session

**Prevention:**
- Use CameraX `STRATEGY_KEEP_ONLY_LATEST` backpressure so MediaPipe never queues frames
- Run MediaPipe inference on a dedicated `Dispatchers.Default` coroutine, NOT on the BLE processing dispatcher
- Implement adaptive frame rate: start at 15 FPS, drop to 5 FPS when thermal state exceeds THERMAL_STATUS_MODERATE (Android ThermalStatusListener)
- Use `pose_landmarker_lite.task` model exclusively, never Full or Heavy -- the lite model is sufficient for joint angle thresholds in gym exercises
- Use CPU delegate, not GPU -- GPU delegate has known memory swapping issues (GitHub issue #6223) and crashes on orientation change with Lite model (issue #5835)
- Process pose landmarks on a separate coroutine from BLE metrics; they should NEVER contend for the same dispatcher
- Add a "thermal budget" monitor that disables CV processing (graceful degradation to BLE-only mode) when the device is throttling

**Detection:**
- Rep count lags behind actual movement when camera is active
- `handleMonitorMetric()` processing time exceeds 50ms (measure with System.nanoTime delta)
- MediaPipe result callback timestamps show gaps >200ms between frames
- Android Vitals reports excessive wake locks or ANR during workout

**Phase:** Address in CV integration phase -- this is the highest-risk integration point

---

### Pitfall 2: MediaPipe Lifecycle Mismanagement Causes Memory Leaks and Crashes

**What goes wrong:** MediaPipe PoseLandmarker and CameraX both require explicit lifecycle management. The current app architecture has no camera lifecycle -- BLE is the only hardware resource. Adding camera introduces a new lifecycle that must coordinate with the existing ViewModel-scoped workout lifecycle without leaking native resources.

**Why it happens:** MediaPipe internally uses native (C++) resources that are not garbage-collected. `PoseLandmarker.close()` MUST be called to release them. CameraX binds to `LifecycleOwner`, but the workout logic lives in `MainViewModel` which outlives individual Composable lifecycle owners. Additionally, MediaPipe's `AndroidPacketGetter.copyRgbToBitmap` has a documented memory allocation issue where it uses `ByteBuffer.allocateDirect` for every frame instead of caching by size (GitHub issue #2098). Over a 45-minute workout, this causes OOM or excessive GC pauses.

**Consequences:**
- Memory leak: PoseLandmarker not closed when navigating away from camera screen, native memory grows unbounded
- Crash on orientation change: GPU delegate + Lite model crashes immediately on rotation (documented: GitHub issue #5835)
- CameraX still bound after workout ends, consuming power and holding camera lock
- OOM crash during long workouts from uncached ByteBuffer allocations
- Black screen: CameraX preview intermittently shows black on certain devices (GitHub issue #4358)

**Prevention:**
- Create a `PoseEstimationManager` that wraps MediaPipe and CameraX with explicit `start()`/`stop()` lifecycle methods
- Bind CameraX to the Composable's `LocalLifecycleOwner`, NOT to the Activity lifecycle -- this ensures camera stops when leaving the workout screen
- Call `poseLandmarker.close()` in `stop()` and create a new instance in `start()` -- do NOT reuse PoseLandmarker across lifecycle events
- Use CPU delegate only (avoids GPU orientation crash entirely)
- Lock screen orientation to portrait when camera is active (workaround for GPU crash, good UX for gym use anyway)
- Implement frame bitmap pool: reuse Bitmap objects across frames rather than allocating new ones
- DisposableEffect in Compose to guarantee cleanup even on unexpected navigation

**Detection:**
- Android Profiler shows native memory growing linearly during workout
- `onCleared()` in ViewModel fires but camera LED stays on
- Black preview after navigating away and back to camera screen
- Crash logs showing `OutOfMemoryError` with `ByteBuffer.allocateDirect` in stack trace

**Phase:** Address in CV integration phase -- lifecycle design must be correct from the start

---

### Pitfall 3: SQLDelight Migration v15->v16 Breaks iOS 4-Layer Defense System

**What goes wrong:** Adding biomechanics persistence columns (VBT metrics, force curves, asymmetry per-rep to RepMetric or new tables) requires a new SQLDelight migration (15.sqm) and bumping the schema version to 16. On Android this is straightforward. On iOS, the project uses a custom 4-layer defense system in `DriverFactory.ios.kt` that bypasses SQLDelight migrations entirely with a no-op schema. Every schema change requires MANUALLY updating the iOS DriverFactory with new CREATE TABLE statements, new `safeAddColumn()` calls, new index creation, AND updating `CURRENT_SCHEMA_VERSION`. Missing any of these causes iOS-only crashes or missing data.

**Why it happens:** iOS SQLite migrations run on worker threads where exceptions propagate through Kotlin/Native's coroutine machinery to `terminateWithUnhandledException`, bypassing all try-catch blocks. The project solved this with a manual schema management system that duplicates the entire schema in Kotlin code. This means EVERY schema change must be applied in THREE places:
1. `VitruvianDatabase.sq` (the source of truth)
2. New `.sqm` migration file (for Android)
3. `DriverFactory.ios.kt` -- `createAllTables()`, `ensureAllColumnsExist()`, `createAllIndexes()`, AND the `CURRENT_SCHEMA_VERSION` constant

The Daem0n memory system has a specific warning (#155) about this: "VitruvianDatabase.sq changes: UPDATE query changes are safe, but CREATE TABLE/column changes require syncing iOS DriverFactory.ios.kt."

**Consequences:**
- iOS app purges entire database on launch (version mismatch detection in `checkDatabaseHealth()` sees version 15 < 16 = OUTDATED = purge)
- iOS users lose ALL workout history, routines, gamification progress, personal records
- New columns missing on iOS: queries that reference them fail silently (SQLDelight generates code expecting them)
- If `CURRENT_SCHEMA_VERSION` is bumped in iOS but tables not created, app crashes with missing table errors

**Prevention:**
- Create a checklist for EVERY schema change: .sq file, .sqm migration, iOS DriverFactory (tables + columns + indexes + version constant)
- For biomechanics columns on RepMetric: add via ALTER TABLE ADD COLUMN in the .sqm, AND add matching `safeAddColumn()` calls in iOS DriverFactory
- For new tables (e.g., CvFormScore, BiomechanicsRepSummary): add to .sqm AND to `createAllTables()` AND to `createAllIndexes()` in iOS DriverFactory
- Test migration locally: increment database version, build and run on existing database, verify no data loss
- Use SQLDelight's `verifySqlDelightMigration` Gradle task with a reference .db file
- Do NOT wrap migrations in BEGIN/END TRANSACTION (documented SQLDelight limitation)
- Consider: add new columns as NULLABLE with defaults rather than creating new tables -- simpler migration path

**Detection:**
- iOS test builds show empty workout history after update
- iOS crash logs from `DriverFactory.ios.kt` during `ensureSchemaComplete()`
- Android works fine but iOS doesn't -- classic migration desync symptom
- SQLDelight generated code has column references that don't exist in iOS tables

**Phase:** Address FIRST in the persistence phase -- schema design before any code

---

### Pitfall 4: ProGuard/R8 Strips MediaPipe Classes in Release Builds

**What goes wrong:** The existing Android app has `isMinifyEnabled = true` for release builds (visible in `androidApp/build.gradle.kts`). MediaPipe Tasks Vision library uses reflection and native JNI that R8 aggressively strips, causing crashes only in release builds. This is a documented, unresolved issue (GitHub issues #4806, #3509, #6138). The official MediaPipe sample app sets `minifyEnabled = false` for release as a workaround.

**Why it happens:** MediaPipe's Android Tasks library uses protobuf-lite internally, and R8 strips `GeneratedMessageLite` fields that are accessed via reflection. Adding `-keep` rules for protobuf classes alone is insufficient -- the `TaskRunner` initialization itself fails with `ExceptionInInitializerError` when minified.

**Consequences:**
- App crashes on launch or when opening camera in release builds only
- Debug builds work perfectly, masking the issue during development
- Community users (who get release APKs) experience crashes while developers never see them
- Standard ProGuard rules (`-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite`) do NOT fix it

**Prevention:**
- Add comprehensive MediaPipe ProGuard rules before first release build:
  ```
  -keep class com.google.mediapipe.** { *; }
  -keep class com.google.protobuf.** { *; }
  -dontwarn com.google.mediapipe.**
  ```
- Test EVERY release build with the camera feature before shipping
- If rules don't work (which is likely based on community reports), consider `isMinifyEnabled = false` for the release variant OR use R8 full mode with `proguardFiles` that keep the entire MediaPipe namespace
- Alternative: keep `isMinifyEnabled = true` but add `-keep` rules iteratively based on crash reports from release testing
- This MUST be tested in CI with a release build that exercises the MediaPipe code path

**Detection:**
- Release APK crashes with `ExceptionInInitializerError` or `NoSuchMethodError` in MediaPipe classes
- Debug APK works fine
- Stack trace mentions `TaskRunner`, `BaseOptions`, or `PoseLandmarkerOptions`

**Phase:** Address immediately when adding MediaPipe dependency -- do not defer to "later"

---

### Pitfall 5: Building UI Components Against Non-Existent Portal APIs Creates Hardcoded Contract Assumptions

**What goes wrong:** Ghost racing overlay, RPG attribute card, and pre-workout briefing composables are being built with stub data because the portal backend (v0.5.5/v0.6.0) doesn't exist yet. When the portal ships, the actual API response shapes will differ from the stub data models, requiring rewrites of the UI components, data mapping, and state management. Worse, "stub" implementations tend to skip error states, loading states, and edge cases that real APIs expose.

**Why it happens:** The project roadmap has these mobile UI components in v0.5.0 but the portal sync backend in v0.5.5 and portal features in v0.7.0. This is intentional (build UI early, integrate later), but the gap creates implicit contract assumptions. For example, a ghost racing overlay stub might assume the ghost data arrives as a flat list of timestamped positions, but the real API might paginate it, stream it via WebSocket, or require client-side interpolation.

**Consequences:**
- UI components assume specific data shapes that the portal team designs differently
- Missing error states: stubs never fail, so the UI has no empty state, no retry, no offline fallback
- Missing loading states: stubs return instantly, so loading indicators are never tested
- "Works with stubs, breaks with real data" -- classic integration failure
- Ghost racing replay assumes synchronized timestamps with BLE data, but real portal data may have different time resolution

**Prevention:**
- Define the data models in `commonMain` as domain objects NOW, even without the API -- these become the contract
- Use a repository interface pattern: `GhostRaceRepository` interface in `commonMain` with a `StubGhostRaceRepository` implementation that exercises ALL states (loading, success, error, empty)
- Stub implementations MUST include:
  - 500ms artificial delay (simulates network)
  - Random failure mode (10% chance of error)
  - Empty data case
  - Large data case (100+ ghost race points)
- Build the UI to handle all states from day one: Loading, Error(retry), Empty, Content
- Use a sealed class or sealed interface for each feature's UI state (e.g., `GhostRaceUiState.Loading`, `.Error`, `.Ready`)
- Gate stubs behind FeatureGate so they don't leak into production accidentally

**Detection:**
- UI component only has a "happy path" -- no error or empty state handling
- Stub data model doesn't match what the portal team is building
- Loading indicator never appears in testing (stubs are instant)
- Ghost race overlay doesn't handle null/missing data gracefully

**Phase:** Address when building each UI component -- design the interface first, stub second

## Moderate Pitfalls

### Pitfall 6: CameraX Coordinate System Mismatch with Compose Overlay

**What goes wrong:** MediaPipe returns pose landmarks in normalized coordinates (0.0-1.0) relative to the input image. CameraX preview may be letterboxed, mirrored (front camera), or rotated. Drawing landmarks on a Compose Canvas overlay requires correctly transforming from image coordinates to screen coordinates, accounting for all three factors. Getting this wrong means skeleton overlay lines don't align with the user's body in the preview.

**Why it happens:** The combination of device rotation, camera sensor orientation, mirror mode (front vs back camera), and CameraX preview scaling creates a 4-dimensional transformation problem. CameraX 1.4+ provides `CoordinateTransformer` and `TransformationInfo` with `sensorToBufferTransform`, but this only handles part of the chain. The Compose overlay also needs to account for the `CameraXViewfinder` layout bounds.

**Prevention:**
- Use CameraX Compose integration (`camera-compose` artifact) which provides `CoordinateTransformer` for the viewfinder
- Always use front camera (users face the phone during exercise) and handle mirror transformation explicitly
- Build a `PoseLandmarkTransformer` utility that converts MediaPipe normalized coords to Compose Canvas coordinates in one step
- Test on multiple screen aspect ratios (16:9, 19.5:9, 20:9, tablet) -- different ratios produce different letterboxing
- Use `derivedStateOf` for landmark positions to avoid recomposing the entire screen when landmarks update at 15+ FPS

**Detection:**
- Skeleton lines are offset from body in preview
- Lines appear mirrored (left/right swapped) on front camera
- Overlay is correct in portrait but wrong in landscape
- Overlay drifts when device aspect ratio doesn't match camera aspect ratio

**Phase:** Address in CV integration phase -- coordinate transformation is a foundational utility

---

### Pitfall 7: KMP expect/actual Boundary for Camera/ML Creates Leaky Abstraction

**What goes wrong:** MediaPipe and CameraX are Android-only libraries. The natural KMP pattern is `expect class PoseEstimator` in `commonMain` with `actual class PoseEstimator` in `androidMain` using MediaPipe, and a no-op stub in `iosMain`. But pose estimation produces RESULTS (landmark positions, joint angles) that the form rules engine in `commonMain` needs to consume. If the abstraction boundary is drawn at the wrong level, either `commonMain` has Android-specific types in its API or `androidMain` duplicates the form scoring logic.

**Why it happens:** Camera and ML are inherently platform-specific. The input (camera frames) and the processing (ML inference) cannot be shared. But the OUTPUT (33 pose landmarks with x/y/z/visibility) and the BUSINESS LOGIC (joint angle calculation, form rules, scoring) should be shared. Drawing the boundary too high (expose camera preview in commonMain) or too low (keep all form logic in androidMain) both create problems.

**Prevention:**
- Define the abstraction boundary at the LANDMARK level, not the camera level:
  - `commonMain`: `PoseLandmarks` data class (33 landmarks with x/y/z/visibility), `FormRulesEngine`, `FormScorer`
  - `androidMain`: `MediaPipePoseEstimator` (CameraX + MediaPipe integration, produces `PoseLandmarks`)
  - `iosMain`: stub that returns null (no CV on iOS for v0.5.0)
- Use `expect fun createPoseEstimator(): PoseEstimator?` returning nullable -- iOS returns null, Android returns real implementation
- Form rules engine receives `PoseLandmarks` and returns `FormCheckResult` -- this is pure business logic in `commonMain`
- Keep CameraX PreviewView / CameraXViewfinder in `androidMain` Compose code, never in `commonMain`
- Do NOT try to abstract the camera preview composable with expect/actual -- use `expect` only for the processing pipeline

**Detection:**
- `commonMain` imports from `com.google.mediapipe` or `androidx.camera` -- these should NEVER appear in commonMain
- iOS build fails with unresolved references to camera/ML types
- Form rules engine is in `androidMain` instead of `commonMain`, preventing future iOS implementation

**Phase:** Address in architecture/design before CV implementation begins

---

### Pitfall 8: Biomechanics Persistence Bloats RepMetric Table with JSON Columns

**What goes wrong:** The existing RepMetric table already stores 32 columns including JSON arrays for force curve data (concentricPositions, concentricLoadsA, concentricLoadsB, concentricVelocities, concentricTimestamps, and eccentrics -- 12 TEXT columns containing JSON arrays). Adding VBT metrics (MCV, velocity zone, velocity loss %), force curve analysis (sticking point position, strength profile type), and asymmetry data (dominance ratio, dominant side, severity) as additional columns makes the table even wider and slower to query.

**Why it happens:** The codebase uses manual JSON serialization for array data (project decision: "Manual JSON serialization for primitive arrays -- Avoid kotlinx.serialization complexity for simple cases"). Adding more computed metrics per-rep means either more JSON columns or more scalar columns. A 45-minute workout at 10 reps per set, 5 sets, 5 exercises = 250 rows. Each row with 40+ columns and JSON arrays becomes expensive to SELECT and INSERT.

**Prevention:**
- Add biomechanics summary fields as NULLABLE scalar columns on RepMetric (not JSON):
  - `mcv REAL` (mean concentric velocity, mm/s)
  - `velocityZone TEXT` (enum: STRENGTH/POWER/SPEED/etc)
  - `velocityLossPercent REAL`
  - `stickingPointRomPercent REAL`
  - `strengthProfile TEXT` (enum: ASCENDING/DESCENDING/BELL)
  - `asymmetryRatio REAL`
  - `dominantSide TEXT` (LEFT/RIGHT/BALANCED)
  - `formScore REAL` (0-100, nullable until CV is active)
- Do NOT create a separate `BiomechanicsRepResult` table with FK to RepMetric -- the 1:1 join overhead is worse than wider columns
- Compute these values from existing raw data (concentricLoadsA/B, concentricVelocities, etc.) and persist the summary at session save time
- Use batch INSERT for rep metrics (existing pattern: reps are saved at set completion, not individually)
- Add an index on `(sessionId, repNumber)` for the new columns -- this index already exists (`idx_rep_metric_session_rep`)

**Detection:**
- Session save takes >1 second (currently instant for ~20 reps)
- Database file size grows faster than expected (monitor with `PRAGMA page_count * PRAGMA page_size`)
- SELECT queries for session replay become noticeably slow

**Phase:** Address in persistence phase -- schema design is the first step

---

### Pitfall 9: Camera Permission Handling Disrupts Active Workout

**What goes wrong:** Camera permission must be requested at runtime on Android. If the user denies permission, or grants it but later revokes it in Settings, the CV form check feature must degrade gracefully WITHOUT disrupting the active workout. The current app has no camera permission handling -- only Bluetooth and notification permissions.

**Why it happens:** The camera permission dialog blocks the UI. If requested mid-workout while reps are being counted and BLE metrics are flowing, the permission dialog can cause the Activity to pause, which may trigger CameraX lifecycle events. If denied, the UI must switch from "camera + form feedback" mode to "no camera" mode without losing workout state. Android 11+ auto-revokes permissions for apps not used recently.

**Prevention:**
- Request camera permission BEFORE workout starts (on feature enable screen, not mid-set)
- Never show permission dialog during active workout -- check permission state and disable CV if not granted
- Build the CV overlay as an optional layer: `ActiveWorkoutScreen` renders with or without it based on `cameraPermissionGranted && cvFeatureEnabled`
- Use Accompanist `rememberPermissionState` in Compose, check `shouldShowRationale` for educational messaging
- If permission is revoked mid-session (user goes to Settings), detect on resume and hide camera overlay without crashing
- Add a `CVFormCheckState` sealed class: `Unavailable` (no permission/not supported), `Ready`, `Active`, `Paused` (thermal throttle), `Disabled` (user opted out)

**Detection:**
- Workout crashes when camera permission is denied
- Permission dialog appears mid-set, causing confusion
- Camera preview shows after permission revoke (stale state)
- BLE processing pauses during permission dialog

**Phase:** Address at the start of CV integration -- permission flow is a prerequisite

---

### Pitfall 10: ActiveSessionEngine Grows Beyond Maintainability Threshold

**What goes wrong:** ActiveSessionEngine is already ~2,600 lines (documented in PROJECT.md). Adding CV pose processing callbacks, form score calculation triggers, biomechanics persistence calls, and additional state flows (formScore, poseConfidence, cvState) will push it past 3,000+ lines, recreating the God object problem that the v0.4.1 decomposition solved for DefaultWorkoutSessionManager.

**Why it happens:** ActiveSessionEngine is the natural home for "during-workout processing" concerns. CV form checking is processed during workout. Biomechanics persistence happens at set completion (already in ActiveSessionEngine). The existing pattern of adding features to ActiveSessionEngine worked for v0.4.5 through v0.4.7, but CV is a fundamentally new concern (camera + ML) that doesn't belong in the BLE metric processing pipeline.

**Prevention:**
- Create `CvFormCheckManager` as a SEPARATE manager (peer to ActiveSessionEngine), NOT nested inside it
- CvFormCheckManager owns: PoseEstimator lifecycle, form rules evaluation, form score StateFlow
- ActiveSessionEngine delegates to CvFormCheckManager via interface, same pattern as existing `detectionManager` (ExerciseDetectionManager)
- Communication: CvFormCheckManager exposes `StateFlow<FormCheckResult?>`, ActiveSessionEngine reads it, no reverse dependency
- Biomechanics persistence: create a `BiomechanicsPersistenceManager` or add persistence to existing `RepMetricRepository` -- do NOT add 15 new persistence method calls to ActiveSessionEngine
- Follow the established delegate pattern: optional nullable parameter `cvFormCheckManager: CvFormCheckManager? = null`

**Detection:**
- ActiveSessionEngine exceeds 3,000 lines
- New features require modifying `handleMonitorMetric()` (the hot path)
- CV state management is mixed with BLE state management in the same class
- Testing CV form check requires the full ActiveSessionEngine test harness

**Phase:** Address in architecture design -- decide manager boundaries before implementation

## Minor Pitfalls

### Pitfall 11: Ghost Race Overlay Assumes Time-Synchronized Data

**What goes wrong:** Ghost racing compares current workout performance against a previous "ghost" session. This requires timestamp-aligned position/force data. But BLE metric samples have variable timing (10-20Hz, not exact), and the ghost data (loaded from database or portal) may have been sampled at different rates. Naive playback at wall-clock time causes the ghost to drift out of sync with the actual exercise.

**Prevention:** Use position-based or rep-based alignment rather than timestamp-based. Map ghost data to ROM percentage (0-100% of the rep) and align by position in the movement, not by elapsed time. This matches the existing 101-point ROM normalization pattern used for force curves.

---

### Pitfall 12: FeatureGate Enum Must Be Extended Without Breaking Existing Patterns

**What goes wrong:** Adding new `Feature` enum values (CV_FORM_CHECK, GHOST_RACING, RPG_ATTRIBUTES, PRE_WORKOUT_BRIEFING) to `FeatureGate.Feature` requires updating the `phoenixFeatures` and `eliteFeatures` sets. If a new feature is added to the enum but not to either set, `isEnabled()` returns false for ALL tiers including Elite, which silently gates the feature for paying users.

**Prevention:** Add a unit test that verifies every `Feature` enum value appears in at least one tier's feature set. Existing `FeatureGateTest.kt` should be extended. Consider: which features go to which tier? CV form check = Phoenix or Elite? RPG = Elite? Define tier assignments in the feature specification, not during implementation.

---

### Pitfall 13: MediaPipe Model Asset Size Increases APK Size

**What goes wrong:** `pose_landmarker_lite.task` is ~5-10 MB. The app bundles this in `assets/`, increasing the APK size for ALL users, including those who never use the CV feature. The existing APK should be lean (BLE app, no camera features currently).

**Prevention:** Use Android App Bundle (AAB) with on-demand delivery for the model file, OR download the model on first use and cache it locally. For v0.5.0 MVP, bundling in assets is acceptable but document the size impact. The model file should be added to `.gitattributes` for Git LFS if it exceeds 10 MB.

---

### Pitfall 14: Compose Recomposition Storm from Pose Landmarks at 15+ FPS

**What goes wrong:** MediaPipe produces 33 pose landmarks at 15-30 FPS. If exposed as `StateFlow<List<PoseLandmark>>`, every frame triggers recomposition of the overlay Canvas AND any composable that reads the landmarks. This adds to the existing recomposition load from BLE metrics (velocity HUD, force curve mini-graph, balance bar) that update at 10-20 Hz.

**Prevention:** Use `Canvas` with `drawWithCache` for the skeleton overlay -- Canvas draw calls don't trigger recomposition. Store landmarks in a `mutableStateOf` read ONLY inside the Canvas `onDraw` lambda. Use `derivedStateOf` for computed values like joint angles that the UI needs. The existing pattern of individual `StateFlow` per UI metric (not aggregated state objects) should be followed. Do NOT emit a new `FormCheckResult` on every frame -- only emit when the form score changes meaningfully (e.g., > 5 point delta).

## Phase-Specific Warnings

| Phase Topic | Likely Pitfall | Mitigation |
|-------------|---------------|------------|
| Schema migration (persistence) | iOS DriverFactory desync | Checklist: .sq, .sqm, iOS DriverFactory (tables + columns + indexes + version) |
| Schema migration (persistence) | Existing data loss on iOS update | iOS defense purges DB when version mismatches -- bump version LAST after all tables/columns are in place |
| MediaPipe integration | Release build crash from R8 | Add ProGuard keep rules on day one, test release build BEFORE any other MediaPipe work |
| MediaPipe integration | Memory leak from native resources | PoseEstimationManager with explicit start/stop, DisposableEffect in Compose |
| MediaPipe + BLE concurrent | Thermal throttling kills both systems | Adaptive frame rate, separate dispatchers, thermal budget monitor |
| CameraX overlay | Coordinate mismatch skeleton/body | PoseLandmarkTransformer utility, test on multiple aspect ratios |
| KMP boundary for CV | commonMain importing Android types | Abstraction at landmark level, not camera level |
| Form rules engine | Over-coupling to specific exercises | Parameterize angle thresholds per exercise, not hardcoded per movement |
| Ghost racing UI | Stub data shapes diverge from portal | Define domain models in commonMain NOW, repository interface with comprehensive stubs |
| RPG attribute card UI | Missing error/loading states | Stub must simulate delay, errors, empty states |
| Pre-workout briefing UI | Assumes specific portal response shape | Sealed class UI state, handle all cases from day one |
| Camera permission | Dialog during active workout | Request before workout starts, never mid-set |
| ActiveSessionEngine growth | God object recurrence | New managers for CV and persistence, delegate pattern |
| FeatureGate extension | New feature silently gated | Unit test: every enum value in at least one tier set |
| APK size | Model file bloats download | App Bundle on-demand delivery or runtime download |
| Compose performance | Recomposition storm from landmarks | Canvas drawWithCache, derivedStateOf for computed values |

## Sources

- Direct codebase analysis: `ActiveSessionEngine.kt` (~2,600 lines), `DriverFactory.ios.kt` (1,073 lines, 4-layer defense), `VitruvianDatabase.sq` (schema v15), `FeatureGate.kt`, `BiomechanicsEngine.kt`, `androidApp/build.gradle.kts` (minifyEnabled = true)
- [MediaPipe Pose Landmarker Android Guide](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker/android) -- official setup, threading model, delegate options
- [MediaPipe GPU memory swapping issue #6223](https://github.com/google-ai-edge/mediapipe/issues/6223) -- GPU delegate memory concerns
- [MediaPipe GPU orientation crash issue #5835](https://github.com/google-ai-edge/mediapipe/issues/5835) -- Lite model + GPU crashes on rotation
- [MediaPipe memory leak issue #2098](https://github.com/google/mediapipe/issues/2098) -- ByteBuffer.allocateDirect misuse
- [MediaPipe slow performance issue #3564](https://github.com/google/mediapipe/issues/3564) -- 2-3 FPS on Android devices
- [MediaPipe R8/ProGuard crash issue #4806](https://github.com/google-ai-edge/mediapipe/issues/4806) -- minifyEnabled crashes
- [MediaPipe R8 issue #6138](https://github.com/google-ai-edge/mediapipe/issues/6138) -- ExceptionInInitializerError with ProGuard
- [MediaPipe R8 issue #3509](https://github.com/google/mediapipe/issues/3509) -- minifyEnabled not buildable
- [CameraX ImageAnalysis backpressure](https://developer.android.com/media/camera/camerax/analyze) -- STRATEGY_KEEP_ONLY_LATEST documentation
- [CameraX Compose integration](https://proandroiddev.com/goodbye-androidview-camerax-goes-full-compose-4d21ca234c4e) -- CameraXViewfinder, CoordinateTransformer
- [SQLDelight 2.0.2 Migrations](https://sqldelight.github.io/sqldelight/2.0.2/multiplatform_sqlite/migrations/) -- migration file format, verification, no-transaction rule
- [Adapting MediaPipe for KMP](https://2bab.me/en/blog/2024-10-04-on-device-model-integration-kmp-2/) -- expect/actual pattern for ML features
- [Android CameraX black screen issue #4358](https://github.com/google-ai-edge/mediapipe/issues/4358) -- intermittent black preview
- [Accompanist Permissions](https://google.github.io/accompanist/permissions/) -- Compose permission handling
- [Android Runtime Permissions Best Practices](https://developer.android.com/training/permissions/requesting) -- graceful degradation pattern
- Daem0n memory warning #155: "VitruvianDatabase.sq changes require syncing iOS DriverFactory.ios.kt"
