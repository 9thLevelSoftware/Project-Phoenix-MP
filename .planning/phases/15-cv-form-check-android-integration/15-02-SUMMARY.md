---
phase: 15-cv-form-check-android-integration
plan: 02
subsystem: cv
tags: [mediapipe, pose-estimation, landmark-detection, angle-calculation, camerax]

# Dependency graph
requires:
  - phase: 15-01
    provides: MediaPipe tasks-vision 0.10.29 and CameraX 1.5.1 dependencies with ProGuard rules
provides:
  - MediaPipe PoseLandmarker lifecycle wrapper with LIVE_STREAM async detection
  - 100ms timestamp throttle for BLE pipeline protection (CV-09)
  - Landmark-to-JointAngles converter supporting all 11 JointAngleType values
  - Bundled pose_landmarker_lite.task model file (~5.78MB)
affects: [15-03, 16-ios-stubs]

# Tech tracking
tech-stack:
  added: [pose_landmarker_lite.task]
  patterns: [listener-callback-pattern, world-landmarks-for-3d-angles, adaptive-frame-throttle]

key-files:
  created:
    - shared/src/androidMain/kotlin/com/devil/phoenixproject/cv/PoseLandmarkerHelper.kt
    - shared/src/androidMain/kotlin/com/devil/phoenixproject/cv/LandmarkAngleCalculator.kt
    - androidApp/src/main/assets/pose_landmarker_lite.task
  modified: []

key-decisions:
  - "CPU delegate only for v1 - GPU delegate has documented crashes on some devices"
  - "100ms minimum inference interval (~10 FPS max) balances form check accuracy with BLE pipeline protection"
  - "Prefer world landmarks (3D) over normalized landmarks (2D) for camera-angle-robust joint angles"
  - "Bundled lite model in APK assets - 5.78MB is acceptable for v1, on-demand download adds complexity"

patterns-established:
  - "PoseLandmarkerListener callback interface: onResults(landmarks, worldLandmarks, timestamp), onEmpty(), onError()"
  - "LandmarkAngleCalculator as stateless object - converts all 11 JointAngleType values from 33 MediaPipe landmarks"
  - "Always close ImageProxy in finally block to prevent camera pipeline stall"

requirements-completed: [CV-09]

# Metrics
duration: 9min
completed: 2026-02-25
---

# Phase 15 Plan 02: MediaPipe Pose Wrapper and Angle Calculator Summary

**MediaPipe PoseLandmarkerHelper with LIVE_STREAM detection, 10 FPS throttle, and LandmarkAngleCalculator producing JointAngles domain model**

## Performance

- **Duration:** 9 min
- **Started:** 2026-02-25T15:38:23Z
- **Completed:** 2026-02-25T15:47:13Z
- **Tasks:** 3
- **Files created:** 3

## Accomplishments

- Downloaded and bundled pose_landmarker_lite.task model (~5.78MB) from Google MediaPipe models
- Created PoseLandmarkerHelper with LIVE_STREAM async detection and 100ms timestamp throttle (CV-09)
- Created LandmarkAngleCalculator converting 33 MediaPipe landmarks to all 11 JointAngleType domain values

## Task Commits

Each task was committed atomically:

1. **Task 1: Download and bundle MediaPipe pose landmarker lite model** - `5a7fc865` (feat)
2. **Task 2: Create PoseLandmarkerHelper with async detection and frame throttling** - `61ec9627` (feat)
3. **Task 3: Create LandmarkAngleCalculator to convert landmarks to JointAngles** - `a0d991b7` (feat)

## Files Created/Modified

- `androidApp/src/main/assets/pose_landmarker_lite.task` - Bundled MediaPipe pose landmarker model file (~5.78MB)
- `shared/src/androidMain/kotlin/com/devil/phoenixproject/cv/PoseLandmarkerHelper.kt` - MediaPipe lifecycle wrapper with LIVE_STREAM mode and frame throttle
- `shared/src/androidMain/kotlin/com/devil/phoenixproject/cv/LandmarkAngleCalculator.kt` - Converts MediaPipe landmarks to JointAngles domain model

## Decisions Made

- **CPU delegate only:** GPU delegate has documented crashes on some devices per research doc. CPU at 10 FPS is sufficient for form checking alongside BLE processing.
- **100ms inference interval:** Caps inference at ~10 FPS to protect BLE pipeline (CV-09). Higher FPS provides no additional form check accuracy.
- **World landmarks preferred:** 3D world landmarks are more robust to camera angle than 2D normalized landmarks. Fall back to 2D when world landmarks unavailable.
- **Lite model bundled in APK:** 5.78MB is acceptable overhead for v1. On-demand download adds failure modes and network dependency.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed MediaPipe result listener signature**
- **Found during:** Task 2 (PoseLandmarkerHelper implementation)
- **Issue:** Plan's `handleResult(result, timestampMs: Long)` signature didn't match MediaPipe API which expects `(PoseLandmarkerResult, MPImage)`
- **Fix:** Changed signature to `handleResult(result: PoseLandmarkerResult, input: MPImage)` and extracted timestamp via `result.timestampMs()`
- **Files modified:** PoseLandmarkerHelper.kt
- **Verification:** Build succeeded after fix
- **Committed in:** `61ec9627` (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (1 bug fix)
**Impact on plan:** Trivial API signature correction. No scope creep.

## Issues Encountered

None - both debug and release builds succeeded after the signature fix.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- PoseLandmarkerHelper ready for CameraX ImageAnalysis integration in Plan 03
- LandmarkAngleCalculator ready to produce JointAngles for FormRulesEngine
- Model file bundled and accessible from assets directory
- Ready for Plan 03: Camera PiP overlay and skeleton rendering

---
*Phase: 15-cv-form-check-android-integration*
*Completed: 2026-02-25*

## Self-Check: PASSED

- All 3 created files verified on disk
- Commit 5a7fc865 verified in git history
- Commit 61ec9627 verified in git history
- Commit a0d991b7 verified in git history
