# Phase 29: Core Sync UI — Context

## Phase Goal

Enable the user-facing cloud sync experience that's been built but commented out since v0.6.0. This phase makes the sync UI accessible to users on both Android and iOS (shared Kotlin code).

## Requirements

- **SYNC-UI-01**: Enable LinkAccount route in NavGraph.kt and "Link Portal Account" button in SettingsTab.kt
- **SYNC-UI-02**: Add Ktor ProGuard keep rules to prevent release build crashes from R8 stripping

## Success Criteria

- LinkAccount route is navigable from Settings → "Link Portal Account"
- LinkAccountScreen shows login/signup/sync controls
- Release build (Android) does not crash on sync due to ProGuard stripping
- Debug build compiles cleanly on both Android and iOS

## Existing Assets

All sync infrastructure was built in v0.6.0 (phases 23-28):
- `LinkAccountScreen.kt` — Complete UI with login/signup tabs, sync status, error display
- `LinkAccountViewModel.kt` — Complete state management: login, signup, logout, sync, clearError
- `SyncModule.kt` — All DI registrations (PortalApiClient, SyncManager, SyncTriggerManager, etc.)
- `App.kt` — Already calls `syncTriggerManager.onAppForeground()` on resume
- `ActiveSessionEngine.kt` — Already calls `syncTriggerManager?.onWorkoutCompleted()`
- `NavigationRoutes.kt` — `LinkAccount` route defined at line 45
- `PresentationModule.kt` — `LinkAccountViewModel` registered as factory

## Key Files

| File | Change | Lines |
|------|--------|-------|
| `shared/.../presentation/navigation/NavGraph.kt` | Uncomment lines 650-670 | ~20 lines |
| `shared/.../presentation/screen/SettingsTab.kt` | Uncomment lines 1310-1351 | ~40 lines |
| `androidApp/proguard-rules.pro` | Add Ktor keep rules | +7 lines |

## Decisions

- Architecture proposals: skipped (phase modifies ~20 lines across 3 files)
- Both plans are Wave 1 (no dependencies, can execute in parallel)

## Plan Structure

- **29-01**: Enable Sync UI (Wave 1) — uncomment NavGraph + SettingsTab, verify compile
- **29-02**: ProGuard Ktor Fix (Wave 1) — add keep rules, verify release build

## Branch

`MVP` — https://github.com/9thLevelSoftware/Project-Phoenix-MP/tree/MVP

## Reference

- `docs/plans/mvp-cloud-sync-mobile.md` — detailed implementation plan
- `.planning/exploration-mvp-cloud-sync.md` — crystallized scope
