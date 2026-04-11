# Phase 31: Polish & Validation — Context (Re-planned)

## Phase Goal

Clean up UX issues, improve sync discoverability, bump version, build releases, and validate end-to-end.

## Requirements

- **SYNC-POLISH-01**: Sync error indicator in SettingsTab observing SyncTriggerManager.hasPersistentError
- **SYNC-POLISH-02**: Version bump to 0.7.0 (versionName + versionCode) + signed release builds
- **SYNC-POLISH-03**: End-to-end sync validation (sign up → sync → verify data on portal)

## Additional UX Fixes (from user feedback)

- Remove Insights tab from bottom nav (confusing for free users, functionality moves to portal)
- Comment out Strength Assessment entry points (requires manual velocity input — not practical without BLE integration)
- Comment out Color Blind Mode from SettingsTab (functional but no complaints, reduce settings clutter)
- Move Cloud Sync card to top of SettingsTab (currently buried after 7+ sections — poor discoverability)

## Key Files

| File | Changes |
|------|---------|
| `EnhancedMainScreen.kt` | Remove Insights NavigationBarItem (lines 324-350) |
| `HomeScreen.kt` | Comment out Strength Assessment button/navigation |
| `ExerciseDetailScreen.kt` | Comment out Strength Assessment button/navigation |
| `NavGraph.kt` | Comment out SmartInsights + StrengthAssessment routes |
| `SettingsTab.kt` | Comment out Color Blind Mode section, move Cloud Sync card to top, add sync error indicator |
| `androidApp/build.gradle.kts` | Version bump to 0.7.0 |

## Plan Structure

- **31-01**: UX Cleanup (Wave 1) — remove Insights tab, comment out Assessment + Color Blind
- **31-02**: Sync Discoverability + Error Indicator (Wave 1) — move Cloud Sync card, add hasPersistentError
- **31-03**: Version Bump & Release Builds (Wave 1) — 0.7.0, release APK, iOS compile check
- **31-04**: E2E Validation & Pre-Release Checklist (Wave 2) — test procedure, UI flow, checklist

## Branch

`MVP` — https://github.com/9thLevelSoftware/Project-Phoenix-MP/tree/MVP
