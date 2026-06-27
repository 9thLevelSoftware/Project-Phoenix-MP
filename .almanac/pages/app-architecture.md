---
title: App Architecture
summary: The app is a shared Compose Multiplatform UI and business-logic core with platform hosts that mainly provide DI, secure storage, permissions, and native adapters.
topics: [systems, frontend, android, ios, sync, integrations]
sources:
  - id: app-content
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/App.kt
    note: Defines the shared app entry point, splash/EULA flow, and lifecycle-triggered sync hook.
  - id: nav-graph
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt
    note: Defines the main route graph and screen boundaries.
  - id: app-module
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/di/AppModule.kt
    note: Shows that shared DI is composed from data, sync, domain, and presentation modules.
  - id: main-viewmodel
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt
    note: Shows how presentation state is assembled from extracted managers and repositories.
  - id: android-host
    type: file
    path: shared/src/androidMain/kotlin/com/devil/phoenixproject/AndroidAppHost.kt
    note: Android host resolves activity-scoped and singleton shared dependencies.
  - id: ios-host
    type: file
    path: shared/src/iosMain/kotlin/com/devil/phoenixproject/IosAppHost.kt
    note: iOS host resolves shared dependencies through Koin and surfaces DI failures on-screen.
status: active
verified: 2026-06-26
---
The architectural center of [[project-phoenix]] is `AppContent`, not the Android or iOS entrypoints. `AppContent` owns theme selection, EULA gating, the launch splash, and a lifecycle observer that triggers `SyncTriggerManager.onAppForeground()` on resume [@app-content]. Read [[frontend]] when the task is already about shared Compose ownership, route placement, or presentation-state wiring, and read [[theme-mode]] when the question is specifically about theme persistence, System or Light or Dark selection, or platform theme-following behavior instead of broader runtime structure.

## Shared runtime boundary

Navigation is shared. `NavGraph` starts at `Home` and routes into workout, routine, analytics, training-cycle, integration, auth, settings, diagnostics, and assessment screens from shared Compose code [@nav-graph]. Platform hosts do not define separate screen stacks.

Dependency injection is split between shared feature modules and platform modules. `appModule` includes `dataModule`, `syncModule`, `domainModule`, and `presentationModule`, while `platformModule` is an `expect/actual` boundary that provides the driver factory, secure settings, BLE repository, health integration, backup manager, and main view model construction [@app-module].

`MainViewModel` is the main shared façade for the UI. It composes `SettingsManager`, `HistoryManager`, `GamificationManager`, `DefaultWorkoutSessionManager`, and `BleConnectionManager`, then re-exports their state as flows for the screens [@main-viewmodel]. This means most screen code talks to one view model even though the underlying behavior has already been decomposed.

## Platform hosts

Android and iOS hosts stay thin but not identical. Android resolves the main view model with `koinActivityViewModel()`, which keeps activity-scoped state across recomposition [@android-host]. iOS resolves everything manually from Koin inside `IosAppHost` and renders `CrashErrorScreen` with the cause chain if DI resolution fails, because Swift hosts the shared Compose controller rather than an Android activity [@ios-host].

## Reading boundary

Read [[project-phoenix]] first when the task depends on hardware limits, local-first product constraints, or why the repo preserves old firmware behavior instead of simplifying around a narrower device contract.

Read [[getting-started]] first when the question is not architectural yet and you need the shortest route to the right cluster hub.

The distinction matters because this page is not the repo-wide router. It explains runtime boundaries after you already know the task is architectural, while [[getting-started]] is the faster page when the symptom could still belong to workouts, sync, data, integrations, or frontend and you only need reading order.

Read [[settings-surface]] before a deeper leaf when the issue begins from the shared Settings tab, because that route is the operational handoff into account linking, integrations, backup or restore, diagnostics, and workout-preference edits even though this page's runtime map still shows them all as one shared Compose tree [@nav-graph].

The next pages to read after this one are [[frontend]] for shared presentation-layer routing, then the cluster hubs and leaf pages behind them: [[workouts]] before [[workout-engine]], [[data]] before [[local-data-model]], [[sync]] for app-foreground refresh and remote state, [[integrations]] for the shared integrations surface, [[auth]] when the failing boundary is already login or callback handling, and [[platform-hosts]] for native boundaries that can invalidate conclusions drawn from shared code alone.
