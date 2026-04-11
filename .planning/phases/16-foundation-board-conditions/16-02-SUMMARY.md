---
phase: 16-foundation-board-conditions
plan: 02
subsystem: security, ui, platform
tags: [android-backup, camera-permission, mediapipe, expect-actual, kmp]

# Dependency graph
requires:
  - phase: 15-cv-form-check
    provides: PoseLandmarkerHelper, FormCheckOverlay, CameraX integration
provides:
  - Android backup exclusion for vitruvian.db and vitruvian_preferences.xml
  - Camera permission rationale with on-device processing guarantee
  - PoseLandmarkerHelper graceful error handling (no crash on missing asset)
  - isIosPlatform expect/actual for platform-conditional feature availability
affects: [19-cv-form-check-ux, paywall-screens, form-check-toggle]

# Tech tracking
tech-stack:
  added: []
  patterns: [android-backup-exclusion, permission-rationale-first, graceful-ml-init]

key-files:
  created:
    - androidApp/src/main/res/xml/backup_rules.xml
    - androidApp/src/main/res/xml/data_extraction_rules.xml
  modified:
    - androidApp/src/main/AndroidManifest.xml
    - shared/src/androidMain/kotlin/com/devil/phoenixproject/cv/PoseLandmarkerHelper.kt
    - shared/src/androidMain/kotlin/com/devil/phoenixproject/presentation/components/FormCheckOverlay.android.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/Platform.kt
    - shared/src/androidMain/kotlin/com/devil/phoenixproject/Platform.android.kt
    - shared/src/iosMain/kotlin/com/devil/phoenixproject/Platform.ios.kt

key-decisions:
  - "Targeted backup exclusion (allowBackup=true) rather than blanket disable -- non-sensitive settings still backed up"
  - "Always-show rationale for camera permission rather than conditional shouldShowRequestPermissionRationale -- builds trust on first request"
  - "isIosPlatform added in Plan 01 alongside FeatureGate -- reused existing commit"

patterns-established:
  - "Backup exclusion: dual XML (fullBackupContent + dataExtractionRules) covering API 23-30 and 31+"
  - "Permission rationale-first: show explanation with on-device guarantee before requesting permission"
  - "ML init error propagation: catch in helper, report via listener.onError, display in UI"

requirements-completed: [BOARD-03, BOARD-05, BOARD-06, BOARD-08]

# Metrics
duration: 7min
completed: 2026-02-27
---

# Phase 16 Plan 02: Board Conditions Summary

**Android backup exclusion for sensitive data, camera permission rationale with on-device guarantee, PoseLandmarker graceful error handling, and isIosPlatform infrastructure for iOS feature suppression**

## Performance

- **Duration:** 7 min
- **Started:** 2026-02-27T21:42:44Z
- **Completed:** 2026-02-27T21:50:12Z
- **Tasks:** 3
- **Files modified:** 8

## Accomplishments
- Database and preferences excluded from Android auto-backup on both API 23-30 (fullBackupContent) and API 31+ (dataExtractionRules)
- Camera permission now shows rationale explaining on-device processing before requesting access (BOARD-05)
- PoseLandmarkerHelper catches initialization failures and displays user-friendly error instead of crashing (BOARD-03)
- isIosPlatform expect/actual ready for Phase 19 to conditionally show "Coming soon" on iOS (BOARD-06)
- Audit confirmed no existing UI lists Form Check as an iOS-available tier feature

## Task Commits

Each task was committed atomically:

1. **Task 1: Add Android backup exclusion for database and preferences** - `8ca9018f` (feat)
2. **Task 2: Add camera permission rationale and PoseLandmarkerHelper error handling** - `be4b762e` (feat)
3. **Task 3: Add isIosPlatform expect/actual for iOS form check suppression** - `702d8bee` (feat, from Plan 01 -- already committed)

**Plan metadata:** (pending)

## Files Created/Modified
- `androidApp/src/main/res/xml/backup_rules.xml` - API 23-30 backup exclusion rules
- `androidApp/src/main/res/xml/data_extraction_rules.xml` - API 31+ data extraction rules (cloud-backup + device-transfer)
- `androidApp/src/main/AndroidManifest.xml` - Added fullBackupContent and dataExtractionRules attributes
- `shared/src/androidMain/kotlin/com/devil/phoenixproject/cv/PoseLandmarkerHelper.kt` - Try-catch in setupPoseLandmarker with listener.onError
- `shared/src/androidMain/kotlin/com/devil/phoenixproject/presentation/components/FormCheckOverlay.android.kt` - Error state display, camera rationale with on-device guarantee
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/Platform.kt` - expect val isIosPlatform with BOARD-06 KDoc
- `shared/src/androidMain/kotlin/com/devil/phoenixproject/Platform.android.kt` - actual val isIosPlatform = false
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/Platform.ios.kt` - actual val isIosPlatform = true

## Decisions Made
- **Targeted backup exclusion**: Kept `android:allowBackup="true"` with specific file exclusions rather than blanket `allowBackup="false"` -- non-sensitive data (themes, settings) can still be backed up
- **Always-show rationale**: Show camera rationale text on first composition regardless of `shouldShowRequestPermissionRationale` result -- the on-device processing guarantee builds trust before the system dialog
- **Task 3 overlap**: The isIosPlatform expect/actual was already committed in Plan 01 (`702d8bee`) as part of FeatureGate work. No duplicate commit needed -- verified content matches Task 3 requirements exactly

## Deviations from Plan

None - plan executed exactly as written. Task 3's isIosPlatform expect/actual was already implemented in Plan 01, so no additional commit was needed.

## Issues Encountered
- Git lock file encountered during Task 3 commit attempt (stale `.git/index.lock`). Removed lock file and confirmed work was already committed in Plan 01.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- All BOARD conditions from Plan 02 are satisfied
- Phase 16 complete (Plan 01 + Plan 02) -- ready for Phase 17+
- isIosPlatform infrastructure ready for Phase 19 CV-10 Form Check toggle
- Backup exclusion active immediately for new installs and updates

## Self-Check: PASSED

All 8 files verified present. All 3 commit hashes (8ca9018f, be4b762e, 702d8bee) verified in git log.

---
*Phase: 16-foundation-board-conditions*
*Completed: 2026-02-27*
