---
title: Settings Surface
summary: Settings is Phoenix's shared operational console, mixing persisted preferences with cross-cluster entry points for portal auth, integrations, backup or restore, diagnostics, workout safety, and profile-scoped body-weight state.
topics: [systems, frontend, flows, workouts, data, sync, integrations]
sources:
  - id: settings-screen
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt
    note: Defines the shared settings route content, injected managers and repositories, and the cross-cluster cards and dialogs exposed from one screen.
  - id: nav-graph
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt
    note: Defines the shared settings route and refreshes backup stats when the route is displayed.
  - id: enhanced-main-screen
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt
    note: Shows that Settings is a first-class bottom-navigation destination in the shared scaffold.
  - id: settings-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/SettingsManager.kt
    note: Defines the shared preference façade that Settings reads and mutates through MainViewModel.
  - id: preferences-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/PreferencesManager.kt
    note: Defines the persisted UserPreferences fields and the multiplatform settings-backed storage contract under the shared settings UI.
  - id: main-viewmodel
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt
    note: Shows that MainViewModel delegates settings state and mutations to SettingsManager while also refreshing backup stats for the UI.
status: active
verified: 2026-06-24
---
Settings is not only a preferences page in Phoenix. `EnhancedMainScreen` gives it a dedicated bottom-navigation destination, `NavGraph` mounts it as a shared route, and `SettingsTab` mixes appearance, workout defaults, portal entry points, backup or restore operations, diagnostics navigation, and app-level metadata in one Compose surface [@enhanced-main-screen] [@nav-graph] [@settings-screen].

## Why this page exists

This screen is a cross-cluster entry surface. A future agent that only reads `SettingsTab.kt` as "UI code" can miss that the route reaches into [[auth]], [[integrations]], [[data-backup-and-repair]], [[machine-diagnostics]], [[workout-safety-and-feedback]], [[theme-mode]], and [[platform-hosts]] from one place [@settings-screen].

The boundary is also mixed in code, not only in copy. `SettingsTab` receives most persisted preference state through `MainViewModel` and [[workout-engine|its]] `SettingsManager` façade, but it also injects `UserProfileRepository`, `ExternalMeasurementRepository`, `DataBackupManager`, and `SyncTriggerManager` directly for profile-aware body-weight display, backup or restore actions, and sync-error badges that do not flow only through one preference object [@settings-screen] [@settings-manager] [@main-viewmodel].

This page is therefore a route hub, not a subsystem hub. It explains how one shared screen hands the user into other ownership boundaries; it is the wrong leaf page once the question is already specifically about portal auth, provider sync, backup import, diagnostics payload decoding, or safe-word behavior [@settings-screen].

## State ownership

The stable preference layer lives under `PreferencesManager.preferencesFlow`, which persists `UserPreferences` through multiplatform settings backed by `SharedPreferences` on Android and `NSUserDefaults` on iOS [@preferences-manager]. `SettingsManager` exposes that flow as screen-facing state such as weight units, theme-related toggles, language, auto-backup, motion start, voice stop, and VBT thresholds, then delegates mutations back to `PreferencesManager` [@settings-manager].

The route is not purely preference-backed, though. `NavGraph` refreshes backup stats when the Settings route opens, `MainViewModel` keeps a separate `backupStats` flow for that UI, and `SettingsTab` runs manual backup or restore dialogs against injected `DataBackupManager` rather than against the general `UserPreferences` object [@nav-graph] [@main-viewmodel] [@settings-screen].

`SettingsTab` also reads profile-scoped measurement state directly. The screen derives `activeProfileId` from `UserProfileRepository.activeProfile`, observes external measurements for the health body-weight type, and highlights the latest matching Health Connect or HealthKit weight beside the local body-weight preference [@settings-screen]. A body-weight symptom that starts in Settings can therefore belong to [[profiles]] or [[health-platform-integration]], not only to the preference layer.

## Branches inside the screen

The top of the screen is operational, not cosmetic. The Cloud Sync card routes to account linking and the integrations surface, so login or provider-connect issues can begin from Settings even though the real code boundary is later in [[auth]], [[sync]], or [[integrations]] [@settings-screen].

The middle of the screen owns persistent workout defaults. Weight unit, body weight, summary countdown, auto-start routine, motion-start, sound toggles, safe-word enablement and calibration, color scheme, and VBT thresholds all live here as preference edits even when their runtime effects later show up inside [[workout-engine]], [[workout-safety-and-feedback]], [[equipment-rack]], or [[strength-assessment-and-insights]] [@settings-screen] [@settings-manager] [@preferences-manager].

The lower part of the screen is where data and tooling operations surface. Auto-backup, backup destination, manual backup, restore, delete-all-workouts, diagnostics, connection logs, and badges navigation all start from Settings, which is why backup churn and developer-tool questions often appear to start in frontend code before they split into [[data-backup-and-repair]] or [[machine-diagnostics]] [@settings-screen].

## Routing rule

Read this page first when the symptom begins in the Settings tab and the owning subsystem is still unclear. Use [[theme-mode]] for global appearance state, [[auth]] or [[sync]] for link-account or portal-status issues, [[integrations]] for the integrations card branch, [[data-backup-and-repair]] for backup or restore flows, [[workout-safety-and-feedback]] for voice-stop or cue preferences, [[machine-diagnostics]] for diagnostics export paths, and [[platform-hosts]] when Android and iOS disagree about locale application, backup destinations, native settings launchers, or permission side effects [@settings-screen] [@settings-manager] [@preferences-manager].

Read [[frontend]] before this page only when the question is still about route ownership or shared scaffold behavior rather than about the Settings surface itself. Read [[getting-started]] first when the repo is still broad and you have not yet established that Settings is the real entry boundary.
