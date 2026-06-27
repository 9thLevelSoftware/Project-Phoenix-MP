---
title: Frontend
summary: This page is the frontend hub for Phoenix's shared Compose UI, routing runtime-boundary, theme, settings-entry, auth or integrations screens, workout-surface, diagnostics, gamification, and host-boundary questions before a future agent drops into leaf pages.
topics: [systems, frontend, android, ios, workouts, sync, integrations]
sources:
  - id: app-content
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/App.kt
    note: Defines the shared Compose app entry point, theme propagation, and top-level runtime UI state.
  - id: nav-graph
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt
    note: Defines the shared route graph and shows that most feature screens live in common Compose code.
  - id: main-vm
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt
    note: Defines the main UI-facing façade for workout, history, settings, and gamification state.
  - id: settings-tab
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt
    note: Shows that shared Compose owns settings-driven UI such as theme mode, diagnostics entry, and safe-word calibration.
  - id: settings-page
    type: file
    path: .almanac/pages/settings-surface.md
    note: Defines the shared Settings route as a cross-cluster operational surface rather than only a preferences screen.
  - id: routines-page
    type: file
    path: .almanac/pages/routines-and-training-cycles.md
    note: Defines the routine-editor and training-cycle programming boundary that can surface through shared Compose screens without being part of the live session engine.
  - id: ios-content
    type: file
    path: iosApp/VitruvianPhoenix/VitruvianPhoenix/ContentView.swift
    note: Shows that iOS hosts the shared Compose controller rather than a parallel SwiftUI screen tree.
  - id: android-host
    type: file
    path: shared/src/androidMain/kotlin/com/devil/phoenixproject/AndroidAppHost.kt
    note: Shows Android also enters the shared Compose tree instead of owning a separate feature UI layer.
status: active
verified: 2026-06-26
---
Phoenix's frontend is a shared Compose tree, not two independently-designed native apps. `AppContent` owns top-level UI state, `NavGraph` owns the route graph, `MainViewModel` fronts most workout-facing presentation state, and both Android and iOS hosts enter that shared tree instead of defining parallel feature screens [@app-content] [@nav-graph] [@main-vm] [@ios-content] [@android-host].

## What belongs here

This hub is for UI-boundary questions, not every feature with a screen. Start here when the task is about screen ownership, route placement, shared state propagation, theme behavior, workout-surface interactions, or why a bug reproduces in one UI flow but not another [@app-content] [@nav-graph] [@main-vm].

This hub is not the right first stop when the failing boundary is already clearly BLE transport, SQLDelight schema, Supabase auth, or provider sync. Those questions still belong to [[workouts]], [[data]], [[sync]], or [[integrations]] even when the first symptom is visible on-screen.

This wiki also distinguishes structural frontend hubs from route-entry hubs. This page explains shared Compose ownership and screen boundaries across the app, while [[settings-surface]] explains one unusually dense screen that launches auth, integrations, backup or restore, diagnostics, and workout-preference flows without owning their underlying business logic [@settings-page].

## Routing rules

Open [[app-architecture]] first when the question is still about entry points, shared DI, or whether a behavior lives in shared Compose code or a platform host. That page is the runtime-boundary map for the whole app.

Open [[settings-surface]] first when the symptom begins from the Settings tab and still spans more than one subsystem, because that route owns cross-cluster entry points for account linking, integrations, backup or restore, diagnostics, workout-preference edits, and profile-scoped body-weight display before the symptom narrows into a leaf page [@settings-page].

Open [[auth]] or [[integrations]] only after that route split is already known. The shared Compose tree owns those screens too, but account-linking and integrations bugs often arrive through the Settings card entry points first, so [[settings-surface]] is the faster page when the first symptom is "the UI flow from Settings is wrong" rather than "GoTrue callback failed" or "provider sync card is stale" [@nav-graph] [@settings-page].

Open [[theme-mode]] first when the issue is global appearance state, System or Light or Dark persistence, dynamic color, or deprecated boolean theme wrappers leaking back into host code [@app-content] [@settings-tab].

Open [[workout-engine]] first when the UI symptom is really a projection of session state, routine flow, rack selection, history save timing, or workout coordinator invariants exposed through `MainViewModel` [@main-vm].

Open [[routines-and-training-cycles]] first when the visible bug is in routine editor screens, superset ordering, training-cycle day flow, or percentage-based programming setup before a workout session starts, because those screens live in shared Compose but their owning boundary is the programming layer rather than the live session engine [@nav-graph] [@routines-page].

Open [[workout-safety-and-feedback]] first when the UI symptom is cue playback, countdown behavior, voice stop, calibration, or the active-workout stop action reachable from the shared workout screen [@settings-tab].

Open [[machine-diagnostics]] first when the question is about the developer-tools route, the diagnostics screen state model, or the redacted export contract surfaced through shared UI [@settings-tab] [@nav-graph].

Open [[gamification]] first when the visible symptom is badges, streaks, celebration dialogs, or the RPG summary card rather than generic screen structure [@main-vm].

Open [[platform-hosts]] first when Android and iOS disagree about permissions, audio behavior, secure storage, health integrations, or lifecycle handling, because those asymmetries sit below the shared Compose tree even when the screens look the same [@ios-content] [@android-host].

## Shared UI facts that shape navigation

Settings is a dense frontend boundary in this repo. `SettingsTab` is not only preferences UI; it is also the entry point for diagnostics, backup or restore, theme changes, and safe-word calibration, which is why several otherwise unrelated pages route through one screen-level neighborhood [@settings-tab]. [[settings-surface]] is the dedicated map for that mixed boundary.

The main feature graph is shared even when a feature crosses clusters. `NavGraph` contains workout, integrations, auth, diagnostics, assessment, and settings routes in the same common module, so "frontend bug" often still needs one more split into the owning subsystem before code changes start [@nav-graph]. That is especially true for integrations and auth, because their shared screens live beside workout and settings routes in Compose even though the real failure often sits later in [[sync]], [[auth]], or [[integrations]].

The iOS host does not weaken that rule. `ContentView.swift` only wraps the shared `MainViewController`, so most iOS-visible UI behavior still originates from the same Compose route and state model unless the bug is explicitly in a host-owned adapter such as audio session, OAuth launch, or permission wiring [@ios-content].

## Read next

Read [[getting-started]] first if the repo is still too broad and you need the top-level reading order. Read [[workouts]] or [[integrations]] before a frontend leaf when the symptom might still be owned by feature logic rather than by UI composition. Keep [[platform-hosts]] nearby whenever native lifecycle or permission behavior can invalidate what the shared screen code appears to say.
